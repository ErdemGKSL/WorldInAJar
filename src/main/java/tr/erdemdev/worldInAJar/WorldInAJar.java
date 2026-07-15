package tr.erdemdev.worldInAJar;

import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldInAJar extends JavaPlugin {
    private JarRepository repository;
    private JarItems items;
    private InteriorService interiors;
    private PreviewService previews;
    private PortalTransferService transfers;

    @Override public void onEnable() {
        saveDefaultConfig();
        repository = new JarRepository(this); repository.load();
        items = new JarItems(this);
        interiors = new InteriorService(this); interiors.loadWorld();
        previews = new PreviewService(this, interiors);
        transfers = new PortalTransferService(this, repository, items, interiors);
        // Recipe registrations survive some plugin managers' hot reload cycle. Remove the
        // old key first so enabling is idempotent instead of disabling the whole plugin.
        getServer().removeRecipe(recipeKey());
        getServer().addRecipe(items.recipe(this));
        getServer().getPluginManager().registerEvents(
                new JarListener(this, repository, items, interiors, previews, transfers), this);
        JarCommand executor = new JarCommand(this);
        PluginCommand command = getCommand("jar");
        if (command == null) throw new IllegalStateException("jar command missing from plugin.yml");
        command.setExecutor(executor); command.setTabCompleter(executor);
        previews.start(repository);
        transfers.start();
        getLogger().info("Loaded " + repository.all().size() + " persistent world jar(s).");
    }

    @Override public void onDisable() {
        if (transfers != null) transfers.stop();
        if (previews != null) previews.stop();
        if (interiors != null) interiors.stop();
        if (repository != null) repository.save();
        getServer().removeRecipe(recipeKey());
    }

    private NamespacedKey recipeKey() {
        return new NamespacedKey(this, "world_jar");
    }

    public void reloadPlugin() {
        reloadConfig(); repository.load();
        previews.stop();
        previews.start(repository);
    }

    JarRepository repository() { return repository; }
    JarItems items() { return items; }
    InteriorService interiors() { return interiors; }
}
