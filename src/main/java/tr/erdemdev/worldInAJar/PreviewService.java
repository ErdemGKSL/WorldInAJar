package tr.erdemdev.worldInAJar;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Maintains viewer-specific, bidirectional display-only views for every placed jar. */
public final class PreviewService {
    private static final float FRONT_PORTAL_INWARD = .2f;
    private static final float BACK_PORTAL_INWARD = -.1f;
    private static final float SEALED_BACKING_OUTWARD = .55f;
    private static final float FLOOR_BLOCK_DROP = .02f;
    private static final float FLOOR_GLASS_HEIGHT = .1f;
    private static final float NON_FLOOR_SURFACE_THICKNESS = .99f;
    private static final double PORTAL_SIDE_SWITCH_OFFSET = .1;
    private static final int EXTERIOR_BLOCKS = 1;
    private static final int INTERIOR_BLOCKS = 2;
    private static final int CHUNK_SNAPSHOTS_PER_TICK = 4;
    private static final int CHUNK_TICKET_LOADS_PER_TICK = 4;
    private final JavaPlugin plugin;
    private final InteriorService interiors;
    private final NamespacedKey displayKey;
    private final Map<UUID, JarScene> scenes = new HashMap<>();
    private final Map<OutsideArea, List<OutsideOffset>> outsideOffsets = new HashMap<>();
    private final Set<PreviewChunk> activeChunkTickets = new HashSet<>();
    private final Map<PreviewChunk, Long> pendingChunkTickets = new ConcurrentHashMap<>();
    private final Set<BlockRequest> pendingBlocks = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> routedBlocks = new ConcurrentHashMap<>();
    private final Set<UUID> routedPlayers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean routeScheduled = new AtomicBoolean();
    private final AtomicBoolean applyScheduled = new AtomicBoolean();
    private volatile List<JarRoute> routes = List.of();
    private volatile boolean running;
    private ExecutorService router;
    private ExecutorService blockScanner;
    private EntityPreviewBackend entityBackend;
    private JarRepository repository;
    private int taskId = -1;
    private int blockRefreshTaskId = -1;
    private int chunkTicketTaskId = -1;
    private long updatePeriodTicks = 1L;
    private long blockRefreshPeriodTicks = 100L;
    private long blockUpdateTicks = 1L;
    private long blockRefreshElapsedTicks;
    private boolean exteriorEnabled;
    private boolean interiorEnabled;
    private boolean interiorShowPlayers;
    private double exteriorViewerDistance;
    private int exteriorMaximumBlocks;
    private int interiorMaximumBlocks;
    private int exteriorMaximumPlayerMarkers;
    private int interiorMaximumPlayerMarkers;
    private int outsideRadius;
    private volatile Set<PreviewChunk> desiredChunkTickets = Set.of();
    private volatile long chunkTicketGeneration;
    private boolean sessionReconcilePending = true;

    public PreviewService(JavaPlugin plugin, InteriorService interiors) {
        this.plugin = plugin;
        this.interiors = interiors;
        this.displayKey = new NamespacedKey(plugin, "preview_jar");
    }

