package tr.erdemdev.worldInAJar;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Matrix4f;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** Maintains viewer-specific, bidirectional display-only views for every placed jar. */
public final class PreviewService {
    private static final float FRONT_PORTAL_INWARD = .2f;
    private static final float BACK_PORTAL_INWARD = -.1f;
    private static final float SEALED_BACKING_OUTWARD = .55f;
    private static final double PORTAL_SIDE_SWITCH_OFFSET = .1;
    private static final int EXTERIOR_BLOCKS = 1;
    private static final int INTERIOR_BLOCKS = 2;
    private final JavaPlugin plugin;
    private final InteriorService interiors;
    private final NamespacedKey displayKey;
    private final Map<UUID, JarScene> scenes = new HashMap<>();
    private final Map<Integer, List<OutsideOffset>> outsideOffsets = new HashMap<>();
    private final Set<BlockRequest> pendingBlocks = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> routedBlocks = new ConcurrentHashMap<>();
    private final Set<UUID> routedPlayers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean routeScheduled = new AtomicBoolean();
    private final AtomicBoolean applyScheduled = new AtomicBoolean();
    private volatile List<JarRoute> routes = List.of();
    private volatile boolean running;
    private ExecutorService router;
    private EntityPreviewBackend entityBackend;
    private JarRepository repository;
    private int taskId = -1;
    private int blockRefreshTaskId = -1;
    private long updatePeriodTicks = 1L;
    private long blockRefreshElapsedTicks;

    public PreviewService(JavaPlugin plugin, InteriorService interiors) {
        this.plugin = plugin;
        this.interiors = interiors;
        this.displayKey = new NamespacedKey(plugin, "preview_jar");
    }

