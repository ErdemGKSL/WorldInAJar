package tr.erdemdev.worldInAJar;

import org.bukkit.GameMode;

import java.util.UUID;

record SpectatorRecovery(Kind kind, UUID jarId, UUID carrierId, GameMode gameMode,
                         double relativeX, double relativeY, double relativeZ,
                         float yaw, float pitch,
                         String fallbackWorld, double fallbackX, double fallbackY, double fallbackZ) {
    SpectatorRecovery {
        if (kind == null || gameMode == null || gameMode == GameMode.SPECTATOR || fallbackWorld == null
                || jarId != null && carrierId == null || !finite(relativeX, relativeY, relativeZ,
                yaw, pitch, fallbackX, fallbackY, fallbackZ)) {
            throw new IllegalArgumentException("Invalid spectator recovery data");
        }
    }

    SpectatorRecovery remap(UUID targetJarId, int offsetX, int offsetY, int offsetZ,
                            int scale, String world, double x, double y, double z) {
        return new SpectatorRecovery(kind, targetJarId, carrierId, gameMode,
                kind == Kind.FOLLOW_CARRIER ? relativeX + offsetX * scale : relativeX,
                kind == Kind.FOLLOW_CARRIER ? relativeY + offsetY * scale : relativeY,
                kind == Kind.FOLLOW_CARRIER ? relativeZ + offsetZ * scale : relativeZ,
                yaw, pitch, kind == Kind.FOLLOW_CARRIER ? world : fallbackWorld,
                kind == Kind.FOLLOW_CARRIER ? x : fallbackX,
                kind == Kind.FOLLOW_CARRIER ? y : fallbackY,
                kind == Kind.FOLLOW_CARRIER ? z : fallbackZ);
    }

    SpectatorRecovery fallback(String world, double x, double y, double z) {
        return new SpectatorRecovery(kind, null, null, gameMode, 0, 0, 0,
                yaw, pitch, kind == Kind.FOLLOW_CARRIER ? world : fallbackWorld,
                kind == Kind.FOLLOW_CARRIER ? x : fallbackX,
                kind == Kind.FOLLOW_CARRIER ? y : fallbackY,
                kind == Kind.FOLLOW_CARRIER ? z : fallbackZ);
    }

    enum Kind { FOLLOW_CARRIER, INSPECT_JAR }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
