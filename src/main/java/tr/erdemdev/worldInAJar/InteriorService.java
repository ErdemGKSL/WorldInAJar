package tr.erdemdev.worldInAJar;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class InteriorService {
    private final JavaPlugin plugin;
    private final TeleportPolicy policy;
    private final String worldName;
    private final int gap;
    private final int baseY;
    private final int stride;
    private final int worldOperationsPerTick;
    private final long worldWorkNanosPerTick;
    private final Map<UUID, UUID> sessions = new java.util.HashMap<>();
    private final Deque<WorldJob> worldJobs = new ArrayDeque<>();
    private final Deque<PendingOperation> pendingOperations = new ArrayDeque<>();
    private final Set<Integer> processingCells = new HashSet<>();
    private final Set<Integer> readyCells = new HashSet<>();
    private final Map<Integer, List<Runnable>> readinessWaiters = new java.util.HashMap<>();
    private final Map<Long, Integer> retainedChunkCounts = new java.util.HashMap<>();
    private final Map<Long, Chunk> retainedChunks = new java.util.HashMap<>();
    private World world;
    private BukkitTask worldJobTask;
    private boolean operationActive;

    public InteriorService(JavaPlugin plugin, TeleportPolicy policy) {
        this.plugin = plugin;
        this.policy = policy;
        worldName = plugin.getConfig().getString("interior.world", "world_in_a_jar");
        gap = plugin.getConfig().getInt("interior.cell-gap", 8);
        baseY = plugin.getConfig().getInt("interior.base-y", 64);
        stride = plugin.getConfig().getInt("interior.allocation-stride", 320);
        worldOperationsPerTick = Math.max(128,
                plugin.getConfig().getInt("interior.combination-blocks-per-tick", 2048));
        double millis = Math.max(0.25,
                plugin.getConfig().getDouble("interior.combination-max-millis-per-tick", 2.0));
        worldWorkNanosPerTick = (long) (millis * 1_000_000L);
    }

    public void loadWorld() {
        world = Bukkit.getWorld(worldName);
        if (world == null) {
            world = new WorldCreator(worldName).environment(World.Environment.NORMAL)
                    .type(WorldType.FLAT).generator(new EmptyGenerator()).generateStructures(false).createWorld();
        }
        if (world == null) throw new IllegalStateException("Paper could not create the jar interior world");
        world.setAutoSave(true);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, plugin.getConfig().getBoolean("interior.mob-spawning", true));
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        clearWeather();
    }

    public World world() { return world; }
    public CellLayout.Cell cell(JarRecord jar) { return CellLayout.cell(jar.cell(), stride, gap, baseY); }
    public int maximumInteriorSize() { return Math.max(1, stride); }
    public int maximumInteriorHeight() { return Math.max(1, world.getMaxHeight() - baseY); }

    public void copyRegionsAsync(JarRecord target, List<InteriorCopy> copies, Runnable completion) {
        beginProcessing(target);
        List<InteriorCopy> copySnapshot = List.copyOf(copies);
        scheduleCopy(target, copySnapshot, completion, 0);
    }

    private void scheduleCopy(JarRecord target, List<InteriorCopy> copies,
                              Runnable completion, int attempt) {
        List<JarRecord> required = new ArrayList<>(copies.size() + 1);
        required.add(target);
        copies.forEach(copy -> required.add(copy.source()));
        Runnable retry = () -> retryLater(
                () -> scheduleCopy(target, copies, completion, attempt + 1), target, attempt);
        enqueueOperation(() -> withLoadedChunks(required, chunks -> {
            Deque<WorldJob> stages = new ArrayDeque<>();
            addBoundaryPreparation(stages, target);
            CellLayout.Cell destinationCell = cell(target);
            for (InteriorCopy copy : copies) {
                addBoundaryPreparation(stages, copy.source());
                stages.addLast(new RegionCopyJob(copy, target, destinationCell));
            }
            submit(new SequenceJob(stages, () -> finishProcessing(target, completion), retry, () -> {
                releaseChunks(chunks);
                finishOperation();
            }));
        }, () -> {
            retry.run();
            finishOperation();
        }), retry);
    }

    public void ensureBuiltAsync(JarRecord jar, Runnable completion) {
        if (processingCells.contains(jar.cell())) {
            readinessWaiters.computeIfAbsent(jar.cell(), ignored -> new ArrayList<>()).add(completion);
            return;
        }
        beginProcessing(jar);
        scheduleEnsureBuilt(jar, completion, 0);
    }

    private void scheduleEnsureBuilt(JarRecord jar, Runnable completion, int attempt) {
        Runnable retry = () -> retryLater(
                () -> scheduleEnsureBuilt(jar, completion, attempt + 1), jar, attempt);
        enqueueOperation(() -> withLoadedChunks(List.of(jar), chunks -> {
            Deque<WorldJob> stages = new ArrayDeque<>();
            addBoundaryPreparation(stages, jar);
            if (stages.isEmpty()) {
                releaseChunks(chunks);
                finishProcessing(jar, completion);
                finishOperation();
                return;
            }
            submit(new SequenceJob(stages, () -> finishProcessing(jar, completion), retry, () -> {
                releaseChunks(chunks);
                finishOperation();
            }));
        }, () -> {
            retry.run();
            finishOperation();
        }), retry);
    }

    private void retryLater(Runnable retry, JarRecord jar, int attempt) {
        long delay = Math.min(100L, 1L << Math.min(attempt, 6));
        if (attempt == 0 || attempt % 5 == 4) {
            plugin.getLogger().warning("Retrying interior cell " + jar.cell()
                    + " after a background processing failure (attempt " + (attempt + 2) + ").");
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, retry, delay);
    }

    public record InteriorCopy(JarRecord source, int offsetX, int offsetY, int offsetZ,
                               Set<JarAssembly.Cell> cells) {
        public InteriorCopy {
            Set<JarAssembly.Cell> sourceCells = source.assembly().cells();
            if (cells == null) {
                cells = sourceCells;
            } else {
                java.util.HashSet<JarAssembly.Cell> selected = new java.util.HashSet<>(cells);
                selected.retainAll(sourceCells);
                cells = Set.copyOf(selected);
            }
        }
        public boolean includes(JarAssembly.Cell cell) { return cells.contains(cell); }
    }

    public boolean ensureBuilt(JarRecord jar) {
        if (!isReady(jar)) return false;
        if (readyCells.contains(jar.cell())) return true;
        CellLayout.Cell c = cell(jar);
        if (world.isChunkLoaded(c.minX() >> 4, c.minZ() >> 4)
                && isBuilt(jar) && isBoundaryCurrent(jar)) {
            readyCells.add(jar.cell());
            return true;
        }
        ensureBuiltAsync(jar, () -> {});
        return false;
    }

    public boolean isReady(JarRecord jar) {
        return !processingCells.contains(jar.cell());
    }

    private void beginProcessing(JarRecord jar) {
        readyCells.remove(jar.cell());
        if (!processingCells.add(jar.cell())) {
            throw new IllegalStateException("Interior cell is already being processed: " + jar.cell());
        }
    }

    private void finishProcessing(JarRecord jar, Runnable completion) {
        processingCells.remove(jar.cell());
        readyCells.add(jar.cell());
        try { completion.run(); }
        finally {
            List<Runnable> waiters = readinessWaiters.remove(jar.cell());
            if (waiters != null) waiters.forEach(Runnable::run);
        }
    }

    private void addBoundaryPreparation(Deque<WorldJob> stages, JarRecord jar) {
        if (!isBuilt(jar)) stages.addLast(new BoundaryJob(jar));
        else if (!isBoundaryCurrent(jar)) stages.addLast(new BoundaryRepairJob(jar));
    }

    private boolean isBuilt(JarRecord jar) {
        CellLayout.Cell c = cell(jar);
        return world.getBlockAt(c.minX(), c.minY() - 2, c.minZ()).getType() == Material.BEDROCK;
    }

    private boolean isBoundaryCurrent(JarRecord jar) {
        CellLayout.Cell c = cell(jar);
        return world.getBlockAt(c.minX(), c.minY() - 3, c.minZ()).getType() == Material.BEDROCK;
    }

    public void enter(Player player, JarRecord jar) {
        if (!jar.hasPortal()) return;
        if (!ensureBuilt(jar)) return;
        sessions.put(player.getUniqueId(), jar.id());
        if (policy.teleport(player, entryLocation(jar, player))) {
            applyEnvironment(player, jar);
        } else {
            sessions.remove(player.getUniqueId());
        }
    }

    /** Admin entry that works without a portal side and regardless of clogging. */
    public boolean forceEnter(Player player, JarRecord jar) {
        if (!ensureBuilt(jar)) return false;
        sessions.put(player.getUniqueId(), jar.id());
        if (policy.teleport(player, entryLocation(jar, player))) {
            applyEnvironment(player, jar);
            return true;
        }
        sessions.remove(player.getUniqueId());
        return false;
    }

    public Location entryLocation(JarRecord jar, Player player) {
        CellLayout.Cell c = cell(jar);
        if (!jar.hasPortal()) {
            JarAssembly.Cell first = jar.assembly().cells().stream()
                    .min(java.util.Comparator.comparingInt(JarAssembly.Cell::y)
                            .thenComparingInt(JarAssembly.Cell::z)
                            .thenComparingInt(JarAssembly.Cell::x)).orElseThrow();
            return new Location(world,
                    c.minX() + first.x() * jar.scale() + jar.scale() / 2.0,
                    c.minY() + first.y() * jar.scale() + 1.1,
                    c.minZ() + first.z() * jar.scale() + jar.scale() / 2.0,
                    player.getYaw(), player.getPitch());
        }
        Location doorBlock = jar.doorBlockLocation();
        int tileX = doorBlock.getBlockX() - jar.x(), tileZ = doorBlock.getBlockZ() - jar.z();
        double x = c.minX() + tileX * jar.scale() + jar.scale() / 2.0;
        double y = c.minY() + jar.doorY() * jar.scale() + 1.1;
        double z = c.minZ() + tileZ * jar.scale() + jar.scale() / 2.0;
        int margin = 2;
        if (jar.door() == BlockFace.NORTH) z = c.minZ() + tileZ * jar.scale() + margin + .5;
        else if (jar.door() == BlockFace.SOUTH) z = c.minZ() + (tileZ + 1) * jar.scale() - margin - .5;
        else if (jar.door() == BlockFace.WEST) x = c.minX() + tileX * jar.scale() + margin + .5;
        else if (jar.door() == BlockFace.EAST) x = c.minX() + (tileX + 1) * jar.scale() - margin - .5;
        return new Location(world, x, y, z, player.getYaw(), player.getPitch());
    }

    public ExitResult exit(Player player, JarRepository repository) {
        JarRecord jar = syncSession(player, player.getLocation(), repository);
        if (jar == null) return ExitResult.NOT_INSIDE;
        if (isClogged(jar)) return ExitResult.CLOGGED;
        Location destination = jar.doorBlockLocation().add(.5, .2, .5)
                .add(jar.door().getModX() * 1.5, 0, jar.door().getModZ() * 1.5);
        if (!policy.teleport(player, destination)) return ExitResult.NOT_INSIDE;
        sessions.remove(player.getUniqueId());
        resetEnvironment(player);
        return ExitResult.EXITED;
    }

    public boolean isClogged(JarRecord jar) {
        Location doorBlock = jar.doorBlockLocation();
        if (!jar.placed() || doorBlock == null || doorBlock.getBlock().getType() != Material.GLASS) return true;
        Block outside = doorBlock.getBlock().getRelative(jar.door());
        return DoorwayCollision.blocksPortal(
                outside.getCollisionShape().getBoundingBoxes(), jar.door());
    }

    public List<Player> occupants(JarRecord jar) {
        List<Player> result = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : sessions.entrySet()) {
            if (!entry.getValue().equals(jar.id())) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline() && contains(jar, player.getLocation())) result.add(player);
        }
        return result;
    }

    public UUID sessionId(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public void forget(Player player) {
        if (sessions.remove(player.getUniqueId()) != null) resetEnvironment(player);
    }

    public JarRecord syncSession(Player player, Location location, JarRepository repository) {
        UUID playerId = player.getUniqueId();
        if (location.getWorld() != world) {
            if (sessions.remove(playerId) != null) resetEnvironment(player);
            return null;
        }

        UUID currentId = sessions.get(playerId);
        JarRecord current = currentId == null ? null : repository.byId(currentId).orElse(null);
        if (current != null && contains(current, location)) {
            applyEnvironment(player, current);
            return current;
        }

        for (JarRecord jar : repository.all()) {
            if (!contains(jar, location)) continue;
            sessions.put(playerId, jar.id());
            applyEnvironment(player, jar);
            return jar;
        }
        if (sessions.remove(playerId) != null) resetEnvironment(player);
        return null;
    }

    public void destroy(JarRecord jar) {
        for (UUID playerId : new ArrayList<>(sessions.keySet())) {
            if (!jar.id().equals(sessions.get(playerId))) continue;
            sessions.remove(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) continue;
            Location doorBlock = jar.doorBlockLocation();
            Location destination = doorBlock == null ? Bukkit.getWorlds().getFirst().getSpawnLocation()
                    : doorBlock.clone().add(.5, .2, .5)
                    .add(jar.door().getModX() * 1.5, 0, jar.door().getModZ() * 1.5);
            policy.teleport(player, destination);
            resetEnvironment(player);
        }

        destroyCell(jar);
    }

    public void destroyCell(JarRecord jar) {
        enqueueOperation(() -> withLoadedChunks(List.of(jar), chunks -> {
            CellLayout.Cell c = cell(jar);
            int sizeX = jar.interiorSizeX(), sizeY = jar.interiorSizeY(), sizeZ = jar.interiorSizeZ();
            BoundingBox bounds = new BoundingBox(c.minX(), c.minY(), c.minZ(),
                    c.minX() + sizeX, c.minY() + sizeY, c.minZ() + sizeZ);
            world.getNearbyEntities(bounds, entity -> !(entity instanceof Player))
                    .forEach(org.bukkit.entity.Entity::remove);
            submit(new SequenceJob(new ArrayDeque<>(List.of(new CellCleanup(jar))),
                    () -> readyCells.remove(jar.cell()), () -> {
                releaseChunks(chunks);
                finishOperation();
            }));
        }, this::finishOperation));
    }

    public void pruneSessions(JarRepository repository) {
        sessions.keySet().removeIf(id -> {
            Player player = Bukkit.getPlayer(id);
            return player == null || !player.isOnline();
        });
        for (Player player : Bukkit.getOnlinePlayers()) syncSession(player, player.getLocation(), repository);
    }

    public void clearWeather() {
        world.setStorm(false);
        world.setThundering(false);
        world.setWeatherDuration(0);
        world.setThunderDuration(0);
        world.setClearWeatherDuration(Integer.MAX_VALUE);
    }

    public void stop() {
        if (worldJobTask != null) worldJobTask.cancel();
        worldJobTask = null;
        worldJobs.clear();
        pendingOperations.clear();
        operationActive = false;
        retainedChunks.values().forEach(chunk -> chunk.removePluginChunkTicket(plugin));
        retainedChunks.clear();
        retainedChunkCounts.clear();
        processingCells.clear();
        readyCells.clear();
        readinessWaiters.clear();
        for (UUID playerId : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) resetEnvironment(player);
        }
        sessions.clear();
    }

    private void applyEnvironment(Player player, JarRecord jar) {
        player.setPlayerWeather(WeatherType.CLEAR);
        Location outside = jar.outsideLocation();
        if (outside == null) {
            player.resetPlayerTime();
            return;
        }
        player.setPlayerTime(outside.getWorld().getFullTime() - world.getFullTime(), true);
    }

    private void resetEnvironment(Player player) {
        player.resetPlayerWeather();
        player.resetPlayerTime();
    }

    public boolean isExitWall(Player player, Location block, JarRepository repository) {
        UUID id = sessions.get(player.getUniqueId());
        JarRecord jar = id == null ? null : repository.byId(id).orElse(null);
        if (jar == null || !jar.hasPortal() || !contains(jar, block)) return false;
        CellLayout.Cell c = cell(jar);
        Location doorBlock = jar.doorBlockLocation();
        int tileX = doorBlock.getBlockX() - jar.x(), tileY = doorBlock.getBlockY() - jar.y();
        int tileZ = doorBlock.getBlockZ() - jar.z();
        int minX = c.minX() + tileX * jar.scale(), minY = c.minY() + tileY * jar.scale();
        int minZ = c.minZ() + tileZ * jar.scale();
        if (block.getBlockY() < minY || block.getBlockY() >= minY + jar.scale()) return false;
        return switch (jar.door()) {
            case NORTH -> block.getBlockZ() == minZ && block.getBlockX() >= minX
                    && block.getBlockX() < minX + jar.scale();
            case SOUTH -> block.getBlockZ() == minZ + jar.scale() - 1 && block.getBlockX() >= minX
                    && block.getBlockX() < minX + jar.scale();
            case WEST -> block.getBlockX() == minX && block.getBlockZ() >= minZ
                    && block.getBlockZ() < minZ + jar.scale();
            case EAST -> block.getBlockX() == minX + jar.scale() - 1 && block.getBlockZ() >= minZ
                    && block.getBlockZ() < minZ + jar.scale();
            default -> false;
        };
    }

    public boolean contains(JarRecord jar, Location location) {
        if (location.getWorld() != world) return false;
        CellLayout.Cell c = cell(jar);
        int relativeX = location.getBlockX() - c.minX();
        int relativeY = location.getBlockY() - c.minY();
        int relativeZ = location.getBlockZ() - c.minZ();
        if (relativeX < 0 || relativeY < 0 || relativeZ < 0) return false;
        return jar.assembly().contains(relativeX / jar.scale(), relativeY / jar.scale(),
                relativeZ / jar.scale());
    }

    public boolean containsAssemblyBounds(JarRecord jar, Location location) {
        if (location.getWorld() != world) return false;
        CellLayout.Cell c = cell(jar);
        JarAssembly assembly = jar.assembly();
        double minX = c.minX() + assembly.minX() * jar.scale();
        double minY = c.minY() + assembly.minY() * jar.scale();
        double minZ = c.minZ() + assembly.minZ() * jar.scale();
        double maxX = c.minX() + assembly.maxX() * jar.scale();
        double maxY = c.minY() + assembly.maxY() * jar.scale();
        double maxZ = c.minZ() + assembly.maxZ() * jar.scale();
        return location.getX() >= minX && location.getX() < maxX
                && location.getY() >= minY && location.getY() < maxY
                && location.getZ() >= minZ && location.getZ() < maxZ;
    }

    public boolean isBoundary(JarRecord jar, Location location) {
        if (!contains(jar, location)) return false;
        CellLayout.Cell c = cell(jar);
        int relativeX = location.getBlockX() - c.minX();
        int relativeY = location.getBlockY() - c.minY();
        int relativeZ = location.getBlockZ() - c.minZ();
        int cellX = relativeX / jar.scale(), cellY = relativeY / jar.scale();
        int cellZ = relativeZ / jar.scale();
        int localX = relativeX % jar.scale(), localY = relativeY % jar.scale();
        int localZ = relativeZ % jar.scale();
        return InteriorBoundary.isBarrier(jar.assembly(),
                new JarAssembly.Cell(cellX, cellY, cellZ), jar.scale(), localX, localY, localZ);
    }

    private void enqueueOperation(Runnable starter) {
        enqueueOperation(starter, () -> {});
    }

    private void enqueueOperation(Runnable starter, Runnable failure) {
        pendingOperations.addLast(new PendingOperation(starter, failure));
        startNextOperation();
    }

    private void startNextOperation() {
        if (operationActive || pendingOperations.isEmpty()) return;
        operationActive = true;
        PendingOperation operation = pendingOperations.removeFirst();
        try {
            operation.starter().run();
        } catch (RuntimeException exception) {
            operationActive = false;
            plugin.getLogger().severe("Could not start interior operation: " + exception.getMessage());
            operation.failure().run();
            startNextOperation();
        }
    }

    private void finishOperation() {
        operationActive = false;
        startNextOperation();
    }

    private void withLoadedChunks(Collection<JarRecord> jars, Consumer<List<Chunk>> action,
                                  Runnable failure) {
        Set<ChunkCoordinate> coordinates = new HashSet<>();
        for (JarRecord jar : jars) coordinates.addAll(interiorChunks(jar));
        List<CompletableFuture<Chunk>> loads = coordinates.stream()
                .map(coordinate -> world.getChunkAtAsync(coordinate.x(), coordinate.z(), true)
                        .exceptionally(exception -> {
                            plugin.getLogger().warning("Could not load an interior chunk: "
                                    + exception.getMessage());
                            return null;
                        }))
                .toList();
        CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new)).thenRun(() -> {
            List<Chunk> loaded = loads.stream().map(CompletableFuture::join)
                    .filter(java.util.Objects::nonNull).toList();
            Runnable completion = () -> {
                if (loaded.size() != coordinates.size()) {
                    failure.run();
                    return;
                }
                retainChunks(loaded);
                try { action.accept(loaded); }
                catch (RuntimeException exception) {
                    releaseChunks(loaded);
                    plugin.getLogger().warning("Could not prepare an interior operation: "
                            + exception.getMessage());
                    failure.run();
                }
            };
            if (Bukkit.isPrimaryThread()) completion.run();
            else if (plugin.isEnabled()) plugin.getServer().getScheduler().runTask(plugin, completion);
        });
    }

    private Set<ChunkCoordinate> interiorChunks(JarRecord jar) {
        Set<ChunkCoordinate> result = new HashSet<>();
        CellLayout.Cell destination = cell(jar);
        int scale = jar.scale();
        for (JarAssembly.Cell assemblyCell : jar.assembly().cells()) {
            int minX = destination.minX() + assemblyCell.x() * scale;
            int minZ = destination.minZ() + assemblyCell.z() * scale;
            int maxX = minX + scale - 1;
            int maxZ = minZ + scale - 1;
            for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
                for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                    result.add(new ChunkCoordinate(chunkX, chunkZ));
                }
            }
        }
        return result;
    }

    private void retainChunks(List<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            long key = Chunk.getChunkKey(chunk.getX(), chunk.getZ());
            if (retainedChunkCounts.merge(key, 1, Integer::sum) == 1) {
                chunk.addPluginChunkTicket(plugin);
                retainedChunks.put(key, chunk);
            }
        }
    }

    private void releaseChunks(List<Chunk> chunks) {
        for (Chunk chunk : chunks) {
            long key = Chunk.getChunkKey(chunk.getX(), chunk.getZ());
            Integer count = retainedChunkCounts.get(key);
            if (count == null) continue;
            if (count > 1) {
                retainedChunkCounts.put(key, count - 1);
            } else {
                retainedChunkCounts.remove(key);
                retainedChunks.remove(key);
                chunk.removePluginChunkTicket(plugin);
            }
        }
    }

    private void submit(WorldJob job) {
        worldJobs.addLast(job);
        if (worldJobTask == null) {
            worldJobTask = plugin.getServer().getScheduler().runTaskTimer(plugin,
                    this::runWorldJobs, 1L, 1L);
        }
    }

    private void runWorldJobs() {
        long deadline = System.nanoTime() + worldWorkNanosPerTick;
        int operations = 0;
        while (!worldJobs.isEmpty() && operations < worldOperationsPerTick
                && System.nanoTime() < deadline) {
            WorldJob job = worldJobs.getFirst();
            boolean complete = false;
            int quantum = Math.min(128, worldOperationsPerTick - operations);
            try {
                for (int index = 0; index < quantum && System.nanoTime() < deadline; index++) {
                    operations++;
                    if (job.step()) {
                        complete = true;
                        break;
                    }
                }
            } catch (RuntimeException exception) {
                complete = true;
                plugin.getLogger().severe("Interior world job failed: " + exception.getMessage());
                exception.printStackTrace();
                job.fail();
            }
            if (complete) worldJobs.removeFirst();
        }
        if (worldJobs.isEmpty() && worldJobTask != null) {
            worldJobTask.cancel();
            worldJobTask = null;
        }
    }

    private interface WorldJob {
        /** Performs at most one bounded world operation and returns true when complete. */
        boolean step();
        default void fail() {}
    }

    private record ChunkCoordinate(int x, int z) {}
    private record PendingOperation(Runnable starter, Runnable failure) {}

    private final class SequenceJob implements WorldJob {
        private final Deque<WorldJob> stages;
        private final Runnable completion;
        private final Runnable failure;
        private final Runnable cleanup;

        private SequenceJob(Deque<WorldJob> stages, Runnable completion) {
            this(stages, completion, () -> {}, () -> {});
        }

        private SequenceJob(Deque<WorldJob> stages, Runnable completion, Runnable cleanup) {
            this(stages, completion, () -> {}, cleanup);
        }

        private SequenceJob(Deque<WorldJob> stages, Runnable completion,
                            Runnable failure, Runnable cleanup) {
            this.stages = stages;
            this.completion = completion;
            this.failure = failure;
            this.cleanup = cleanup;
        }

        @Override public boolean step() {
            while (!stages.isEmpty()) {
                if (!stages.getFirst().step()) return false;
                stages.removeFirst();
            }
            try { completion.run(); }
            finally { cleanup.run(); }
            return true;
        }

        @Override public void fail() {
            try { failure.run(); }
            finally { cleanup.run(); }
        }
    }

    private final class BoundaryJob implements WorldJob {
        private final JarRecord jar;
        private final CellLayout.Cell destination;
        private final List<JarAssembly.Cell> cells;
        private final int scale;
        private final int floorArea;
        private final int wallArea;
        private final int operationsPerCell;
        private int cellIndex;
        private int operationIndex;
        private boolean marked;

        private BoundaryJob(JarRecord jar) {
            this.jar = jar;
            destination = cell(jar);
            cells = List.copyOf(jar.assembly().cells());
            scale = jar.scale();
            floorArea = scale * scale;
            wallArea = Math.max(0, scale - 2) * scale;
            operationsPerCell = floorArea * 2 + wallArea * 4;
        }

        @Override public boolean step() {
            if (isBuilt(jar)) return true;
            if (cellIndex >= cells.size()) {
                if (!marked) {
                    world.getBlockAt(destination.minX(), destination.minY() - 2,
                            destination.minZ()).setType(Material.BEDROCK, false);
                    world.getBlockAt(destination.minX(), destination.minY() - 3,
                            destination.minZ()).setType(Material.BEDROCK, false);
                    marked = true;
                }
                return true;
            }

            JarAssembly.Cell assemblyCell = cells.get(cellIndex);
            int local = operationIndex;
            int localX;
            int localY;
            int localZ;
            if (local < floorArea) {
                localX = local % scale;
                localY = 0;
                localZ = local / scale;
            } else if ((local -= floorArea) < floorArea) {
                localX = local % scale;
                localY = scale - 1;
                localZ = local / scale;
            } else if ((local -= floorArea) < wallArea) {
                localX = 0;
                localY = local / scale + 1;
                localZ = local % scale;
            } else if ((local -= wallArea) < wallArea) {
                localX = scale - 1;
                localY = local / scale + 1;
                localZ = local % scale;
            } else if ((local -= wallArea) < wallArea) {
                localX = local % scale;
                localY = local / scale + 1;
                localZ = 0;
            } else {
                local -= wallArea;
                localX = local % scale;
                localY = local / scale + 1;
                localZ = scale - 1;
            }

            Material material = InteriorBoundary.isBarrier(
                    jar.assembly(), assemblyCell, scale, localX, localY, localZ)
                    ? Material.BARRIER : Material.AIR;
            int baseX = destination.minX() + assemblyCell.x() * scale;
            int baseY = destination.minY() + assemblyCell.y() * scale;
            int baseZ = destination.minZ() + assemblyCell.z() * scale;
            world.getBlockAt(baseX + localX, baseY + localY, baseZ + localZ)
                    .setType(material, false);

            operationIndex++;
            if (operationIndex >= operationsPerCell) {
                operationIndex = 0;
                cellIndex++;
            }
            return false;
        }
    }

    /** Adds only newly required barriers so upgrading cannot erase blocks at shared faces. */
    private final class BoundaryRepairJob implements WorldJob {
        private final JarRecord jar;
        private final CellLayout.Cell destination;
        private final List<JarAssembly.Cell> cells;
        private final int scale;
        private final int floorArea;
        private final int wallArea;
        private final int operationsPerCell;
        private int cellIndex;
        private int operationIndex;

        private BoundaryRepairJob(JarRecord jar) {
            this.jar = jar;
            destination = cell(jar);
            cells = List.copyOf(jar.assembly().cells());
            scale = jar.scale();
            floorArea = scale * scale;
            wallArea = Math.max(0, scale - 2) * scale;
            operationsPerCell = floorArea * 2 + wallArea * 4;
        }

        @Override public boolean step() {
            if (isBoundaryCurrent(jar)) return true;
            if (cellIndex >= cells.size()) {
                world.getBlockAt(destination.minX(), destination.minY() - 3,
                        destination.minZ()).setType(Material.BEDROCK, false);
                return true;
            }

            int local = operationIndex;
            int localX;
            int localY;
            int localZ;
            if (local < floorArea) {
                localX = local % scale;
                localY = 0;
                localZ = local / scale;
            } else if ((local -= floorArea) < floorArea) {
                localX = local % scale;
                localY = scale - 1;
                localZ = local / scale;
            } else if ((local -= floorArea) < wallArea) {
                localX = 0;
                localY = local / scale + 1;
                localZ = local % scale;
            } else if ((local -= wallArea) < wallArea) {
                localX = scale - 1;
                localY = local / scale + 1;
                localZ = local % scale;
            } else if ((local -= wallArea) < wallArea) {
                localX = local % scale;
                localY = local / scale + 1;
                localZ = 0;
            } else {
                local -= wallArea;
                localX = local % scale;
                localY = local / scale + 1;
                localZ = scale - 1;
            }
            JarAssembly.Cell assemblyCell = cells.get(cellIndex);
            if (InteriorBoundary.isBarrier(
                    jar.assembly(), assemblyCell, scale, localX, localY, localZ)) {
                int baseX = destination.minX() + assemblyCell.x() * scale;
                int baseY = destination.minY() + assemblyCell.y() * scale;
                int baseZ = destination.minZ() + assemblyCell.z() * scale;
                world.getBlockAt(baseX + localX, baseY + localY, baseZ + localZ)
                        .setType(Material.BARRIER, false);
            }

            operationIndex++;
            if (operationIndex >= operationsPerCell) {
                operationIndex = 0;
                cellIndex++;
            }
            return false;
        }
    }

    private final class RegionCopyJob implements WorldJob {
        private final InteriorCopy copy;
        private final JarRecord target;
        private final JarRecord source;
        private final CellLayout.Cell sourceCell;
        private final CellLayout.Cell destinationCell;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final int scale;
        private final int cellVolume;
        private final List<JarAssembly.Cell> includedCells;
        private final int volume;
        private final int offsetX;
        private final int offsetY;
        private final int offsetZ;
        private int index;
        private List<Entity> entities;
        private int entityIndex;
        private int skippedBlocks;

        private RegionCopyJob(InteriorCopy copy, JarRecord target,
                              CellLayout.Cell destinationCell) {
            this.copy = copy;
            this.target = target;
            source = copy.source();
            sourceCell = cell(source);
            this.destinationCell = destinationCell;
            sizeX = source.interiorSizeX();
            sizeY = source.interiorSizeY();
            sizeZ = source.interiorSizeZ();
            scale = source.scale();
            cellVolume = Math.multiplyExact(Math.multiplyExact(scale, scale), scale);
            includedCells = List.copyOf(copy.cells());
            volume = Math.multiplyExact(includedCells.size(), cellVolume);
            offsetX = copy.offsetX() * target.scale();
            offsetY = copy.offsetY() * target.scale();
            offsetZ = copy.offsetZ() * target.scale();
        }

        @Override public boolean step() {
            if (index < volume) {
                JarAssembly.Cell assemblyCell = includedCells.get(index / cellVolume);
                int local = index % cellVolume;
                int x = assemblyCell.x() * scale + local % scale;
                int z = assemblyCell.z() * scale + (local / scale) % scale;
                int y = assemblyCell.y() * scale + local / (scale * scale);
                index++;
                org.bukkit.block.Block block = world.getBlockAt(
                        sourceCell.minX() + x, sourceCell.minY() + y, sourceCell.minZ() + z);
                if (block.getType().isAir() || block.getType() == Material.BARRIER) return false;
                Location destination = new Location(world, destinationCell.minX() + offsetX + x,
                        destinationCell.minY() + offsetY + y, destinationCell.minZ() + offsetZ + z);
                if (destination.getBlock().getType() != Material.BARRIER) {
                    try {
                        block.getState().copy(destination).update(true, false);
                    } catch (RuntimeException exception) {
                        skippedBlocks++;
                        if (skippedBlocks == 1) {
                            plugin.getLogger().warning("Skipping an interior block that could not be copied at "
                                    + block.getX() + "," + block.getY() + "," + block.getZ()
                                    + ": " + exception.getMessage());
                        }
                    }
                }
                return false;
            }
            if (entities == null) {
                BoundingBox bounds = new BoundingBox(sourceCell.minX(), sourceCell.minY(), sourceCell.minZ(),
                        sourceCell.minX() + sizeX, sourceCell.minY() + sizeY, sourceCell.minZ() + sizeZ);
                entities = new ArrayList<>(world.getNearbyEntities(bounds));
            }
            if (entityIndex >= entities.size()) return true;
            Entity entity = entities.get(entityIndex++);
            int tileX = Math.floorDiv(entity.getLocation().getBlockX() - sourceCell.minX(), source.scale());
            int tileY = Math.floorDiv(entity.getLocation().getBlockY() - sourceCell.minY(), source.scale());
            int tileZ = Math.floorDiv(entity.getLocation().getBlockZ() - sourceCell.minZ(), source.scale());
            if (!copy.includes(new JarAssembly.Cell(tileX, tileY, tileZ))) return false;
            Location moved = entity.getLocation().clone().add(
                    destinationCell.minX() + offsetX - sourceCell.minX(),
                    destinationCell.minY() + offsetY - sourceCell.minY(),
                    destinationCell.minZ() + offsetZ - sourceCell.minZ());
            boolean success = entity instanceof Player player
                    ? policy.teleport(player, moved) : entity.teleport(moved);
            if (success && entity instanceof Player player) {
                sessions.put(player.getUniqueId(), target.id());
                applyEnvironment(player, target);
            }
            return false;
        }
    }

    public enum ExitResult { EXITED, NOT_INSIDE, CLOGGED }

    private static final class EmptyGenerator extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
    }

    private final class CellCleanup implements WorldJob {
        private final CellLayout.Cell cell;
        private final List<JarAssembly.Cell> assemblyCells;
        private final int scale;
        private final int cellVolume;
        private final int volume;
        private int index;

        private CellCleanup(JarRecord jar) {
            cell = InteriorService.this.cell(jar);
            assemblyCells = List.copyOf(jar.assembly().cells());
            scale = jar.scale();
            cellVolume = Math.multiplyExact(Math.multiplyExact(scale, scale), scale);
            volume = Math.multiplyExact(assemblyCells.size(), cellVolume);
        }

        @Override public boolean step() {
            if (index >= volume) {
                world.getBlockAt(cell.minX(), cell.minY() - 2, cell.minZ()).setType(Material.AIR, false);
                world.getBlockAt(cell.minX(), cell.minY() - 3, cell.minZ()).setType(Material.AIR, false);
                return true;
            }
            JarAssembly.Cell assemblyCell = assemblyCells.get(index / cellVolume);
            int local = index % cellVolume;
            int x = assemblyCell.x() * scale + local % scale;
            int z = assemblyCell.z() * scale + (local / scale) % scale;
            int y = assemblyCell.y() * scale + local / (scale * scale);
            world.getBlockAt(cell.minX() + x, cell.minY() + y, cell.minZ() + z)
                    .setType(Material.AIR, false);
            index++;
            return false;
        }
    }
}
