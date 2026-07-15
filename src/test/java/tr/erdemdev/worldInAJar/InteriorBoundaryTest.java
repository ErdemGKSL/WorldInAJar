package tr.erdemdev.worldInAJar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteriorBoundaryTest {
    private static final int SCALE = 5;

    @Test
    void sealsExposedSideAtOpenFloorOfSteppedAssembly() {
        JarAssembly assembly = new JarAssembly(List.of(
                new JarPart(0, 0, 0, 2, 1, 1),
                new JarPart(1, 1, 0, 1, 1, 1)));
        JarAssembly.Cell upper = new JarAssembly.Cell(1, 1, 0);

        assertTrue(InteriorBoundary.isBarrier(assembly, upper, SCALE, 0, 0, 2));
        assertFalse(InteriorBoundary.isBarrier(assembly, upper, SCALE, 1, 0, 2));
    }

    @Test
    void sealsExposedDepthSideAtOpenFloorOfSteppedAssembly() {
        JarAssembly assembly = new JarAssembly(List.of(
                new JarPart(0, 0, 0, 1, 1, 2),
                new JarPart(0, 1, 1, 1, 1, 1)));
        JarAssembly.Cell upper = new JarAssembly.Cell(0, 1, 1);

        assertTrue(InteriorBoundary.isBarrier(assembly, upper, SCALE, 2, 0, 0));
        assertFalse(InteriorBoundary.isBarrier(assembly, upper, SCALE, 2, 0, 1));
    }

    @Test
    void sealsExposedSideWhenAnotherSideAtTheSameCornerIsShared() {
        JarAssembly assembly = new JarAssembly(List.of(
                new JarPart(0, 0, 0, 1, 1, 1),
                new JarPart(0, 0, -1, 1, 1, 1)));
        JarAssembly.Cell cell = new JarAssembly.Cell(0, 0, 0);

        assertTrue(InteriorBoundary.isBarrier(assembly, cell, SCALE, 0, 2, 0));
    }

    @Test
    void keepsInteriorOfSharedFacesOpen() {
        JarAssembly assembly = new JarAssembly(List.of(
                new JarPart(0, 0, 0, 2, 1, 1),
                new JarPart(1, 1, 0, 1, 1, 1)));

        assertFalse(InteriorBoundary.isBarrier(assembly,
                new JarAssembly.Cell(1, 0, 0), SCALE, 0, 2, 2));
        assertFalse(InteriorBoundary.isBarrier(assembly,
                new JarAssembly.Cell(1, 1, 0), SCALE, 2, 0, 2));
    }
}
