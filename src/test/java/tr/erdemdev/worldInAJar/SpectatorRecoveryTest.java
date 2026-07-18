package tr.erdemdev.worldInAJar;

import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpectatorRecoveryTest {
    @Test void remapPreservesPositionWithinAnAttachedJar() {
        UUID source = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        SpectatorRecovery recovery = recovery(source);

        SpectatorRecovery remapped = recovery.remap(target, 2, 1, -3, 32,
                "world", 10.5, 65, 10.5);

        assertEquals(target, remapped.jarId());
        assertEquals(76.25, remapped.relativeX());
        assertEquals(52.5, remapped.relativeY());
        assertEquals(-89.25, remapped.relativeZ());
        assertEquals(GameMode.ADVENTURE, remapped.gameMode());
    }

    @Test void deletionConvertsRecoveryToAnExteriorFallback() {
        SpectatorRecovery fallback = recovery(UUID.randomUUID())
                .fallback("outside", 4.5, 70, -8.5);

        assertNull(fallback.jarId());
        assertNull(fallback.carrierId());
        assertEquals("outside", fallback.fallbackWorld());
        assertEquals(4.5, fallback.fallbackX());
        assertEquals(70, fallback.fallbackY());
        assertEquals(-8.5, fallback.fallbackZ());
    }

    @Test void nonFiniteLocationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SpectatorRecovery(
                UUID.randomUUID(), UUID.randomUUID(), GameMode.SURVIVAL,
                Double.NaN, 1, 1, 0, 0, "world", 0, 64, 0));
    }

    @Test void jarRecoveryRequiresANonSpectatorModeAndCarrier() {
        assertThrows(IllegalArgumentException.class, () -> new SpectatorRecovery(
                UUID.randomUUID(), null, GameMode.SURVIVAL,
                1, 1, 1, 0, 0, "world", 0, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> new SpectatorRecovery(
                UUID.randomUUID(), UUID.randomUUID(), GameMode.SPECTATOR,
                1, 1, 1, 0, 0, "world", 0, 64, 0));
    }

    private static SpectatorRecovery recovery(UUID jarId) {
        return new SpectatorRecovery(jarId, UUID.randomUUID(), GameMode.ADVENTURE,
                12.25, 20.5, 6.75, 40, -10, "outside", 0.5, 65, 0.5);
    }
}
