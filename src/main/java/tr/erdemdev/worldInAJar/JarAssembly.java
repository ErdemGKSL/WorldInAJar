package tr.erdemdev.worldInAJar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable, validated shape of a jar assembly. */
public final class JarAssembly {
    private final List<JarPart> parts;
    private final Set<Cell> cells;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    public JarAssembly(List<JarPart> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("A jar assembly needs at least one part");
        }
        this.parts = List.copyOf(parts);

        int minimumX = Integer.MAX_VALUE, minimumY = Integer.MAX_VALUE, minimumZ = Integer.MAX_VALUE;
        int maximumX = Integer.MIN_VALUE, maximumY = Integer.MIN_VALUE, maximumZ = Integer.MIN_VALUE;
        int cellCount = 0;
        for (JarPart part : this.parts) {
            minimumX = Math.min(minimumX, part.x());
            minimumY = Math.min(minimumY, part.y());
            minimumZ = Math.min(minimumZ, part.z());
            maximumX = Math.max(maximumX, Math.addExact(part.x(), part.width()));
            maximumY = Math.max(maximumY, Math.addExact(part.y(), part.height()));
            maximumZ = Math.max(maximumZ, Math.addExact(part.z(), part.depth()));
            cellCount = Math.addExact(cellCount, Math.multiplyExact(
                    Math.multiplyExact(part.width(), part.height()), part.depth()));
        }

        Set<Cell> occupied = new LinkedHashSet<>(hashCapacity(cellCount));
        for (JarPart part : this.parts) {
            int endX = part.x() + part.width();
            int endY = part.y() + part.height();
            int endZ = part.z() + part.depth();
            for (int x = part.x(); x < endX; x++) {
                for (int y = part.y(); y < endY; y++) {
                    for (int z = part.z(); z < endZ; z++) {
                        if (!occupied.add(new Cell(x, y, z))) {
                            throw new IllegalArgumentException("Jar parts cannot overlap");
                        }
                    }
                }
            }
        }
        this.cells = Collections.unmodifiableSet(occupied);
        this.minX = minimumX;
        this.minY = minimumY;
        this.minZ = minimumZ;
        this.maxX = maximumX;
        this.maxY = maximumY;
        this.maxZ = maximumZ;
    }

    public static JarAssembly single() { return new JarAssembly(List.of(new JarPart(0, 0, 1, 1))); }
    public static JarAssembly rectangle(int width, int depth) {
        return new JarAssembly(List.of(new JarPart(0, 0, width, depth)));
    }
    public static JarAssembly cuboid(int width, int height, int depth) {
        return new JarAssembly(List.of(new JarPart(0, 0, 0, width, height, depth)));
    }

    public List<JarPart> parts() { return parts; }
    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }
    public int width() { return maxX - minX; }
    public int height() { return maxY - minY; }
    public int depth() { return maxZ - minZ; }

    public JarAssembly normalized() {
        return minX == 0 && minY == 0 && minZ == 0 ? this : translated(-minX, -minY, -minZ);
    }

    public JarAssembly translated(int dx, int dy, int dz) {
        if (dx == 0 && dy == 0 && dz == 0) return this;
        return new JarAssembly(parts.stream().map(part -> part.translated(dx, dy, dz)).toList());
    }

    /** Returns the cached immutable cell index. */
    public Set<Cell> cells() { return cells; }

    public boolean contains(int cellX, int cellY, int cellZ) {
        if (cellX < minX || cellX >= maxX || cellY < minY || cellY >= maxY
                || cellZ < minZ || cellZ >= maxZ) return false;
        return cells.contains(new Cell(cellX, cellY, cellZ));
    }

    public JarPart partAt(int cellX, int cellY, int cellZ) {
        if (!contains(cellX, cellY, cellZ)) return null;
        for (JarPart part : parts) if (part.contains(cellX, cellY, cellZ)) return part;
        return null;
    }

    public JarAssembly without(JarPart removed) {
        List<JarPart> remaining = new ArrayList<>(parts);
        if (!remaining.remove(removed) || remaining.isEmpty()) return null;
        return new JarAssembly(remaining).normalized();
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof JarAssembly that && parts.equals(that.parts);
    }

    @Override
    public int hashCode() { return parts.hashCode(); }

    @Override
    public String toString() { return "JarAssembly[parts=" + parts + ']'; }

    private static int hashCapacity(int expectedSize) {
        if (expectedSize < 3) return expectedSize + 1;
        return expectedSize < 1 << 30 ? (int) Math.ceil(expectedSize / 0.75d) : Integer.MAX_VALUE;
    }

    public record Cell(int x, int y, int z) {}
}
