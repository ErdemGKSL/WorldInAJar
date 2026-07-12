package tr.erdemdev.worldInAJar;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Matrix4f;

import java.util.*;
import java.util.function.Predicate;

/** Maintains viewer-specific, bidirectional display-only views for every placed jar. */
public final class PreviewService {
    private final JavaPlugin plugin;
    private final InteriorService interiors;
    private final NamespacedKey displayKey;
    private final Map<UUID, JarScene> scenes = new HashMap<>();
    private JarRepository repository;
    private int taskId = -1;
    private long ticks;

    public PreviewService(JavaPlugin plugin, InteriorService interiors) {
        this.plugin = plugin;
        this.interiors = interiors;
        this.displayKey = new NamespacedKey(plugin, "preview_jar");
    }

    public void start(JarRepository repository) {
        stop();
        this.repository = repository;
        removeOrphans();
        long period = Math.max(1L, plugin.getConfig().getLong("preview.update-ticks", 5L));
        taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, this::tick, 1L, period);
    }

    public void stop() {
        if (taskId != -1) plugin.getServer().getScheduler().cancelTask(taskId);
        taskId = -1;
        for (JarScene scene : scenes.values()) scene.removeAll();
        scenes.clear();
        ticks = 0;
    }

    public void invalidate(JarRecord jar) {
        JarScene scene = scenes.get(jar.id());
        if (scene != null) scene.invalid = true;
    }

    public void refresh(JarRecord jar) {
        if (!isPlaced(jar)) {
            remove(jar.id());
            return;
        }
        JarScene scene = scenes.computeIfAbsent(jar.id(), ignored -> new JarScene(jar));
        if (!scene.jar.equals(jar)) {
            scene.removeAll();
            scene = new JarScene(jar);
            scenes.put(jar.id(), scene);
        }
        refreshBlocks(scene, true, true);
    }

    public void remove(UUID id) {
        JarScene scene = scenes.remove(id);
        if (scene != null) scene.removeAll();
    }

    public void forget(Player player) {
        UUID id = player.getUniqueId();
        for (JarScene scene : scenes.values()) {
            scene.exteriorViewers.remove(id);
            scene.interiorViewers.remove(id);
            scene.removeAvatar(id, scene.occupants);
            scene.removeAvatar(id, scene.outsiders);
        }
    }

    private void tick() {
        ticks++;
        Set<UUID> valid = new HashSet<>();
        for (JarRecord jar : repository.all()) {
            valid.add(jar.id());
            if (!isPlaced(jar)) {
                remove(jar.id());
                continue;
            }
            JarScene scene = scenes.computeIfAbsent(jar.id(), ignored -> new JarScene(jar));
            if (!scene.jar.equals(jar)) {
                scene.removeAll();
                scene = new JarScene(jar);
                scenes.put(jar.id(), scene);
            }
            updateViewers(scene);
            int blockPeriod = Math.max(1, plugin.getConfig().getInt("preview.block-refresh-ticks", 100));
            if (scene.invalid || ticks % blockPeriod == 0) refreshBlocks(scene, false, false);
            updateOccupants(scene);
            updateOutsidePlayers(scene);
        }
        for (UUID id : new ArrayList<>(scenes.keySet())) if (!valid.contains(id)) remove(id);
        interiors.pruneSessions(repository);
    }

    private void updateViewers(JarScene scene) {
        double exteriorDistance = positive("preview.exterior.viewer-distance", 6.0);
        Location jarCenter = scene.jar.outsideLocation().clone().add(.5, .5, .5);
        Set<UUID> exterior = plugin.getConfig().getBoolean("preview.exterior.enabled", true)
                ? nearbyPlayers(jarCenter, exteriorDistance, player -> true) : Set.of();
        applyVisibility(scene.exteriorViewers, exterior, scene.exteriorEntities());

        Set<UUID> interior = new HashSet<>();
        if (plugin.getConfig().getBoolean("preview.interior.enabled", true)) {
            // The outside view surrounds the whole cell, so every occupant is a viewer.
            for (Player player : interiors.occupants(scene.jar)) interior.add(player.getUniqueId());
        }
        applyVisibility(scene.interiorViewers, interior, scene.interiorEntities());
    }

    private void applyVisibility(Set<UUID> previous, Set<UUID> current, Collection<? extends VirtualEntity> entities) {
        for (UUID id : previous) {
            if (current.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null) for (VirtualEntity entity : entities) entity.destroy(player);
        }
        for (UUID id : current) {
            if (previous.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player != null) for (VirtualEntity entity : entities) entity.spawn(player);
        }
        previous.clear();
        previous.addAll(current);
    }

    private void refreshBlocks(JarScene scene, boolean forceExterior, boolean forceInterior) {
        scene.invalid = false;

        int exteriorMaximum = plugin.getConfig().getBoolean("preview.exterior.enabled", true)
                ? bounded("preview.exterior.max-blocks", 2048, 0, 4096) : 0;
        List<InteriorBox> interior = sampleInterior(scene.jar, exteriorMaximum);
        int exteriorFingerprint = Objects.hash(interior);
        if (forceExterior || scene.exteriorFingerprint != exteriorFingerprint) {
            if (interior.size() >= exteriorMaximum && exteriorMaximum > 0 && scene.exteriorFingerprint != exteriorFingerprint)
                plugin.getLogger().warning("Jar " + scene.jar.id() + " has more visible blocks than preview.exterior.max-blocks ("
                        + exteriorMaximum + "); the miniature is truncated.");
            // Spawn replacements before removing the old displays so viewers never see an empty frame.
            List<VirtualBlockDisplay> stale = new ArrayList<>(scene.exteriorBlocks);
            scene.exteriorBlocks.clear();
            spawnExteriorBlocks(scene, interior);
            showTo(scene.exteriorViewers, scene.exteriorBlocks);
            removeEntities(stale, scene.exteriorViewers);
            scene.exteriorFingerprint = exteriorFingerprint;
        }

        int outsideMaximum = plugin.getConfig().getBoolean("preview.interior.enabled", true)
                ? bounded("preview.interior.max-blocks", 4096, 0, 16384) : 0;
        int radius = bounded("preview.interior.outside-radius", 6, 1, 24);
        List<OutsideSample> outside = sampleOutside(scene.jar, radius, outsideMaximum);
        BlockData floor = scene.jar.outsideLocation().clone().subtract(0, 1, 0).getBlock().getBlockData();
        int interiorFingerprint = Objects.hash(outside, floor.getAsString(), radius);
        if (forceInterior || scene.interiorFingerprint != interiorFingerprint) {
            List<VirtualBlockDisplay> stale = new ArrayList<>(scene.interiorBlocks);
            scene.interiorBlocks.clear();
            spawnInteriorFloor(scene, floor);
            spawnInteriorGlassSurfaces(scene);
            spawnInteriorBlocks(scene, outside);
            showTo(scene.interiorViewers, scene.interiorBlocks);
            removeEntities(stale, scene.interiorViewers);
            scene.interiorFingerprint = interiorFingerprint;
        }
    }

    /** Exact 1:1 translation of the interior: one display per visible block. Merging neighbours
     *  is not an option because scaled displays stretch their texture instead of tiling it. */
    private List<InteriorBox> sampleInterior(JarRecord jar, int maximum) {
        if (maximum == 0) return List.of();
        CellLayout.Cell cell = interiors.cell(jar);
        int scale = jar.scale();
        World world = interiors.world();
        List<InteriorBox> result = new ArrayList<>();
        for (int y = 0; y < scale - 1; y++) {
            for (int x = 1; x < scale - 1; x++) for (int z = 1; z < scale - 1; z++) {
                Material material = world.getBlockAt(cell.minX() + x, cell.minY() + y, cell.minZ() + z).getType();
                if (!renderable(material, false)
                        || !exposed(world, cell.minX() + x, cell.minY() + y, cell.minZ() + z)) continue;
                result.add(new InteriorBox(x, y, z, 1, 1, 1, material));
                if (result.size() >= maximum) return result;
            }
        }
        return result;
    }

    private List<OutsideSample> sampleOutside(JarRecord jar, int radius, int maximum) {
        if (maximum == 0) return List.of();
        Location origin = jar.outsideLocation();
        List<OutsideSample> result = new ArrayList<>(maximum);
        // The support block is rendered separately as one cell-wide floor. Sampling below the
        // jar would manufacture a coarse terrain shell around it instead of preserving that view.
        outer: for (int dy = 0; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                int x = origin.getBlockX() + dx, y = origin.getBlockY() + dy, z = origin.getBlockZ() + dz;
                BlockData blockData = origin.getWorld().getBlockAt(x, y, z).getBlockData();
                // Buried blocks would eat the whole budget before any surface is reached.
                if (renderable(blockData.getMaterial(), true) && exposed(origin.getWorld(), x, y, z)) {
                    result.add(new OutsideSample(dx, dy, dz, blockData));
                    if (result.size() >= maximum) break outer;
                }
            }
        }
        return result;
    }

    private static boolean exposed(World world, int x, int y, int z) {
        return !world.getBlockAt(x + 1, y, z).getType().isOccluding() || !world.getBlockAt(x - 1, y, z).getType().isOccluding()
                || !world.getBlockAt(x, y + 1, z).getType().isOccluding() || !world.getBlockAt(x, y - 1, z).getType().isOccluding()
                || !world.getBlockAt(x, y, z + 1).getType().isOccluding() || !world.getBlockAt(x, y, z - 1).getType().isOccluding();
    }

    private static boolean renderable(Material material, boolean includeGlass) {
        return material.isBlock() && !material.isAir() && (includeGlass || material != Material.GLASS)
                && material != Material.BARRIER && material != Material.STRUCTURE_VOID;
    }

    private void spawnExteriorBlocks(JarScene scene, List<InteriorBox> boxes) {
        Location origin = scene.jar.outsideLocation().clone().add(.5, 0, .5);
        float unit = 1f / scene.jar.scale();
        // Sub-pixel shrink so touching boxes (and the glass faces) never share a plane and z-fight.
        float epsilon = 0.0005f;
        for (InteriorBox box : boxes) {
            VirtualBlockDisplay display = new VirtualBlockDisplay(origin, box.material.createBlockData(), new Matrix4f()
                    .translation(-.5f + box.x * unit + epsilon, box.y * unit + epsilon, -.5f + box.z * unit + epsilon)
                    .scale(box.sx * unit - 2 * epsilon, box.sy * unit - 2 * epsilon, box.sz * unit - 2 * epsilon));
            scene.exteriorBlocks.add(display);
        }
    }

    private void spawnInteriorBlocks(JarScene scene, List<OutsideSample> samples) {
        // Inside the jar the player is 1/scale sized, so every outside block renders as a cell-sized
        // cube tiled around the cell exactly where the real block sits around the jar outside.
        // The entities all live at the cell center (kept loaded by the occupant) and are pushed
        // into place through the transformation, since chunks that far out are never loaded.
        CellLayout.Cell cell = interiors.cell(scene.jar);
        int scale = scene.jar.scale();
        Location center = new Location(interiors.world(), cell.minX() + scale / 2.0,
                cell.minY() + scale / 2.0, cell.minZ() + scale / 2.0);
        float half = scale / 2f;
        for (OutsideSample sample : samples) {
            VirtualBlockDisplay display = new VirtualBlockDisplay(center, sample.blockData, new Matrix4f()
                    .translation(sample.dx * (float) scale - half, sample.dy * (float) scale - half,
                            sample.dz * (float) scale - half)
                    .scale((float) scale));
            scene.interiorBlocks.add(display);
        }
    }

    private void spawnInteriorFloor(JarScene scene, BlockData blockData) {
        if (!renderable(blockData.getMaterial(), true)) return;
        CellLayout.Cell cell = interiors.cell(scene.jar);
        int scale = scene.jar.scale();
        float half = scale / 2f;
        Location center = new Location(interiors.world(), cell.minX() + half,
                cell.minY() + half, cell.minZ() + half);
        VirtualBlockDisplay display = new VirtualBlockDisplay(center, blockData,
        // Its top face is flush with the top of the barrier floor players stand on.
                new Matrix4f()
                .translation(-half, 1f - half - scale, -half)
                .scale(scale));
        scene.interiorBlocks.add(display);
    }

    private void spawnInteriorGlassSurfaces(JarScene scene) {
        CellLayout.Cell cell = interiors.cell(scene.jar);
        int scale = scene.jar.scale();
        float half = scale / 2f;
        Location center = new Location(interiors.world(), cell.minX() + half,
                cell.minY() + half, cell.minZ() + half);

        spawnGlassSurface(scene, center, new Matrix4f().translation(-half, -half, -half).scale(1, scale, scale));
        spawnGlassSurface(scene, center, new Matrix4f().translation(half - 1, -half, -half).scale(1, scale, scale));
        spawnGlassSurface(scene, center, new Matrix4f().translation(-half, -half, -half).scale(scale, scale, 1));
        spawnGlassSurface(scene, center, new Matrix4f().translation(-half, -half, half - 1).scale(scale, scale, 1));
        spawnGlassSurface(scene, center, new Matrix4f().translation(-half, half - 1, -half).scale(scale, 1, scale));
    }

    private void spawnGlassSurface(JarScene scene, Location center, Matrix4f transformation) {
        scene.interiorBlocks.add(new VirtualBlockDisplay(center, Material.GLASS.createBlockData(), transformation));
    }

    private void updateOccupants(JarScene scene) {
        Set<UUID> present = new HashSet<>();
        CellLayout.Cell cell = interiors.cell(scene.jar);
        int maximum = bounded("preview.exterior.max-player-markers", 16, 0, 100);
        for (Player target : interiors.occupants(scene.jar)) {
            if (present.size() >= maximum || !plugin.getConfig().getBoolean("preview.exterior.enabled", true)) break;
            present.add(target.getUniqueId());
            double x = (target.getX() - cell.minX()) / scene.jar.scale() - .5;
            double y = (target.getY() - cell.minY()) / scene.jar.scale();
            double z = (target.getZ() - cell.minZ()) / scene.jar.scale() - .5;
            Location mapped = scene.jar.outsideLocation().clone().add(.5 + x, y, .5 + z);
            updateAvatar(scene, scene.occupants, target, mapped, 1f / scene.jar.scale(), scene.exteriorViewers);
        }
        removeAbsent(scene, scene.occupants, present);
    }

    private void updateOutsidePlayers(JarScene scene) {
        if (!plugin.getConfig().getBoolean("preview.interior.show-players", true)) {
            removeAbsent(scene, scene.outsiders, Set.of());
            return;
        }
        int radius = bounded("preview.interior.outside-radius", 6, 1, 24);
        Location jar = scene.jar.outsideLocation().clone().add(.5, .5, .5);
        Set<UUID> present = new HashSet<>();
        int maximum = bounded("preview.interior.max-player-markers", 16, 0, 100);
        for (Player target : jar.getWorld().getNearbyPlayers(jar, radius)) {
            if (present.size() >= maximum) break;
            if (target.getLocation().distanceSquared(jar) > radius * radius) continue;
            present.add(target.getUniqueId());
            Location mapped = mapOutsideLocation(scene.jar, target.getLocation());
            // Skip markers that map into unloaded chunks; the display would be discarded instantly.
            if (!mapped.getWorld().isChunkLoaded(mapped.getBlockX() >> 4, mapped.getBlockZ() >> 4)) {
                present.remove(target.getUniqueId());
                continue;
            }
            // An outside player is scale times larger than an occupant.
            updateAvatar(scene, scene.outsiders, target, mapped, scene.jar.scale(), scene.interiorViewers);
        }
        removeAbsent(scene, scene.outsiders, present);
    }

    private Location mapOutsideLocation(JarRecord jar, Location outside) {
        Location block = jar.outsideLocation();
        int scale = jar.scale();
        CellLayout.Cell cell = interiors.cell(jar);
        double dx = outside.getX() - block.getX(), dy = outside.getY() - block.getY(), dz = outside.getZ() - block.getZ();
        // Feet position of the outside player, scaled up; the mannequin body extends from there.
        return new Location(interiors.world(), cell.minX() + dx * scale,
                cell.minY() + dy * scale, cell.minZ() + dz * scale);
    }

    /** Mirrors a real player as a scaled mannequin: full model, skin, name, pose and equipment.
     *  The scale attribute is clamped by the game to [0.0625, 16] on any entity, real or fake. */
    private void updateAvatar(JarScene scene, Map<UUID, Avatar> avatars, Player target, Location location,
                              float scale, Set<UUID> viewers) {
        Avatar avatar = avatars.get(target.getUniqueId());
        if (avatar == null) {
            VirtualMannequin body = new VirtualMannequin(target, location, scale);
            avatar = new Avatar(body);
            avatars.put(target.getUniqueId(), avatar);
            showTo(viewers, List.of(body));
        }
        location.setYaw(target.getLocation().getYaw());
        location.setPitch(target.getLocation().getPitch());
        avatar.body.update(target, location, online(viewers));
    }

    private Set<UUID> nearbyPlayers(Location center, double radius, Predicate<Player> filter) {
        Set<UUID> result = new HashSet<>();
        for (Player player : center.getWorld().getNearbyPlayers(center, radius)) {
            if (player.getLocation().distanceSquared(center) <= radius * radius && filter.test(player)) result.add(player.getUniqueId());
        }
        return result;
    }

    private void showTo(Set<UUID> viewers, Collection<? extends VirtualEntity> entities) {
        for (UUID id : viewers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) for (VirtualEntity entity : entities) entity.spawn(player);
        }
    }

    private Collection<Player> online(Set<UUID> viewers) {
        List<Player> players = new ArrayList<>(viewers.size());
        for (UUID id : viewers) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) players.add(player);
        }
        return players;
    }

    private void removeAbsent(JarScene scene, Map<UUID, Avatar> avatars, Set<UUID> present) {
        for (UUID id : new ArrayList<>(avatars.keySet())) if (!present.contains(id)) scene.removeAvatar(id, avatars);
    }

    private void removeEntities(Collection<? extends VirtualEntity> entities, Set<UUID> viewers) {
        for (Player viewer : online(viewers)) for (VirtualEntity entity : entities) entity.destroy(viewer);
    }

    private boolean isPlaced(JarRecord jar) {
        Location outside = jar.outsideLocation();
        return outside != null && outside.getBlock().getType() == Material.GLASS;
    }

    private int bounded(String path, int fallback, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, plugin.getConfig().getInt(path, fallback)));
    }

    private double positive(String path, double fallback) {
        return Math.max(.5, plugin.getConfig().getDouble(path, fallback));
    }

    private void removeOrphans() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(displayKey, PersistentDataType.BYTE)) entity.remove();
            }
        }
    }

    private record InteriorBox(int x, int y, int z, int sx, int sy, int sz, Material material) {}
    private record OutsideSample(int dx, int dy, int dz, BlockData blockData) {}
    private record Avatar(VirtualMannequin body) {}

    private final class JarScene {
        private final JarRecord jar;
        private final List<VirtualBlockDisplay> exteriorBlocks = new ArrayList<>();
        private final List<VirtualBlockDisplay> interiorBlocks = new ArrayList<>();
        private final Map<UUID, Avatar> occupants = new HashMap<>();
        private final Map<UUID, Avatar> outsiders = new HashMap<>();
        private final Set<UUID> exteriorViewers = new HashSet<>();
        private final Set<UUID> interiorViewers = new HashSet<>();
        private int exteriorFingerprint = Integer.MIN_VALUE;
        private int interiorFingerprint = Integer.MIN_VALUE;
        private boolean invalid = true;

        private JarScene(JarRecord jar) { this.jar = jar; }

        private List<VirtualEntity> exteriorEntities() {
            List<VirtualEntity> entities = new ArrayList<>(exteriorBlocks);
            for (Avatar avatar : occupants.values()) entities.add(avatar.body);
            return entities;
        }

        private List<VirtualEntity> interiorEntities() {
            List<VirtualEntity> entities = new ArrayList<>(interiorBlocks);
            for (Avatar avatar : outsiders.values()) entities.add(avatar.body);
            return entities;
        }

        private void removeAvatar(UUID id, Map<UUID, Avatar> avatars) {
            Avatar avatar = avatars.remove(id);
            if (avatar != null) for (Player viewer : online(avatars == occupants ? exteriorViewers : interiorViewers)) avatar.body.destroy(viewer);
        }

        private void removeAll() {
            removeEntities(exteriorBlocks, exteriorViewers);
            removeEntities(interiorBlocks, interiorViewers);
            for (Player viewer : online(exteriorViewers)) for (Avatar avatar : occupants.values()) avatar.body.destroy(viewer);
            for (Player viewer : online(interiorViewers)) for (Avatar avatar : outsiders.values()) avatar.body.destroy(viewer);
            exteriorBlocks.clear(); interiorBlocks.clear(); occupants.clear(); outsiders.clear();
            exteriorViewers.clear(); interiorViewers.clear();
        }
    }
}
