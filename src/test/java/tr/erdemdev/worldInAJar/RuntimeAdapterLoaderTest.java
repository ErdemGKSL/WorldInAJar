package tr.erdemdev.worldInAJar;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAdapterLoaderTest {
    @Test
    void selectsEveryExactVersionPackage() {
        assertExact("1.21.11", "v1_21_11");
        assertExact("26.1", "v26_1");
        assertExact("26.1.1", "v26_1_1");
        assertExact("26.1.2", "v26_1_2");
        assertExact("26.2", "v26_2");
    }

    @Test
    void fallsForwardToLatestKnownPatchOnlyInsideTwentySixOne() {
        RuntimeAdapterLoader.Selection selection = RuntimeAdapterLoader.select("26.1.7");

        assertFalse(selection.exact());
        assertEquals("26.1.2", selection.adapterVersion());
        assertTrue(selection.candidates().get("PAPER").contains(".v26_1_2.paper."));
    }

    @Test
    void mapsPaperBuildVersionsToTheirAdapters() {
        assertPaperBuildExact("1.21.11", "v1_21_11");
        assertPaperBuildExact("26.1", "v26_1");
        assertPaperBuildExact("26.1.1", "v26_1_1");
        assertPaperBuildExact("26.1.2", "v26_1_2");
        assertPaperBuildExact("26.2", "v26_2");

        RuntimeAdapterLoader.Selection fallback = RuntimeAdapterLoader.select("26.1.7.build.42");
        assertFalse(fallback.exact());
        assertEquals("26.1.2", fallback.adapterVersion());
    }

    @Test
    void rejectsOtherUnknownFamilies() {
        assertThrows(IllegalStateException.class, () -> RuntimeAdapterLoader.select("26.2.1"));
        assertThrows(IllegalStateException.class, () -> RuntimeAdapterLoader.select("26.3"));
        assertThrows(IllegalStateException.class, () -> RuntimeAdapterLoader.select("27.1"));
    }

    @Test
    void serverNameProvidesPortablePlatformFallback() {
        assertEquals(ServerPlatform.PAPER, RuntimeAdapterLoader.detectPlatform("Paper"));
        assertEquals(ServerPlatform.SPIGOT, RuntimeAdapterLoader.detectPlatform("Spigot"));
        assertEquals(ServerPlatform.BUKKIT, RuntimeAdapterLoader.detectPlatform("CraftBukkit"));
    }

    private static void assertExact(String version, String packageToken) {
        RuntimeAdapterLoader.Selection selection = RuntimeAdapterLoader.select(version);
        assertTrue(selection.exact());
        assertEquals(version, selection.adapterVersion());
        assertTrue(selection.candidates().get("BUKKIT").contains("." + packageToken + ".bukkit."));
        assertTrue(selection.candidates().get("SPIGOT").contains("." + packageToken + ".spigot."));
        assertTrue(selection.candidates().get("PAPER").contains("." + packageToken + ".paper."));
    }

    private static void assertPaperBuildExact(String version, String packageToken) {
        RuntimeAdapterLoader.Selection selection = RuntimeAdapterLoader.select(version + ".build.29");
        assertTrue(selection.exact());
        assertEquals(version, selection.adapterVersion());
        assertTrue(selection.candidates().get("PAPER").contains("." + packageToken + ".paper."));
    }
}
