package tr.erdemdev.worldInAJar;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalAttachmentGeometryTest {
    private static final JarAssembly.Cell ORIGIN_CELL = new JarAssembly.Cell(0, 0, 0);

    @Test
    void findsEveryBlockTouchingTheOpenInteriorFace() {
        assertEquals(Set.of(position(11, 21, 31), position(12, 21, 31),
                        position(11, 22, 31), position(12, 22, 31)),
                Set.copyOf(PortalAttachmentGeometry.touchingInteriorFace(
                        JarAssembly.single(), ORIGIN_CELL, 10, 20, 30, 4, BlockFace.NORTH)));
        assertEquals(Set.of(position(11, 21, 32), position(12, 21, 32),
                        position(11, 22, 32), position(12, 22, 32)),
                Set.copyOf(PortalAttachmentGeometry.touchingInteriorFace(
                        JarAssembly.single(), ORIGIN_CELL, 10, 20, 30, 4, BlockFace.SOUTH)));
        assertEquals(Set.of(position(11, 21, 31), position(11, 21, 32),
                        position(11, 22, 31), position(11, 22, 32)),
                Set.copyOf(PortalAttachmentGeometry.touchingInteriorFace(
                        JarAssembly.single(), ORIGIN_CELL, 10, 20, 30, 4, BlockFace.WEST)));
        assertEquals(Set.of(position(12, 21, 31), position(12, 21, 32),
                        position(12, 22, 31), position(12, 22, 32)),
                Set.copyOf(PortalAttachmentGeometry.touchingInteriorFace(
                        JarAssembly.single(), ORIGIN_CELL, 10, 20, 30, 4, BlockFace.EAST)));
    }

    @Test
    void facesWithoutAnInteriorOpeningHaveNoAttachmentBlocks() {
        assertEquals(Set.of(), Set.copyOf(PortalAttachmentGeometry.touchingInteriorFace(
                JarAssembly.single(), ORIGIN_CELL, 10, 20, 30, 2, BlockFace.NORTH)));
    }

    @Test
    void rejectsVerticalPortalFaces() {
        assertThrows(IllegalArgumentException.class,
                () -> PortalAttachmentGeometry.touchingInteriorFace(
                        JarAssembly.single(), ORIGIN_CELL, 10, 20, 30, 4, BlockFace.UP));
    }

    @Test
    void includesCornersOfTheRoomAgainstTheSideWalls() {
        // scale 5: playable room spans local 1..3, so the corner of the wall (local x=1, y=1)
        // touching the west side wall must be reported alongside the middle positions.
        Set<PortalAttachmentGeometry.BlockPosition> positions = Set.copyOf(
                PortalAttachmentGeometry.touchingInteriorFace(
                        JarAssembly.single(), ORIGIN_CELL, 0, 0, 0, 5, BlockFace.NORTH));
        assertTrue(positions.contains(position(1, 1, 1)));
        assertTrue(positions.contains(position(3, 3, 1)));
    }

    @Test
    void includesHoppersAcrossAdjoiningCellsOfACombinedWall() {
        // A jar assembly two cells wide along X (door on the north wall of the (0,0,0) cell) has
        // no barrier between the cells, so a hopper touching the wall at the far corner of the
        // second cell must still be found even though it's outside the door's own cell.
        JarAssembly assembly = JarAssembly.rectangle(2, 1);
        int scale = 5;
        Set<PortalAttachmentGeometry.BlockPosition> positions = Set.copyOf(
                PortalAttachmentGeometry.touchingInteriorFace(
                        assembly, ORIGIN_CELL, 0, 0, 0, scale, BlockFace.NORTH));
        // Corner of the second (neighbor) cell, at the open boundary shared with the first cell.
        assertTrue(positions.contains(position(scale, 1, 1)));
        // Far outer corner of the second cell, against its own outer side wall.
        assertTrue(positions.contains(position(2 * scale - 2, 3, 1)));
    }

    private static PortalAttachmentGeometry.BlockPosition position(int x, int y, int z) {
        return new PortalAttachmentGeometry.BlockPosition(x, y, z);
    }
}
