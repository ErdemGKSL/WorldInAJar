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
                JarRecord record = new JarRecord(id, UUID.fromString(yaml.getString(path + "owner")),
                        Objects.requireNonNull(yaml.getString(path + "world")), yaml.getInt(path + "x"),
                        yaml.getInt(path + "y"), yaml.getInt(path + "z"),
                        BlockFace.valueOf(yaml.getString(path + "door", "NORTH")),
                        yaml.getInt(path + "cell"), yaml.getInt(path + "scale", 60));
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
            yaml.set(path + "door", record.door().name()); yaml.set(path + "cell", record.cell());
            yaml.set(path + "scale", record.scale());
        }
        try { yaml.save(file); }
        catch (IOException exception) { plugin.getLogger().severe("Could not save jars.yml: " + exception.getMessage()); }
    }

    public JarRecord create(UUID owner, Location location, BlockFace door, int scale) {
        JarRecord record = new JarRecord(UUID.randomUUID(), owner, location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ(), door, nextCell++, scale);
        records.put(record.id(), record); save(); return record;
    }

    public void put(JarRecord record) { records.put(record.id(), record); save(); }
    public Optional<JarRecord> byId(UUID id) { return Optional.ofNullable(records.get(id)); }
    public Optional<JarRecord> at(Location location) { return records.values().stream().filter(j -> j.isAt(location)).findFirst(); }
    public Collection<JarRecord> all() { return Collections.unmodifiableCollection(records.values()); }
}
