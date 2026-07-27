package tr.erdemdev.worldInAJar.adapter.v1_21_11.bukkit;

import org.bukkit.plugin.java.JavaPlugin;
import tr.erdemdev.worldInAJar.ServerPlatform;
import tr.erdemdev.worldInAJar.adapter.v1_21_11.AbstractRuntimeAdapter;

public final class BukkitRuntimeAdapter extends AbstractRuntimeAdapter {
    public BukkitRuntimeAdapter(JavaPlugin plugin) {
        super(plugin, ServerPlatform.BUKKIT);
    }
}
