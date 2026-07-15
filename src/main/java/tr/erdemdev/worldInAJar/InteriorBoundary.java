package tr.erdemdev.worldInAJar;

/** Pure block-level boundary geometry for a scaled jar assembly. */
final class InteriorBoundary {
    private InteriorBoundary() {}

    static boolean isBarrier(JarAssembly assembly, JarAssembly.Cell cell, int scale,
                             int localX, int localY, int localZ) {
        return localX == 0 && !assembly.contains(cell.x() - 1, cell.y(), cell.z())
                || localX == scale - 1 && !assembly.contains(cell.x() + 1, cell.y(), cell.z())
                || localY == 0 && !assembly.contains(cell.x(), cell.y() - 1, cell.z())
                || localY == scale - 1 && !assembly.contains(cell.x(), cell.y() + 1, cell.z())
                || localZ == 0 && !assembly.contains(cell.x(), cell.y(), cell.z() - 1)
                || localZ == scale - 1 && !assembly.contains(cell.x(), cell.y(), cell.z() + 1);
    }
}
