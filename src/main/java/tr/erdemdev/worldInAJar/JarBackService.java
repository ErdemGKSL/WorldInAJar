package tr.erdemdev.worldInAJar;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks which player has continuously held each jar item long enough to become its
 * "last holder", and lets that player recall lost jars through the /jar back chest menu.
 */
public final class JarBackService implements Listener {
    private static final long SCAN_INTERVAL_TICKS = 100L;

    private final WorldInAJar plugin;
    private final JarRepository repository;
    private final JarItems items;
    private final InteriorService interiors;
    private final PreviewService previews;
    private final JarItemLoreService itemLore;
    private final Map<UUID, Holding> holdings = new HashMap<>();
    private final long holdMillis;
    private BukkitTask task;

    JarBackService(WorldInAJar plugin, JarRepository repository, JarItems items,
                   InteriorService interiors, PreviewService previews, JarItemLoreService itemLore) {
        this.plugin = plugin;
        this.repository = repository;
        this.items = items;
        this.interiors = interiors;
        this.previews = previews;
        this.itemLore = itemLore;
        long minutes = Math.max(1, plugin.getConfig().getInt("jar.last-holder-minutes", 5));
        holdMillis = minutes * 60_000L;
    }

    public void start() {
        if (task != null) task.cancel();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::scanHolders,
                SCAN_INTERVAL_TICKS, SCAN_INTERVAL_TICKS);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        holdings.clear();
    }

    private void scanHolders() {
        Map<UUID, UUID> seen = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (ItemStack stack : player.getInventory().getContents()) {
                UUID jarId = items.id(stack);
                if (jarId != null) seen.putIfAbsent(jarId, player.getUniqueId());
            }
            UUID cursorId = items.id(player.getItemOnCursor());
            if (cursorId != null) seen.putIfAbsent(cursorId, player.getUniqueId());
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, UUID> entry : seen.entrySet()) {
            Holding holding = holdings.get(entry.getKey());
            if (holding == null || !holding.playerId().equals(entry.getValue())) {
                holdings.put(entry.getKey(), new Holding(entry.getValue(), now));
                continue;
            }
            if (now - holding.since() >= holdMillis
                    && !holding.playerId().equals(repository.lastHolder(entry.getKey()))) {
                repository.setLastHolder(entry.getKey(), holding.playerId());
            }
        }
        holdings.keySet().retainAll(seen.keySet());
    }

    public void open(Player player) {
        List<JarRecord> jars = repository.all().stream()
                .filter(jar -> player.getUniqueId().equals(repository.lastHolder(jar.id())))
                .sorted(Comparator.comparing(jar -> jar.id().toString()))
                .toList();
        if (jars.isEmpty()) {
            player.sendMessage("§cNo jars remember you as their last holder.");
            return;
        }
        int size = Math.min(54, (jars.size() + 8) / 9 * 9);
        List<UUID> slots = new ArrayList<>();
        Menu menu = new Menu(slots);
        Inventory inventory = Bukkit.createInventory(menu, size, Component.text("Retrieve Your Jars"));
        menu.inventory = inventory;
        for (JarRecord jar : jars) {
            if (slots.size() >= size) break;
            inventory.setItem(slots.size(), displayItem(jar));
            slots.add(jar.id());
        }
        player.openInventory(inventory);
    }

    /** A display-only stack without jar PDC keys, so menu copies can never leak or duplicate. */
    private ItemStack displayItem(JarRecord jar) {
        ItemStack stack = new ItemStack(Material.GLASS);
        String name = repository.name(jar.id());
        stack.editMeta(meta -> {
            meta.displayName(Component.text(name != null ? name : "World in a Jar"));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Footprint: " + jar.width() + "x" + jar.height()
                    + "x" + jar.depth()));
            lore.add(Component.text(jar.placed()
                    ? "Placed in " + jar.world() + " at " + jar.x() + ", " + jar.y() + ", " + jar.z()
                    : "Loose as an item"));
            lore.add(Component.text("Click to retrieve into your inventory"));
            meta.lore(lore);
        });
        return stack;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= menu.jarIds.size()) return;
        UUID jarId = menu.jarIds.get(slot);
        player.closeInventory();
        retrieve(player, jarId);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) event.setCancelled(true);
    }

    private void retrieve(Player player, UUID jarId) {
        JarRecord jar = repository.byId(jarId).orElse(null);
        if (jar == null) {
            player.sendMessage("§cThat jar no longer exists.");
            return;
        }
        if (!player.getUniqueId().equals(repository.lastHolder(jarId))) {
            player.sendMessage("§cThat jar no longer remembers you as its last holder.");
            return;
        }
        if (!interiors.isReady(jar)) {
            player.sendMessage("§cThat jar world is not ready yet. Please wait.");
            return;
        }
        if (jar.placed()) {
            JarRecord carried = jar.pickedUp();
            repository.put(carried);
            previews.seal(carried);
            clearFootprint(jar);
            give(player, carried);
        } else {
            removeItemInstances(jarId);
            give(player, jar);
        }
        String name = repository.name(jarId);
        player.sendMessage("§aRetrieved " + (name != null ? name : "a world jar")
                + " into your inventory.");
    }

    private void clearFootprint(JarRecord jar) {
        Location origin = jar.outsideLocation();
        if (origin == null) return;
        for (JarAssembly.Cell cell : jar.assembly().cells()) {
            var block = origin.getBlock().getRelative(cell.x(), cell.y(), cell.z());
            if (block.getType() != Material.GLASS) continue;
            block.setType(Material.AIR, false);
            previews.transportBlock(block.getLocation());
        }
    }

    private void give(Player player, JarRecord jar) {
        ItemStack stack = items.create(jar.id(), jar.assembly(), jar.scale(),
                itemLore.playerNames(jar), repository.name(jar.id()));
        for (ItemStack leftover : player.getInventory().addItem(stack).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    /** Removes every existing item stack carrying this jar id, wherever it currently sits. */
    private void removeItemInstances(UUID jarId) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            removeFrom(online.getInventory(), jarId);
            removeFrom(online.getEnderChest(), jarId);
            if (jarId.equals(items.id(online.getItemOnCursor()))) online.setItemOnCursor(null);
            Inventory top = online.getOpenInventory().getTopInventory();
            if (!(top.getHolder() instanceof Menu)) removeFrom(top, jarId);
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item item && jarId.equals(items.id(item.getItemStack()))) {
                    item.remove();
                } else if (entity instanceof InventoryHolder holder) {
                    removeFrom(holder.getInventory(), jarId);
                }
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities(false)) {
                    if (state instanceof InventoryHolder holder) removeFrom(holder.getInventory(), jarId);
                }
            }
        }
    }

    private void removeFrom(Inventory inventory, UUID jarId) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (jarId.equals(items.id(inventory.getItem(slot)))) inventory.setItem(slot, null);
        }
    }

    private record Holding(UUID playerId, long since) {}

    private static final class Menu implements InventoryHolder {
        private final List<UUID> jarIds;
        private Inventory inventory;

        private Menu(List<UUID> jarIds) { this.jarIds = jarIds; }

        @Override public Inventory getInventory() { return inventory; }
    }
}
