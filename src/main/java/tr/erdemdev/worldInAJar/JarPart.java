package tr.erdemdev.worldInAJar;

public record JarPart(int x, int y, int z, int width, int height, int depth) {
    public JarPart(int x, int z, int width, int depth) {
        this(x, 0, z, width, 1, depth);
    }

    public JarPart {
        if (width < 1 || height < 1 || depth < 1) {
            throw new IllegalArgumentException("Jar part dimensions must be positive");
        }
    }

    public boolean contains(int cellX, int cellY, int cellZ) {
        return cellX >= x && cellX < x + width
                && cellY >= y && cellY < y + height
                && cellZ >= z && cellZ < z + depth;
    }

    public JarPart translated(int dx, int dy, int dz) {
        return new JarPart(x + dx, y + dy, z + dz, width, height, depth);
    }
}
