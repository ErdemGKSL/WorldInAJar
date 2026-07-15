package tr.erdemdev.worldInAJar;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;

import java.util.UUID;
import java.util.List;

public record JarRecord(UUID id, UUID owner, String world, int x, int y, int z,
                        BlockFace door, int doorX, int doorZ, int cell, int scale,
                        List<JarPart> parts, boolean placed) {
    public JarRecord {
        parts = List.copyOf(parts);
        JarAssembly assembly = new JarAssembly(parts);
        if (door != null && (!horizontal(door) || !assembly.contains(doorX, doorZ)
                || assembly.contains(doorX + door.getModX(), doorZ + door.getModZ()))) {
            throw new IllegalArgumentException("Portal side must be on an exposed horizontal jar face");
        }
    }
    public Location outsideLocation() {
        var loadedWorld = Bukkit.getWorld(world);
        return loadedWorld == null ? null : new Location(loadedWorld, x, y, z);
    }

    public boolean isAt(Location location) {
        return placed && location.getWorld().getName().equals(world) && location.getBlockY() == y
                && assembly().contains(location.getBlockX() - x, location.getBlockZ() - z);
    }

    public JarRecord movedTo(Location location, BlockFace ignoredFacing) {
        return new JarRecord(id, owner, location.getWorld().getName(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ(), door, doorX, doorZ,
                cell, scale, parts, true);
    }

    public JarRecord pickedUp() {
        return new JarRecord(id, owner, world, x, y, z, door, doorX, doorZ,
                cell, scale, parts, false);
    }

    public JarRecord withPortal(int tileX, int tileZ, BlockFace face) {
        return new JarRecord(id, owner, world, x, y, z, face, tileX, tileZ,
                cell, scale, parts, placed);
    }

    public JarRecord withoutPortal() {
        return new JarRecord(id, owner, world, x, y, z, null, 0, 0,
                cell, scale, parts, placed);
    }

    public boolean hasPortal() { return door != null; }

    public JarAssembly assembly() { return new JarAssembly(parts); }
    public int width() { return assembly().width(); }
    public int depth() { return assembly().depth(); }
    public int interiorSizeX() { return width() * scale; }
    public int interiorSizeZ() { return depth() * scale; }

    public Location outsideCenter() {
        Location origin = outsideLocation();
        return origin == null ? null : origin.add(width() / 2.0, .5, depth() / 2.0);
    }

    public Location doorBlockLocation() {
        Location origin = outsideLocation();
        return origin == null || door == null ? null : origin.add(doorX, 0, doorZ);
    }

    public static JarAssembly.Tile defaultPortalTile(JarAssembly assembly, BlockFace face) {
        JarAssembly.Tile selected = null;
        double centerX = assembly.width() / 2.0, centerZ = assembly.depth() / 2.0;
        for (JarAssembly.Tile tile : assembly.tiles()) {
            if (assembly.contains(tile.x() + face.getModX(), tile.z() + face.getModZ())) continue;
            double score = doorScore(tile, face, centerX, centerZ);
            double selectedScore = selected == null ? Double.NEGATIVE_INFINITY
                    : doorScore(selected, face, centerX, centerZ);
            if (selected == null || score > selectedScore || score == selectedScore
                    && (tile.z() < selected.z() || tile.z() == selected.z() && tile.x() < selected.x())) selected = tile;
        }
        if (selected == null) throw new IllegalArgumentException("Jar assembly has no exposed portal face");
        return selected;
    }

    private static double doorScore(JarAssembly.Tile tile, BlockFace face, double centerX, double centerZ) {
        double outward = tile.x() * face.getModX() + tile.z() * face.getModZ();
        double lateral = face.getModX() == 0 ? Math.abs(tile.x() + .5 - centerX) : Math.abs(tile.z() + .5 - centerZ);
        return outward * 1000 - lateral;
    }

    private static boolean horizontal(BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH
                || face == BlockFace.EAST || face == BlockFace.WEST;
    }
}
