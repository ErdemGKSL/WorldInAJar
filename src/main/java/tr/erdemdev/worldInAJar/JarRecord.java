package tr.erdemdev.worldInAJar;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable persisted state for one jar, with its validated geometry cached once. */
public final class JarRecord {
    private final UUID id;
    private final UUID owner;
    private final String world;
    private final int x;
    private final int y;
    private final int z;
    private final BlockFace door;
    private final int doorX;
    private final int doorY;
    private final int doorZ;
    private final int cell;
    private final int scale;
    private final List<JarPart> parts;
    private final boolean placed;
    private final JarAssembly assembly;
    private final int width;
    private final int height;
    private final int depth;

    public JarRecord(UUID id, UUID owner, String world, int x, int y, int z,
                     BlockFace door, int doorX, int doorY, int doorZ, int cell, int scale,
                     List<JarPart> parts, boolean placed) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.world = Objects.requireNonNull(world, "world");
        this.x = x;
        this.y = y;
        this.z = z;
        this.door = door;
        this.doorX = doorX;
        this.doorY = doorY;
        this.doorZ = doorZ;
        this.cell = cell;
        this.scale = scale;
        this.parts = List.copyOf(parts);
        this.placed = placed;
        this.assembly = new JarAssembly(this.parts);
        this.width = assembly.width();
        this.height = assembly.height();
        this.depth = assembly.depth();
        if (door != null && (!horizontal(door) || !assembly.contains(doorX, doorY, doorZ)
                || assembly.contains(doorX + door.getModX(), doorY, doorZ + door.getModZ()))) {
            throw new IllegalArgumentException("Portal side must be on an exposed horizontal jar face");
        }
    }

    public UUID id() { return id; }
    public UUID owner() { return owner; }
    public String world() { return world; }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public BlockFace door() { return door; }
    public int doorX() { return doorX; }
    public int doorY() { return doorY; }
    public int doorZ() { return doorZ; }
    public int cell() { return cell; }
    public int scale() { return scale; }
    public List<JarPart> parts() { return parts; }
    public boolean placed() { return placed; }

    public Location outsideLocation() {
        var loadedWorld = Bukkit.getWorld(world);
        return loadedWorld == null ? null : new Location(loadedWorld, x, y, z);
    }

    public boolean isAt(Location location) {
        return placed && location.getWorld().getName().equals(world)
                && assembly.contains(location.getBlockX() - x, location.getBlockY() - y,
                location.getBlockZ() - z);
    }

    public JarRecord movedTo(Location location, BlockFace ignoredFacing) {
        return new JarRecord(id, owner, location.getWorld().getName(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ(), door, doorX, doorY, doorZ,
                cell, scale, parts, true);
    }

    public JarRecord pickedUp() {
        return new JarRecord(id, owner, world, x, y, z, door, doorX, doorY, doorZ,
                cell, scale, parts, false);
    }

    public JarRecord withPortal(int cellX, int cellY, int cellZ, BlockFace face) {
        return new JarRecord(id, owner, world, x, y, z, face, cellX, cellY, cellZ,
                cell, scale, parts, placed);
    }

    public JarRecord withoutPortal() {
        return new JarRecord(id, owner, world, x, y, z, null, 0, 0, 0,
                cell, scale, parts, placed);
    }

    public boolean hasPortal() { return door != null; }
    public JarAssembly assembly() { return assembly; }
    public int width() { return width; }
    public int height() { return height; }
    public int depth() { return depth; }
    public int interiorSizeX() { return width * scale; }
    public int interiorSizeY() { return height * scale; }
    public int interiorSizeZ() { return depth * scale; }

    public Location outsideCenter() {
        Location origin = outsideLocation();
        return origin == null ? null : origin.add(width / 2.0, height / 2.0, depth / 2.0);
    }

    public Location doorBlockLocation() {
        Location origin = outsideLocation();
        return origin == null || door == null ? null : origin.add(doorX, doorY, doorZ);
    }

    public static JarAssembly.Cell defaultPortalCell(JarAssembly assembly, BlockFace face) {
        JarAssembly.Cell selected = null;
        double centerX = assembly.width() / 2.0, centerY = assembly.height() / 2.0;
        double centerZ = assembly.depth() / 2.0;
        for (JarAssembly.Cell cell : assembly.cells()) {
            if (assembly.contains(cell.x() + face.getModX(), cell.y(), cell.z() + face.getModZ())) continue;
            double score = doorScore(cell, face, centerX, centerY, centerZ);
            double selectedScore = selected == null ? Double.NEGATIVE_INFINITY
                    : doorScore(selected, face, centerX, centerY, centerZ);
            if (selected == null || score > selectedScore || score == selectedScore
                    && (cell.y() < selected.y() || cell.y() == selected.y()
                    && (cell.z() < selected.z() || cell.z() == selected.z() && cell.x() < selected.x()))) {
                selected = cell;
            }
        }
        if (selected == null) throw new IllegalArgumentException("Jar assembly has no exposed portal face");
        return selected;
    }

    private static double doorScore(JarAssembly.Cell cell, BlockFace face,
                                    double centerX, double centerY, double centerZ) {
        double outward = cell.x() * face.getModX() + cell.z() * face.getModZ();
        double lateral = face.getModX() == 0 ? Math.abs(cell.x() + .5 - centerX)
                : Math.abs(cell.z() + .5 - centerZ);
        return outward * 1000 - lateral - Math.abs(cell.y() + .5 - centerY);
    }

    private static boolean horizontal(BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH
                || face == BlockFace.EAST || face == BlockFace.WEST;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof JarRecord that)) return false;
        return x == that.x && y == that.y && z == that.z && doorX == that.doorX
                && doorY == that.doorY && doorZ == that.doorZ && cell == that.cell
                && scale == that.scale && placed == that.placed && id.equals(that.id)
                && owner.equals(that.owner) && world.equals(that.world) && door == that.door
                && parts.equals(that.parts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, owner, world, x, y, z, door, doorX, doorY, doorZ,
                cell, scale, parts, placed);
    }

    @Override
    public String toString() {
        return "JarRecord[id=" + id + ", owner=" + owner + ", world=" + world
                + ", x=" + x + ", y=" + y + ", z=" + z + ", door=" + door
                + ", doorX=" + doorX + ", doorY=" + doorY + ", doorZ=" + doorZ
                + ", cell=" + cell + ", scale=" + scale + ", parts=" + parts
                + ", placed=" + placed + ']';
    }
}
