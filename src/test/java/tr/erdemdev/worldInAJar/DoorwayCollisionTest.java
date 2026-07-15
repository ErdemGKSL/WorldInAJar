package tr.erdemdev.worldInAJar;

import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoorwayCollisionTest {
    @Test
    void fullBlocksClogEveryPortalFace() {
        List<BoundingBox> fullBlock = List.of(box(0, 0, 0, 1, 1, 1));

        assertTrue(DoorwayCollision.blocksPortal(fullBlock, BlockFace.NORTH));
        assertTrue(DoorwayCollision.blocksPortal(fullBlock, BlockFace.SOUTH));
        assertTrue(DoorwayCollision.blocksPortal(fullBlock, BlockFace.WEST));
        assertTrue(DoorwayCollision.blocksPortal(fullBlock, BlockFace.EAST));
    }

    @Test
    void floorCoveringsAndShallowSnowDoNotClog() {
        assertFalse(DoorwayCollision.blocksPortal(
                List.of(box(0, 0, 0, 1, 1.0 / 16.0, 1)), BlockFace.EAST));
        assertFalse(DoorwayCollision.blocksPortal(
                List.of(box(0, 0, 0, 1, 0.5, 1)), BlockFace.EAST));
        assertTrue(DoorwayCollision.blocksPortal(
                List.of(box(0, 0, 0, 1, 9.0 / 16.0, 1)), BlockFace.EAST));
    }

    @Test
    void doorOnlyClogsWhenItsCurrentShapeReachesPortalSide() {
        assertTrue(DoorwayCollision.blocksPortal(
                List.of(box(0, 0, 0, 3.0 / 16.0, 1, 1)), BlockFace.EAST));
        assertFalse(DoorwayCollision.blocksPortal(
                List.of(box(13.0 / 16.0, 0, 0, 1, 1, 1)), BlockFace.EAST));

        assertTrue(DoorwayCollision.blocksPortal(
                List.of(box(0, 0, 0, 1, 1, 3.0 / 16.0)), BlockFace.SOUTH));
        assertFalse(DoorwayCollision.blocksPortal(
                List.of(box(0, 0, 13.0 / 16.0, 1, 1, 1)), BlockFace.SOUTH));
    }

    @Test
    void trapdoorUsesOpenOrientationAndClosedHalf() {
        assertTrue(DoorwayCollision.blocksPortal(
                List.of(box(0, 0, 0, 3.0 / 16.0, 1, 1)), BlockFace.EAST));
        assertFalse(DoorwayCollision.blocksPortal(
                List.of(box(13.0 / 16.0, 0, 0, 1, 1, 1)), BlockFace.EAST));
        assertFalse(DoorwayCollision.blocksPortal(
                List.of(box(0, 0, 0, 1, 3.0 / 16.0, 1)), BlockFace.EAST));
        assertTrue(DoorwayCollision.blocksPortal(
                List.of(box(0, 13.0 / 16.0, 0, 1, 1, 1)), BlockFace.EAST));
    }

    private static BoundingBox box(double minX, double minY, double minZ,
                                   double maxX, double maxY, double maxZ) {
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
