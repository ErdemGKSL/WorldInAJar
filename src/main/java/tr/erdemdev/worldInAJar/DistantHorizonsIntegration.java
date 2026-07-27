package tr.erdemdev.worldInAJar;

import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configures DH Support's per-world remote settings for the interior world.
 *
 * <p>DH Support sends this configuration whenever Distant Horizons changes level. Consequently
 * the zero render-distance policy applies only while a player is in {@code world_in_a_jar}; the
 * normal policy for the outside world is sent again as soon as they leave. This deliberately
 * uses DH Support's public Bukkit configuration rather than its internal, version-specific
 * message classes.</p>
 */
final class DistantHorizonsIntegration {
    private static final String DH_SUPPORT_PLUGIN = "DHSupport";

    private final WorldInAJar plugin;
    private final Map<String, PreviousValue> previousValues = new LinkedHashMap<>();
    private Plugin dhSupport;
    private boolean applied;

    DistantHorizonsIntegration(WorldInAJar plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (!plugin.getConfig().getBoolean("distant-horizons.enabled", true)) return;
        dhSupport = plugin.getServer().getPluginManager().getPlugin(DH_SUPPORT_PLUGIN);
        if (dhSupport == null || !dhSupport.isEnabled()) {
            plugin.getLogger().fine("DH Support is not installed; skipping Distant Horizons integration.");
            return;
        }

        String worldName = plugin.interiors().world().getName();
        String prefix = "worlds." + worldName + ".";
        FileConfiguration config = dhSupport.getConfig();
        int renderDistance = Math.max(0, plugin.getConfig().getInt("distant-horizons.interior-render-distance", 0));

        rememberAndSet(config, prefix + "render_distance", renderDistance);
        rememberAndSet(config, prefix + "distant_generation_enabled", false);
        rememberAndSet(config, prefix + "real_time_updates_enabled", false);
        rememberAndSet(config, prefix + "login_data_sync_enabled", false);
        dhSupport.saveConfig();
        reloadDhSupport();
        applied = true;
        plugin.getLogger().info("Configured DH Support to hide distant LODs in interior world '" + worldName + "'.");
    }

    void stop() {
        if (!applied || dhSupport == null || !dhSupport.isEnabled()) return;
        FileConfiguration config = dhSupport.getConfig();
        for (Map.Entry<String, PreviousValue> entry : previousValues.entrySet()) {
            config.set(entry.getKey(), entry.getValue().present ? entry.getValue().value : null);
        }
        dhSupport.saveConfig();
        reloadDhSupport();
        previousValues.clear();
        applied = false;
    }

    private void rememberAndSet(FileConfiguration config, String path, Object value) {
        previousValues.putIfAbsent(path, new PreviousValue(config.contains(path), config.get(path)));
        config.set(path, value);
    }

    private void reloadDhSupport() {
        // DH Support exposes reload through its stable Bukkit command. Its Java internals are not
        // an API and have changed alongside the DH wire protocol.
        ConsoleCommandSender console = plugin.getServer().getConsoleSender();
        if (!plugin.getServer().dispatchCommand(console, "dhs reload")) {
            plugin.getLogger().warning("Could not reload DH Support; restart it or run /dhs reload to apply the jar-world LOD policy.");
        }
    }

    private record PreviousValue(boolean present, Object value) {
    }
}
