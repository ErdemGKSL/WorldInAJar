package tr.erdemdev.worldInAJar;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JarItemsTest {
    @Test
    void persistentJarLoreListsPlayersInStableOrder() {
        List<Component> lore = JarItems.lore(UUID.randomUUID(), JarAssembly.single(),
                List.of("zoe", "Alex"));

        assertEquals(Component.text("Players inside (2):"), lore.get(2));
        assertEquals(Component.text("- Alex"), lore.get(3));
        assertEquals(Component.text("- zoe"), lore.get(4));
    }

    @Test
    void persistentJarLoreShowsWhenNoLivePlayersAreInside() {
        List<Component> lore = JarItems.lore(UUID.randomUUID(), JarAssembly.single(), List.of());

        assertEquals(Component.text("Players inside: None"), lore.get(2));
    }

    @Test
    void unboundRecipeJarDoesNotHaveAnOccupantSection() {
        List<Component> lore = JarItems.lore(null, JarAssembly.single(), List.of("Alex"));

        assertEquals(2, lore.size());
    }
}
