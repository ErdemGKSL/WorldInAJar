package tr.erdemdev.worldInAJar;

import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

/** Pure geometry for blocks touching the playable side of an interior portal surface. */
final class PortalAttachmentGeometry {
    private PortalAttachmentGeometry() {}

    /**
     * Walks every cell of the assembly that forms the door's wall (i.e. shares the door cell's
     * depth along the portal's normal axis and has no neighbor further outward), and returns every
     * playable position in that cell directly behind the wall. Unlike scanning a single cell in
     * isolation, this includes positions at a cell's lateral/vertical edges whenever an adjoining
     * assembly cell keeps that edge open, so hoppers placed at the corner of a combined jar's
     * portal wall are still found.
     */
    static List<BlockPosition> touchingInteriorFace(JarAssembly assembly, JarAssembly.Cell doorCell,
                                                     int originX, int originY, int originZ,
                                                     int scale, BlockFace portalFace) {
        if (!horizontal(portalFace)) {
            throw new IllegalArgumentException("Portal face must be horizontal: " + portalFace);
        }
        if (scale <= 2) return List.of();
        int normalX = portalFace.getModX();
        int normalZ = portalFace.getModZ();
        boolean alongX = normalZ != 0;
        List<BlockPosition> positions = new ArrayList<>();
        for (JarAssembly.Cell cell : assembly.cells()) {
            if (alongX ? cell.z() != doorCell.z() : cell.x() != doorCell.x()) continue;
            if (assembly.contains(cell.x() + normalX, cell.y(), cell.z() + normalZ)) continue;
            int baseX = originX + cell.x() * scale;
            int baseY = originY + cell.y() * scale;
            int baseZ = originZ + cell.z() * scale;
            int depthLocal = normalX < 0 || normalZ < 0 ? 1 : scale - 2;
            for (int vertical = 0; vertical < scale; vertical++) {
                for (int lateral = 0; lateral < scale; lateral++) {
                    int localX = alongX ? lateral : depthLocal;
                    int localZ = alongX ? depthLocal : lateral;
                    if (InteriorBoundary.isBarrier(assembly, cell, scale, localX, vertical, localZ)) {
                        continue;
                    }
                    positions.add(new BlockPosition(baseX + localX, baseY + vertical, baseZ + localZ));
                }
            }
        }
        return positions;
    }

    private static boolean horizontal(BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH
                || face == BlockFace.WEST || face == BlockFace.EAST;
    }

    record BlockPosition(int x, int y, int z) {}
}
