package tr.erdemdev.worldInAJar;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Marks teleports issued by this plugin so the boundary guard in {@link JarListener} can tell
 * them apart from external teleports. Every plugin code path that legitimately moves a player
 * across the interior-world boundary must go through {@link #teleport}.
 */
public final class TeleportPolicy {
    private final Set<UUID> permitted = new HashSet<>();

    public boolean teleport(Player player, Location destination) {
        permitted.add(player.getUniqueId());
        try {
            return player.teleport(destination);
        } finally {
            permitted.remove(player.getUniqueId());
        }
    }

    public void permitted(Player player, Runnable action) {
        permitted.add(player.getUniqueId());
        try {
            action.run();
        } finally {
            permitted.remove(player.getUniqueId());
        }
    }

    boolean isPermitted(Player player) {
        return permitted.contains(player.getUniqueId());
    }
}