    public void start(JarRepository repository) {
        stop();
        this.repository = repository;
        configureEntityBackend();
        running = true;
        router = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WorldInAJar-preview-router");
            thread.setDaemon(true);
            return thread;
        });
        rebuildRoutes();
        removeOrphans();
        updatePeriodTicks = Math.max(1L, plugin.getConfig().getLong("preview.update-ticks", 5L));
        taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin, this::tick, 1L, updatePeriodTicks);
    }

    public void stop() {
        running = false;
        if (entityBackend != null) entityBackend.stop();
        entityBackend = null;
        if (taskId != -1) plugin.getServer().getScheduler().cancelTask(taskId);
        taskId = -1;
        if (blockRefreshTaskId != -1) plugin.getServer().getScheduler().cancelTask(blockRefreshTaskId);
        blockRefreshTaskId = -1;
        if (router != null) router.shutdownNow();
        router = null;
        pendingBlocks.clear(); pendingPlayers.clear(); routedBlocks.clear(); routedPlayers.clear();
        routeScheduled.set(false); applyScheduled.set(false); routes = List.of();
        for (JarScene scene : scenes.values()) scene.removeAll();
        scenes.clear();
        updatePeriodTicks = 1L;
        blockRefreshElapsedTicks = 0L;
    }

    public void invalidate(JarRecord jar) {
        JarScene scene = scenes.get(jar.id());
        if (scene != null) {
            scene.exteriorInvalid = true;
            scene.interiorInvalid = true;
        }
    }

    /** Publishes a block change without retaining any Bukkit object past the event callback. */
    public void transportBlock(Location location) {
        if (!running || location.getWorld() == null) return;
        pendingBlocks.add(new BlockRequest(location.getWorld().getUID(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ()));
        scheduleRoute();
    }

    /** Coalesces high-frequency movement/state events before touching preview scenes. */
    public void transportPlayer(Player player) {
        if (!running) return;
        pendingPlayers.add(player.getUniqueId());
        scheduleRoute();
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
        rebuildRoutes();
    }

    public void remove(UUID id) {
        if (entityBackend != null) entityBackend.remove(id);
        JarScene scene = scenes.remove(id);
        if (scene != null) scene.removeAll();
        if (repository != null) rebuildRoutes();
    }

    public void seal(JarRecord jar) {
        remove(jar.id());
        if (interiors.occupants(jar).isEmpty()) return;
        JarScene scene = new JarScene(jar);
        scenes.put(jar.id(), scene);
        spawnSealedSurfaces(scene);
        updateSealedViewers(scene);
        rebuildRoutes();
    }

    public void forget(Player player) {
        UUID id = player.getUniqueId();
        if (entityBackend != null) entityBackend.forget(id);
        for (JarScene scene : scenes.values()) {
            scene.exteriorViewers.remove(id);
            scene.insetPortalViewers.remove(id);
            scene.interiorViewers.remove(id);
            scene.removeAvatar(id, scene.occupants);
            scene.removeAvatar(id, scene.outsiders);
        }
    }

    public void preparePlayerTransition(Player player) {
        if (entityBackend != null) entityBackend.removeSource(player.getUniqueId());
    }

    private void tick() {
        interiors.pruneSessions(repository);
        rebuildRoutes();
        long blockPeriod = Math.max(1L, plugin.getConfig().getLong("preview.block-refresh-ticks", 100L));
        blockRefreshElapsedTicks += updatePeriodTicks;
        boolean reconcileBlocks = blockRefreshElapsedTicks >= blockPeriod;
        if (reconcileBlocks) blockRefreshElapsedTicks %= blockPeriod;
        Set<UUID> valid = new HashSet<>();
        for (JarRecord jar : repository.all()) {
            valid.add(jar.id());
            if (!jar.placed()) {
                if (entityBackend != null) entityBackend.remove(jar.id());
                if (interiors.occupants(jar).isEmpty()) {
                    remove(jar.id());
                    continue;
                }
                JarScene scene = scenes.computeIfAbsent(jar.id(), ignored -> new JarScene(jar));
                if (!scene.jar.equals(jar)) {
                    scene.removeAll();
                    scene = new JarScene(jar);
                    scenes.put(jar.id(), scene);
                }
                if (!scene.sealed) spawnSealedSurfaces(scene);
                updateSealedViewers(scene);
                continue;
            }
            if (!isPlaced(jar)) {
                remove(jar.id());
                continue;
            }
            JarScene scene = scenes.get(jar.id());
            boolean newScene = scene == null;
            if (newScene) {
                scene = new JarScene(jar);
                scenes.put(jar.id(), scene);
            }
            if (!scene.jar.equals(jar)) {
                scene.removeAll();
                scene = new JarScene(jar);
                scenes.put(jar.id(), scene);
                newScene = true;
            }
            updateViewers(scene);
            if (newScene || reconcileBlocks) refreshBlocks(scene, true, true);
            updateOccupants(scene);
            updateOutsidePlayers(scene);
            if (entityBackend != null) entityBackend.update(jar, scene.exteriorViewers, scene.interiorViewers);
        }
        for (UUID id : new ArrayList<>(scenes.keySet())) if (!valid.contains(id)) remove(id);
    }

    private void updateViewers(JarScene scene) {
        double exteriorDistance = positive("preview.exterior.viewer-distance", 6.0);
        Location jarCenter = scene.jar.outsideLocation().clone().add(.5, .5, .5);
        Set<UUID> exterior = plugin.getConfig().getBoolean("preview.exterior.enabled", true)
                ? nearbyPlayers(jarCenter, exteriorDistance, player -> true) : Set.of();
        applyExteriorVisibility(scene, exterior);
        updateExteriorPortalPositions(scene);

        Set<UUID> interior = new HashSet<>();
        if (plugin.getConfig().getBoolean("preview.interior.enabled", true)) {
            // The outside view surrounds the whole cell, so every occupant is a viewer.
            for (Player player : interiors.occupants(scene.jar)) interior.add(player.getUniqueId());
        }
        applyVisibility(scene.interiorViewers, interior, scene.interiorEntities());
    }

    private void updateSealedViewers(JarScene scene) {
        applyExteriorVisibility(scene, Set.of());
        Set<UUID> interior = new HashSet<>();
        for (Player player : interiors.occupants(scene.jar)) interior.add(player.getUniqueId());
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

    private void applyExteriorVisibility(JarScene scene, Set<UUID> current) {
        for (UUID id : scene.exteriorViewers) {
            if (current.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            for (VirtualEntity entity : scene.exteriorEntities()) entity.destroy(player);
            if (scene.exteriorPortal != null) scene.exteriorPortal.destroy(player);
            scene.insetPortalViewers.remove(id);
        }
        for (UUID id : current) {
            if (scene.exteriorViewers.contains(id)) continue;
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            for (VirtualEntity entity : scene.exteriorEntities()) entity.spawn(player);
            scene.spawnPortal(player);
        }
        scene.exteriorViewers.clear();
        scene.exteriorViewers.addAll(current);
    }

    private void updateExteriorPortalPositions(JarScene scene) {
        if (scene.exteriorPortal == null) return;
        for (UUID id : scene.exteriorViewers) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) continue;
            boolean inset = onDoorSide(scene.jar, player.getLocation());
            if (inset == scene.insetPortalViewers.contains(id)) continue;
            scene.exteriorPortal.transform(player, portalTransformation(scene.jar.door(),
                    inset ? FRONT_PORTAL_INWARD : BACK_PORTAL_INWARD));
            if (inset) scene.insetPortalViewers.add(id); else scene.insetPortalViewers.remove(id);
        }
    }

    private void refreshBlocks(JarScene scene, boolean forceExterior, boolean forceInterior) {
        if (forceExterior) {
            int exteriorMaximum = plugin.getConfig().getBoolean("preview.exterior.enabled", true)
                    ? bounded("preview.exterior.max-blocks", 2048, 0, 4096) : 0;
            List<InteriorBox> interior = sampleInterior(scene.jar, exteriorMaximum);
            int exteriorFingerprint = blockFingerprint(interior);
            if (scene.exteriorFingerprint != exteriorFingerprint) {
            if (interior.size() >= exteriorMaximum && exteriorMaximum > 0 && scene.exteriorFingerprint != exteriorFingerprint)
                plugin.getLogger().warning("Jar " + scene.jar.id() + " has more visible blocks than preview.exterior.max-blocks ("
                        + exteriorMaximum + "); the miniature is truncated.");
            // Spawn replacements before removing the old displays so viewers never see an empty frame.
            List<VirtualBlockDisplay> stale = new ArrayList<>(scene.exteriorBlocks);
            VirtualBlockDisplay stalePortal = scene.exteriorPortal;
            scene.exteriorBlocks.clear();
            scene.exteriorPortal = null;
            spawnExteriorBlocks(scene, interior);
            showTo(scene.exteriorViewers, scene.exteriorBlocks);
            for (Player viewer : online(scene.exteriorViewers)) scene.spawnPortal(viewer);
            removeEntities(stale, scene.exteriorViewers);
            if (stalePortal != null) for (Player viewer : online(scene.exteriorViewers)) stalePortal.destroy(viewer);
            scene.exteriorFingerprint = exteriorFingerprint;
            }
            scene.exteriorInvalid = false;
        }

        if (forceInterior) {
            int outsideMaximum = plugin.getConfig().getBoolean("preview.interior.enabled", true)
                    ? bounded("preview.interior.max-blocks", 4096, 0, 16384) : 0;
            int radius = bounded("preview.interior.outside-radius", 6, 1, 24);
            List<OutsideSample> outside = sampleOutside(scene.jar, radius, outsideMaximum);
            BlockData floor = scene.jar.outsideLocation().clone().subtract(0, 1, 0).getBlock().getBlockData();
            int interiorFingerprint = blockFingerprint(outside, floor, radius);
            if (scene.interiorFingerprint != interiorFingerprint) {
            List<VirtualBlockDisplay> stale = new ArrayList<>(scene.interiorBlocks);
            scene.interiorBlocks.clear();
            spawnInteriorFloor(scene, floor);
            spawnInteriorGlassSurfaces(scene);
            spawnInteriorBlocks(scene, outside);
            showTo(scene.interiorViewers, scene.interiorBlocks);
            removeEntities(stale, scene.interiorViewers);
            scene.interiorFingerprint = interiorFingerprint;
            }
            scene.interiorInvalid = false;
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
                BlockData blockData = world.getBlockAt(cell.minX() + x, cell.minY() + y,
                        cell.minZ() + z).getBlockData();
                if (!renderable(blockData.getMaterial(), false)
                        || !exposed(world, cell.minX() + x, cell.minY() + y, cell.minZ() + z)) continue;
                result.add(new InteriorBox(x, y, z, 1, 1, 1, blockData));
                if (result.size() >= maximum) return result;
            }
        }
        return result;
    }

    private List<OutsideSample> sampleOutside(JarRecord jar, int radius, int maximum) {
        if (maximum == 0) return List.of();
        Location origin = jar.outsideLocation();
        List<OutsideSample> result = new ArrayList<>(maximum);
        World world = origin.getWorld();
        // The support block directly below the jar is rendered separately as the cell-wide floor.
        for (OutsideOffset offset : outsideOffsets(radius)) {
            if (offset.dx == 0 && offset.dz == 0 && offset.dy == -1) continue;
            int x = origin.getBlockX() + offset.dx;
            int y = origin.getBlockY() + offset.dy;
            int z = origin.getBlockZ() + offset.dz;
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
            BlockData blockData = world.getBlockAt(x, y, z).getBlockData();
            // Buried blocks would eat the whole budget before any surface is reached.
            if (renderable(blockData.getMaterial(), true) && exposed(world, x, y, z)) {
                result.add(new OutsideSample(offset.dx, offset.dy, offset.dz, blockData));
                if (result.size() >= maximum) break;
            }
        }
        return result;
    }

    private List<OutsideOffset> outsideOffsets(int radius) {
        return outsideOffsets.computeIfAbsent(radius, value -> {
            List<OutsideOffset> offsets = new ArrayList<>();
            int radiusSquared = value * value;
            for (int dy = -value; dy <= value; dy++) {
                for (int dx = -value; dx <= value; dx++) for (int dz = -value; dz <= value; dz++) {
                    int distanceSquared = dx * dx + dy * dy + dz * dz;
                    if (distanceSquared == 0 || distanceSquared > radiusSquared) continue;
                    offsets.add(new OutsideOffset(dx, dy, dz, distanceSquared));
                }
            }
            offsets.sort(Comparator.comparingInt(OutsideOffset::distanceSquared));
            return List.copyOf(offsets);
        });
    }

    private static int blockFingerprint(List<InteriorBox> blocks) {
        int fingerprint = 1;
        for (InteriorBox block : blocks) {
            fingerprint = 31 * fingerprint + Objects.hash(
                    block.x, block.y, block.z, block.blockData.getAsString());
        }
        return fingerprint;
    }

    private static int blockFingerprint(List<OutsideSample> blocks, BlockData floor, int radius) {
        int fingerprint = Objects.hash(floor.getAsString(), radius);
        for (OutsideSample block : blocks) {
            fingerprint = 31 * fingerprint + Objects.hash(
                    block.dx, block.dy, block.dz, block.blockData.getAsString());
        }
        return fingerprint;
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
            VirtualBlockDisplay display = new VirtualBlockDisplay(origin, box.blockData, new Matrix4f()
                    .translation(-.5f + box.x * unit + epsilon, box.y * unit + epsilon, -.5f + box.z * unit + epsilon)
                    .scale(box.sx * unit - 2 * epsilon, box.sy * unit - 2 * epsilon, box.sz * unit - 2 * epsilon));
            scene.exteriorBlocks.add(display);
        }
        spawnExteriorPortal(scene);
    }

    private void spawnExteriorPortal(JarScene scene) {
        org.bukkit.block.BlockFace door = scene.jar.door();
        scene.exteriorPortal = new VirtualBlockDisplay(
                scene.jar.outsideLocation(), portalData(door), portalTransformation(door, 0f));
    }

    private static Matrix4f portalTransformation(org.bukkit.block.BlockFace door, float inward) {
        Matrix4f transformation = new Matrix4f();
        float offset = 0.501f - inward;
        if (door == org.bukkit.block.BlockFace.NORTH) transformation.translate(0, 0, -offset);
        else if (door == org.bukkit.block.BlockFace.SOUTH) transformation.translate(0, 0, offset);
        else if (door == org.bukkit.block.BlockFace.WEST) transformation.translate(-offset, 0, 0);
        else if (door == org.bukkit.block.BlockFace.EAST) transformation.translate(offset, 0, 0);
        return transformation;
    }

    private static boolean onDoorSide(JarRecord jar, Location viewer) {
        Location center = jar.outsideLocation().clone().add(.5, .5, .5);
        double dx = viewer.getX() - center.getX(), dz = viewer.getZ() - center.getZ();
        return dx * jar.door().getModX() + dz * jar.door().getModZ() > PORTAL_SIDE_SWITCH_OFFSET;
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

        BlockData glass = Material.GLASS.createBlockData();
        BlockData portal = portalData(scene.jar.door());
        spawnSurface(scene, center, scene.jar.door() == org.bukkit.block.BlockFace.WEST ? portal : glass,
                new Matrix4f().translation(-half, -half, -half).scale(1, scale, scale));
        spawnSurface(scene, center, scene.jar.door() == org.bukkit.block.BlockFace.EAST ? portal : glass,
                new Matrix4f().translation(half - 1, -half, -half).scale(1, scale, scale));
        spawnSurface(scene, center, scene.jar.door() == org.bukkit.block.BlockFace.NORTH ? portal : glass,
                new Matrix4f().translation(-half, -half, -half).scale(scale, scale, 1));
        spawnSurface(scene, center, scene.jar.door() == org.bukkit.block.BlockFace.SOUTH ? portal : glass,
                new Matrix4f().translation(-half, -half, half - 1).scale(scale, scale, 1));
        spawnSurface(scene, center, glass,
                new Matrix4f().translation(-half, -half + .025f, -half).scale(scale, 1, scale));
        spawnSurface(scene, center, glass,
                new Matrix4f().translation(-half, half - 1, -half).scale(scale, 1, scale));
    }

    private void spawnSealedSurfaces(JarScene scene) {
        List<VirtualBlockDisplay> stale = new ArrayList<>(scene.interiorBlocks);
        scene.interiorBlocks.clear();
        CellLayout.Cell cell = interiors.cell(scene.jar);
        int scale = scene.jar.scale();
        float half = scale / 2f;
        Location center = new Location(interiors.world(), cell.minX() + half,
                cell.minY() + half, cell.minZ() + half);
        BlockData black = Material.BLACK_CONCRETE.createBlockData();

        for (org.bukkit.block.BlockFace face : List.of(org.bukkit.block.BlockFace.WEST,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.NORTH,
                org.bukkit.block.BlockFace.SOUTH)) {
            spawnSurface(scene, center, black, interiorWallTransformation(face, half, scale,
                    face == scene.jar.door() ? SEALED_BACKING_OUTWARD : 0f));
        }
        spawnSurface(scene, center, black, new Matrix4f().translation(-half, -half, -half).scale(scale, 1, scale));
        spawnSurface(scene, center, black, new Matrix4f().translation(-half, half - 1, -half).scale(scale, 1, scale));
        spawnSurface(scene, center, portalData(scene.jar.door()),
                interiorWallTransformation(scene.jar.door(), half, scale, 0f));

        showTo(scene.interiorViewers, scene.interiorBlocks);
        removeEntities(stale, scene.interiorViewers);
        scene.sealed = true;
        scene.exteriorInvalid = false;
        scene.interiorInvalid = false;
    }

    private static Matrix4f interiorWallTransformation(org.bukkit.block.BlockFace face,
                                                        float half, int scale, float outward) {
        float x = -half;
        float z = -half;
        if (face == org.bukkit.block.BlockFace.EAST) x = half - 1;
        if (face == org.bukkit.block.BlockFace.SOUTH) z = half - 1;
        x += face.getModX() * outward;
        z += face.getModZ() * outward;
        Matrix4f transformation = new Matrix4f().translation(x, -half, z);
        return face == org.bukkit.block.BlockFace.NORTH || face == org.bukkit.block.BlockFace.SOUTH
                ? transformation.scale(scale, scale, 1) : transformation.scale(1, scale, scale);
    }

    private void spawnSurface(JarScene scene, Location center, BlockData blockData, Matrix4f transformation) {
        scene.interiorBlocks.add(new VirtualBlockDisplay(center, blockData, transformation));
    }

    private static BlockData portalData(org.bukkit.block.BlockFace door) {
        Orientable portal = (Orientable) Material.NETHER_PORTAL.createBlockData();
        portal.setAxis(door == org.bukkit.block.BlockFace.NORTH || door == org.bukkit.block.BlockFace.SOUTH
                ? Axis.X : Axis.Z);
        return portal;
    }

    private void updateOccupants(JarScene scene) {
        if (entityBackend != null) {
            removeAbsent(scene, scene.occupants, Set.of());
            return;
        }
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
        if (entityBackend != null) {
            removeAbsent(scene, scene.outsiders, Set.of());
            return;
        }
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
                cell.minY() + 1 + dy * scale, cell.minZ() + dz * scale);
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
        return jar.placed() && outside != null && outside.getBlock().getType() == Material.GLASS;
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

    private void configureEntityBackend() {
        String mode = plugin.getConfig().getString("entity-preview",
                plugin.getConfig().getString("preview.entity-preview", "manequeen"));
        if (mode == null) return;
        mode = mode.trim();
        if (mode.equalsIgnoreCase("manequeen") || mode.equalsIgnoreCase("mannequin")) return;
        if (!mode.equalsIgnoreCase("protocol")) {
            plugin.getLogger().warning("Unknown entity-preview mode '" + mode + "'; using manequeens.");
            return;
        }
        org.bukkit.plugin.Plugin protocolLib = plugin.getServer().getPluginManager().getPlugin("ProtocolLib");
        if (protocolLib == null || !protocolLib.isEnabled()) {
            plugin.getLogger().warning("entity-preview is protocol, but ProtocolLib is not enabled; using mannequins.");
            return;
        }
        try {
            entityBackend = new ProtocolEntityPreview(plugin, interiors);
            plugin.getLogger().info("Using ProtocolLib entity previews.");
        } catch (LinkageError | RuntimeException exception) {
            entityBackend = null;
            plugin.getLogger().warning("Could not start ProtocolLib entity previews; using mannequins: " + exception.getMessage());
        }
    }

    private void rebuildRoutes() {
        if (repository == null) {
            routes = List.of();
            return;
        }
        UUID interiorWorld = interiors.world().getUID();
        int radius = bounded("preview.interior.outside-radius", 6, 1, 24);
        List<JarRoute> snapshots = new ArrayList<>();
        for (JarRecord jar : repository.all()) {
            Location outside = jar.outsideLocation();
            if (!jar.placed() || outside == null) continue;
            CellLayout.Cell cell = interiors.cell(jar);
            snapshots.add(new JarRoute(jar.id(), outside.getWorld().getUID(), outside.getBlockX(),
                    outside.getBlockY(), outside.getBlockZ(), radius, interiorWorld,
                    cell.minX(), cell.minY(), cell.minZ(), jar.scale()));
        }
        routes = List.copyOf(snapshots);
    }

    private void scheduleRoute() {
        ExecutorService executor = router;
        if (!running || executor == null || !routeScheduled.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                List<JarRoute> snapshot = routes;
                for (BlockRequest block : new ArrayList<>(pendingBlocks)) {
                    if (!pendingBlocks.remove(block)) continue;
                    for (JarRoute route : snapshot) {
                        int mask = route.route(block);
                        if (mask != 0) routedBlocks.merge(route.jarId, mask, (left, right) -> left | right);
                    }
                }
                for (UUID player : new ArrayList<>(pendingPlayers)) {
                    if (pendingPlayers.remove(player)) routedPlayers.add(player);
                }
            } finally {
                routeScheduled.set(false);
                scheduleApply();
                if (!pendingBlocks.isEmpty() || !pendingPlayers.isEmpty()) scheduleRoute();
            }
        });
    }

    private void scheduleApply() {
        if (!running || !applyScheduled.compareAndSet(false, true)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            boolean blocksChanged = false;
            try {
                for (Map.Entry<UUID, Integer> entry : new HashMap<>(routedBlocks).entrySet()) {
                    if (!routedBlocks.remove(entry.getKey(), entry.getValue())) continue;
                    JarScene scene = scenes.get(entry.getKey());
                    if (scene == null) continue;
                    if ((entry.getValue() & EXTERIOR_BLOCKS) != 0) scene.exteriorInvalid = true;
                    if ((entry.getValue() & INTERIOR_BLOCKS) != 0) scene.interiorInvalid = true;
                    blocksChanged = true;
                }
                for (UUID playerId : new HashSet<>(routedPlayers)) {
                    if (!routedPlayers.remove(playerId)) continue;
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null) interiors.syncSession(player, player.getLocation(), repository);
                }
            } finally {
                applyScheduled.set(false);
                if (blocksChanged) scheduleBlockRefresh();
                if (!routedBlocks.isEmpty() || !routedPlayers.isEmpty()) scheduleApply();
            }
        });
    }

    private void scheduleBlockRefresh() {
        if (!running || blockRefreshTaskId != -1) return;
        long configuredTicks = Math.max(1L, plugin.getConfig().getLong("preview.block-update-ticks", 1L));
        if (configuredTicks == 1L) {
            refreshInvalidBlocks();
            return;
        }
        blockRefreshTaskId = plugin.getServer().getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            blockRefreshTaskId = -1;
            refreshInvalidBlocks();
        }, configuredTicks - 1L);
    }

    private void refreshInvalidBlocks() {
        if (!running) return;
        for (JarScene scene : scenes.values()) {
            if (scene.sealed || (!scene.exteriorInvalid && !scene.interiorInvalid)) continue;
            refreshBlocks(scene, scene.exteriorInvalid, scene.interiorInvalid);
        }
    }

    private record InteriorBox(int x, int y, int z, int sx, int sy, int sz, BlockData blockData) {}
    private record OutsideSample(int dx, int dy, int dz, BlockData blockData) {}
    private record OutsideOffset(int dx, int dy, int dz, int distanceSquared) {}
    private record BlockRequest(UUID world, int x, int y, int z) {}
    private record JarRoute(UUID jarId, UUID outsideWorld, int outsideX, int outsideY, int outsideZ,
                            int outsideRadius, UUID interiorWorld, int minX, int minY, int minZ, int scale) {
        private int route(BlockRequest block) {
            int mask = 0;
            if (block.world.equals(interiorWorld)
                    && block.x >= minX && block.x < minX + scale
                    && block.y >= minY && block.y < minY + scale
                    && block.z >= minZ && block.z < minZ + scale) mask |= EXTERIOR_BLOCKS;
            if (block.world.equals(outsideWorld)
                    && Math.abs(block.x - outsideX) <= outsideRadius
                    && Math.abs(block.y - outsideY) <= outsideRadius
                    && Math.abs(block.z - outsideZ) <= outsideRadius) mask |= INTERIOR_BLOCKS;
            return mask;
        }
    }
    private record Avatar(VirtualMannequin body) {}

    private final class JarScene {
        private final JarRecord jar;
        private final List<VirtualBlockDisplay> exteriorBlocks = new ArrayList<>();
        private VirtualBlockDisplay exteriorPortal;
        private final List<VirtualBlockDisplay> interiorBlocks = new ArrayList<>();
        private final Map<UUID, Avatar> occupants = new HashMap<>();
        private final Map<UUID, Avatar> outsiders = new HashMap<>();
        private final Set<UUID> exteriorViewers = new HashSet<>();
        private final Set<UUID> interiorViewers = new HashSet<>();
        private final Set<UUID> insetPortalViewers = new HashSet<>();
        private int exteriorFingerprint = Integer.MIN_VALUE;
        private int interiorFingerprint = Integer.MIN_VALUE;
        private volatile boolean exteriorInvalid = true;
        private volatile boolean interiorInvalid = true;
        private boolean sealed;

        private JarScene(JarRecord jar) { this.jar = jar; }

        private List<VirtualEntity> exteriorEntities() {
            List<VirtualEntity> entities = new ArrayList<>(exteriorBlocks);
            for (Avatar avatar : occupants.values()) entities.add(avatar.body);
            return entities;
        }

        private void spawnPortal(Player viewer) {
            if (exteriorPortal == null) return;
            boolean inset = onDoorSide(jar, viewer.getLocation());
            exteriorPortal.spawn(viewer, portalTransformation(jar.door(),
                    inset ? FRONT_PORTAL_INWARD : BACK_PORTAL_INWARD));
            if (inset) insetPortalViewers.add(viewer.getUniqueId());
            else insetPortalViewers.remove(viewer.getUniqueId());
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
            if (exteriorPortal != null) for (Player viewer : online(exteriorViewers)) exteriorPortal.destroy(viewer);
            removeEntities(interiorBlocks, interiorViewers);
            for (Player viewer : online(exteriorViewers)) for (Avatar avatar : occupants.values()) avatar.body.destroy(viewer);
            for (Player viewer : online(interiorViewers)) for (Avatar avatar : outsiders.values()) avatar.body.destroy(viewer);
            exteriorBlocks.clear(); interiorBlocks.clear(); occupants.clear(); outsiders.clear();
            exteriorPortal = null;
            exteriorViewers.clear(); interiorViewers.clear(); insetPortalViewers.clear();
        }
    }
}
