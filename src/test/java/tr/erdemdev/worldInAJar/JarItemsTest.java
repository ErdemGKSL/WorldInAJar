package tr.erdemdev.worldInAJar;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JarItemsTest {
    @Test
    void persistentJarLoreListsPlayersInStableOrder() {
        List<String> lore = JarItems.lore(UUID.randomUUID(), JarAssembly.single(),
                List.of("zoe", "Alex"));

        assertEquals("Players inside (2):", lore.get(2));
        assertEquals("- Alex", lore.get(3));
        assertEquals("- zoe", lore.get(4));
    }

    @Test
    void persistentJarLoreShowsWhenNoLivePlayersAreInside() {
        List<String> lore = JarItems.lore(UUID.randomUUID(), JarAssembly.single(), List.of());

        assertEquals("Players inside: None", lore.get(2));
    }

    @Test
    void unboundRecipeJarDoesNotHaveAnOccupantSection() {
        List<String> lore = JarItems.lore(null, JarAssembly.single(), List.of("Alex"));

        assertEquals(2, lore.size());
    }
}
