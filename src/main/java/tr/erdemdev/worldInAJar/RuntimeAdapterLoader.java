package tr.erdemdev.worldInAJar;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.util.Locale;
import java.util.Map;

public final class RuntimeAdapterLoader {
    private static final Map<String, String> V1_21_11 = adapters("v1_21_11");
    private static final Map<String, String> V26_1 = adapters("v26_1");
    private static final Map<String, String> V26_1_1 = adapters("v26_1_1");
    private static final Map<String, String> V26_1_2 = adapters("v26_1_2");
    private static final Map<String, String> V26_2 = adapters("v26_2");

    private RuntimeAdapterLoader() {
    }

    public static RuntimeAdapter load(JavaPlugin plugin) {
        String minecraft = normalizeMinecraftVersion(Bukkit.getBukkitVersion().split("-", 2)[0]);
        ServerPlatform platform = detectPlatform(plugin.getServer().getName());
        Selection selection = select(minecraft);
        if (!selection.exact()) {
            plugin.getLogger().warning("Minecraft " + minecraft + " has no exact adapter; using nearest "
                    + selection.adapterVersion() + " adapter from the same 26.1 family.");
        }
        Map<String, String> candidates = selection.candidates();
        String implementation = candidates.get(platform.name());
        try {
            Class<?> type = Class.forName(implementation, true, plugin.getClass().getClassLoader());
            Constructor<?> constructor = type.getConstructor(JavaPlugin.class);
            RuntimeAdapter adapter = (RuntimeAdapter) constructor.newInstance(plugin);
            plugin.getLogger().info("Selected " + implementation + " for " + minecraft + " " + platform + ".");
            return adapter;
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("Cannot initialize runtime adapter " + implementation, error);
        }
    }

    static Selection select(String minecraft) {
        Map<String, String> exact = switch (minecraft) {
            case "1.21.11" -> V1_21_11;
            case "26.1" -> V26_1;
            case "26.1.1" -> V26_1_1;
            case "26.1.2" -> V26_1_2;
            case "26.2" -> V26_2;
            default -> null;
        };
        if (exact != null) return new Selection(minecraft, exact, true);
        if (minecraft.startsWith("26.1.")) {
            int requested;
            try {
                requested = Integer.parseInt(minecraft.substring("26.1.".length()));
            } catch (NumberFormatException malformed) {
                throw unsupported(minecraft);
            }
            if (requested <= 0) return new Selection("26.1", V26_1, false);
            if (requested == 1) return new Selection("26.1.1", V26_1_1, false);
            return new Selection("26.1.2", V26_1_2, false);
        }
        throw unsupported(minecraft);
    }

    /** Paper 26.1.1 reports its API as e.g. {@code 26.1.1.build.29}; adapters are
     * selected by the underlying Minecraft release, not Paper's build suffix. */
    static String normalizeMinecraftVersion(String version) {
        return version.replaceFirst("\\.build\\.\\d+$", "");
    }

    private static IllegalStateException unsupported(String minecraft) {
        return new IllegalStateException("Unsupported Minecraft version " + minecraft
                + "; supported families: 1.21.11, 26.1.x, 26.2");
    }

    private static Map<String, String> adapters(String versionPackage) {
        String prefix = "tr.erdemdev.worldInAJar.adapter." + versionPackage + ".";
        return Map.of(
                "BUKKIT", prefix + "bukkit.BukkitRuntimeAdapter",
                "SPIGOT", prefix + "spigot.SpigotRuntimeAdapter",
                "PAPER", prefix + "paper.PaperRuntimeAdapter"
        );
    }

    static ServerPlatform detectPlatform(String serverName) {
        ClassLoader loader = RuntimeAdapterLoader.class.getClassLoader();
        if (present("io.papermc.paper.configuration.Configuration", loader)
                || serverName.toLowerCase(Locale.ROOT).contains("paper")) {
            return ServerPlatform.PAPER;
        }
        if (present("org.spigotmc.SpigotConfig", loader)
                || serverName.toLowerCase(Locale.ROOT).contains("spigot")) {
            return ServerPlatform.SPIGOT;
        }
        return ServerPlatform.BUKKIT;
    }

    private static boolean present(String name, ClassLoader loader) {
        try {
            Class.forName(name, false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    record Selection(String adapterVersion, Map<String, String> candidates, boolean exact) {
    }
}
