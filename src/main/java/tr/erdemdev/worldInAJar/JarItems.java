package tr.erdemdev.worldInAJar;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class JarItems {
    private final JavaPlugin plugin;
    private final NamespacedKey itemKey;
    private final NamespacedKey idKey;
    private final NamespacedKey widthKey;
    private final NamespacedKey depthKey;
    private final NamespacedKey scaleKey;
    private final NamespacedKey partsKey;
    private final NamespacedKey partsVersionKey;
    private final NamespacedKey portalSideKey;

    public JarItems(JavaPlugin plugin) {
        this.plugin = plugin;
        itemKey = new NamespacedKey(plugin, "jar");
        idKey = new NamespacedKey(plugin, "jar_id");
        widthKey = new NamespacedKey(plugin, "jar_width");
        depthKey = new NamespacedKey(plugin, "jar_depth");
        scaleKey = new NamespacedKey(plugin, "jar_scale");
        partsKey = new NamespacedKey(plugin, "jar_parts");
        partsVersionKey = new NamespacedKey(plugin, "jar_parts_version");
        portalSideKey = new NamespacedKey(plugin, "portal_side");
    }

    public ItemStack create(UUID id) {
        int scale = Math.max(16, Math.min(256, plugin.getConfig().getInt("jar.scale", 32)));
        return create(id, JarAssembly.single(), scale);
    }

    public ItemStack create(UUID id, JarAssembly assembly, int scale) {
        return create(id, assembly, scale, List.of());
    }

    public ItemStack create(UUID id, JarAssembly assembly, int scale, Collection<String> occupantNames) {
        assembly = assembly.normalized();
        JarAssembly storedAssembly = assembly;
        ItemStack item = new ItemStack(Material.GLASS);
        item.editMeta(meta -> {
            String dimensions = storedAssembly.width() + "x" + storedAssembly.height()
                    + "x" + storedAssembly.depth();
            meta.displayName(Component.text(storedAssembly.cells().size() == 1
                    ? "World in a Jar" : "Combined World Jar (" + dimensions + ")"));
            meta.lore(lore(id, storedAssembly, occupantNames));
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
            meta.getPersistentDataContainer().set(widthKey, PersistentDataType.INTEGER, storedAssembly.width());
            meta.getPersistentDataContainer().set(depthKey, PersistentDataType.INTEGER, storedAssembly.depth());
            meta.getPersistentDataContainer().set(scaleKey, PersistentDataType.INTEGER, scale);
            int[] encoded = new int[storedAssembly.parts().size() * 6];
            for (int index = 0; index < storedAssembly.parts().size(); index++) {
                JarPart part = storedAssembly.parts().get(index);
                encoded[index * 6] = part.x(); encoded[index * 6 + 1] = part.y();
                encoded[index * 6 + 2] = part.z(); encoded[index * 6 + 3] = part.width();
                encoded[index * 6 + 4] = part.height(); encoded[index * 6 + 5] = part.depth();
            }
            meta.getPersistentDataContainer().set(partsKey, PersistentDataType.INTEGER_ARRAY, encoded);
            meta.getPersistentDataContainer().set(partsVersionKey, PersistentDataType.INTEGER, 2);
            if (id != null) meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, id.toString());
        });
        return item;
    }

    public boolean updateOccupants(ItemStack item, Collection<String> occupantNames) {
        UUID id = id(item);
        JarAssembly assembly = assembly(item);
        if (id == null || assembly == null) return false;
        List<Component> updated = lore(id, assembly, occupantNames);
        ItemMeta meta = item.getItemMeta();
        if (meta != null && updated.equals(meta.lore())) return false;
        item.editMeta(edited -> edited.lore(updated));
        return true;
    }

    static List<Component> lore(UUID id, JarAssembly assembly, Collection<String> occupantNames) {
        String dimensions = assembly.width() + "x" + assembly.height() + "x" + assembly.depth();
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(id == null
                ? "Place to create a miniature world" : "Contains a persistent miniature world"));
        lore.add(Component.text("Footprint: " + dimensions + ", parts: " + assembly.parts().size()));
        if (id == null) return List.copyOf(lore);

        List<String> visibleNames = occupantNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
                .toList();
        if (visibleNames.isEmpty()) {
            lore.add(Component.text("Players inside: None"));
        } else {
            lore.add(Component.text("Players inside (" + visibleNames.size() + "):"));
            visibleNames.forEach(name -> lore.add(Component.text("- " + name)));
        }
        return List.copyOf(lore);
    }

    public boolean isJar(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE);
    }

    public ItemStack createPortalSide() {
        ItemStack item = new ItemStack(Material.PURPLE_STAINED_GLASS_PANE);
        item.editMeta(meta -> {
            meta.displayName(Component.text("Jar Portal Side"));
            meta.lore(List.of(Component.text("Right-click an exposed jar side to install")));
            meta.getPersistentDataContainer().set(portalSideKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    public boolean isPortalSide(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(portalSideKey, PersistentDataType.BYTE);
    }

    public UUID id(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE)) return null;
        String value = meta.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        try { return value == null ? null : UUID.fromString(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    public JarAssembly assembly(ItemStack item) {
        if (!isJar(item)) return null;
        ItemMeta meta = item.getItemMeta();
        int[] encoded = meta.getPersistentDataContainer().get(partsKey, PersistentDataType.INTEGER_ARRAY);
        Integer version = meta.getPersistentDataContainer().get(partsVersionKey, PersistentDataType.INTEGER);
        if (version != null && version >= 2 && encoded != null && encoded.length > 0 && encoded.length % 6 == 0) {
            java.util.ArrayList<JarPart> parts = new java.util.ArrayList<>();
            try {
                for (int index = 0; index < encoded.length; index += 6) {
                    parts.add(new JarPart(encoded[index], encoded[index + 1], encoded[index + 2],
                            encoded[index + 3], encoded[index + 4], encoded[index + 5]));
                }
                return new JarAssembly(parts).normalized();
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        if ((version == null || version < 2) && encoded != null && encoded.length > 0 && encoded.length % 4 == 0) {
            java.util.ArrayList<JarPart> parts = new java.util.ArrayList<>();
            try {
                for (int index = 0; index < encoded.length; index += 4) {
                    parts.add(new JarPart(encoded[index], encoded[index + 1],
                            encoded[index + 2], encoded[index + 3]));
                }
                return new JarAssembly(parts).normalized();
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        Integer width = meta.getPersistentDataContainer().get(widthKey, PersistentDataType.INTEGER);
        Integer depth = meta.getPersistentDataContainer().get(depthKey, PersistentDataType.INTEGER);
        return JarAssembly.rectangle(width == null ? 1 : Math.max(1, width), depth == null ? 1 : Math.max(1, depth));
    }

    public int scale(ItemStack item) {
        if (!isJar(item)) return Math.max(16, Math.min(256, plugin.getConfig().getInt("jar.scale", 32)));
        ItemMeta meta = item.getItemMeta();
        int fallbackScale = Math.max(16, Math.min(256, plugin.getConfig().getInt("jar.scale", 32)));
        Integer scale = meta.getPersistentDataContainer().get(scaleKey, PersistentDataType.INTEGER);
        return scale == null ? fallbackScale : Math.max(16, Math.min(256, scale));
    }

    public ShapedRecipe recipe(JavaPlugin plugin) {
        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(plugin, "world_jar"), create(null));
        recipe.shape("G G", "G G", "GGG");
        recipe.setIngredient('G', Material.GLASS);
        return recipe;
    }

    public ShapedRecipe portalSideRecipe() {
        ShapedRecipe recipe = new ShapedRecipe(portalSideRecipeKey(), createPortalSide());
        recipe.shape("PP", "PP");
        recipe.setIngredient('P', Material.GLASS_PANE);
        return recipe;
    }

    public NamespacedKey portalSideRecipeKey() {
        return new NamespacedKey(plugin, "portal_side");
    }

}
