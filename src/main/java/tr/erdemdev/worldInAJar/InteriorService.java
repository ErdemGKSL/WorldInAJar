package tr.erdemdev.worldInAJar;

import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class InteriorService {
    private final JavaPlugin plugin;
    private final String worldName;
    private final int gap;
    private final int baseY;
    private final int stride;
    private final Map<UUID, UUID> sessions = new java.util.HashMap<>();
    private World world;

    public InteriorService(JavaPlugin plugin) {
        this.plugin = plugin;
        worldName = plugin.getConfig().getString("interior.world", "world_in_a_jar");
        gap = plugin.getConfig().getInt("interior.cell-gap", 8);
        baseY = plugin.getConfig().getInt("interior.base-y", 64);
        stride = plugin.getConfig().getInt("interior.allocation-stride", 320);
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

    public void copyRegions(JarRecord target, List<InteriorCopy> copies) {
        ensureBuilt(target);
        CellLayout.Cell destinationCell = cell(target);
        for (InteriorCopy copy : copies) copyInterior(copy, target, destinationCell);
    }

    private void copyInterior(InteriorCopy copy, JarRecord target, CellLayout.Cell destinationCell) {
        JarRecord source = copy.source();
        ensureBuilt(source);
        CellLayout.Cell sourceCell = cell(source);
        int offsetX = copy.offsetX() * target.scale();
        int offsetY = copy.offsetY() * target.scale();
        int offsetZ = copy.offsetZ() * target.scale();
        for (int y = 0; y < source.interiorSizeY(); y++) {
            for (int x = 0; x < source.interiorSizeX(); x++) {
                for (int z = 0; z < source.interiorSizeZ(); z++) {
                    JarAssembly.Cell assemblyCell = new JarAssembly.Cell(
                            x / source.scale(), y / source.scale(), z / source.scale());
                    if (!copy.includes(assemblyCell)) continue;
                    org.bukkit.block.Block block = world.getBlockAt(
                            sourceCell.minX() + x, sourceCell.minY() + y, sourceCell.minZ() + z);
                    if (block.getType().isAir() || block.getType() == Material.BARRIER) continue;
                    Location destination = new Location(world, destinationCell.minX() + offsetX + x,
                            destinationCell.minY() + offsetY + y, destinationCell.minZ() + offsetZ + z);
                    if (destination.getBlock().getType() == Material.BARRIER) continue;
                    block.getState().copy(destination).update(true, false);
                }
            }
        }

        BoundingBox sourceBounds = new BoundingBox(sourceCell.minX(), sourceCell.minY(), sourceCell.minZ(),
                sourceCell.minX() + source.interiorSizeX(), sourceCell.minY() + source.interiorSizeY(),
                sourceCell.minZ() + source.interiorSizeZ());
        for (Entity entity : new ArrayList<>(world.getNearbyEntities(sourceBounds))) {
            int tileX = Math.floorDiv(entity.getLocation().getBlockX() - sourceCell.minX(), source.scale());
            int tileY = Math.floorDiv(entity.getLocation().getBlockY() - sourceCell.minY(), source.scale());
            int tileZ = Math.floorDiv(entity.getLocation().getBlockZ() - sourceCell.minZ(), source.scale());
            if (!copy.includes(new JarAssembly.Cell(tileX, tileY, tileZ))) continue;
            Location moved = entity.getLocation().clone().add(
                    destinationCell.minX() + offsetX - sourceCell.minX(),
                    destinationCell.minY() + offsetY - sourceCell.minY(),
                    destinationCell.minZ() + offsetZ - sourceCell.minZ());
            if (entity.teleport(moved) && entity instanceof Player player) {
                sessions.put(player.getUniqueId(), target.id());
                applyEnvironment(player, target);
            }
        }
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

    public void ensureBuilt(JarRecord jar) {
        CellLayout.Cell c = cell(jar);
        // A marker below the cell makes initialization idempotent across restarts.
        if (world.getBlockAt(c.minX(), c.minY() - 2, c.minZ()).getType() == Material.BEDROCK) {
            buildBoundary(jar, false);
            return;
        }
        buildBoundary(jar, true);
        world.getBlockAt(c.minX(), c.minY() - 2, c.minZ()).setType(Material.BEDROCK, false);
    }

    private void buildBoundary(JarRecord jar, boolean clearOpenEdges) {
        CellLayout.Cell c = cell(jar);
        int scale = jar.scale();
        JarAssembly assembly = jar.assembly();
        for (JarAssembly.Cell cell : assembly.cells()) {
            int baseX = c.minX() + cell.x() * scale;
            int baseY = c.minY() + cell.y() * scale;
            int baseZ = c.minZ() + cell.z() * scale;
            for (int x = 0; x < scale; x++) for (int z = 0; z < scale; z++) {
                boundaryBlock(assembly.contains(cell.x(), cell.y() - 1, cell.z()), clearOpenEdges,
                        baseX + x, baseY, baseZ + z);
                boundaryBlock(assembly.contains(cell.x(), cell.y() + 1, cell.z()), clearOpenEdges,
                        baseX + x, baseY + scale - 1, baseZ + z);
            }
            for (int y = 1; y < scale - 1; y++) {
                boundaryPlane(assembly.contains(cell.x() - 1, cell.y(), cell.z()), clearOpenEdges,
                        baseX, baseY + y, baseZ, 0, 1, scale);
                boundaryPlane(assembly.contains(cell.x() + 1, cell.y(), cell.z()), clearOpenEdges,
                        baseX + scale - 1, baseY + y, baseZ, 0, 1, scale);
                boundaryPlane(assembly.contains(cell.x(), cell.y(), cell.z() - 1), clearOpenEdges,
                        baseX, baseY + y, baseZ, 1, 0, scale);
                boundaryPlane(assembly.contains(cell.x(), cell.y(), cell.z() + 1), clearOpenEdges,
                        baseX, baseY + y, baseZ + scale - 1, 1, 0, scale);
            }
        }
    }

    private void boundaryBlock(boolean open, boolean clearOpen, int x, int y, int z) {
        if (open && !clearOpen) return;
        world.getBlockAt(x, y, z).setType(open ? Material.AIR : Material.BARRIER, false);
    }

    private void boundaryPlane(boolean open, boolean clearOpen, int x, int y, int z,
                               int stepX, int stepZ, int length) {
        if (open && !clearOpen) return;
        Material material = open ? Material.AIR : Material.BARRIER;
        for (int offset = 0; offset < length; offset++) {
            world.getBlockAt(x + offset * stepX, y, z + offset * stepZ).setType(material, false);
        }
    }

    public void enter(Player player, JarRecord jar) {
        if (!jar.hasPortal()) return;
        ensureBuilt(jar);
        sessions.put(player.getUniqueId(), jar.id());
        CellLayout.Cell c = cell(jar);
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
        if (player.teleport(new Location(world, x, y, z, player.getYaw(), player.getPitch()))) {
            applyEnvironment(player, jar);
        } else {
            sessions.remove(player.getUniqueId());
        }
    }

    public ExitResult exit(Player player, JarRepository repository) {
        JarRecord jar = syncSession(player, player.getLocation(), repository);
        if (jar == null) return ExitResult.NOT_INSIDE;
        if (isClogged(jar)) return ExitResult.CLOGGED;
        Location destination = jar.doorBlockLocation().add(.5, .2, .5)
                .add(jar.door().getModX() * 1.5, 0, jar.door().getModZ() * 1.5);
        if (!player.teleport(destination)) return ExitResult.NOT_INSIDE;
        sessions.remove(player.getUniqueId());
        resetEnvironment(player);
        return ExitResult.EXITED;
    }

    public boolean isClogged(JarRecord jar) {
        Location doorBlock = jar.doorBlockLocation();
        if (!jar.placed() || doorBlock == null || doorBlock.getBlock().getType() != Material.GLASS) return true;
        return !doorBlock.clone().add(jar.door().getModX(), 0, jar.door().getModZ()).getBlock().isPassable();
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
            player.teleport(destination);
            resetEnvironment(player);
        }

        destroyCell(jar);
    }

    public void destroyCell(JarRecord jar) {
        CellLayout.Cell c = cell(jar);
        int sizeX = jar.interiorSizeX(), sizeY = jar.interiorSizeY(), sizeZ = jar.interiorSizeZ();
        BoundingBox bounds = new BoundingBox(c.minX(), c.minY(), c.minZ(),
                c.minX() + sizeX, c.minY() + sizeY, c.minZ() + sizeZ);
        world.getNearbyEntities(bounds, entity -> !(entity instanceof Player)).forEach(org.bukkit.entity.Entity::remove);
        plugin.getServer().getScheduler().runTask(plugin, new CellCleanup(c, sizeX, sizeY, sizeZ));
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
        JarAssembly assembly = jar.assembly();
        return localY == 0 && !assembly.contains(cellX, cellY - 1, cellZ)
                || localY == jar.scale() - 1 && !assembly.contains(cellX, cellY + 1, cellZ)
                || localX == 0 && !assembly.contains(cellX - 1, cellY, cellZ)
                || localX == jar.scale() - 1 && !assembly.contains(cellX + 1, cellY, cellZ)
                || localZ == 0 && !assembly.contains(cellX, cellY, cellZ - 1)
                || localZ == jar.scale() - 1 && !assembly.contains(cellX, cellY, cellZ + 1);
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

    private final class CellCleanup implements Runnable {
        private static final int BLOCKS_PER_TICK = 8192;
        private final CellLayout.Cell cell;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final int volume;
        private int index;

        private CellCleanup(CellLayout.Cell cell, int sizeX, int sizeY, int sizeZ) {
            this.cell = cell;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.volume = sizeX * sizeY * sizeZ;
        }

        @Override
        public void run() {
            int end = Math.min(volume, index + BLOCKS_PER_TICK);
            while (index < end) {
                int x = index % sizeX;
                int z = (index / sizeX) % sizeZ;
                int y = index / (sizeX * sizeZ);
                world.getBlockAt(cell.minX() + x, cell.minY() + y, cell.minZ() + z).setType(Material.AIR, false);
                index++;
            }
            if (index < volume) {
                plugin.getServer().getScheduler().runTask(plugin, this);
            } else {
                world.getBlockAt(cell.minX(), cell.minY() - 2, cell.minZ()).setType(Material.AIR, false);
            }
        }
    }
}
