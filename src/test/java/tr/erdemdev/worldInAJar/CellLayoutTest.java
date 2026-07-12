package tr.erdemdev.worldInAJar;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CellLayoutTest {
    @Test void allocationIsStableAndUnique() {
        Set<String> locations = new HashSet<>();
        for (int index = 0; index < 500; index++) {
            CellLayout.Cell cell = CellLayout.cell(index, 320, 8, 64);
            assertEquals(64, cell.minY());
            assertTrue(locations.add(cell.minX() + ":" + cell.minZ()), "duplicate cell " + index);
        }
    }

    @Test void startsInExpectedSquareSpiralRings() {
        assertEquals(new CellLayout.Cell(0, 64, 0), CellLayout.cell(0, 320, 8, 64));
        assertEquals(new CellLayout.Cell(328, 64, 0), CellLayout.cell(1, 320, 8, 64));
        assertEquals(new CellLayout.Cell(328, 64, 328), CellLayout.cell(2, 320, 8, 64));
        assertEquals(new CellLayout.Cell(0, 64, 328), CellLayout.cell(3, 320, 8, 64));
    }
}
