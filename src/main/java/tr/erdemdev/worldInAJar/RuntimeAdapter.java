package tr.erdemdev.worldInAJar;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.CompletableFuture;

/**
 * The only boundary through which core code may use server-implementation or
 * Minecraft-version-specific behavior.
 */
public interface RuntimeAdapter {
    String minecraftVersion();

    ServerPlatform platform();

    VirtualEntityFactory virtualEntities();

    CompletableFuture<Chunk> loadChunk(World world, int x, int z, boolean generate);

    CompletableFuture<Boolean> teleport(Entity entity, Location destination);

    void setCamera(Player viewer, Entity target);

    default EntityPreviewBackend createProtocolPreview(JavaPlugin plugin, InteriorService interiors) {
        return null;
    }

    default void close() {
    }
}
