package tr.erdemdev.worldInAJar.adapter.v1_21_11;

import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
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
    public World createInteriorWorld(String worldName, ChunkGenerator generator) {
        return createInteriorWorld(worldName, generator, true);
    }

    protected final World createInteriorWorld(String worldName, ChunkGenerator generator, boolean flatPreset) {
        WorldCreator creator = new WorldCreator(worldName).environment(World.Environment.NORMAL)
                .generator(generator).generateStructures(false);
        if (flatPreset) creator.type(WorldType.FLAT);
        return creator.createWorld();
    }

    @Override
    public void configureInteriorWorld(World world, boolean mobSpawning) {
        setBooleanRule(world, "SPAWN_MOBS", "DO_MOB_SPAWNING", mobSpawning);
        setBooleanRule(world, "ADVANCE_WEATHER", "DO_WEATHER_CYCLE", false);
    }

    @SuppressWarnings("unchecked")
    private void setBooleanRule(World world, String currentField, String legacyField, boolean value) {
        GameRule<?> rule = gameRuleField(currentField);
        if (rule == null) rule = gameRuleField(legacyField);
        if (rule == null) {
            plugin.getLogger().warning("No game rule found for " + currentField + "/" + legacyField + ".");
            return;
        }
        world.setGameRule((GameRule<Boolean>) rule, value);
    }

    private static GameRule<?> gameRuleField(String fieldName) {
        try {
            Object value = GameRule.class.getField(fieldName).get(null);
            return value instanceof GameRule<?> rule ? rule : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
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
