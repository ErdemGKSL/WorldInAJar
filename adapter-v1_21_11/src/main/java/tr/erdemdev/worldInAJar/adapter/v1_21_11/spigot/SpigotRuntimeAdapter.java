package tr.erdemdev.worldInAJar.adapter.v1_21_11.spigot;

import org.bukkit.plugin.java.JavaPlugin;
import tr.erdemdev.worldInAJar.ServerPlatform;
import tr.erdemdev.worldInAJar.adapter.v1_21_11.AbstractRuntimeAdapter;

public final class SpigotRuntimeAdapter extends AbstractRuntimeAdapter {
    public SpigotRuntimeAdapter(JavaPlugin plugin) {
        super(plugin, ServerPlatform.SPIGOT);
    }
}