    public void start(JarRepository repository) {
        stop();
        this.repository = repository;
        loadSettings();
        configureEntityBackend();
        running = true;
        router = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WorldInAJar-preview-router");
            thread.setDaemon(true);
            return thread;
        });
        blockScanner = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WorldInAJar-block-preview");
            thread.setDaemon(true);
            return thread;
        });
        rebuildRoutes();
        removeOrphans();
        sessionReconcilePending = true;
        taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin, this::tick, 1L, updatePeriodTicks);
    }

    public void stop() {
        running = false;
        chunkTicketGeneration++;
        if (entityBackend != null) entityBackend.stop();
        entityBackend = null;
        if (taskId != -1) plugin.getServer().getScheduler().cancelTask(taskId);
        taskId = -1;
        if (blockRefreshTaskId != -1) plugin.getServer().getScheduler().cancelTask(blockRefreshTaskId);
        blockRefreshTaskId = -1;
        if (chunkTicketTaskId != -1) plugin.getServer().getScheduler().cancelTask(chunkTicketTaskId);
        chunkTicketTaskId = -1;
        if (router != null) router.shutdownNow();
        router = null;
        if (blockScanner != null) blockScanner.shutdownNow();
        blockScanner = null;
        pendingBlocks.clear(); pendingPlayers.clear(); routedBlocks.clear(); routedPlayers.clear();
        routeScheduled.set(false); applyScheduled.set(false); routes = List.of();
        desiredChunkTickets = Set.of();
        pendingChunkTickets.clear();
        releaseChunkTickets();
        for (JarScene scene : scenes.values()) scene.removeAll();
        scenes.clear();
        updatePeriodTicks = 1L;
        blockRefreshElapsedTicks = 0L;
        sessionReconcilePending = true;
    }

    public void invalidate(JarRecord jar) {
        JarScene scene = scenes.get(jar.id());
        if (scene != null) {
            scene.exteriorInvalid = true;
            scene.interiorInvalid = true;
        }
    }

    /** Publishes a block change without retaining any Bukkit object past the event callback. */
    public void transportBlock(Location location) {
        if (!running || location.getWorld() == null) return;
        pendingBlocks.add(new BlockRequest(location.getWorld().getUID(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ()));
        scheduleRoute();
    }

    /** Coalesces high-frequency movement/state events before touching preview scenes. */
    public void transportPlayer(Player player) {
        if (!running) return;
        pendingPlayers.add(player.getUniqueId());
        scheduleRoute();
    }

    public void refresh(JarRecord jar) {
        if (!isPlaced(jar)) {
            remove(jar.id());
            return;
        }
        JarScene scene = scenes.computeIfAbsent(jar.id(), ignored -> new JarScene(jar));
        if (!scene.jar.equals(jar)) {
            scene = replaceScene(scene, jar);
        }
        refreshBlocks(scene, true, true);
        rebuildRoutes();
    }

    public void remove(UUID id) {
        removeScene(id);
        if (repository != null) rebuildRoutes();
    }

    private void removeScene(UUID id) {
        if (entityBackend != null) entityBackend.remove(id);
        JarScene scene = scenes.remove(id);
        if (scene != null) scene.removeAll();
    }

    public void seal(JarRecord jar) {
        removeScene(jar.id());
        rebuildRoutes();
        if (interiors.occupants(jar).isEmpty()) return;
        JarScene scene = new JarScene(jar);
        scenes.put(jar.id(), scene);
        spawnSealedSurfaces(scene);
        updateSealedViewers(scene);
    }

    public void forget(Player player) {
        UUID id = player.getUniqueId();
        if (entityBackend != null) entityBackend.forget(id);
        for (JarScene scene : scenes.values()) {
            scene.exteriorViewers.remove(id);
            scene.insetPortalViewers.remove(id);
            scene.interiorViewers.remove(id);
            scene.removeAvatar(id, scene.occupants);
            scene.removeAvatar(id, scene.outsiders);
        }
    }

    public void sleep(Player player, JarRecord jar, Location location) {
        JarScene scene = scenes.computeIfAbsent(jar.id(), ignored -> new JarScene(jar));
        if (!scene.jar.equals(jar)) scene = replaceScene(scene, jar);
        scene.removeAvatar(player.getUniqueId(), scene.sleepers);
        Location sleeping = location.clone();
        sleeping.setPitch(90f);
        VirtualMannequin body = new VirtualMannequin(player, sleeping, 1f);
        body.sleep(sleeping, List.of());
        scene.sleepers.put(player.getUniqueId(), new Avatar(body));
        showTo(scene.interiorViewers, List.of(body));
    }

    public void moveSleeper(UUID playerId, JarRecord source, JarRecord target, Location location) {
        JarScene sourceScene = scenes.get(source.id());
        Avatar sleeper = sourceScene == null ? null : sourceScene.sleepers.remove(playerId);
        if (sleeper != null) {
            for (Player viewer : online(sourceScene.interiorViewers)) sleeper.body.destroy(viewer);
        } else {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) sleep(player, target, location);
            return;
        }

        JarScene targetScene = scenes.computeIfAbsent(target.id(), ignored -> new JarScene(target));
        if (!targetScene.jar.equals(target)) targetScene = replaceScene(targetScene, target);
        targetScene.removeAvatar(playerId, targetScene.sleepers);
        sleeper.body.sleep(location, List.of());
        targetScene.sleepers.put(playerId, sleeper);
        showTo(targetScene.interiorViewers, List.of(sleeper.body));
    }

    public void wake(UUID playerId) {
        for (JarScene scene : scenes.values()) scene.removeAvatar(playerId, scene.sleepers);
    }

    public void preparePlayerTransition(Player player) {
        if (entityBackend != null) entityBackend.removeSource(player.getUniqueId());
    }

    private void tick() {
        blockRefreshElapsedTicks += updatePeriodTicks;
        boolean reconcileBlocks = blockRefreshElapsedTicks >= blockRefreshPeriodTicks;
        if (reconcileBlocks) blockRefreshElapsedTicks %= blockRefreshPeriodTicks;
        // Player movement events keep protocol-mode sessions current. Retain a slower full
        // reconciliation as a fallback without scanning every online player every preview tick.
        if (entityBackend == null || sessionReconcilePending || reconcileBlocks) {
            interiors.pruneSessions(repository);
            sessionReconcilePending = false;
        }
        Set<UUID> valid = new HashSet<>();
        for (JarRecord jar : repository.all()) {
            valid.add(jar.id());
            if (!jar.placed()) {
                if (entityBackend != null) entityBackend.remove(jar.id());
                JarScene existing = scenes.get(jar.id());
                if (interiors.occupants(jar).isEmpty()
                        && (existing == null || existing.sleepers.isEmpty())) {
                    removeScene(jar.id());
                    continue;
                }
                JarScene scene = scenes.computeIfAbsent(jar.id(), ignored -> new JarScene(jar));
                if (!scene.jar.equals(jar)) {
                    scene = replaceScene(scene, jar);
                }
                if (!scene.sealed) spawnSealedSurfaces(scene);
                updateSealedViewers(scene);
                continue;
            }
            JarScene scene = scenes.get(jar.id());
            boolean replaceScene = scene == null || !scene.jar.equals(jar);
            if ((replaceScene || reconcileBlocks) && !isPlaced(jar)) {
                removeScene(jar.id());
                continue;
            }
            boolean newScene = replaceScene;
            if (replaceScene) {
                scene = scene == null ? new JarScene(jar) : replaceScene(scene, jar);
                scenes.put(jar.id(), scene);
            }
            updateViewers(scene);
            if (newScene || reconcileBlocks) {
                scene.exteriorInvalid = true;
                scene.interiorInvalid = true;
            }
            if ((scene.exteriorInvalid && !scene.exteriorViewers.isEmpty())
                    || (scene.interiorInvalid && !scene.interiorViewers.isEmpty())) {
                refreshBlocks(scene, false, false);
            }
            updateOccupants(scene);
            updateOutsidePlayers(scene);
            if (entityBackend != null) entityBackend.update(jar, scene.exteriorViewers, scene.interiorViewers);
        }
        for (UUID id : new ArrayList<>(scenes.keySet())) if (!valid.contains(id)) removeScene(id);
        updateChunkTickets();
    }

    private JarScene replaceScene(JarScene previous, JarRecord jar) {
        Map<UUID, Avatar> sleepers = new HashMap<>(previous.sleepers);
        for (Player viewer : online(previous.interiorViewers)) {
            for (Avatar sleeper : sleepers.values()) sleeper.body.destroy(viewer);
        }
        previous.sleepers.clear();
        previous.removeAll();
        JarScene replacement = new JarScene(jar);
        replacement.sleepers.putAll(sleepers);
        scenes.put(jar.id(), replacement);
        return replacement;
    }

    private void updateViewers(JarScene scene) {
        Location jarCenter = scene.jar.outsideCenter();
        Set<UUID> exterior = exteriorEnabled
                ? nearbyPlayers(jarCenter, exteriorViewerDistance) : Set.of();
        applyExteriorVisibility(scene, exterior);
        updateExteriorPortalPositions(scene);

        Set<UUID> interior = new HashSet<>();
        if (interiorEnabled) {
            // The outside view surrounds the whole cell, so every occupant is a viewer.
            for (Player player : interiors.occupants(scene.jar)) interior.add(player.getUniqueId());
        }
        applyVisibility(scene.interiorViewers, interior, scene.interiorEntities());
    }

    private void updateSealedViewers(JarScene scene) {
        applyExteriorVisibility(scene, Set.of());
        Set<UUID> interior = new HashSet<>();
        for (Player player : interiors.occupants(scene.jar)) interior.add(player.getUniqueId());
        applyVisibility(scene.interiorViewers, interior, scene.interiorEntities());
    }

    private void applyVisibility(Set<UUID> previous, Set<UUID> current, Collection<? extends VirtualEntity> entities) {
        if (previous.equals(current)) return;
        for (UUID id : previous) {
            if (current.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null) for (VirtualEntity entity : entities) entity.destroy(player);
        }
        for (UUID id : current) {
            if (previous.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null) for (VirtualEntity entity : entities) entity.spawn(player);
        }
        previous.clear();
        previous.addAll(current);
    }

    private void applyExteriorVisibility(JarScene scene, Set<UUID> current) {
        if (scene.exteriorViewers.equals(current)) return;
        for (UUID id : scene.exteriorViewers) {
            if (current.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            for (VirtualEntity entity : scene.exteriorEntities()) entity.destroy(player);
            if (scene.exteriorPortal != null) scene.exteriorPortal.destroy(player);
            scene.insetPortalViewers.remove(id);
        }
        for (UUID id : current) {
            if (scene.exteriorViewers.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            for (VirtualEntity entity : scene.exteriorEntities()) entity.spawn(player);
            scene.spawnPortal(player);
        }
        scene.exteriorViewers.clear();
        scene.exteriorViewers.addAll(current);
    }

    private void updateExteriorPortalPositions(JarScene scene) {
        if (scene.exteriorPortal == null) return;
        for (UUID id : scene.exteriorViewers) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            boolean inset = onDoorSide(scene.jar, player.getLocation());
            if (inset == scene.insetPortalViewers.contains(id)) continue;
            scene.exteriorPortal.transform(player, portalTransformation(scene.jar,
                    inset ? FRONT_PORTAL_INWARD : BACK_PORTAL_INWARD));
            if (inset) scene.insetPortalViewers.add(id); else scene.insetPortalViewers.remove(id);
        }
    }

    private void refreshBlocks(JarScene scene, boolean forceExterior, boolean forceInterior) {
        scene.exteriorInvalid |= forceExterior;
        scene.interiorInvalid |= forceInterior;
        if (scene.blockScanRunning) return;
        ExecutorService scanner = blockScanner;
        Location outside = scene.jar.outsideLocation();
        if (!running || scanner == null || outside == null) return;

        boolean scanExterior = scene.exteriorInvalid && !scene.exteriorViewers.isEmpty();
        boolean scanInterior = scene.interiorInvalid && !scene.interiorViewers.isEmpty();
        if (!scanExterior && !scanInterior) return;
        if (scanExterior) scene.exteriorInvalid = false;
        if (scanInterior) scene.interiorInvalid = false;
        scene.blockScanRunning = true;
        int exteriorMaximum = scanExterior && exteriorEnabled ? exteriorMaximumBlocks : 0;
        int outsideMaximum = scanInterior && interiorEnabled ? interiorMaximumBlocks : 0;
        int radius = outsideRadius;
        BlockScanRequest request = new BlockScanRequest(scene.jar.id(), scene.jar, interiors.cell(scene.jar),
                interiors.world(), outside.getWorld(), outside.getBlockX(), outside.getBlockY(), outside.getBlockZ(),
                scanExterior, scanInterior, exteriorMaximum, outsideMaximum, radius);
        CompletableFuture<SnapshotWorld> interiorSnapshot = scanExterior
                ? captureChunks(request.interiorWorld, interiorChunks(request), scanner)
                : CompletableFuture.completedFuture(SnapshotWorld.empty(request.interiorWorld));
        CompletableFuture<SnapshotWorld> outsideSnapshot = scanInterior
                ? captureChunks(request.outsideWorld, outsideChunks(request), scanner)
                : CompletableFuture.completedFuture(SnapshotWorld.empty(request.outsideWorld));
        CompletableFuture.allOf(interiorSnapshot, outsideSnapshot).thenRun(() -> {
            try {
                scanner.execute(() -> {
                    BlockScanResult result;
                    try {
                        SnapshotBlockScan snapshot = new SnapshotBlockScan(
                                request, interiorSnapshot.join(), outsideSnapshot.join());
                        List<InteriorBox> interior = request.scanExterior
                                ? sampleInterior(snapshot, request.exteriorMaximum) : List.of();
                        List<OutsideSample> outsideBlocks = request.scanInterior
                                ? sampleOutside(snapshot, request.radius, request.outsideMaximum) : List.of();
                        List<FloorSample> floor = request.scanInterior ? sampleFloor(snapshot) : List.of();
                        result = new BlockScanResult(request, interior, outsideBlocks, floor,
                                request.scanExterior ? blockFingerprint(interior) : Integer.MIN_VALUE,
                                request.scanInterior
                                        ? blockFingerprint(outsideBlocks, floor, request.radius)
                                        : Integer.MIN_VALUE);
                    } catch (RuntimeException exception) {
                        plugin.getLogger().warning("Could not sample jar preview blocks asynchronously: "
                                + exception.getMessage());
                        result = new BlockScanResult(request, List.of(), List.of(), List.of(),
                                Integer.MIN_VALUE, Integer.MIN_VALUE);
                    }
                    BlockScanResult completed = result;
                    if (running && blockScanner == scanner) {
                        plugin.getServer().getScheduler().runTask(plugin, () -> applyBlockScan(completed));
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                scene.blockScanRunning = false;
                scene.exteriorInvalid |= scanExterior;
                scene.interiorInvalid |= scanInterior;
            }
        });
    }

    private void applyBlockScan(BlockScanResult result) {
        if (!running) return;
        JarScene scene = scenes.get(result.request.jarId);
        if (scene == null || scene.jar != result.request.jar) return;
        scene.blockScanRunning = false;
        boolean exteriorFailed = result.request.scanExterior
                && result.exteriorFingerprint == Integer.MIN_VALUE;
        boolean interiorFailed = result.request.scanInterior
                && result.interiorFingerprint == Integer.MIN_VALUE;
        scene.exteriorInvalid |= exteriorFailed;
        scene.interiorInvalid |= interiorFailed;

        if (result.request.scanExterior && result.exteriorFingerprint != Integer.MIN_VALUE) {
            if (scene.exteriorFingerprint != result.exteriorFingerprint) {
                if (result.interior.size() >= result.request.exteriorMaximum
                        && result.request.exteriorMaximum > 0) {
                    plugin.getLogger().warning("Jar " + scene.jar.id()
                            + " has more visible blocks than preview.exterior.max-blocks ("
                            + result.request.exteriorMaximum + "); the miniature is truncated.");
                }
                List<VirtualBlockDisplay> stale = new ArrayList<>(scene.exteriorBlocks);
                VirtualBlockDisplay stalePortal = scene.exteriorPortal;
                scene.exteriorBlocks.clear();
                scene.exteriorPortal = null;
                spawnExteriorBlocks(scene, result.interior);
                showTo(scene.exteriorViewers, scene.exteriorBlocks);
                for (Player viewer : online(scene.exteriorViewers)) scene.spawnPortal(viewer);
                removeEntities(stale, scene.exteriorViewers);
                if (stalePortal != null) {
                    for (Player viewer : online(scene.exteriorViewers)) stalePortal.destroy(viewer);
                }
                scene.exteriorFingerprint = result.exteriorFingerprint;
            }
        }

        if (result.request.scanInterior && result.interiorFingerprint != Integer.MIN_VALUE) {
            if (scene.interiorFingerprint != result.interiorFingerprint) {
                List<VirtualBlockDisplay> stale = new ArrayList<>(scene.interiorBlocks);
                scene.interiorBlocks.clear();
                spawnInteriorFloor(scene, result.floor);
                spawnInteriorGlassSurfaces(scene);
                spawnInteriorBlocks(scene, result.outside);
                showTo(scene.interiorViewers, scene.interiorBlocks);
                removeEntities(stale, scene.interiorViewers);
                scene.interiorFingerprint = result.interiorFingerprint;
            }
        }
        if (!exteriorFailed && !interiorFailed && (scene.exteriorInvalid || scene.interiorInvalid)) {
            refreshBlocks(scene, false, false);
        }
    }

    private CompletableFuture<SnapshotWorld> captureChunks(World world, Set<ChunkCoordinate> coordinates,
                                                            ExecutorService scanner) {
        CompletableFuture<SnapshotWorld> result = new CompletableFuture<>();
        captureChunkBatch(world, List.copyOf(coordinates), 0, new HashMap<>(), result, scanner);
        return result;
    }

    private void captureChunkBatch(World world, List<ChunkCoordinate> coordinates, int start,
                                   Map<Long, ChunkSnapshot> snapshots,
                                   CompletableFuture<SnapshotWorld> result, ExecutorService scanner) {
        if (!running || blockScanner != scanner || result.isDone()) {
            result.cancel(false);
            return;
        }
        int end = Math.min(coordinates.size(), start + CHUNK_SNAPSHOTS_PER_TICK);
        List<CompletableFuture<ChunkSnapshot>> batch = new ArrayList<>(end - start);
        for (int index = start; index < end; index++) {
            ChunkCoordinate coordinate = coordinates.get(index);
            batch.add(world.getChunkAtAsync(coordinate.x, coordinate.z, false)
                    .thenApply(chunk -> chunk == null ? null
                            : chunk.getChunkSnapshot(false, false, false, false))
                    .exceptionally(ignored -> null));
        }
        CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).thenRun(() -> {
            for (CompletableFuture<ChunkSnapshot> future : batch) {
                ChunkSnapshot snapshot = future.join();
                if (snapshot != null) {
                    snapshots.put(Chunk.getChunkKey(snapshot.getX(), snapshot.getZ()), snapshot);
                }
            }
            if (end >= coordinates.size()) {
                result.complete(new SnapshotWorld(
                        world.getMinHeight(), world.getMaxHeight(), Map.copyOf(snapshots)));
            } else if (running) {
                plugin.getServer().getScheduler().runTask(plugin,
                        () -> captureChunkBatch(world, coordinates, end, snapshots, result, scanner));
            }
        });
    }

    private Set<ChunkCoordinate> interiorChunks(BlockScanRequest request) {
        return interiorChunks(request.jar, request.cell);
    }

    private Set<ChunkCoordinate> interiorChunks(JarRecord jar, CellLayout.Cell cell) {
        Set<ChunkCoordinate> result = new HashSet<>();
        int scale = jar.scale();
        for (JarAssembly.Cell assemblyCell : jar.assembly().cells()) {
            int minX = cell.minX() + assemblyCell.x() * scale - 1;
            int maxX = cell.minX() + (assemblyCell.x() + 1) * scale;
            int minZ = cell.minZ() + assemblyCell.z() * scale - 1;
            int maxZ = cell.minZ() + (assemblyCell.z() + 1) * scale;
            addChunks(result, minX, maxX, minZ, maxZ);
        }
        return result;
    }

    private Set<ChunkCoordinate> outsideChunks(BlockScanRequest request) {
        return outsideChunks(request.jar, request.outsideX, request.outsideZ, request.radius);
    }

    private Set<ChunkCoordinate> outsideChunks(JarRecord jar, int outsideX, int outsideZ, int radius) {
        JarAssembly assembly = jar.assembly();
        Set<ChunkCoordinate> result = new HashSet<>();
        addChunks(result, outsideX - radius - 1,
                outsideX + assembly.width() + radius,
                outsideZ - radius - 1,
                outsideZ + assembly.depth() + radius);
        return result;
    }

    private static void addChunks(Set<ChunkCoordinate> result, int minX, int maxX, int minZ, int maxZ) {
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                result.add(new ChunkCoordinate(chunkX, chunkZ));
            }
        }
    }

    private void updateChunkTickets() {
        Set<PreviewChunk> desired = new HashSet<>();
        for (JarScene scene : scenes.values()) {
            if (!scene.jar.placed()) continue;
            if (!scene.exteriorViewers.isEmpty()) {
                desired.addAll(scene.exteriorViewChunks);
            }
            if (!scene.interiorViewers.isEmpty()) {
                desired.addAll(scene.interiorViewChunks);
            }
        }
        desiredChunkTickets = Set.copyOf(desired);

        for (Iterator<PreviewChunk> iterator = activeChunkTickets.iterator(); iterator.hasNext();) {
            PreviewChunk chunk = iterator.next();
            if (desired.contains(chunk)) continue;
            World world = Bukkit.getWorld(chunk.worldId);
            if (world != null) world.removePluginChunkTicket(chunk.x, chunk.z, plugin);
            iterator.remove();
        }
        scheduleChunkTicketLoads();
    }

    private static Set<PreviewChunk> previewChunks(UUID worldId, Set<ChunkCoordinate> chunks) {
        Set<PreviewChunk> result = new HashSet<>(chunks.size());
        for (ChunkCoordinate chunk : chunks) result.add(new PreviewChunk(worldId, chunk.x, chunk.z));
        return Set.copyOf(result);
    }

    private void scheduleChunkTicketLoads() {
        if (!running || chunkTicketTaskId != -1 || !hasMissingChunkTickets()) return;
        chunkTicketTaskId = plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            chunkTicketTaskId = -1;
            loadChunkTicketBatch();
            scheduleChunkTicketLoads();
        }, 1L);
    }

    private boolean hasMissingChunkTickets() {
        for (PreviewChunk chunk : desiredChunkTickets) {
            if (!activeChunkTickets.contains(chunk) && !pendingChunkTickets.containsKey(chunk)
                    && Bukkit.getWorld(chunk.worldId) != null) return true;
        }
        return false;
    }

    private void loadChunkTicketBatch() {
        long generation = chunkTicketGeneration;
        int scheduled = 0;
        for (PreviewChunk requested : desiredChunkTickets) {
            if (scheduled >= CHUNK_TICKET_LOADS_PER_TICK) break;
            if (activeChunkTickets.contains(requested)
                    || pendingChunkTickets.putIfAbsent(requested, generation) != null) continue;
            World world = Bukkit.getWorld(requested.worldId);
            if (world == null) {
                pendingChunkTickets.remove(requested, generation);
                continue;
            }
            scheduled++;
            world.getChunkAtAsync(requested.x, requested.z, true).whenComplete((chunk, error) ->
                    completeChunkTicketLoad(requested, generation, chunk, error));
        }
    }

    private void completeChunkTicketLoad(PreviewChunk requested, long generation,
                                         Chunk chunk, Throwable error) {
        Runnable completion = () -> {
            if (!pendingChunkTickets.remove(requested, generation)) return;
            if (error == null && chunk != null && running && generation == chunkTicketGeneration
                    && desiredChunkTickets.contains(requested)) {
                chunk.addPluginChunkTicket(plugin);
                activeChunkTickets.add(requested);
                if (allDesiredChunkTicketsActive()) refreshAfterChunkLoads();
            }
            scheduleChunkTicketLoads();
        };
        if (Bukkit.isPrimaryThread()) {
            completion.run();
        } else if (plugin.isEnabled()) {
            try {
                plugin.getServer().getScheduler().runTask(plugin, completion);
            } catch (IllegalStateException ignored) {
                pendingChunkTickets.remove(requested, generation);
            }
        } else {
            pendingChunkTickets.remove(requested, generation);
        }
    }

    private boolean allDesiredChunkTicketsActive() {
        return !desiredChunkTickets.isEmpty() && activeChunkTickets.containsAll(desiredChunkTickets);
    }

    private void refreshAfterChunkLoads() {
        boolean invalidated = false;
        for (JarScene scene : scenes.values()) {
            if (!scene.jar.placed()) continue;
            if (!scene.exteriorViewers.isEmpty()) {
                scene.exteriorInvalid = true;
                invalidated = true;
            }
            if (!scene.interiorViewers.isEmpty()) {
                scene.interiorInvalid = true;
                invalidated = true;
            }
        }
        if (invalidated) scheduleBlockRefresh();
    }

    private void releaseChunkTickets() {
        for (PreviewChunk chunk : activeChunkTickets) {
            World world = Bukkit.getWorld(chunk.worldId);
            if (world != null) world.removePluginChunkTicket(chunk.x, chunk.z, plugin);
        }
        activeChunkTickets.clear();
    }

    /** Exact 1:1 translation of the interior: one display per visible block. Merging neighbours
     *  is not an option because scaled displays stretch their texture instead of tiling it. */
    private List<InteriorBox> sampleInterior(SnapshotBlockScan snapshot, int maximum) {
        if (maximum == 0) return List.of();
        BlockScanRequest request = snapshot.request;
        JarRecord jar = request.jar;
        CellLayout.Cell cell = request.cell;
        int sizeX = jar.interiorSizeX(), sizeY = jar.interiorSizeY(), sizeZ = jar.interiorSizeZ();
        SnapshotWorld world = snapshot.interior;
        int scale = jar.scale();
        List<InteriorBox> result = new ArrayList<>(Math.min(maximum, 2048));
        List<JarAssembly.Cell> cells = jar.assembly().cells().stream()
                .sorted(Comparator.comparingInt(JarAssembly.Cell::y)
                        .thenComparingInt(JarAssembly.Cell::x)
                        .thenComparingInt(JarAssembly.Cell::z)).toList();
        for (JarAssembly.Cell assemblyCell : cells) {
            int minX = Math.max(1, assemblyCell.x() * scale);
            int maxX = Math.min(sizeX - 1, (assemblyCell.x() + 1) * scale);
            int minY = Math.max(0, assemblyCell.y() * scale);
            int maxY = Math.min(sizeY - 1, (assemblyCell.y() + 1) * scale);
            int minZ = Math.max(1, assemblyCell.z() * scale);
            int maxZ = Math.min(sizeZ - 1, (assemblyCell.z() + 1) * scale);
            for (int y = minY; y < maxY; y++) {
                for (int x = minX; x < maxX; x++) for (int z = minZ; z < maxZ; z++) {
                    int worldX = cell.minX() + x, worldY = cell.minY() + y, worldZ = cell.minZ() + z;
                    Material material = world.type(worldX, worldY, worldZ);
                    if (!renderable(material, false)
                            || !exposed(world, worldX, worldY, worldZ)) continue;
                    BlockData blockData = world.blockData(worldX, worldY, worldZ);
                    result.add(new InteriorBox(x, y, z, 1, 1, 1, blockData));
                    if (result.size() >= maximum) return result;
                }
            }
        }
        return result;
    }

    private List<FloorSample> sampleFloor(SnapshotBlockScan snapshot) {
        BlockScanRequest request = snapshot.request;
        JarRecord jar = request.jar;
        SnapshotWorld world = snapshot.outside;
        List<FloorSample> result = new ArrayList<>();
        JarAssembly assembly = jar.assembly();
        for (JarAssembly.Cell cell : assembly.cells().stream()
                .filter(cell -> !assembly.contains(cell.x(), cell.y() - 1, cell.z()))
                .sorted(Comparator.comparingInt(JarAssembly.Cell::y)
                        .thenComparingInt(JarAssembly.Cell::z)
                        .thenComparingInt(JarAssembly.Cell::x)).toList()) {
            int floorY = request.outsideY + cell.y() - 1;
            BlockData blockData = floorY < world.minHeight || floorY >= world.maxHeight
                    ? Material.AIR.createBlockData()
                    : world.blockData(request.outsideX + cell.x(), floorY,
                    request.outsideZ + cell.z());
            result.add(new FloorSample(cell.x(), cell.y(), cell.z(), blockData));
        }
        return List.copyOf(result);
    }

    private List<OutsideSample> sampleOutside(SnapshotBlockScan snapshot, int radius, int maximum) {
        if (maximum == 0) return List.of();
        BlockScanRequest request = snapshot.request;
        List<OutsideSample> result = new ArrayList<>(maximum);
        SnapshotWorld world = snapshot.outside;
        JarAssembly assembly = request.jar.assembly();
        Set<JarAssembly.Cell> occupied = assembly.cells();
        // The support block directly below the jar is rendered separately as the cell-wide floor.
        for (OutsideOffset offset : outsideOffsets(radius, assembly)) {
            if (occupied.contains(new JarAssembly.Cell(offset.dx, offset.dy, offset.dz))
                    || occupied.contains(new JarAssembly.Cell(offset.dx, offset.dy + 1, offset.dz))) continue;
            int x = request.outsideX + offset.dx;
            int y = request.outsideY + offset.dy;
            int z = request.outsideZ + offset.dz;
            if (y < world.minHeight || y >= world.maxHeight) continue;
            Material material = world.type(x, y, z);
            // Buried blocks would eat the whole budget before any surface is reached.
            if (renderable(material, true) && exposed(world, x, y, z)) {
                BlockData blockData = world.blockData(x, y, z);
                result.add(new OutsideSample(offset.dx, offset.dy, offset.dz, blockData));
                if (result.size() >= maximum) break;
            }
        }
        return result;
    }

    private List<OutsideOffset> outsideOffsets(int radius, JarAssembly assembly) {
        OutsideArea area = new OutsideArea(radius, assembly.width(), assembly.height(), assembly.depth());
        return outsideOffsets.computeIfAbsent(area, value -> {
            List<OutsideOffset> offsets = new ArrayList<>();
            int radiusSquared = value.radius * value.radius;
            for (int dy = -value.radius; dy < value.height + value.radius; dy++) {
                for (int dx = -value.radius; dx < value.width + value.radius; dx++) {
                    for (int dz = -value.radius; dz < value.depth + value.radius; dz++) {
                        int distanceX = dx < 0 ? -dx : dx >= value.width ? dx - value.width + 1 : 0;
                        int distanceZ = dz < 0 ? -dz : dz >= value.depth ? dz - value.depth + 1 : 0;
                        int distanceY = dy < 0 ? -dy : dy >= value.height ? dy - value.height + 1 : 0;
                        int distanceSquared = distanceX * distanceX + distanceY * distanceY
                                + distanceZ * distanceZ;
                        if (distanceSquared > radiusSquared) continue;
                        offsets.add(new OutsideOffset(dx, dy, dz, distanceSquared));
                    }
                }
            }
            offsets.sort(Comparator.comparingInt(OutsideOffset::distanceSquared));
            return List.copyOf(offsets);
        });
    }

    private static int blockFingerprint(List<InteriorBox> blocks) {
        int fingerprint = 1;
        for (InteriorBox block : blocks) {
            fingerprint = 31 * fingerprint + Objects.hash(
                    block.x, block.y, block.z, block.blockData.getAsString());
        }
        return fingerprint;
    }

    private static int blockFingerprint(List<OutsideSample> blocks, List<FloorSample> floor, int radius) {
        int fingerprint = Objects.hash(radius);
        for (FloorSample sample : floor) {
            fingerprint = 31 * fingerprint + Objects.hash(
                    sample.cellX, sample.cellY, sample.cellZ, sample.blockData.getAsString());
        }
        for (OutsideSample block : blocks) {
            fingerprint = 31 * fingerprint + Objects.hash(
                    block.dx, block.dy, block.dz, block.blockData.getAsString());
        }
        return fingerprint;
    }

    private static boolean exposed(SnapshotWorld world, int x, int y, int z) {
        return !world.type(x + 1, y, z).isOccluding() || !world.type(x - 1, y, z).isOccluding()
                || !world.type(x, y + 1, z).isOccluding() || !world.type(x, y - 1, z).isOccluding()
                || !world.type(x, y, z + 1).isOccluding() || !world.type(x, y, z - 1).isOccluding();
    }

    private static boolean renderable(Material material, boolean includeGlass) {
        return material.isBlock() && !material.isAir() && (includeGlass || material != Material.GLASS)
                && material != Material.BARRIER && material != Material.STRUCTURE_VOID;
    }

    private void spawnExteriorBlocks(JarScene scene, List<InteriorBox> boxes) {
        Location origin = scene.jar.outsideLocation().clone().add(.5, 0, .5);
        float unit = 1f / scene.jar.scale();
        // Sub-pixel shrink so touching boxes (and the glass faces) never share a plane and z-fight.
        float epsilon = 0.0005f;
        for (InteriorBox box : boxes) {
            VirtualBlockDisplay display = new VirtualBlockDisplay(origin, box.blockData, new Matrix4f()
                    .translation(-.5f + box.x * unit + epsilon, box.y * unit + epsilon, -.5f + box.z * unit + epsilon)
                    .scale(box.sx * unit - 2 * epsilon, box.sy * unit - 2 * epsilon, box.sz * unit - 2 * epsilon));
            scene.exteriorBlocks.add(display);
        }
        spawnExteriorPortal(scene);
    }

    private void spawnExteriorPortal(JarScene scene) {
        if (!scene.jar.hasPortal()) return;
        org.bukkit.block.BlockFace door = scene.jar.door();
        scene.exteriorPortal = new VirtualBlockDisplay(
                scene.jar.outsideLocation(), portalData(door), portalTransformation(scene.jar, 0f));
    }

    private static Matrix4f portalTransformation(JarRecord jar, float inward) {
        org.bukkit.block.BlockFace door = jar.door();
        Location doorBlock = jar.doorBlockLocation();
        float tileX = doorBlock.getBlockX() - jar.x();
        float tileZ = doorBlock.getBlockZ() - jar.z();
        float tileY = doorBlock.getBlockY() - jar.y();
        Matrix4f transformation = new Matrix4f().translate(tileX, tileY, tileZ);
        float offset = 0.501f - inward;
        if (door == org.bukkit.block.BlockFace.NORTH) transformation.translate(0, 0, -offset);
        else if (door == org.bukkit.block.BlockFace.SOUTH) transformation.translate(0, 0, offset);
        else if (door == org.bukkit.block.BlockFace.WEST) transformation.translate(-offset, 0, 0);
        else if (door == org.bukkit.block.BlockFace.EAST) transformation.translate(offset, 0, 0);
        return transformation;
    }

    private static boolean onDoorSide(JarRecord jar, Location viewer) {
        return onPortalSide(viewer, jar.x() + jar.doorX(), jar.z() + jar.doorZ(), jar.door());
    }

    static boolean onPortalSide(Location viewer, int portalX, int portalZ,
                                org.bukkit.block.BlockFace portalFace) {
        double planeX = portalX + (portalFace == org.bukkit.block.BlockFace.EAST ? 1.0
                : portalFace == org.bukkit.block.BlockFace.WEST ? 0.0 : .5);
        double planeZ = portalZ + (portalFace == org.bukkit.block.BlockFace.SOUTH ? 1.0
                : portalFace == org.bukkit.block.BlockFace.NORTH ? 0.0 : .5);
        double dx = viewer.getX() - planeX, dz = viewer.getZ() - planeZ;
        return dx * portalFace.getModX() + dz * portalFace.getModZ() > PORTAL_SIDE_SWITCH_OFFSET;
    }

    private void spawnInteriorBlocks(JarScene scene, List<OutsideSample> samples) {
        // Inside the jar the player is 1/scale sized, so every outside block renders as a cell-sized
        // cube tiled around the cell exactly where the real block sits around the jar outside.
        // The entities all live at the cell center (kept loaded by the occupant) and are pushed
        // into place through the transformation, since chunks that far out are never loaded.
        CellLayout.Cell cell = interiors.cell(scene.jar);
        int scale = scene.jar.scale();
        float halfX = scene.jar.interiorSizeX() / 2f;
        float halfY = scene.jar.interiorSizeY() / 2f;
        float halfZ = scene.jar.interiorSizeZ() / 2f;
        Location center = new Location(interiors.world(), cell.minX() + halfX,
                cell.minY() + halfY, cell.minZ() + halfZ);
        for (OutsideSample sample : samples) {
            VirtualBlockDisplay display = new VirtualBlockDisplay(center, sample.blockData, new Matrix4f()
                    .translation(sample.dx * (float) scale - halfX, sample.dy * (float) scale - halfY,
                            sample.dz * (float) scale - halfZ)
                    .scale((float) scale));
            scene.interiorBlocks.add(display);
        }
    }

    private void spawnInteriorFloor(JarScene scene, List<FloorSample> samples) {
        CellLayout.Cell cell = interiors.cell(scene.jar);
        int scale = scene.jar.scale();
        float sizeX = scene.jar.interiorSizeX(), sizeY = scene.jar.interiorSizeY();
        float sizeZ = scene.jar.interiorSizeZ();
        float halfX = sizeX / 2f, halfY = sizeY / 2f, halfZ = sizeZ / 2f;
        Location center = new Location(interiors.world(), cell.minX() + halfX,
                cell.minY() + halfY, cell.minZ() + halfZ);
        for (FloorSample sample : samples) {
            if (!renderable(sample.blockData.getMaterial(), true)) continue;
            VirtualBlockDisplay display = new VirtualBlockDisplay(center, sample.blockData,
                    // Keep its top face just below the barrier floor to avoid display collisions.
                    new Matrix4f().translation(sample.cellX * scale - halfX,
                            (sample.cellY - 1) * scale + 1f - FLOOR_BLOCK_DROP - halfY,
                            sample.cellZ * scale - halfZ).scale(scale));
            scene.interiorBlocks.add(display);
        }
    }

    private void spawnInteriorGlassSurfaces(JarScene scene) {
        spawnAssemblySurfaces(scene, Material.GLASS.createBlockData(), true, true, 0f);
    }

    private void spawnSealedSurfaces(JarScene scene) {
        List<VirtualBlockDisplay> stale = new ArrayList<>(scene.interiorBlocks);
        scene.interiorBlocks.clear();
        BlockData black = Material.BLACK_CONCRETE.createBlockData();
        spawnAssemblySurfaces(scene, black, false, false, SEALED_BACKING_OUTWARD);
        if (scene.jar.hasPortal()) spawnDoorSurface(scene, portalData(scene.jar.door()), 0f);

        showTo(scene.interiorViewers, scene.interiorBlocks);
        removeEntities(stale, scene.interiorViewers);
        scene.sealed = true;
        scene.exteriorInvalid = false;
        scene.interiorInvalid = false;
    }

    private void spawnAssemblySurfaces(JarScene scene, BlockData blockData,
                                       boolean paneSides, boolean portalAtDoor, float doorOutward) {
        CellLayout.Cell cell = interiors.cell(scene.jar);
        int scale = scene.jar.scale();
        float halfX = scene.jar.interiorSizeX() / 2f, halfY = scene.jar.interiorSizeY() / 2f;
        float halfZ = scene.jar.interiorSizeZ() / 2f;
        Location center = new Location(interiors.world(), cell.minX() + halfX,
                cell.minY() + halfY, cell.minZ() + halfZ);
        JarAssembly assembly = scene.jar.assembly();
        JarAssembly.Cell doorCell = scene.jar.hasPortal()
                ? new JarAssembly.Cell(scene.jar.doorX(), scene.jar.doorY(), scene.jar.doorZ()) : null;
        for (JarAssembly.Cell assemblyCell : assembly.cells()) {
            for (org.bukkit.block.BlockFace face : List.of(org.bukkit.block.BlockFace.WEST,
                    org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.DOWN,
                    org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.NORTH,
                    org.bukkit.block.BlockFace.SOUTH)) {
                if (assembly.contains(assemblyCell.x() + face.getModX(),
                        assemblyCell.y() + face.getModY(), assemblyCell.z() + face.getModZ())) continue;
                boolean door = doorCell != null && assemblyCell.equals(doorCell) && face == scene.jar.door();
                BlockData data = portalAtDoor && door ? portalData(face)
                        : paneSides && face.getModY() == 0 ? sidePaneData(face) : blockData;
                spawnSurface(scene, center, data, cellSurfaceTransformation(assemblyCell, face, scale,
                        halfX, halfY, halfZ, door ? doorOutward : 0f));
            }
        }
    }

    private static BlockData sidePaneData(org.bukkit.block.BlockFace face) {
        MultipleFacing pane = (MultipleFacing) Material.GLASS_PANE.createBlockData();
        boolean northSouthWall = face == org.bukkit.block.BlockFace.NORTH
                || face == org.bukkit.block.BlockFace.SOUTH;
        pane.setFace(northSouthWall ? org.bukkit.block.BlockFace.EAST
                : org.bukkit.block.BlockFace.NORTH, true);
        pane.setFace(northSouthWall ? org.bukkit.block.BlockFace.WEST
                : org.bukkit.block.BlockFace.SOUTH, true);
        return pane;
    }

    private void spawnDoorSurface(JarScene scene, BlockData data, float outward) {
        if (!scene.jar.hasPortal()) return;
        CellLayout.Cell cell = interiors.cell(scene.jar);
        int scale = scene.jar.scale();
        float halfX = scene.jar.interiorSizeX() / 2f, halfY = scene.jar.interiorSizeY() / 2f;
        float halfZ = scene.jar.interiorSizeZ() / 2f;
        Location center = new Location(interiors.world(), cell.minX() + halfX,
                cell.minY() + halfY, cell.minZ() + halfZ);
        JarAssembly.Cell doorCell = new JarAssembly.Cell(
                scene.jar.doorX(), scene.jar.doorY(), scene.jar.doorZ());
        spawnSurface(scene, center, data, cellSurfaceTransformation(doorCell, scene.jar.door(), scale,
                halfX, halfY, halfZ, outward));
    }

    private static Matrix4f cellSurfaceTransformation(JarAssembly.Cell cell,
                                                       org.bukkit.block.BlockFace face,
                                                       int scale, float halfX, float halfY,
                                                       float halfZ, float outward) {
        float x = cell.x() * scale - halfX;
        float y = cell.y() * scale - halfY;
        float z = cell.z() * scale - halfZ;
        if (face == org.bukkit.block.BlockFace.WEST) x += 1f - NON_FLOOR_SURFACE_THICKNESS;
        if (face == org.bukkit.block.BlockFace.EAST) x += scale - 1f;
        if (face == org.bukkit.block.BlockFace.UP) y += scale - 1f;
        if (face == org.bukkit.block.BlockFace.NORTH) z += 1f - NON_FLOOR_SURFACE_THICKNESS;
        if (face == org.bukkit.block.BlockFace.SOUTH) z += scale - 1f;
        x += face.getModX() * outward;
        y += face.getModY() * outward;
        z += face.getModZ() * outward;
        if (face == org.bukkit.block.BlockFace.DOWN) y += 1f - FLOOR_BLOCK_DROP;
        float surfaceExtension = 1f - NON_FLOOR_SURFACE_THICKNESS;
        float halfExtension = surfaceExtension / 2f;
        float extendedScale = scale + surfaceExtension;
        if (face == org.bukkit.block.BlockFace.UP) {
            x -= halfExtension;
            z -= halfExtension;
        } else if (face == org.bukkit.block.BlockFace.NORTH
                || face == org.bukkit.block.BlockFace.SOUTH) {
            x -= halfExtension;
            y -= halfExtension;
        } else if (face == org.bukkit.block.BlockFace.WEST
                || face == org.bukkit.block.BlockFace.EAST) {
            y -= halfExtension;
            z -= halfExtension;
        }
        Matrix4f transformation = new Matrix4f().translation(x, y, z);
        if (face == org.bukkit.block.BlockFace.DOWN) {
            return transformation.scale(scale, FLOOR_GLASS_HEIGHT, scale);
        }
        if (face == org.bukkit.block.BlockFace.UP) {
            return transformation.scale(extendedScale, NON_FLOOR_SURFACE_THICKNESS, extendedScale);
        }
        return face == org.bukkit.block.BlockFace.NORTH || face == org.bukkit.block.BlockFace.SOUTH
                ? transformation.scale(extendedScale, extendedScale, NON_FLOOR_SURFACE_THICKNESS)
                : transformation.scale(NON_FLOOR_SURFACE_THICKNESS, extendedScale, extendedScale);
    }

    private void spawnSurface(JarScene scene, Location center, BlockData blockData, Matrix4f transformation) {
        scene.interiorBlocks.add(new VirtualBlockDisplay(center, blockData, transformation));
    }

    private static BlockData portalData(org.bukkit.block.BlockFace door) {
        Orientable portal = (Orientable) Material.NETHER_PORTAL.createBlockData();
        portal.setAxis(door == org.bukkit.block.BlockFace.NORTH || door == org.bukkit.block.BlockFace.SOUTH
                ? Axis.X : Axis.Z);
        return portal;
    }

    private void updateOccupants(JarScene scene) {
        if (entityBackend != null) {
            removeAbsent(scene, scene.occupants, Set.of());
            return;
        }
        Set<UUID> present = new HashSet<>();
        CellLayout.Cell cell = interiors.cell(scene.jar);
        int maximum = exteriorMaximumPlayerMarkers;
        for (Player target : interiors.occupants(scene.jar)) {
            if (present.size() >= maximum || !exteriorEnabled) break;
            present.add(target.getUniqueId());
            double x = (target.getX() - cell.minX()) / scene.jar.scale() - .5;
            double y = (target.getY() - cell.minY()) / scene.jar.scale();
            double z = (target.getZ() - cell.minZ()) / scene.jar.scale() - .5;
            Location mapped = scene.jar.outsideLocation().clone().add(.5 + x, y, .5 + z);
            updateAvatar(scene, scene.occupants, target, mapped, 1f / scene.jar.scale(), scene.exteriorViewers);
        }
        removeAbsent(scene, scene.occupants, present);
    }

    private void updateOutsidePlayers(JarScene scene) {
        if (entityBackend != null) {
            removeAbsent(scene, scene.outsiders, Set.of());
            return;
        }
        if (!interiorShowPlayers) {
            removeAbsent(scene, scene.outsiders, Set.of());
            return;
        }
        int radius = outsideRadius;
        Location jar = scene.jar.outsideCenter();
        Set<UUID> present = new HashSet<>();
        int maximum = interiorMaximumPlayerMarkers;
        for (Player target : jar.getWorld().getNearbyPlayers(jar, radius)) {
            if (present.size() >= maximum) break;
            if (target.getLocation().distanceSquared(jar) > radius * radius) continue;
            present.add(target.getUniqueId());
            Location mapped = mapOutsideLocation(scene.jar, target.getLocation());
            // An outside player is scale times larger than an occupant.
            updateAvatar(scene, scene.outsiders, target, mapped, scene.jar.scale(), scene.interiorViewers);
        }
        removeAbsent(scene, scene.outsiders, present);
    }

    private Location mapOutsideLocation(JarRecord jar, Location outside) {
        Location block = jar.outsideLocation();
        int scale = jar.scale();
        CellLayout.Cell cell = interiors.cell(jar);
        double dx = outside.getX() - block.getX(), dy = outside.getY() - block.getY(), dz = outside.getZ() - block.getZ();
        // Feet position of the outside player, scaled up; the mannequin body extends from there.
        return new Location(interiors.world(), cell.minX() + dx * scale,
                cell.minY() + 1 + dy * scale, cell.minZ() + dz * scale);
    }

    /** Mirrors a real player as a scaled mannequin: full model, skin, name, pose and equipment.
     *  The scale attribute is clamped by the game to [0.0625, 16] on any entity, real or fake. */
    private void updateAvatar(JarScene scene, Map<UUID, Avatar> avatars, Player target, Location location,
                              float scale, Set<UUID> viewers) {
        Avatar avatar = avatars.get(target.getUniqueId());
        if (avatar == null) {
            VirtualMannequin body = new VirtualMannequin(target, location, scale);
            avatar = new Avatar(body);
            avatars.put(target.getUniqueId(), avatar);
            showTo(viewers, List.of(body));
        }
        location.setYaw(target.getLocation().getYaw());
        location.setPitch(target.getLocation().getPitch());
        avatar.body.update(target, location, online(viewers));
    }

    private Set<UUID> nearbyPlayers(Location center, double radius) {
        Set<UUID> result = new HashSet<>();
        for (Player player : center.getWorld().getNearbyPlayers(center, radius)) {
            if (player.getLocation().distanceSquared(center) <= radius * radius) result.add(player.getUniqueId());
        }
        return result;
    }

    private void showTo(Set<UUID> viewers, Collection<? extends VirtualEntity> entities) {
        for (UUID id : viewers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) for (VirtualEntity entity : entities) entity.spawn(player);
        }
    }

    private Collection<Player> online(Set<UUID> viewers) {
        List<Player> players = new ArrayList<>(viewers.size());
        for (UUID id : viewers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) players.add(player);
        }
        return players;
    }

    private void removeAbsent(JarScene scene, Map<UUID, Avatar> avatars, Set<UUID> present) {
        for (UUID id : new ArrayList<>(avatars.keySet())) if (!present.contains(id)) scene.removeAvatar(id, avatars);
    }

    private void removeEntities(Collection<? extends VirtualEntity> entities, Set<UUID> viewers) {
        for (Player viewer : online(viewers)) for (VirtualEntity entity : entities) entity.destroy(viewer);
    }

    private boolean isPlaced(JarRecord jar) {
        World world = Bukkit.getWorld(jar.world());
        if (!jar.placed() || world == null) return false;
        JarAssembly assembly = jar.assembly();
        int minimumY = jar.y() + assembly.minY();
        int maximumY = jar.y() + assembly.maxY();
        if (minimumY < world.getMinHeight() || maximumY > world.getMaxHeight()) return false;

        // Placement validation is a fallback for changes missed by events. Never acquire a
        // chunk ticket just to reconcile a preview for an area with no active players.
        int minimumChunkX = (jar.x() + assembly.minX()) >> 4;
        int maximumChunkX = (jar.x() + assembly.maxX() - 1) >> 4;
        int minimumChunkZ = (jar.z() + assembly.minZ()) >> 4;
        int maximumChunkZ = (jar.z() + assembly.maxZ() - 1) >> 4;
        for (int chunkX = minimumChunkX; chunkX <= maximumChunkX; chunkX++) {
            for (int chunkZ = minimumChunkZ; chunkZ <= maximumChunkZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) return true;
            }
        }
        for (JarAssembly.Cell cell : assembly.cells()) {
            if (world.getBlockAt(jar.x() + cell.x(), jar.y() + cell.y(),
                    jar.z() + cell.z()).getType() != Material.GLASS) return false;
        }
        return true;
    }

    private int bounded(String path, int fallback, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, plugin.getConfig().getInt(path, fallback)));
    }

    private double positive(String path, double fallback) {
        return Math.max(.5, plugin.getConfig().getDouble(path, fallback));
    }

    private void loadSettings() {
        updatePeriodTicks = Math.max(1L, plugin.getConfig().getLong("preview.update-ticks", 5L));
        blockRefreshPeriodTicks = Math.max(1L,
                plugin.getConfig().getLong("preview.block-refresh-ticks", 100L));
        blockUpdateTicks = Math.max(1L, plugin.getConfig().getLong("preview.block-update-ticks", 1L));
        exteriorEnabled = plugin.getConfig().getBoolean("preview.exterior.enabled", true);
        interiorEnabled = plugin.getConfig().getBoolean("preview.interior.enabled", true);
        interiorShowPlayers = plugin.getConfig().getBoolean("preview.interior.show-players", true);
        exteriorViewerDistance = positive("preview.exterior.viewer-distance", 6.0);
        exteriorMaximumBlocks = bounded("preview.exterior.max-blocks", 2048, 0, 4096);
        interiorMaximumBlocks = bounded("preview.interior.max-blocks", 4096, 0, 16384);
        exteriorMaximumPlayerMarkers = bounded("preview.exterior.max-player-markers", 16, 0, 100);
        interiorMaximumPlayerMarkers = bounded("preview.interior.max-player-markers", 16, 0, 100);
        outsideRadius = bounded("preview.interior.outside-radius", 6, 1, 24);
    }

    private void removeOrphans() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE)) entity.remove();
            }
        }
    }

    private void configureEntityBackend() {
        String mode = plugin.getConfig().getString("entity-preview",
                plugin.getConfig().getString("preview.entity-preview", "manequeen"));
        if (mode == null) return;
        mode = mode.trim();
        if (mode.equalsIgnoreCase("manequeen") || mode.equalsIgnoreCase("mannequin")) return;
        if (!mode.equalsIgnoreCase("protocol")) {
            plugin.getLogger().warning("Unknown entity-preview mode '" + mode + "'; using manequeens.");
            return;
        }
        org.bukkit.plugin.Plugin protocolLib = plugin.getServer().getPluginManager().getPlugin("ProtocolLib");
        if (protocolLib == null || !protocolLib.isEnabled()) {
            plugin.getLogger().warning("entity-preview is protocol, but ProtocolLib is not enabled; using mannequins.");
            return;
        }
        try {
            entityBackend = new ProtocolEntityPreview(plugin, interiors);
            plugin.getLogger().info("Using ProtocolLib entity previews.");
        } catch (LinkageError | RuntimeException exception) {
            entityBackend = null;
            plugin.getLogger().warning("Could not start ProtocolLib entity previews; using mannequins: " + exception.getMessage());
        }
    }

    private void rebuildRoutes() {
        if (repository == null) {
            routes = List.of();
            return;
        }
        UUID interiorWorld = interiors.world().getUID();
        List<JarRoute> snapshots = new ArrayList<>();
        for (JarRecord jar : repository.all()) {
            Location outside = jar.outsideLocation();
            if (!jar.placed() || outside == null) continue;
            CellLayout.Cell cell = interiors.cell(jar);
            snapshots.add(new JarRoute(jar.id(), outside.getWorld().getUID(), outside.getBlockX(),
                    outside.getBlockY(), outside.getBlockZ(), outsideRadius, interiorWorld,
                    cell.minX(), cell.minY(), cell.minZ(), jar.interiorSizeX(), jar.interiorSizeY(),
                    jar.interiorSizeZ(), jar.width(), jar.height(), jar.depth()));
        }
        routes = List.copyOf(snapshots);
    }

    private void scheduleRoute() {
        ExecutorService executor = router;
        if (!running || executor == null || !routeScheduled.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                List<JarRoute> snapshot = routes;
                for (BlockRequest block : new ArrayList<>(pendingBlocks)) {
                    if (!pendingBlocks.remove(block)) continue;
                    for (JarRoute route : snapshot) {
                        int mask = route.route(block);
                        if (mask != 0) routedBlocks.merge(route.jarId, mask, (left, right) -> left | right);
                    }
                }
                for (UUID player : new ArrayList<>(pendingPlayers)) {
                    if (pendingPlayers.remove(player)) routedPlayers.add(player);
                }
            } finally {
                routeScheduled.set(false);
                scheduleApply();
                if (!pendingBlocks.isEmpty() || !pendingPlayers.isEmpty()) scheduleRoute();
            }
        });
    }

    private void scheduleApply() {
        if (!running || !applyScheduled.compareAndSet(false, true)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            boolean blocksChanged = false;
            try {
                for (Map.Entry<UUID, Integer> entry : new HashMap<>(routedBlocks).entrySet()) {
                    if (!routedBlocks.remove(entry.getKey(), entry.getValue())) continue;
                    JarScene scene = scenes.get(entry.getKey());
                    if (scene == null) continue;
                    if ((entry.getValue() & EXTERIOR_BLOCKS) != 0) scene.exteriorInvalid = true;
                    if ((entry.getValue() & INTERIOR_BLOCKS) != 0) scene.interiorInvalid = true;
                    blocksChanged = true;
                }
                for (UUID playerId : new HashSet<>(routedPlayers)) {
                    if (!routedPlayers.remove(playerId)) continue;
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null) interiors.syncSession(player, player.getLocation(), repository);
                }
            } finally {
                applyScheduled.set(false);
                if (blocksChanged) scheduleBlockRefresh();
                if (!routedBlocks.isEmpty() || !routedPlayers.isEmpty()) scheduleApply();
            }
        });
    }

    private void scheduleBlockRefresh() {
        if (!running || blockRefreshTaskId != -1) return;
        if (blockUpdateTicks == 1L) {
            refreshInvalidBlocks();
            return;
        }
        blockRefreshTaskId = plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            blockRefreshTaskId = -1;
            refreshInvalidBlocks();
        }, blockUpdateTicks - 1L);
    }

    private void refreshInvalidBlocks() {
        if (!running) return;
        for (JarScene scene : scenes.values()) {
            if (scene.sealed || (!scene.exteriorInvalid && !scene.interiorInvalid)) continue;
            refreshBlocks(scene, scene.exteriorInvalid, scene.interiorInvalid);
        }
    }

    private record InteriorBox(int x, int y, int z, int sx, int sy, int sz, BlockData blockData) {}
    private record FloorSample(int cellX, int cellY, int cellZ, BlockData blockData) {}
    private record OutsideSample(int dx, int dy, int dz, BlockData blockData) {}
    private record OutsideOffset(int dx, int dy, int dz, int distanceSquared) {}
    private record OutsideArea(int radius, int width, int height, int depth) {}
    private record BlockScanRequest(UUID jarId, JarRecord jar, CellLayout.Cell cell,
                                    World interiorWorld, World outsideWorld,
                                    int outsideX, int outsideY, int outsideZ,
                                    boolean scanExterior, boolean scanInterior,
                                    int exteriorMaximum, int outsideMaximum, int radius) {}
    private record BlockScanResult(BlockScanRequest request, List<InteriorBox> interior,
                                   List<OutsideSample> outside, List<FloorSample> floor,
                                   int exteriorFingerprint, int interiorFingerprint) {}
    private record SnapshotBlockScan(BlockScanRequest request, SnapshotWorld interior,
                                     SnapshotWorld outside) {}
    private record ChunkCoordinate(int x, int z) {}
    private record PreviewChunk(UUID worldId, int x, int z) {}
    private record SnapshotWorld(int minHeight, int maxHeight, Map<Long, ChunkSnapshot> chunks) {
        private static SnapshotWorld empty(World world) {
            return new SnapshotWorld(world.getMinHeight(), world.getMaxHeight(), Map.of());
        }

        private Material type(int x, int y, int z) {
            if (y < minHeight || y >= maxHeight) return Material.AIR;
            ChunkSnapshot snapshot = chunks.get(Chunk.getChunkKey(x >> 4, z >> 4));
            return snapshot == null ? Material.AIR : snapshot.getBlockType(x & 15, y, z & 15);
        }

        private BlockData blockData(int x, int y, int z) {
            if (y < minHeight || y >= maxHeight) return Material.AIR.createBlockData();
            ChunkSnapshot snapshot = chunks.get(Chunk.getChunkKey(x >> 4, z >> 4));
            return snapshot == null ? Material.AIR.createBlockData()
                    : snapshot.getBlockData(x & 15, y, z & 15);
        }
    }
    private record BlockRequest(UUID world, int x, int y, int z) {}
    private record JarRoute(UUID jarId, UUID outsideWorld, int outsideX, int outsideY, int outsideZ,
                            int outsideRadius, UUID interiorWorld, int minX, int minY, int minZ,
                            int sizeX, int sizeY, int sizeZ,
                            int width, int height, int depth) {
        private int route(BlockRequest block) {
            int mask = 0;
            if (block.world.equals(interiorWorld)
                    && block.x >= minX && block.x < minX + sizeX
                    && block.y >= minY && block.y < minY + sizeY
                    && block.z >= minZ && block.z < minZ + sizeZ) mask |= EXTERIOR_BLOCKS;
            if (block.world.equals(outsideWorld)
                    && block.x >= outsideX - outsideRadius && block.x < outsideX + width + outsideRadius
                    && block.y >= outsideY - outsideRadius
                    && block.y < outsideY + height + outsideRadius
                    && block.z >= outsideZ - outsideRadius && block.z < outsideZ + depth + outsideRadius) mask |= INTERIOR_BLOCKS;
            return mask;
        }
    }
    private record Avatar(VirtualMannequin body) {}

    private final class JarScene {
        private final JarRecord jar;
        private final Set<PreviewChunk> exteriorViewChunks;
        private final Set<PreviewChunk> interiorViewChunks;
        private final List<VirtualBlockDisplay> exteriorBlocks = new ArrayList<>();
        private VirtualBlockDisplay exteriorPortal;
        private final List<VirtualBlockDisplay> interiorBlocks = new ArrayList<>();
        private final Map<UUID, Avatar> occupants = new HashMap<>();
        private final Map<UUID, Avatar> outsiders = new HashMap<>();
        private final Map<UUID, Avatar> sleepers = new HashMap<>();
        private final Set<UUID> exteriorViewers = new HashSet<>();
        private final Set<UUID> interiorViewers = new HashSet<>();
        private final Set<UUID> insetPortalViewers = new HashSet<>();
        private int exteriorFingerprint = Integer.MIN_VALUE;
        private int interiorFingerprint = Integer.MIN_VALUE;
        private volatile boolean exteriorInvalid = true;
        private volatile boolean interiorInvalid = true;
        private boolean blockScanRunning;
        private boolean sealed;

        private JarScene(JarRecord jar) {
            this.jar = jar;
            if (!jar.placed()) {
                exteriorViewChunks = Set.of();
                interiorViewChunks = Set.of();
                return;
            }
            exteriorViewChunks = previewChunks(interiors.world().getUID(),
                    interiorChunks(jar, interiors.cell(jar)));
            Location outside = jar.outsideLocation();
            interiorViewChunks = outside == null ? Set.of() : previewChunks(outside.getWorld().getUID(),
                    outsideChunks(jar, outside.getBlockX(), outside.getBlockZ(), outsideRadius));
        }

        private List<VirtualEntity> exteriorEntities() {
            List<VirtualEntity> entities = new ArrayList<>(exteriorBlocks);
            for (Avatar avatar : occupants.values()) entities.add(avatar.body);
            return entities;
        }

        private void spawnPortal(Player viewer) {
            if (exteriorPortal == null) return;
            boolean inset = onDoorSide(jar, viewer.getLocation());
            exteriorPortal.spawn(viewer, portalTransformation(jar,
                    inset ? FRONT_PORTAL_INWARD : BACK_PORTAL_INWARD));
            if (inset) insetPortalViewers.add(viewer.getUniqueId());
            else insetPortalViewers.remove(viewer.getUniqueId());
        }

        private List<VirtualEntity> interiorEntities() {
            List<VirtualEntity> entities = new ArrayList<>(interiorBlocks);
            for (Avatar avatar : outsiders.values()) entities.add(avatar.body);
            for (Avatar avatar : sleepers.values()) entities.add(avatar.body);
            return entities;
        }

        private void removeAvatar(UUID id, Map<UUID, Avatar> avatars) {
            Avatar avatar = avatars.remove(id);
            if (avatar != null) for (Player viewer : online(avatars == occupants ? exteriorViewers : interiorViewers)) avatar.body.destroy(viewer);
        }

        private void removeAll() {
            removeEntities(exteriorBlocks, exteriorViewers);
            if (exteriorPortal != null) for (Player viewer : online(exteriorViewers)) exteriorPortal.destroy(viewer);
            removeEntities(interiorBlocks, interiorViewers);
            for (Player viewer : online(exteriorViewers)) for (Avatar avatar : occupants.values()) avatar.body.destroy(viewer);
            for (Player viewer : online(interiorViewers)) for (Avatar avatar : outsiders.values()) avatar.body.destroy(viewer);
            for (Player viewer : online(interiorViewers)) for (Avatar avatar : sleepers.values()) avatar.body.destroy(viewer);
            exteriorBlocks.clear(); interiorBlocks.clear(); occupants.clear(); outsiders.clear(); sleepers.clear();
            exteriorPortal = null;
            exteriorViewers.clear(); interiorViewers.clear(); insetPortalViewers.clear();
        }
    }
}
