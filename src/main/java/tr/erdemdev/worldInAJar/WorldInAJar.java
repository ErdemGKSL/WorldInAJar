package tr.erdemdev.worldInAJar;

import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class WorldInAJar extends JavaPlugin {
    private JarRepository repository;
    private JarItems items;
    private InteriorService interiors;
    private JarItemLoreService itemLore;
    private PreviewService previews;
    private PortalTransferService transfers;
    private SpectatorService spectators;
    private TeleportPolicy policy;
    private JarBackService back;

    @Override public void onEnable() {
        saveDefaultConfig();
        policy = new TeleportPolicy();
        repository = new JarRepository(this); repository.load();
        items = new JarItems(this);
        interiors = new InteriorService(this, policy); interiors.loadWorld();
        itemLore = new JarItemLoreService(this, repository, items, interiors);
        previews = new PreviewService(this, interiors);
        transfers = new PortalTransferService(this, repository, items, interiors, policy);
        spectators = new SpectatorService(this, repository, items, interiors, previews, policy);
        back = new JarBackService(this, repository, items, interiors, previews, itemLore);
        // Recipe registrations survive some plugin managers' hot reload cycle. Remove the
        // old key first so enabling is idempotent instead of disabling the whole plugin.
        getServer().removeRecipe(recipeKey());
        getServer().removeRecipe(items.portalSideRecipeKey());
        removeLegacyCombinationRecipes();
        getServer().addRecipe(items.recipe(this));
        getServer().addRecipe(items.portalSideRecipe());
        getServer().getPluginManager().registerEvents(
                new JarListener(this, repository, items, itemLore, interiors, previews, transfers,
                        spectators, policy), this);
        getServer().getPluginManager().registerEvents(back, this);
        JarCommand executor = new JarCommand(this);
        PluginCommand command = getCommand("jar");
        if (command == null) throw new IllegalStateException("jar command missing from plugin.yml");
        command.setExecutor(executor); command.setTabCompleter(executor);
        previews.start(repository);
        transfers.start();
        spectators.start();
        back.start();
        getLogger().info("Loaded " + repository.all().size() + " persistent world jar(s).");
    }

    @Override public void onDisable() {
        if (back != null) back.stop();
        if (spectators != null) spectators.stop();
        if (transfers != null) transfers.stop();
        if (previews != null) previews.stop();
        if (interiors != null) interiors.stop();
        if (repository != null) repository.close();
        getServer().removeRecipe(recipeKey());
        if (items != null) getServer().removeRecipe(items.portalSideRecipeKey());
        removeLegacyCombinationRecipes();
    }

    private NamespacedKey recipeKey() {
        return new NamespacedKey(this, "world_jar");
    }

    private void removeLegacyCombinationRecipes() {
        for (int count = 2; count <= 9; count++) {
            getServer().removeRecipe(new NamespacedKey(this, "combine_jars_" + count));
        }
    }

    public void reloadPlugin() {
        reloadConfig(); repository.load();
        previews.stop();
        previews.start(repository);
    }

    JarRepository repository() { return repository; }
    JarItems items() { return items; }
    InteriorService interiors() { return interiors; }
    SpectatorService spectators() { return spectators; }
    PortalTransferService transfers() { return transfers; }
    TeleportPolicy policy() { return policy; }
    JarBackService back() { return back; }
}
