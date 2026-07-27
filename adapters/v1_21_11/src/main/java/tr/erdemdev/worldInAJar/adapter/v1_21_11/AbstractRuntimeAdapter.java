package tr.erdemdev.worldInAJar.adapter.v1_21_11;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import tr.erdemdev.worldInAJar.RuntimeAdapter;
import tr.erdemdev.worldInAJar.ServerPlatform;
import tr.erdemdev.worldInAJar.VirtualEntityFactory;
import tr.erdemdev.worldInAJar.EntityPreviewBackend;
import tr.erdemdev.worldInAJar.InteriorService;

import java.util.concurrent.CompletableFuture;

public abstract class AbstractRuntimeAdapter implements RuntimeAdapter {
    protected final JavaPlugin plugin;
    private final ServerPlatform platform;
    private final VirtualEntityFactory virtualEntities = new NmsVirtualEntityFactory();

    protected AbstractRuntimeAdapter(JavaPlugin plugin, ServerPlatform platform) {
        this.plugin = plugin;
        this.platform = platform;
    }

    @Override
    public final String minecraftVersion() {
        return "1.21.11";
    }

    @Override
    public final ServerPlatform platform() {
        return platform;
    }

    @Override
    public final VirtualEntityFactory virtualEntities() {
        return virtualEntities;
    }

    @Override
    public CompletableFuture<Chunk> loadChunk(World world, int x, int z, boolean generate) {
        CompletableFuture<Chunk> result = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                result.complete(world.getChunkAt(x, z, generate));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    @Override
    public CompletableFuture<Boolean> teleport(Entity entity, Location destination) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                result.complete(entity.teleport(destination));
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }

    @Override
    public void setCamera(Player viewer, Entity target) {
        NmsCamera.set(viewer, target);
    }

    @Override
    public EntityPreviewBackend createProtocolPreview(JavaPlugin plugin, InteriorService interiors) {
        if (!("1.21" + ".11").equals(minecraftVersion())) return null;
        try {
            Class<?> type = Class.forName("tr.erdemdev.worldInAJar.ProtocolEntityPreview", true,
                    plugin.getClass().getClassLoader());
            return (EntityPreviewBackend) type.getConstructor(JavaPlugin.class, InteriorService.class)
                    .newInstance(plugin, interiors);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Cannot initialize ProtocolLib preview backend", exception);
        }
    }
}
