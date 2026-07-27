package tr.erdemdev.worldInAJar.adapter.v1_21_11.paper;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import tr.erdemdev.worldInAJar.ServerPlatform;
import tr.erdemdev.worldInAJar.adapter.v1_21_11.AbstractRuntimeAdapter;

import java.util.concurrent.CompletableFuture;

public final class PaperRuntimeAdapter extends AbstractRuntimeAdapter {
    public PaperRuntimeAdapter(JavaPlugin plugin) {
        super(plugin, ServerPlatform.PAPER);
    }

    @Override
    public CompletableFuture<Chunk> loadChunk(World world, int x, int z, boolean generate) {
        return world.getChunkAtAsync(x, z, generate);
    }

    @Override
    public CompletableFuture<Boolean> teleport(Entity entity, Location destination) {
        return entity.teleportAsync(destination);
    }
}
