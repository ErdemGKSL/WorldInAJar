package tr.erdemdev.worldInAJar;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SpectatorService {
    public enum StartResult { STARTED, NO_CARRIER, UNAVAILABLE }

    private final WorldInAJar plugin;
    private final JarRepository repository;
    private final JarItems items;
    private final InteriorService interiors;
    private final File file;
    private final Map<UUID, SpectatorRecovery> recoveries = new HashMap<>();
    private final Set<UUID> active = new HashSet<>();
    private BukkitTask task;

    public SpectatorService(WorldInAJar plugin, JarRepository repository, JarItems items,
                            InteriorService interiors) {
        this.plugin = plugin;
        this.repository = repository;
        this.items = items;
        this.interiors = interiors;
        file = new File(plugin.getDataFolder(), "spectators.yml");
    }

    public void start() {
        load();
        if (task != null) task.cancel();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
        save();
        active.clear();
    }

    public StartResult tryStart(Player player, JarRecord jar) {
        if (jar == null || jar.placed() || player.getGameMode() == GameMode.SPECTATOR
                || recoveries.containsKey(player.getUniqueId())) return StartResult.UNAVAILABLE;
        Player carrier = findCarrier(jar.id(), player.getUniqueId());
        if (carrier == null) return StartResult.NO_CARRIER;

        Location location = player.getLocation();
        CellLayout.Cell cell = interiors.cell(jar);
        Location fallback = fallback(jar);
        SpectatorRecovery recovery;
        try {
            recovery = new SpectatorRecovery(jar.id(), carrier.getUniqueId(), player.getGameMode(),
                    location.getX() - cell.minX(), location.getY() - cell.minY(),
                    location.getZ() - cell.minZ(), location.getYaw(), location.getPitch(),
                    fallback.getWorld().getName(), fallback.getX(), fallback.getY(), fallback.getZ());
        } catch (IllegalArgumentException exception) {
            return StartResult.UNAVAILABLE;
        }
        recoveries.put(player.getUniqueId(), recovery);
        if (!save()) {
            recoveries.remove(player.getUniqueId());
            player.sendMessage("§cYour spectator return point could not be saved.");
            return StartResult.UNAVAILABLE;
        }

        active.add(player.getUniqueId());
        player.setGameMode(GameMode.SPECTATOR);
        if (!player.teleport(carrier.getLocation()) || !target(player, carrier)) {
            restore(player);
            return StartResult.UNAVAILABLE;
        }
        player.sendMessage("§aYou are spectating the player carrying this jar.");
        return StartResult.STARTED;
    }

    public boolean hasRecovery(Player player) {
        return recoveries.containsKey(player.getUniqueId());
    }

    public boolean allowsTarget(Player player, Entity target) {
        SpectatorRecovery recovery = recoveries.get(player.getUniqueId());
        return recovery == null || target.getUniqueId().equals(recovery.carrierId());
    }

    public boolean restore(Player player) {
        SpectatorRecovery recovery = recoveries.get(player.getUniqueId());
        if (recovery == null) return false;
        return restore(player, recovery);
    }

    public void onJoin(Player player) {
        if (!hasRecovery(player)) return;
        active.remove(player.getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) restore(player);
        });
    }

    public void onQuit(Player player) {
        UUID playerId = player.getUniqueId();
        active.remove(playerId);
        for (UUID spectatorId : new ArrayList<>(active)) {
            SpectatorRecovery recovery = recoveries.get(spectatorId);
            if (recovery == null || !playerId.equals(recovery.carrierId())) continue;
            Player spectator = Bukkit.getPlayer(spectatorId);
            if (spectator != null && spectator.isOnline()) restore(spectator, recovery);
        }
        save();
    }

    public void placed(JarRecord jar) {
        forJar(jar.id(), (playerId, recovery) -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) restore(player, recovery);
        });
    }

    public void prepareAttachment(JarRecord source, JarRecord target,
                                  int offsetX, int offsetY, int offsetZ) {
        Location fallback = fallback(target);
        forJar(source.id(), (playerId, recovery) -> recoveries.put(playerId,
                recovery.remap(target.id(), offsetX, offsetY, offsetZ, source.scale(),
                        fallback.getWorld().getName(), fallback.getX(), fallback.getY(), fallback.getZ())));
        save();
    }

    public void deleted(JarRecord jar) {
        Location fallback = fallback(jar);
        forJar(jar.id(), (playerId, recovery) -> {
            SpectatorRecovery updated = recovery.fallback(fallback.getWorld().getName(),
                    fallback.getX(), fallback.getY(), fallback.getZ());
            recoveries.put(playerId, updated);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) restore(player, updated);
        });
        save();
    }

    private void tick() {
        for (Map.Entry<UUID, SpectatorRecovery> entry : new ArrayList<>(recoveries.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            SpectatorRecovery recovery = entry.getValue();
            if (!active.contains(entry.getKey())) {
                restore(player, recovery);
                continue;
            }
            JarRecord jar = recovery.jarId() == null ? null : repository.byId(recovery.jarId()).orElse(null);
            if (jar == null || jar.placed()) {
                restore(player, recovery);
                continue;
            }
            Player carrier = Bukkit.getPlayer(recovery.carrierId());
            if (carrier == null || !carrier.isOnline() || !contains(carrier, jar.id())) {
                restore(player, recovery);
                continue;
            }
            if (player.getGameMode() != GameMode.SPECTATOR) player.setGameMode(GameMode.SPECTATOR);
            if (player.getSpectatorTarget() != carrier) target(player, carrier);
        }
    }

    private boolean restore(Player player, SpectatorRecovery recovery) {
        Location destination;
        JarRecord jar = recovery.jarId() == null ? null : repository.byId(recovery.jarId()).orElse(null);
        if (jar != null) {
            if (!interiors.ensureBuilt(jar)) return false;
            CellLayout.Cell cell = interiors.cell(jar);
            destination = new Location(interiors.world(), cell.minX() + recovery.relativeX(),
                    cell.minY() + recovery.relativeY(), cell.minZ() + recovery.relativeZ(),
                    recovery.yaw(), recovery.pitch());
            if (!interiors.contains(jar, destination)) destination = interiors.entryLocation(jar, player);
        } else {
            World world = Bukkit.getWorld(recovery.fallbackWorld());
            destination = world == null ? Bukkit.getWorlds().getFirst().getSpawnLocation()
                    : new Location(world, recovery.fallbackX(), recovery.fallbackY(),
                    recovery.fallbackZ(), recovery.yaw(), recovery.pitch());
        }

        if (player.getGameMode() == GameMode.SPECTATOR) player.setSpectatorTarget(null);
        if (!player.teleport(destination)) {
            if (active.contains(player.getUniqueId())) {
                Player carrier = Bukkit.getPlayer(recovery.carrierId());
                if (carrier != null && carrier.isOnline()) target(player, carrier);
            }
            return false;
        }
        player.setGameMode(recovery.gameMode());
        active.remove(player.getUniqueId());
        recoveries.remove(player.getUniqueId());
        save();
        interiors.syncSession(player, player.getLocation(), repository);
        player.sendMessage(jar == null
                ? "§cThat jar no longer exists. You were moved to a safe location."
                : "§aYou returned inside the jar.");
        return true;
    }

    private boolean target(Player spectator, Player carrier) {
        if (spectator.equals(carrier) || spectator.getGameMode() != GameMode.SPECTATOR) return false;
        try {
            spectator.setSpectatorTarget(carrier);
            return true;
        } catch (IllegalStateException exception) {
            plugin.getLogger().warning("Could not set spectator target for " + spectator.getName()
                    + ": " + exception.getMessage());
            return false;
        }
    }

    private Player findCarrier(UUID jarId, UUID spectatorId) {
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> !player.getUniqueId().equals(spectatorId) && contains(player, jarId))
                .min(Comparator.comparing(player -> player.getUniqueId().toString())).orElse(null);
    }

    private boolean contains(Player player, UUID jarId) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (jarId.equals(items.id(item))) return true;
        }
        return false;
    }

    private Location fallback(JarRecord jar) {
        Location outside = jar.outsideLocation();
        return outside == null ? Bukkit.getWorlds().getFirst().getSpawnLocation()
                : outside.clone().add(.5, 1, .5);
    }

    private void forJar(UUID jarId, java.util.function.BiConsumer<UUID, SpectatorRecovery> action) {
        for (Map.Entry<UUID, SpectatorRecovery> entry : new ArrayList<>(recoveries.entrySet())) {
            if (jarId.equals(entry.getValue().jarId())) action.accept(entry.getKey(), entry.getValue());
        }
    }

    private void load() {
        recoveries.clear();
        active.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            String path = "players." + key + ".";
            try {
                UUID playerId = UUID.fromString(key);
                String jarValue = yaml.getString(path + "jar");
                String carrierValue = yaml.getString(path + "carrier");
                SpectatorRecovery recovery = new SpectatorRecovery(
                        jarValue == null ? null : UUID.fromString(jarValue),
                        carrierValue == null ? null : UUID.fromString(carrierValue),
                        GameMode.valueOf(yaml.getString(path + "game-mode", "SURVIVAL")),
                        yaml.getDouble(path + "relative.x"), yaml.getDouble(path + "relative.y"),
                        yaml.getDouble(path + "relative.z"), (float) yaml.getDouble(path + "yaw"),
                        (float) yaml.getDouble(path + "pitch"),
                        yaml.getString(path + "fallback.world", Bukkit.getWorlds().getFirst().getName()),
                        yaml.getDouble(path + "fallback.x"), yaml.getDouble(path + "fallback.y"),
                        yaml.getDouble(path + "fallback.z"));
                recoveries.put(playerId, recovery);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Skipping invalid spectator recovery " + key + ": "
                        + exception.getMessage());
            }
        }
    }

    private boolean save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, SpectatorRecovery> entry : recoveries.entrySet()) {
            String path = "players." + entry.getKey() + ".";
            SpectatorRecovery recovery = entry.getValue();
            yaml.set(path + "jar", recovery.jarId() == null ? null : recovery.jarId().toString());
            yaml.set(path + "carrier", recovery.carrierId() == null ? null : recovery.carrierId().toString());
            yaml.set(path + "game-mode", recovery.gameMode().name());
            yaml.set(path + "relative.x", recovery.relativeX());
            yaml.set(path + "relative.y", recovery.relativeY());
            yaml.set(path + "relative.z", recovery.relativeZ());
            yaml.set(path + "yaw", recovery.yaw());
            yaml.set(path + "pitch", recovery.pitch());
            yaml.set(path + "fallback.world", recovery.fallbackWorld());
            yaml.set(path + "fallback.x", recovery.fallbackX());
            yaml.set(path + "fallback.y", recovery.fallbackY());
            yaml.set(path + "fallback.z", recovery.fallbackZ());
        }
        try {
            yaml.save(file);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save spectators.yml: " + exception.getMessage());
            return false;
        }
    }
}
