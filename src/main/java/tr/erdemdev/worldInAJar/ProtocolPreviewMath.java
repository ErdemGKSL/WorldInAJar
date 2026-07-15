package tr.erdemdev.worldInAJar;

final class ProtocolPreviewMath {
    private ProtocolPreviewMath() {}

    static double mapCoordinate(double value, double sourceOrigin, double targetOrigin, double scale) {
        return targetOrigin + (value - sourceOrigin) * scale;
    }
}
