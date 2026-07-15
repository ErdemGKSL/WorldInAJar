package tr.erdemdev.worldInAJar;

import org.junit.jupiter.api.Test;
import org.bukkit.block.BlockFace;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JarAssemblyTest {
    @Test
    void supportsNonRectangularPartsAndHoles() {
        JarAssembly assembly = new JarAssembly(List.of(
                new JarPart(0, 0, 2, 1),
                new JarPart(0, 1, 1, 2),
                new JarPart(2, 2, 1, 1)));

        assertEquals(3, assembly.width());
        assertEquals(3, assembly.depth());
        assertEquals(5, assembly.cells().size());
        assertFalse(assembly.contains(1, 0, 1));
        assertTrue(assembly.contains(2, 0, 2));
    }

    @Test
    void rejectsOverlappingParts() {
        assertThrows(IllegalArgumentException.class, () -> new JarAssembly(List.of(
                new JarPart(0, 0, 2, 2), new JarPart(1, 1, 1, 1))));
    }

    @Test
    void normalizationPreservesRelativeLayout() {
        JarAssembly normalized = new JarAssembly(List.of(
                new JarPart(-2, 4, 1, 1), new JarPart(1, 6, 2, 1))).normalized();

        assertEquals(new JarPart(0, 0, 1, 1), normalized.parts().get(0));
        assertEquals(new JarPart(3, 2, 2, 1), normalized.parts().get(1));
        assertEquals(5, normalized.width());
        assertEquals(3, normalized.depth());
    }

    @Test
    void removingPartReturnsNormalizedRemainingAssembly() {
        JarPart removed = new JarPart(0, 0, 1, 1);
        JarAssembly assembly = new JarAssembly(List.of(
                removed, new JarPart(2, 1, 1, 2), new JarPart(3, 2, 1, 1)));

        JarAssembly remaining = assembly.without(removed);

        assertNotNull(remaining);
        assertEquals(List.of(new JarPart(0, 0, 1, 2), new JarPart(1, 1, 1, 1)), remaining.parts());
    }

    @Test
    void defaultPortalTileIsExposedOnRequestedFace() {
        JarAssembly assembly = new JarAssembly(List.of(
                new JarPart(0, 0, 2, 1), new JarPart(0, 1, 1, 2)));

        JarAssembly.Cell east = JarRecord.defaultPortalCell(assembly, BlockFace.EAST);

        assertTrue(assembly.contains(east.x(), east.y(), east.z()));
        assertFalse(assembly.contains(east.x() + 1, east.y(), east.z()));
    }

    @Test
    void portalCannotBeStoredOnInternalFace() {
        assertThrows(IllegalArgumentException.class, () -> new JarRecord(
                UUID.randomUUID(), UUID.randomUUID(), "world", 0, 64, 0,
                BlockFace.EAST, 0, 0, 0, 0, 32,
                List.of(new JarPart(0, 0, 2, 1)), true));
    }

    @Test
    void supportsVerticalPartsAndOpenSharedFaces() {
        JarAssembly assembly = new JarAssembly(List.of(
                new JarPart(0, 0, 0, 1, 1, 1),
                new JarPart(0, 1, 0, 1, 1, 1)));

        assertEquals(2, assembly.height());
        assertEquals(2, assembly.cells().size());
        assertTrue(assembly.contains(0, 0, 0));
        assertTrue(assembly.contains(0, 1, 0));
        assertFalse(assembly.contains(0, 2, 0));
    }

    @Test
    void normalizationPreservesVerticalOffsets() {
        JarAssembly normalized = new JarAssembly(List.of(
                new JarPart(0, -2, 0, 1, 1, 1),
                new JarPart(1, 1, 0, 1, 2, 1))).normalized();

        assertEquals(new JarPart(0, 0, 0, 1, 1, 1), normalized.parts().get(0));
        assertEquals(new JarPart(1, 3, 0, 1, 2, 1), normalized.parts().get(1));
        assertEquals(5, normalized.height());
    }

    @Test
    void reusesCachedGeometry() {
        JarAssembly assembly = new JarAssembly(List.of(
                new JarPart(0, 0, 0, 2, 2, 1),
                new JarPart(2, 1, 0, 1, 1, 1)));
        JarRecord jar = new JarRecord(UUID.randomUUID(), UUID.randomUUID(), "world",
                0, 64, 0, BlockFace.WEST, 0, 0, 0, 0, 32,
                assembly.parts(), true);

        assertSame(assembly.cells(), assembly.cells());
        assertSame(jar.assembly(), jar.assembly());
        assertSame(jar.assembly().cells(), jar.assembly().cells());
        assertEquals(3, jar.width());
        assertEquals(2, jar.height());
    }
}
