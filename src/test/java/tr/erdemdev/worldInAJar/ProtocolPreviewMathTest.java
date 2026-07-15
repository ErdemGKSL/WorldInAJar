package tr.erdemdev.worldInAJar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProtocolPreviewMathTest {
    @Test void scalesAWorldPointAroundMappedEntityOrigins() {
        assertEquals(101.0, ProtocolPreviewMath.mapCoordinate(42.0, 10.0, 100.0, 1.0 / 32.0));
        assertEquals(1124.0, ProtocolPreviewMath.mapCoordinate(42.0, 10.0, 100.0, 32.0));
    }

    @Test void preservesTranslationWhenScaleIsOne() {
        assertEquals(-3.5, ProtocolPreviewMath.mapCoordinate(6.5, 20.0, 10.0, 1.0));
    }
}
