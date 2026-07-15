package tr.erdemdev.worldInAJar;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewServiceTest {
    @Test
    void portalShiftUsesEachPortalsPosition() {
        Location viewer = at(11.2, 5.5);

        assertTrue(PreviewService.onPortalSide(viewer, 10, 5, BlockFace.EAST));
        assertFalse(PreviewService.onPortalSide(viewer, 12, 5, BlockFace.EAST));
    }

    @Test
    void portalShiftUsesEachPortalsRotation() {
        Location westViewer = at(9.8, 5.5);

        assertTrue(PreviewService.onPortalSide(westViewer, 10, 5, BlockFace.WEST));
        assertFalse(PreviewService.onPortalSide(westViewer, 10, 5, BlockFace.EAST));
    }

    private static Location at(double x, double z) {
        return new Location(null, x, 0, z);
    }
}
