package tr.erdemdev.worldInAJar;

import org.bukkit.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JarItemLoreServiceTest {
    @Test
    void onlyLiveNonSpectatorPlayersAreVisible() {
        assertTrue(JarItemLoreService.isVisible(GameMode.SURVIVAL, false));
        assertTrue(JarItemLoreService.isVisible(GameMode.CREATIVE, false));
        assertTrue(JarItemLoreService.isVisible(GameMode.ADVENTURE, false));
        assertFalse(JarItemLoreService.isVisible(GameMode.SPECTATOR, false));
        assertFalse(JarItemLoreService.isVisible(GameMode.SURVIVAL, true));
    }
}
