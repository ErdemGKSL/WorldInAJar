package tr.erdemdev.worldInAJar;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PortalAttachmentGeometryTest {
    @Test
    void findsEveryBlockTouchingTheOpenInteriorFace() {
        assertEquals(Set.of(position(11, 21, 31), position(12, 21, 31),
                        position(11, 22, 31), position(12, 22, 31)),
                Set.copyOf(PortalAttachmentGeometry.touchingInteriorFace(
                        10, 20, 30, 4, BlockFace.NORTH)));
        assertEquals(Set.of(position(11, 21, 32), position(12, 21, 32),
                        position(11, 22, 32), position(12, 22, 32)),
                Set.copyOf(PortalAttachmentGeometry.touchingInteriorFace(
                        10, 20, 30, 4, BlockFace.SOUTH)));
        assertEquals(Set.of(position(11, 21, 31), position(11, 21, 32),
                        position(11, 22, 31), position(11, 22, 32)),
                Set.copyOf(PortalAttachmentGeometry.touchingInteriorFace(
                        10, 20, 30, 4, BlockFace.WEST)));
        assertEquals(Set.of(position(12, 21, 31), position(12, 21, 32),
                        position(12, 22, 31), position(12, 22, 32)),
                Set.copyOf(PortalAttachmentGeometry.touchingInteriorFace(
                        10, 20, 30, 4, BlockFace.EAST)));
    }

    @Test
    void facesWithoutAnInteriorOpeningHaveNoAttachmentBlocks() {
        assertEquals(Set.of(), Set.copyOf(PortalAttachmentGeometry.touchingInteriorFace(
                10, 20, 30, 2, BlockFace.NORTH)));
    }

    @Test
    void rejectsVerticalPortalFaces() {
        assertThrows(IllegalArgumentException.class,
                () -> PortalAttachmentGeometry.touchingInteriorFace(
                        10, 20, 30, 4, BlockFace.UP));
    }

    private static PortalAttachmentGeometry.BlockPosition position(int x, int y, int z) {
        return new PortalAttachmentGeometry.BlockPosition(x, y, z);
    }
}
