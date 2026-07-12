package tr.erdemdev.worldInAJar;

import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    }

    public World world() { return world; }
    public CellLayout.Cell cell(JarRecord jar) { return CellLayout.cell(jar.cell(), stride, gap, baseY); }

    public void ensureBuilt(JarRecord jar) {
        CellLayout.Cell c = cell(jar);
        int s = jar.scale();
        // A marker below the cell makes initialization idempotent across restarts.
        if (world.getBlockAt(c.minX(), c.minY() - 2, c.minZ()).getType() == Material.BEDROCK) {
            normalizeBoundary(c, s);
            return;
        }
        for (int x = 0; x < s; x++) for (int z = 0; z < s; z++) {
            world.getBlockAt(c.minX() + x, c.minY(), c.minZ() + z).setType(Material.BARRIER, false);
            world.getBlockAt(c.minX() + x, c.minY() + s - 1, c.minZ() + z).setType(Material.BARRIER, false);
        }
        for (int y = 1; y < s - 1; y++) for (int i = 0; i < s; i++) {
            world.getBlockAt(c.minX(), c.minY() + y, c.minZ() + i).setType(Material.BARRIER, false);
            world.getBlockAt(c.minX() + s - 1, c.minY() + y, c.minZ() + i).setType(Material.BARRIER, false);
            world.getBlockAt(c.minX() + i, c.minY() + y, c.minZ()).setType(Material.BARRIER, false);
            world.getBlockAt(c.minX() + i, c.minY() + y, c.minZ() + s - 1).setType(Material.BARRIER, false);
        }
        world.getBlockAt(c.minX(), c.minY() - 2, c.minZ()).setType(Material.BEDROCK, false);
    }

    private void normalizeBoundary(CellLayout.Cell c, int size) {
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) {
            world.getBlockAt(c.minX() + x, c.minY(), c.minZ() + z).setType(Material.BARRIER, false);
            world.getBlockAt(c.minX() + x, c.minY() + size - 1, c.minZ() + z).setType(Material.BARRIER, false);
        }
        for (int y = 1; y < size - 1; y++) for (int i = 0; i < size; i++) {
            world.getBlockAt(c.minX(), c.minY() + y, c.minZ() + i).setType(Material.BARRIER, false);
            world.getBlockAt(c.minX() + size - 1, c.minY() + y, c.minZ() + i).setType(Material.BARRIER, false);
            world.getBlockAt(c.minX() + i, c.minY() + y, c.minZ()).setType(Material.BARRIER, false);
            world.getBlockAt(c.minX() + i, c.minY() + y, c.minZ() + size - 1).setType(Material.BARRIER, false);
        }
    }

    public void enter(Player player, JarRecord jar) {
        ensureBuilt(jar);
        sessions.put(player.getUniqueId(), jar.id());
        CellLayout.Cell c = cell(jar);
        double x = c.centerX(jar.scale()) + .5, z = c.centerZ(jar.scale()) + .5;
        int margin = 2;
        if (jar.door() == BlockFace.NORTH) z = c.minZ() + margin + .5;
        else if (jar.door() == BlockFace.SOUTH) z = c.minZ() + jar.scale() - margin - .5;
        else if (jar.door() == BlockFace.WEST) x = c.minX() + margin + .5;
        else if (jar.door() == BlockFace.EAST) x = c.minX() + jar.scale() - margin - .5;
        player.teleport(new Location(world, x, c.minY() + 1.1, z, player.getYaw(), player.getPitch()));
    }

    public boolean exit(Player player, JarRepository repository) {
        UUID jarId = sessions.remove(player.getUniqueId());
        if (jarId == null) return false;
        JarRecord jar = repository.byId(jarId).orElse(null);
        if (jar == null || jar.outsideLocation() == null) return false;
        Location destination = jar.outsideLocation().add(.5, .2, .5)
                .add(jar.door().getModX() * 1.5, 0, jar.door().getModZ() * 1.5);
        player.teleport(destination);
        return true;
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

    public void forget(Player player) { sessions.remove(player.getUniqueId()); }

    public void pruneSessions(JarRepository repository) {
        sessions.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            JarRecord jar = repository.byId(entry.getValue()).orElse(null);
            return player == null || !player.isOnline() || jar == null || !contains(jar, player.getLocation());
        });
    }

    public boolean isExitWall(Player player, Location block, JarRepository repository) {
        UUID id = sessions.get(player.getUniqueId());
        JarRecord jar = id == null ? null : repository.byId(id).orElse(null);
        if (jar == null || block.getWorld() != world) return false;
        CellLayout.Cell c = cell(jar); int s = jar.scale();
        return switch (jar.door()) {
            case NORTH -> block.getBlockZ() == c.minZ();
            case SOUTH -> block.getBlockZ() == c.minZ() + s - 1;
            case WEST -> block.getBlockX() == c.minX();
            case EAST -> block.getBlockX() == c.minX() + s - 1;
            default -> false;
        };
    }

    public boolean contains(JarRecord jar, Location location) {
        if (location.getWorld() != world) return false;
        CellLayout.Cell c = cell(jar); int s = jar.scale();
        return location.getBlockX() >= c.minX() && location.getBlockX() < c.minX() + s
                && location.getBlockY() >= c.minY() && location.getBlockY() < c.minY() + s
                && location.getBlockZ() >= c.minZ() && location.getBlockZ() < c.minZ() + s;
    }

    public boolean isBoundary(JarRecord jar, Location location) {
        if (!contains(jar, location)) return false;
        CellLayout.Cell c = cell(jar); int s = jar.scale();
        int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
        return x == c.minX() || x == c.minX() + s - 1 || z == c.minZ() || z == c.minZ() + s - 1
                || y == c.minY() || y == c.minY() + s - 1;
    }

    private static final class EmptyGenerator extends ChunkGenerator {
        @Override public boolean shouldGenerateNoise() { return false; }
        @Override public boolean shouldGenerateSurface() { return false; }
        @Override public boolean shouldGenerateCaves() { return false; }
        @Override public boolean shouldGenerateDecorations() { return false; }
        @Override public boolean shouldGenerateMobs() { return false; }
        @Override public boolean shouldGenerateStructures() { return false; }
    }
}
