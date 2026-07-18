package tr.erdemdev.worldInAJar;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

final class JarItemLoreService {
    private final WorldInAJar plugin;
    private final JarRepository repository;
    private final JarItems items;
    private final InteriorService interiors;

    JarItemLoreService(WorldInAJar plugin, JarRepository repository, JarItems items,
                       InteriorService interiors) {
        this.plugin = plugin;
        this.repository = repository;
        this.items = items;
        this.interiors = interiors;
    }

    List<String> playerNames(JarRecord jar) {
        return interiors.occupants(jar).stream()
                .filter(player -> isVisible(player.getGameMode(), player.isDead()))
                .map(Player::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    static boolean isVisible(GameMode gameMode, boolean dead) {
        return gameMode != GameMode.SPECTATOR && !dead;
    }

    void refresh(UUID jarId) {
        if (jarId == null) return;
        JarRecord jar = repository.byId(jarId).orElse(null);
        if (jar == null) return;
        List<String> names = playerNames(jar);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            refresh(player.getInventory(), jarId, names);
            refresh(player.getEnderChest(), jarId, names);
            refresh(player.getOpenInventory().getTopInventory(), jarId, names);
            ItemStack cursor = player.getItemOnCursor();
            if (jarId.equals(items.id(cursor)) && items.updateOccupants(cursor, names)) {
                player.setItemOnCursor(cursor);
            }
        }
        for (World world : plugin.getServer().getWorlds()) {
            for (Item entity : world.getEntitiesByClass(Item.class)) {
                ItemStack stack = entity.getItemStack();
                if (jarId.equals(items.id(stack)) && items.updateOccupants(stack, names)) {
                    entity.setItemStack(stack);
                }
            }
        }
    }

    void refresh(Collection<UUID> jarIds) {
        jarIds.stream().filter(java.util.Objects::nonNull).distinct().forEach(this::refresh);
    }

    void refreshPlayer(Player player) {
        refreshInventory(player.getInventory());
        refreshInventory(player.getEnderChest());
        refreshInventory(player.getOpenInventory().getTopInventory());
        ItemStack cursor = player.getItemOnCursor();
        UUID cursorId = items.id(cursor);
        JarRecord cursorJar = cursorId == null ? null : repository.byId(cursorId).orElse(null);
        if (cursorJar != null && items.updateOccupants(cursor, playerNames(cursorJar))) {
            player.setItemOnCursor(cursor);
        }
    }

    void refreshInventory(Inventory inventory) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            UUID jarId = items.id(stack);
            if (jarId == null) continue;
            JarRecord jar = repository.byId(jarId).orElse(null);
            if (jar != null && items.updateOccupants(stack, playerNames(jar))) {
                inventory.setItem(slot, stack);
            }
        }
    }

    private void refresh(Inventory inventory, UUID jarId, List<String> names) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (jarId.equals(items.id(stack)) && items.updateOccupants(stack, names)) {
                inventory.setItem(slot, stack);
            }
        }
    }
}
