package tr.erdemdev.worldInAJar;

import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class JarRepository {
    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, JarRecord> records = new HashMap<>();
    private int nextCell;

    public JarRepository(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "jars.yml");
    }

    public void load() {
        records.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        nextCell = yaml.getInt("next-cell", 0);
        ConfigurationSection jars = yaml.getConfigurationSection("jars");
        if (jars == null) return;
        for (String key : jars.getKeys(false)) {
            try {
                String path = "jars." + key + ".";
                UUID id = UUID.fromString(key);
                String world = Objects.requireNonNull(yaml.getString(path + "world"));
                int x = yaml.getInt(path + "x"), y = yaml.getInt(path + "y"), z = yaml.getInt(path + "z");
                boolean placed = yaml.contains(path + "placed")
                        ? yaml.getBoolean(path + "placed") : isPhysicalJar(world, x, y, z);
                List<JarPart> parts = loadParts(yaml, path);
                JarAssembly assembly = new JarAssembly(parts);
                String storedFace = yaml.getString(path + "portal-face");
                BlockFace portalFace = storedFace == null
                        ? BlockFace.valueOf(yaml.getString(path + "door", "NORTH"))
                        : storedFace.equals("NONE") ? null : BlockFace.valueOf(storedFace);
                JarAssembly.Tile portalTile = portalFace == null ? new JarAssembly.Tile(0, 0)
                        : yaml.contains(path + "portal-x")
                        ? new JarAssembly.Tile(yaml.getInt(path + "portal-x"), yaml.getInt(path + "portal-z"))
                        : JarRecord.defaultPortalTile(assembly, portalFace);
                JarRecord record = new JarRecord(id, UUID.fromString(yaml.getString(path + "owner")),
                        world, x, y, z,
                        portalFace, portalTile.x(), portalTile.z(),
                        yaml.getInt(path + "cell"), yaml.getInt(path + "scale", 60),
                        parts, placed);
                records.put(id, record);
                nextCell = Math.max(nextCell, record.cell() + 1);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Skipping invalid jar record " + key + ": " + exception.getMessage());
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-cell", nextCell);
        for (JarRecord record : records.values()) {
            String path = "jars." + record.id() + ".";
            yaml.set(path + "owner", record.owner().toString());
            yaml.set(path + "world", record.world());
            yaml.set(path + "x", record.x()); yaml.set(path + "y", record.y()); yaml.set(path + "z", record.z());
            yaml.set(path + "portal-face", record.door() == null ? "NONE" : record.door().name());
            yaml.set(path + "portal-x", record.doorX()); yaml.set(path + "portal-z", record.doorZ());
            yaml.set(path + "cell", record.cell());
            yaml.set(path + "scale", record.scale());
            yaml.set(path + "parts", record.parts().stream().map(part -> {
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("x", part.x()); values.put("z", part.z());
                values.put("width", part.width()); values.put("depth", part.depth());
                return values;
            }).toList());
            yaml.set(path + "placed", record.placed());
        }
        try { yaml.save(file); }
        catch (IOException exception) { plugin.getLogger().severe("Could not save jars.yml: " + exception.getMessage()); }
    }

    public JarRecord create(UUID owner, Location location, BlockFace door, int scale, JarAssembly assembly) {
        assembly = assembly.normalized();
        JarAssembly.Tile portal = JarRecord.defaultPortalTile(assembly, door);
        JarRecord record = new JarRecord(UUID.randomUUID(), owner, location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(),
                door, portal.x(), portal.z(), nextCell++,
                scale, assembly.normalized().parts(), true);
        records.put(record.id(), record); save(); return record;
    }

    public JarRecord createCarried(UUID owner, String world, int x, int y, int z,
                                   BlockFace door, int doorX, int doorZ, int scale, JarAssembly assembly) {
        JarRecord record = new JarRecord(UUID.randomUUID(), owner, world, x, y, z,
                door, doorX, doorZ, nextCell++,
                scale, assembly.normalized().parts(), false);
        records.put(record.id(), record); save(); return record;
    }

    public JarRecord replaceInNewCell(JarRecord previous, String world, int x, int y, int z,
                                      BlockFace door, int doorX, int doorZ,
                                      JarAssembly assembly, boolean placed) {
        JarRecord record = new JarRecord(previous.id(), previous.owner(), world, x, y, z,
                door, doorX, doorZ,
                nextCell++, previous.scale(), assembly.normalized().parts(), placed);
        records.put(record.id(), record); save(); return record;
    }

    public void put(JarRecord record) { records.put(record.id(), record); save(); }
    public Optional<JarRecord> remove(UUID id) {
        JarRecord removed = records.remove(id);
        if (removed != null) save();
        return Optional.ofNullable(removed);
    }
    public Optional<JarRecord> byId(UUID id) { return Optional.ofNullable(records.get(id)); }
    public Optional<JarRecord> at(Location location) { return records.values().stream().filter(j -> j.isAt(location)).findFirst(); }
    public Collection<JarRecord> all() { return Collections.unmodifiableCollection(records.values()); }

    private boolean isPhysicalJar(String worldName, int x, int y, int z) {
        var world = plugin.getServer().getWorld(worldName);
        return world != null && world.getBlockAt(x, y, z).getType() == org.bukkit.Material.GLASS;
    }

    private List<JarPart> loadParts(YamlConfiguration yaml, String path) {
        List<Map<?, ?>> stored = yaml.getMapList(path + "parts");
        if (stored.isEmpty()) {
            return List.of(new JarPart(0, 0, Math.max(1, yaml.getInt(path + "width", 1)),
                    Math.max(1, yaml.getInt(path + "depth", 1))));
        }
        List<JarPart> parts = new ArrayList<>();
        for (Map<?, ?> values : stored) {
            parts.add(new JarPart(number(values.get("x")), number(values.get("z")),
                    number(values.get("width")), number(values.get("depth"))));
        }
        return new JarAssembly(parts).normalized().parts();
    }

    private static int number(Object value) {
        if (value instanceof Number number) return number.intValue();
        throw new IllegalArgumentException("Invalid jar part number: " + value);
    }
}
