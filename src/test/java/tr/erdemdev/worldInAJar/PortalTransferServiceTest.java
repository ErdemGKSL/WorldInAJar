package tr.erdemdev.worldInAJar;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PortalTransferServiceTest {
    @Test void outwardDistanceUsesTheDoorNormal() {
        assertEquals(1.0, PortalTransferService.outwardDistance(at(5, 3), 5, 4, BlockFace.NORTH));
        assertEquals(1.0, PortalTransferService.outwardDistance(at(5, 5), 5, 4, BlockFace.SOUTH));
        assertEquals(1.0, PortalTransferService.outwardDistance(at(3, 4), 4, 4, BlockFace.WEST));
        assertEquals(1.0, PortalTransferService.outwardDistance(at(5, 4), 4, 4, BlockFace.EAST));
    }

    @Test void progressDistinguishesEnteringFromExiting() {
        Location outside = at(5, 2);
        Location door = at(5, 3);
        assertEquals(-1.0, PortalTransferService.outwardProgress(outside, door, BlockFace.NORTH));
        assertEquals(1.0, PortalTransferService.outwardProgress(door, outside, BlockFace.NORTH));
    }

    @Test void overlapRequiresPositiveIntersection() {
        assertTrue(PortalTransferService.overlaps(0, 1, .5, 1.5));
        assertFalse(PortalTransferService.overlaps(0, 1, 1, 2));
        assertFalse(PortalTransferService.overlaps(0, 1, 2, 3));
    }

    private static Location at(double x, double z) {
        return new Location(null, x, 0, z);
    }
}
