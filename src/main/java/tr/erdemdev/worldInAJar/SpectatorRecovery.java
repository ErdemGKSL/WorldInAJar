package tr.erdemdev.worldInAJar;

import org.bukkit.GameMode;

import java.util.UUID;

record SpectatorRecovery(UUID jarId, UUID carrierId, GameMode gameMode,
                         double relativeX, double relativeY, double relativeZ,
                         float yaw, float pitch,
                         String fallbackWorld, double fallbackX, double fallbackY, double fallbackZ) {
    SpectatorRecovery {
        if (gameMode == null || gameMode == GameMode.SPECTATOR || fallbackWorld == null
                || jarId != null && carrierId == null || !finite(relativeX, relativeY, relativeZ,
                yaw, pitch, fallbackX, fallbackY, fallbackZ)) {
            throw new IllegalArgumentException("Invalid spectator recovery data");
        }
    }

    SpectatorRecovery remap(UUID targetJarId, int offsetX, int offsetY, int offsetZ,
                            int scale, String world, double x, double y, double z) {
        return new SpectatorRecovery(targetJarId, carrierId, gameMode,
                relativeX + offsetX * scale, relativeY + offsetY * scale,
                relativeZ + offsetZ * scale, yaw, pitch, world, x, y, z);
    }

    SpectatorRecovery fallback(String world, double x, double y, double z) {
        return new SpectatorRecovery(null, null, gameMode, 0, 0, 0,
                yaw, pitch, world, x, y, z);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
