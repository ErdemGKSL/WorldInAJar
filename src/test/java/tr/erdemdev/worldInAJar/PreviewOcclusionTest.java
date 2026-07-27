package tr.erdemdev.worldInAJar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewOcclusionTest {
    private static final int RADIUS = 8;

    @Test
    void blockFacingOpenAirIsVisible() {
        PreviewOcclusion.OccluderGrid grid = grid();
        solid(grid, 4, 0, 0);

        assertTrue(PreviewOcclusion.visible(grid, centre(), 4, 0, 0));
    }

    @Test
    void blockBehindAWallIsCulled() {
        PreviewOcclusion.OccluderGrid grid = grid();
        wall(grid, 2);
        solid(grid, 4, 0, 0);

        assertFalse(PreviewOcclusion.visible(grid, centre(), 4, 0, 0));
    }

    @Test
    void wallItselfStaysVisible() {
        PreviewOcclusion.OccluderGrid grid = grid();
        wall(grid, 2);

        assertTrue(PreviewOcclusion.visible(grid, centre(), 2, 0, 0));
    }

    @Test
    void buriedBlockIsCulled() {
        PreviewOcclusion.OccluderGrid grid = grid();
        for (int x = 3; x <= 5; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            solid(grid, x, y, z);
        }

        assertFalse(PreviewOcclusion.visible(grid, centre(), 4, 0, 0));
    }

    @Test
    void blockHiddenFromOneEyePointIsKeptWhenAnotherSeesIt() {
        PreviewOcclusion.OccluderGrid grid = grid();
        // A wall east of the jar, ending at z = 2. Only the far cell of a 1x1x3 assembly can see
        // round it, which is exactly why visibility is traced from the whole interior volume.
        for (int y = -RADIUS; y <= RADIUS; y++) for (int z = -RADIUS; z <= 1; z++) solid(grid, 1, y, z);
        solid(grid, 4, 0, 2);

        assertFalse(PreviewOcclusion.visible(grid, centre(), 4, 0, 2));
        assertTrue(PreviewOcclusion.visible(grid, new double[] {.5, .5, .5, .5, .5, 2.5}, 4, 0, 2));
    }

    @Test
    void blockJustPastAnEdgeIsKeptByTheCornerRays() {
        PreviewOcclusion.OccluderGrid grid = grid();
        // Hides the centre of the target's west face but leaves its southern corners in view.
        solid(grid, 2, 0, 0);
        solid(grid, 3, 0, 0);

        assertTrue(PreviewOcclusion.visible(grid, centre(), 3, 0, 1));
    }

    @Test
    void blockOutsideTheGridIsTreatedAsVisible() {
        PreviewOcclusion.OccluderGrid grid = grid();

        assertTrue(PreviewOcclusion.visible(grid, centre(), RADIUS + 4, 0, 0));
    }

    @Test
    void fingerprintTracksTheBlockLayout() {
        PreviewOcclusion.OccluderGrid empty = grid();
        PreviewOcclusion.OccluderGrid occupied = grid();
        long before = occupied.fingerprint();
        solid(occupied, 4, 0, 0);

        assertNotEquals(before, occupied.fingerprint());
        assertNotEquals(empty.fingerprint(), occupied.fingerprint());
    }

    @Test
    void eyePointsCoverTheJarAndRespectTheBudget() {
        double[] eyes = PreviewOcclusion.eyePoints(JarAssembly.single(), 32, 5, 128);

        assertTrue(eyes.length > 0 && eyes.length % 3 == 0);
        assertTrue(eyes.length / 3 <= 128, "expected at most 128 eye points, got " + eyes.length / 3);
        double minimum = 1, maximum = 0;
        for (double coordinate : eyes) {
            assertTrue(coordinate > 0 && coordinate < 1, "eye point outside the cell: " + coordinate);
            minimum = Math.min(minimum, coordinate);
            maximum = Math.max(maximum, coordinate);
        }
        // The whole cell is covered, not just its middle.
        assertTrue(minimum < .05 && maximum > .95, "eye points only span " + minimum + ".." + maximum);
    }

    @Test
    void eyePointsSkipCellsTheAssemblyDoesNotHave() {
        // An L shape: the (1, 0, 1) corner of the bounding box holds no cell.
        JarAssembly assembly = new JarAssembly(List.of(
                new JarPart(0, 0, 0, 2, 1, 1), new JarPart(0, 0, 1, 1, 1, 1)));

        double[] eyes = PreviewOcclusion.eyePoints(assembly, 32, 5, 4096);

        for (int eye = 0; eye + 2 < eyes.length; eye += 3) {
            assertTrue(assembly.contains((int) Math.floor(eyes[eye]), (int) Math.floor(eyes[eye + 1]),
                    (int) Math.floor(eyes[eye + 2])), "eye point outside the assembly");
        }
    }

    @Test
    void tightBudgetFallsBackToTheCornersOfTheJar() {
        double[] eyes = PreviewOcclusion.eyePoints(JarAssembly.single(), 32, 1, 1);

        assertEquals(8, eyes.length / 3);
    }

    /** A single-cell jar: one block of open space at the origin, surrounded by radius blocks. */
    private static PreviewOcclusion.OccluderGrid grid() {
        return new PreviewOcclusion.OccluderGrid(-RADIUS, -RADIUS, -RADIUS,
                1 + 2 * RADIUS, 1 + 2 * RADIUS, 1 + 2 * RADIUS);
    }

    private static double[] centre() {
        return new double[] {.5, .5, .5};
    }

    private static void solid(PreviewOcclusion.OccluderGrid grid, int x, int y, int z) {
        grid.setOccluding(x, y, z);
        grid.setRenderable(x, y, z);
    }

    private static void wall(PreviewOcclusion.OccluderGrid grid, int x) {
        for (int y = -RADIUS; y <= RADIUS; y++) {
            for (int z = -RADIUS; z <= RADIUS; z++) solid(grid, x, y, z);
        }
    }
}
