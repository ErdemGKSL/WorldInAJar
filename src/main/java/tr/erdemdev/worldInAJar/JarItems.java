package tr.erdemdev.worldInAJar;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

public final class JarItems {
    private final NamespacedKey itemKey;
    private final NamespacedKey idKey;

    public JarItems(JavaPlugin plugin) {
        itemKey = new NamespacedKey(plugin, "jar");
        idKey = new NamespacedKey(plugin, "jar_id");
    }

    public ItemStack create(UUID id) {
        ItemStack item = new ItemStack(Material.GLASS);
        item.editMeta(meta -> {
            meta.displayName(Component.text("World in a Jar"));
            meta.lore(List.of(Component.text(id == null ? "Place to create a miniature world" : "Contains a persistent miniature world")));
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
            if (id != null) meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toString());
        });
        return item;
    }

    public boolean isJar(ItemStack item) {
        return item != null && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    public UUID id(ItemStack item) {
        if (!isJar(item)) return null;
        String value = item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        try { return value == null ? null : UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    public ShapedRecipe recipe(JavaPlugin plugin) {
        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(plugin, "world_jar"), create(null));
        recipe.shape("G G", "G G", "GGG");
        recipe.setIngredient('G', Material.GLASS);
        return recipe;
    }
}
