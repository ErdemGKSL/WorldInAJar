package tr.erdemdev.worldInAJar;

public final class CellLayout {
    private CellLayout() {}

    public static Cell cell(int index, int scale, int gap, int baseY) {
        int side = Math.max(1, (int) Math.ceil(Math.sqrt(index + 1.0)));
        int rowStart = (side - 1) * (side - 1);
        int offset = index - rowStart;
        int x;
        int z;
        if (offset < side) {
            x = side - 1;
            z = offset;
        } else {
            x = offset - side;
            z = side - 1;
        }
        int stride = scale + gap;
        return new Cell(x * stride, baseY, z * stride);
    }

    public record Cell(int minX, int minY, int minZ) {
        public int centerX(int scale) { return minX + scale / 2; }
        public int centerZ(int scale) { return minZ + scale / 2; }
    }
}
