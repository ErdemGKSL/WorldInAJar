package tr.erdemdev.worldInAJar;

public record JarPart(int x, int z, int width, int depth) {
    public JarPart {
        if (width < 1 || depth < 1) throw new IllegalArgumentException("Jar part dimensions must be positive");
    }

    public boolean contains(int tileX, int tileZ) {
        return tileX >= x && tileX < x + width && tileZ >= z && tileZ < z + depth;
    }

    public JarPart translated(int dx, int dz) {
        return new JarPart(x + dx, z + dz, width, depth);
    }
}
