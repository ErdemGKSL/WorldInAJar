package tr.erdemdev.worldInAJar;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.mojang.datafixers.util.Pair;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.protocol.game.ClientboundProjectilePowerPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityLinkPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

/** Same-type, client-only entity mirrors transmitted through ProtocolLib. */
final class ProtocolEntityPreview implements EntityPreviewBackend {
    private static final AtomicInteger IDS = new AtomicInteger(1_900_000_000);
    private static final Set<PacketType> MOVEMENT_PACKETS = Set.of(
            PacketType.Play.Server.ENTITY_POSITION_SYNC, PacketType.Play.Server.REL_ENTITY_MOVE,
            PacketType.Play.Server.REL_ENTITY_MOVE_LOOK, PacketType.Play.Server.ENTITY_LOOK,
            PacketType.Play.Server.MOVE_MINECART, PacketType.Play.Server.ENTITY_VELOCITY,
            PacketType.Play.Server.ENTITY_TELEPORT);
    private static final Set<PacketType> RELATIONSHIP_PACKETS = Set.of(
            PacketType.Play.Server.ATTACH_ENTITY, PacketType.Play.Server.MOUNT);
    private final JavaPlugin plugin;
    private final InteriorService interiors;
    private final ProtocolManager protocol;
    private final Map<MirrorKey, Mirror> mirrors = new HashMap<>();
    private final Map<SourceEntityKey, Integer> mirroredSources = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> mirroredEntityIds = new ConcurrentHashMap<>();
    private final Set<PacketIdentity> forwardedHandles = ConcurrentHashMap.newKeySet();
    private final ExecutorService packetExecutor;
    private final PacketAdapter eventPipe;
    private final int exteriorMaximum;
    private final int interiorMaximum;
    private final int outsideRadius;
    private volatile boolean running = true;

    ProtocolEntityPreview(JavaPlugin plugin, InteriorService interiors) {
        this.plugin = plugin;
        this.interiors = interiors;
        this.protocol = ProtocolLibrary.getProtocolManager();
        this.exteriorMaximum = bounded("preview.exterior.max-entities", 64, 0, 256);
        this.interiorMaximum = bounded("preview.interior.max-entities", 64, 0, 256);
        this.outsideRadius = bounded("preview.interior.outside-radius", 6, 1, 24);
        this.packetExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WorldInAJar-protocol-preview");
            thread.setDaemon(true);
            return thread;
        });
        this.eventPipe = new PacketAdapter(plugin, List.of(PacketType.Play.Server.ANIMATION,
                PacketType.Play.Server.ENTITY_STATUS, PacketType.Play.Server.HURT_ANIMATION,
                PacketType.Play.Server.ENTITY_EFFECT, PacketType.Play.Server.REMOVE_ENTITY_EFFECT,
                PacketType.Play.Server.ENTITY_EQUIPMENT, PacketType.Play.Server.ENTITY_METADATA,
                PacketType.Play.Server.ENTITY_HEAD_ROTATION, PacketType.Play.Server.ENTITY_SOUND,
                PacketType.Play.Server.PROJECTILE_POWER, PacketType.Play.Server.DAMAGE_EVENT,
                PacketType.Play.Server.COLLECT, PacketType.Play.Server.ENTITY_POSITION_SYNC,
                PacketType.Play.Server.REL_ENTITY_MOVE, PacketType.Play.Server.REL_ENTITY_MOVE_LOOK,
                PacketType.Play.Server.ENTITY_LOOK, PacketType.Play.Server.MOVE_MINECART,
                PacketType.Play.Server.ENTITY_VELOCITY, PacketType.Play.Server.ENTITY_TELEPORT,
                PacketType.Play.Server.ATTACH_ENTITY, PacketType.Play.Server.MOUNT)) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Integer sourceId = event.getPacket().getIntegers().readSafely(0);
                if (sourceId == null) return;
                // The callback may be on a network thread. This concurrent, world-agnostic index is only
                // a cheap prefilter; the exact world check is made later on the server thread.
                if (!mirroredEntityIds.containsKey(sourceId)) return;
                PacketType packetType = event.getPacketType();
                PacketIdentity packetIdentity = new PacketIdentity(event.getPacket().getHandle());
                if (!forwardedHandles.add(packetIdentity)) return;
                PacketContainer packet = MOVEMENT_PACKETS.contains(packetType)
                        || RELATIONSHIP_PACKETS.contains(packetType) ? null : event.getPacket().deepClone();
                UUID packetViewerId = event.getPlayer().getUniqueId();
                runOnMain(() -> {
                    try {
                        Player packetViewer = Bukkit.getPlayer(packetViewerId);
                        if (packetViewer == null) return;
                        UUID sourceWorldId = packetViewer.getWorld().getUID();
                        if (!mirroredSources.containsKey(new SourceEntityKey(sourceWorldId, sourceId))) return;
                        if (MOVEMENT_PACKETS.contains(packetType)) {
                            relayMovement(sourceWorldId, sourceId);
                        } else if (RELATIONSHIP_PACKETS.contains(packetType)) {
                            relayRelationships(sourceWorldId, sourceId);
                        } else if (packetType.equals(PacketType.Play.Server.PROJECTILE_POWER)) {
                            relayProjectilePower(sourceWorldId, sourceId, packet);
                        } else if (packetType.equals(PacketType.Play.Server.DAMAGE_EVENT)) {
                            relayDamage(sourceWorldId, sourceId, packet);
                        } else if (packetType.equals(PacketType.Play.Server.COLLECT)) {
                            relayCollect(sourceWorldId, sourceId, packet);
                        } else if (packetType.equals(PacketType.Play.Server.ENTITY_METADATA)) {
                            relayMetadata(sourceWorldId, sourceId, packet);
                        } else {
                            relay(sourceWorldId, sourceId, packet);
                        }
                    } finally {
                        // Keep the handle marked through this tick: the server can send one packet object to
                        // several viewers and it must only be mirrored once.
                        if (running) plugin.getServer().getScheduler().runTask(plugin,
                                () -> forwardedHandles.remove(packetIdentity));
                    }
                });
            }
        };
        protocol.addPacketListener(eventPipe);
    }

    @Override
    public void update(JarRecord jar, Set<UUID> exteriorViewers, Set<UUID> interiorViewers) {
        Set<MirrorKey> present = new HashSet<>();
        if (!exteriorViewers.isEmpty()) {
            sync(jar, Side.EXTERIOR, interiorEntities(jar, exteriorMaximum), exteriorViewers, present);
        }
        if (!interiorViewers.isEmpty()) {
            sync(jar, Side.INTERIOR, outsideEntities(jar, interiorMaximum), interiorViewers, present);
        }
        for (MirrorKey key : new ArrayList<>(mirrors.keySet())) {
            if (key.jarId.equals(jar.id()) && !present.contains(key)) removeMirror(key);
        }
    }

    private void sync(JarRecord jar, Side side, List<Entity> sources, Set<UUID> viewerIds, Set<MirrorKey> present) {
        Collection<Player> viewers = online(viewerIds);
        CoordinateMapping mapping = mapping(jar, side);
        List<MirrorUpdate> updates = new ArrayList<>(sources.size());
        for (Entity source : sources) {
            MirrorKey key = new MirrorKey(jar.id(), side, source.getUniqueId());
            present.add(key);
            Mirror mirror = mirrors.computeIfAbsent(key, ignored -> new Mirror(key, source));
            updates.add(new MirrorUpdate(mirror, source, source.getLocation(), scale(jar, side), mapping));
        }
        updates.sort((left, right) -> Integer.compare(spawnPriority(left.source), spawnPriority(right.source)));
        for (MirrorUpdate update : updates) update.mirror.prepare(jar, update.source, update.scale, viewers);
        for (MirrorUpdate update : updates) {
            update.mirror.spawnFor(update.source, update.sourceLocation, update.mapping, viewers);
        }
        for (MirrorUpdate update : updates) {
            update.mirror.syncFor(update.source, update.sourceLocation, update.mapping, viewers);
        }
    }

    private static int spawnPriority(Entity source) {
        return ((CraftEntity) source).getHandle() instanceof Projectile ? 1 : 0;
    }

    private List<Entity> interiorEntities(JarRecord jar, int maximum) {
        if (maximum == 0) return List.of();
        CellLayout.Cell cell = interiors.cell(jar);
        BoundingBox bounds = new BoundingBox(cell.minX(), cell.minY(), cell.minZ(),
                cell.minX() + jar.interiorSizeX(), cell.minY() + jar.interiorSizeY(),
                cell.minZ() + jar.interiorSizeZ());
        List<Entity> result = new ArrayList<>();
        for (Entity entity : interiors.world().getNearbyEntities(bounds,
                entity -> eligible(entity) && interiors.contains(jar, entity.getLocation()))) {
            result.add(entity);
            if (result.size() >= maximum) break;
        }
        return result;
    }

    private List<Entity> outsideEntities(JarRecord jar, int maximum) {
        if (maximum == 0) return List.of();
        Location center = jar.outsideCenter();
        List<Entity> result = new ArrayList<>();
        for (Entity entity : center.getWorld().getNearbyEntities(
                center, outsideRadius, outsideRadius, outsideRadius, this::eligible)) {
            if (entity.getLocation().distanceSquared(center) > outsideRadius * outsideRadius) continue;
            result.add(entity);
            if (result.size() >= maximum) break;
        }
        return result;
    }

    private boolean eligible(Entity entity) {
        return entity.isValid() && !(entity instanceof Display) && !(entity instanceof Mannequin)
                && !(entity instanceof org.bukkit.entity.Item);
    }

    private Location mapped(JarRecord jar, Side side, Location source) {
        if (side == Side.EXTERIOR) {
            CellLayout.Cell cell = interiors.cell(jar);
            double scale = jar.scale();
            Location outside = jar.outsideLocation();
            return new Location(outside.getWorld(), outside.getX() + (source.getX() - cell.minX()) / scale,
                    outside.getY() + (source.getY() - cell.minY()) / scale,
                    outside.getZ() + (source.getZ() - cell.minZ()) / scale,
                    source.getYaw(), source.getPitch());
        }
        Location outside = jar.outsideLocation();
        CellLayout.Cell cell = interiors.cell(jar);
        double scale = jar.scale();
        return new Location(interiors.world(), cell.minX() + (source.getX() - outside.getX()) * scale,
                cell.minY() + 1 + (source.getY() - outside.getY()) * scale,
                cell.minZ() + (source.getZ() - outside.getZ()) * scale,
                source.getYaw(), source.getPitch());
    }

    private CoordinateMapping mapping(JarRecord jar, Side side) {
        Location outside = jar.outsideLocation();
        CellLayout.Cell cell = interiors.cell(jar);
        double scale = scale(jar, side);
        if (side == Side.EXTERIOR) {
            return new CoordinateMapping(scale, outside.getX() - cell.minX() * scale,
                    outside.getY() - cell.minY() * scale, outside.getZ() - cell.minZ() * scale);
        }
        return new CoordinateMapping(scale, cell.minX() - outside.getX() * scale,
                cell.minY() + 1 - outside.getY() * scale, cell.minZ() - outside.getZ() * scale);
    }

    private static double scale(JarRecord jar, Side side) {
        return side == Side.EXTERIOR ? 1.0 / jar.scale() : jar.scale();
    }

    @Override
    public void remove(UUID jarId) {
        for (MirrorKey key : new ArrayList<>(mirrors.keySet())) if (key.jarId.equals(jarId)) removeMirror(key);
    }

    @Override
    public void removeSource(UUID sourceId) {
        for (MirrorKey key : new ArrayList<>(mirrors.keySet())) {
            if (key.sourceId.equals(sourceId)) removeMirror(key);
        }
    }

    @Override
    public void forget(UUID playerId) {
        for (Mirror mirror : mirrors.values()) mirror.forget(playerId);
    }

    @Override
    public void stop() {
        protocol.removePacketListener(eventPipe);
        for (Mirror mirror : mirrors.values()) mirror.destroyAll();
        mirrors.clear();
        mirroredSources.clear();
        mirroredEntityIds.clear();
        running = false;
        packetExecutor.shutdown();
    }

    private void removeMirror(MirrorKey key) {
        Mirror mirror = mirrors.remove(key);
        if (mirror != null) {
            mirror.unregisterSource();
            mirror.destroyAll();
        }
    }

    private void registerSource(SourceEntityKey source) {
        mirroredSources.merge(source, 1, Integer::sum);
        mirroredEntityIds.merge(source.entityId, 1, Integer::sum);
    }

    private void unregisterSource(SourceEntityKey source) {
        mirroredSources.computeIfPresent(source, (ignored, count) -> count == 1 ? null : count - 1);
        mirroredEntityIds.computeIfPresent(source.entityId,
                (ignored, count) -> count == 1 ? null : count - 1);
    }

    private void relay(UUID sourceWorldId, int sourceId, PacketContainer sourcePacket) {
        List<RelayTarget> targets = new ArrayList<>();
        for (Mirror mirror : mirrors.values()) {
            if (!mirror.matchesStoredSource(sourceWorldId, sourceId)) continue;
            Collection<Player> viewers = online(mirror.viewers);
            if (!viewers.isEmpty()) targets.add(new RelayTarget(mirror.id, List.copyOf(viewers)));
        }
        dispatch(() -> {
            for (RelayTarget target : targets) {
                PacketContainer packet = sourcePacket.deepClone();
                packet.getIntegers().writeSafely(0, target.entityId);
                sendNow(target.viewers, List.of(packet));
            }
        });
    }

    private void relayMovement(UUID sourceWorldId, int sourceId) {
        for (Mirror mirror : mirrors.values()) {
            if (!mirror.matchesSource(sourceWorldId, sourceId)) continue;
            Entity source = Bukkit.getEntity(mirror.sourceUuid);
            if (source != null) mirror.syncMovement(source);
        }
    }

    private void relayRelationships(UUID sourceWorldId, int sourceId) {
        for (Mirror mirror : mirrors.values()) {
            if (!mirror.matchesSource(sourceWorldId, sourceId)) continue;
            Entity source = Bukkit.getEntity(mirror.sourceUuid);
            if (source != null) mirror.syncRelationships(source, online(mirror.viewers));
        }
    }

    private void relayProjectilePower(UUID sourceWorldId, int sourceId, PacketContainer sourcePacket) {
        if (!(sourcePacket.getHandle() instanceof ClientboundProjectilePowerPacket original)) return;
        List<ScaledRelayTarget> targets = new ArrayList<>();
        for (Mirror mirror : mirrors.values()) {
            if (!mirror.matchesSource(sourceWorldId, sourceId)) continue;
            List<Player> viewers = List.copyOf(online(mirror.viewers));
            if (!viewers.isEmpty()) targets.add(new ScaledRelayTarget(
                    mirror.id, mirror.worldScale, viewers));
        }
        double accelerationPower = original.getAccelerationPower();
        dispatch(() -> targets.forEach(target -> sendNow(target.viewers, List.of(PacketContainer.fromPacket(
                new ClientboundProjectilePowerPacket(target.entityId,
                        accelerationPower * target.worldScale))))));
    }

    private void relayDamage(UUID sourceWorldId, int sourceId, PacketContainer sourcePacket) {
        if (!(sourcePacket.getHandle() instanceof ClientboundDamageEventPacket original)) return;
        List<DamageRelayTarget> targets = new ArrayList<>();
        for (Mirror mirror : mirrors.values()) {
            if (!mirror.matchesSource(sourceWorldId, sourceId)) continue;
            int causeId = remappedId(mirror.key, sourceWorldId, original.sourceCauseId());
            int directId = remappedId(mirror.key, sourceWorldId, original.sourceDirectId());
            List<Player> viewers = List.copyOf(online(mirror.viewers));
            if (!viewers.isEmpty()) targets.add(new DamageRelayTarget(mirror.id, causeId, directId,
                    original.sourcePosition().map(mirror::mappedPoint), viewers));
        }
        dispatch(() -> targets.forEach(target -> sendNow(target.viewers, List.of(PacketContainer.fromPacket(
                new ClientboundDamageEventPacket(target.entityId, original.sourceType(), target.causeId,
                        target.directId, target.sourcePosition))))));
    }

    private void relayCollect(UUID sourceWorldId, int sourceId, PacketContainer sourcePacket) {
        if (!(sourcePacket.getHandle() instanceof ClientboundTakeItemEntityPacket original)) return;
        List<CollectRelayTarget> targets = new ArrayList<>();
        for (Mirror item : mirrors.values()) {
            if (!item.matchesStoredSource(sourceWorldId, sourceId)) continue;
            Mirror collector = mirrorBySourceId(item.key, sourceWorldId, original.getPlayerId());
            if (collector == null) continue;
            List<Player> viewers = new ArrayList<>();
            for (Player viewer : online(item.viewers)) {
                if (collector.viewers.contains(viewer.getUniqueId())) viewers.add(viewer);
            }
            if (!viewers.isEmpty()) targets.add(new CollectRelayTarget(
                    item.id, collector.id, original.getAmount(), List.copyOf(viewers)));
        }
        dispatch(() -> targets.forEach(target -> sendNow(target.viewers, List.of(PacketContainer.fromPacket(
                new ClientboundTakeItemEntityPacket(target.entityId, target.collectorId, target.amount))))));
    }

    private void relayMetadata(UUID sourceWorldId, int sourceId, PacketContainer sourcePacket) {
        if (!(sourcePacket.getHandle() instanceof ClientboundSetEntityDataPacket original)) return;
        List<MetadataRelayTarget> targets = new ArrayList<>();
        for (Mirror mirror : mirrors.values()) {
            if (!mirror.matchesSource(sourceWorldId, sourceId)) continue;
            Entity source = Bukkit.getEntity(mirror.sourceUuid);
            if (source == null) continue;
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            List<Player> viewers = List.copyOf(online(mirror.viewers));
            if (!viewers.isEmpty()) targets.add(new MetadataRelayTarget(mirror.id,
                    List.copyOf(hiddenNametagMetadata(handle, original.packedItems())), viewers));
        }
        dispatch(() -> targets.forEach(target -> sendNow(target.viewers, List.of(PacketContainer.fromPacket(
                new ClientboundSetEntityDataPacket(target.entityId, target.metadata))))));
    }

    private static List<SynchedEntityData.DataValue<?>> hiddenNametagMetadata(
            net.minecraft.world.entity.Entity handle, List<SynchedEntityData.DataValue<?>> values) {
        List<SynchedEntityData.DataValue<?>> allValues = handle.getEntityData().packAll();
        int visibilityId = allValues.stream()
                .filter(value -> value.serializer() == EntityDataSerializers.BOOLEAN)
                .mapToInt(SynchedEntityData.DataValue::id)
                .min().orElse(-1);
        int customNameId = allValues.stream()
                .filter(value -> value.serializer() == EntityDataSerializers.OPTIONAL_COMPONENT)
                .mapToInt(SynchedEntityData.DataValue::id)
                .min().orElse(-1);
        if (visibilityId < 0 && customNameId < 0) return values;
        List<SynchedEntityData.DataValue<?>> result = new ArrayList<>(values.size());
        for (SynchedEntityData.DataValue<?> value : values) {
            if (value.id() == visibilityId) {
                result.add(new SynchedEntityData.DataValue<>(visibilityId, EntityDataSerializers.BOOLEAN, false));
            } else if (value.id() == customNameId) {
                result.add(new SynchedEntityData.DataValue<>(customNameId,
                        EntityDataSerializers.OPTIONAL_COMPONENT,
                        Optional.<net.minecraft.network.chat.Component>empty()));
            } else {
                result.add(value);
            }
        }
        return result;
    }

    private int remappedId(MirrorKey key, UUID sourceWorldId, int sourceId) {
        if (sourceId < 0) return -1;
        Mirror mirror = mirrorBySourceId(key, sourceWorldId, sourceId);
        return mirror == null ? -1 : mirror.id;
    }

    private Mirror mirrorBySourceId(MirrorKey key, UUID sourceWorldId, int sourceId) {
        for (Mirror candidate : mirrors.values()) {
            if (!candidate.key.jarId.equals(key.jarId) || candidate.key.side != key.side) continue;
            if (candidate.matchesSource(sourceWorldId, sourceId)) return candidate;
        }
        return null;
    }

    private Mirror relatedMirror(MirrorKey key, Entity source) {
        if (source == null) return null;
        return mirrors.get(new MirrorKey(key.jarId, key.side, source.getUniqueId()));
    }

    private Collection<Player> online(Set<UUID> ids) {
        List<Player> result = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) result.add(player);
        }
        return result;
    }

    private int bounded(String path, int fallback, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, plugin.getConfig().getInt(path, fallback)));
    }

    private void send(Player viewer, Packet<? super ClientGamePacketListener> packet) {
        send(List.of(viewer), List.of(PacketContainer.fromPacket(packet)));
    }

    private void send(Collection<Player> viewers, Collection<PacketContainer> packets) {
        if (viewers.isEmpty() || packets.isEmpty()) return;
        List<Player> viewerSnapshot = List.copyOf(viewers);
        List<PacketContainer> packetSnapshot = List.copyOf(packets);
        dispatch(() -> sendNow(viewerSnapshot, packetSnapshot));
    }

    private void sendNow(Collection<Player> viewers, Collection<PacketContainer> packets) {
        for (Player viewer : viewers) {
            for (PacketContainer packet : packets) {
                try {
                    protocol.sendServerPacket(viewer, packet, false);
                } catch (RuntimeException exception) {
                    if (running) plugin.getLogger().warning("Could not send protocol preview packet: "
                            + exception.getMessage());
                }
            }
        }
    }

    private void dispatch(Runnable task) {
        if (!running) return;
        try {
            packetExecutor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException exception) {
                    if (running) plugin.getLogger().warning("Protocol preview worker failed: "
                            + exception.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The plugin is stopping and no more preview packets should be sent.
        }
    }

    private void runOnMain(Runnable task) {
        if (!running) return;
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private final class Mirror {
        private final int id = IDS.getAndDecrement();
        private final MirrorKey key;
        private final UUID uuid;
        private final UUID sourceUuid;
        private final String playerName;
        private final PlayerTeam hiddenNameTeam;
        private final Set<UUID> viewers = new HashSet<>();
        private final Map<UUID, RelationshipState> relationships = new HashMap<>();
        private final AtomicReference<StateDelivery> pendingState = new AtomicReference<>();
        private final AtomicBoolean stateScheduled = new AtomicBoolean();
        private final AtomicReference<MovementDelivery> pendingMovement = new AtomicReference<>();
        private final AtomicBoolean movementScheduled = new AtomicBoolean();
        private JarRecord jar;
        private double worldScale;
        private int sourceEntityId;
        private UUID sourceWorldId;

        private Mirror(MirrorKey key, Entity source) {
            this.key = key;
            this.uuid = source instanceof Player ? source.getUniqueId() : UUID.randomUUID();
            this.sourceUuid = source.getUniqueId();
            this.playerName = source instanceof Player player ? player.getName() : null;
            this.hiddenNameTeam = playerName == null ? null : hiddenNameTeam(playerName);
            this.sourceEntityId = source.getEntityId();
            this.sourceWorldId = source.getWorld().getUID();
            registerSource(sourceKey());
        }

        private void prepare(JarRecord jar, Entity source, double scale, Collection<Player> desired) {
            this.jar = jar;
            this.worldScale = scale;
            SourceEntityKey previousSource = sourceKey();
            int currentEntityId = source.getEntityId();
            UUID currentWorldId = source.getWorld().getUID();
            if (sourceEntityId != currentEntityId || !sourceWorldId.equals(currentWorldId)) {
                ProtocolEntityPreview.this.unregisterSource(previousSource);
                sourceEntityId = currentEntityId;
                sourceWorldId = currentWorldId;
                registerSource(sourceKey());
            }
            Set<UUID> desiredIds = new HashSet<>();
            for (Player viewer : desired) desiredIds.add(viewer.getUniqueId());
            for (UUID viewerId : new HashSet<>(viewers)) {
                if (desiredIds.contains(viewerId)) continue;
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) destroy(viewer);
                viewers.remove(viewerId);
                relationships.remove(viewerId);
            }
        }

        private void spawnFor(Entity source, Location sourceLocation, CoordinateMapping mapping,
                              Collection<Player> desired) {
            for (Player viewer : desired) {
                if (!viewers.contains(viewer.getUniqueId()) && spawn(viewer, source, sourceLocation, mapping)) {
                    viewers.add(viewer.getUniqueId());
                }
            }
        }

        private void syncFor(Entity source, Location sourceLocation, CoordinateMapping mapping,
                             Collection<Player> desired) {
            List<Player> active = new ArrayList<>(desired.size());
            for (Player viewer : desired) {
                if (!viewers.contains(viewer.getUniqueId())) continue;
                active.add(viewer);
                syncRelationships(source, List.of(viewer));
            }
            if (!active.isEmpty()) queueState(active,
                    snapshotState(source, sourceLocation, mapping, worldScale));
        }

        private boolean spawn(Player viewer, Entity source, Location sourceLocation,
                              CoordinateMapping mapping) {
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            ChunkMap.TrackedEntity tracked = handle.moonrise$getTrackedEntity();
            if (tracked == null) return false;
            Packet<ClientGamePacketListener> nativePacket = handle.getAddEntityPacket(tracked.serverEntity);
            if (!(nativePacket instanceof ClientboundAddEntityPacket original)) return false;
            Integer data = spawnData(viewer, handle, original.getData());
            if (data == null) return false;
            SpawnState state = new SpawnState(sourceLocation.getX(), sourceLocation.getY(), sourceLocation.getZ(),
                    mapping, handle.getXRot(), handle.getYRot(), handle.getType(), data,
                    handle.getDeltaMovement(), handle.getYHeadRot(), worldScale);
            dispatch(() -> {
                List<PacketContainer> packets = new ArrayList<>(2);
                if (hiddenNameTeam != null) {
                    packets.add(PacketContainer.fromPacket(
                            ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(hiddenNameTeam, true)));
                }
                packets.add(PacketContainer.fromPacket(new ClientboundAddEntityPacket(id, uuid,
                        state.mapping.x(state.sourceX), state.mapping.y(state.sourceY),
                        state.mapping.z(state.sourceZ), state.pitch, state.yaw, state.type, state.data,
                        state.velocity.scale(state.worldScale), state.headYaw)));
                sendNow(List.of(viewer), packets);
            });
            return true;
        }

        private Integer spawnData(Player viewer, net.minecraft.world.entity.Entity handle, int original) {
            if (!(handle instanceof Projectile projectile)) return original;
            net.minecraft.world.entity.Entity owner = projectile.getOwner();
            Mirror ownerMirror = owner == null ? null : relatedMirror(key, owner.getBukkitEntity());
            if (ownerMirror != null && ownerMirror.viewers.contains(viewer.getUniqueId())) return ownerMirror.id;
            return handle instanceof FishingHook ? null : 0;
        }

        private boolean matchesSource(UUID worldId, int entityId) {
            if (!matchesStoredSource(worldId, entityId)) return false;
            Entity current = Bukkit.getEntity(sourceUuid);
            return current != null && current.isValid() && current.getEntityId() == entityId
                    && current.getWorld().getUID().equals(worldId);
        }

        private boolean matchesStoredSource(UUID worldId, int entityId) {
            return sourceEntityId == entityId && sourceWorldId.equals(worldId);
        }

        private SourceEntityKey sourceKey() {
            return new SourceEntityKey(sourceWorldId, sourceEntityId);
        }

        private void unregisterSource() {
            ProtocolEntityPreview.this.unregisterSource(sourceKey());
        }

        private EntityState snapshotState(Entity source, Location sourceLocation,
                                          CoordinateMapping mapping, double scale) {
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            Packet<ClientGamePacketListener> attributesPacket = null;
            List<Pair<EquipmentSlot, ItemStack>> equipment = List.of();
            double sourceScale = 1.0;
            boolean supportsAttributes = false;
            if (handle instanceof LivingEntity living) {
                supportsAttributes = true;
                Collection<AttributeInstance> attributes = living.getAttributes().getSyncableAttributes();
                if (!attributes.isEmpty()) attributesPacket = new ClientboundUpdateAttributesPacket(id, attributes);
                sourceScale = living.getScale();
                equipment = equipment(living);
            }
            return new EntityState(hiddenNametagMetadata(handle, handle.getEntityData().packAll()),
                    movementState(handle, sourceLocation, mapping, scale),
                    attributesPacket, equipment, sourceScale, supportsAttributes);
        }

        private MovementState movementState(net.minecraft.world.entity.Entity handle,
                                            Location sourceLocation, CoordinateMapping mapping, double scale) {
            PacketContainer headRotation = PacketContainer.fromPacket(
                    new ClientboundRotateHeadPacket(handle, Mth.packDegrees(handle.getYHeadRot())));
            headRotation.getIntegers().write(0, id);
            return new MovementState(sourceLocation.getX(), sourceLocation.getY(), sourceLocation.getZ(), mapping,
                    handle.getDeltaMovement(), handle.getYRot(), handle.getXRot(),
                    handle.onGround(), headRotation, scale);
        }

        private void queueState(Collection<Player> viewers, EntityState state) {
            pendingState.set(new StateDelivery(List.copyOf(viewers), state));
            if (stateScheduled.compareAndSet(false, true)) dispatch(this::drainState);
        }

        private void drainState() {
            StateDelivery delivery;
            while ((delivery = pendingState.getAndSet(null)) != null) sendStateNow(delivery);
            stateScheduled.set(false);
            if (pendingState.get() != null && stateScheduled.compareAndSet(false, true)) dispatch(this::drainState);
        }

        private void sendStateNow(StateDelivery delivery) {
            EntityState state = delivery.state;
            List<PacketContainer> packets = movementPackets(state.movement);
            packets.addFirst(PacketContainer.fromPacket(new ClientboundSetEntityDataPacket(id, state.metadata)));
            if (state.attributes != null) packets.add(PacketContainer.fromPacket(state.attributes));
            if (state.supportsAttributes) {
                AttributeInstance scaled = new AttributeInstance(Attributes.SCALE, ignored -> {});
                scaled.setBaseValue(Math.max(.0625, Math.min(16.0,
                        state.sourceScale * state.movement.worldScale)));
                packets.add(PacketContainer.fromPacket(new ClientboundUpdateAttributesPacket(id, List.of(scaled))));
            }
            if (!state.equipment.isEmpty()) {
                packets.add(PacketContainer.fromPacket(new ClientboundSetEquipmentPacket(id, state.equipment)));
            }
            sendNow(delivery.viewers, packets);
        }

        private List<PacketContainer> movementPackets(MovementState state) {
            Vec3 velocity = state.velocity.scale(state.worldScale);
            List<PacketContainer> packets = new ArrayList<>(3);
            packets.add(PacketContainer.fromPacket(ClientboundTeleportEntityPacket.teleport(id,
                    new PositionMoveRotation(new Vec3(state.mapping.x(state.sourceX),
                            state.mapping.y(state.sourceY), state.mapping.z(state.sourceZ)), velocity,
                            state.yaw, state.pitch), Set.<Relative>of(), state.onGround)));
            packets.add(state.headRotation);
            packets.add(PacketContainer.fromPacket(new ClientboundSetEntityMotionPacket(id, velocity)));
            return packets;
        }

        private void syncMovement(Entity source) {
            if (jar == null) return;
            Location sourceLocation = source.getLocation();
            Collection<Player> active = online(viewers);
            if (active.isEmpty()) return;
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            MovementState state = movementState(handle, sourceLocation,
                    mapping(jar, key.side), worldScale);
            queueMovement(active, state);
        }

        private void queueMovement(Collection<Player> viewers, MovementState state) {
            pendingMovement.set(new MovementDelivery(List.copyOf(viewers), state));
            if (movementScheduled.compareAndSet(false, true)) dispatch(this::drainMovement);
        }

        private void drainMovement() {
            MovementDelivery delivery;
            while ((delivery = pendingMovement.getAndSet(null)) != null) {
                sendNow(delivery.viewers, movementPackets(delivery.state));
            }
            movementScheduled.set(false);
            if (pendingMovement.get() != null && movementScheduled.compareAndSet(false, true)) {
                dispatch(this::drainMovement);
            }
        }

        private Vec3 mappedPoint(Vec3 point) {
            Entity source = Bukkit.getEntity(sourceUuid);
            if (source == null || jar == null) return point;
            Location sourceLocation = source.getLocation();
            Location targetLocation = mapped(jar, key.side, sourceLocation);
            return new Vec3(ProtocolPreviewMath.mapCoordinate(point.x, sourceLocation.getX(),
                    targetLocation.getX(), worldScale),
                    ProtocolPreviewMath.mapCoordinate(point.y, sourceLocation.getY(),
                            targetLocation.getY(), worldScale),
                    ProtocolPreviewMath.mapCoordinate(point.z, sourceLocation.getZ(),
                            targetLocation.getZ(), worldScale));
        }

        private void syncRelationships(Entity source, Collection<Player> desired) {
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            for (Player viewer : desired) {
                UUID viewerId = viewer.getUniqueId();
                RelationshipState current = relationshipState(source, viewerId);
                RelationshipState previous = relationships.get(viewerId);
                if (previous == null || !current.passengers.equals(previous.passengers)) {
                    if (!current.passengers.isEmpty() || previous != null && !previous.passengers.isEmpty()) {
                        PacketContainer packet = PacketContainer.fromPacket(new ClientboundSetPassengersPacket(handle));
                        packet.getIntegers().write(0, id);
                        packet.getIntegerArrays().write(0, current.passengers.stream().mapToInt(Integer::intValue).toArray());
                        send(List.of(viewer), List.of(packet));
                    }
                }
                if (current.leashHolderId != -1
                        && (previous == null || current.leashHolderId != previous.leashHolderId)) {
                    if (current.leashHolderId != 0 || previous != null && previous.leashHolderId > 0) {
                        PacketContainer packet = PacketContainer.fromPacket(new ClientboundSetEntityLinkPacket(handle, null));
                        packet.getIntegers().write(0, id).write(1, current.leashHolderId);
                        send(List.of(viewer), List.of(packet));
                    }
                }
                relationships.put(viewerId, current);
            }
        }

        private RelationshipState relationshipState(Entity source, UUID viewerId) {
            List<Integer> passengers = new ArrayList<>();
            for (Entity passenger : source.getPassengers()) {
                Mirror mirror = relatedMirror(key, passenger);
                if (mirror != null && mirror.viewers.contains(viewerId)) passengers.add(mirror.id);
            }
            int leashHolderId = -1;
            if (source instanceof org.bukkit.entity.LivingEntity leashable) {
                leashHolderId = 0;
                if (leashable.isLeashed()) {
                    Mirror holder = relatedMirror(key, leashable.getLeashHolder());
                    if (holder != null && holder.viewers.contains(viewerId)) leashHolderId = holder.id;
                }
            }
            return new RelationshipState(List.copyOf(passengers), leashHolderId);
        }

        private PlayerTeam hiddenNameTeam(String entry) {
            Scoreboard scoreboard = new Scoreboard();
            PlayerTeam team = scoreboard.addPlayerTeam("wiaj" + Integer.toUnsignedString(id, 36));
            team.setNameTagVisibility(Team.Visibility.NEVER);
            scoreboard.addPlayerToTeam(entry, team);
            return team;
        }

        private List<Pair<EquipmentSlot, ItemStack>> equipment(LivingEntity living) {
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
            for (EquipmentSlot slot : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET,
                    EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)) {
                equipment.add(Pair.of(slot, living.getItemBySlot(slot).copy()));
            }
            return List.copyOf(equipment);
        }

        private void destroyAll() {
            for (UUID viewerId : viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) destroy(viewer);
            }
            viewers.clear();
            relationships.clear();
        }

        private void forget(UUID viewerId) {
            viewers.remove(viewerId);
            relationships.remove(viewerId);
        }

        private void destroy(Player viewer) {
            relationships.remove(viewer.getUniqueId());
            send(viewer, new ClientboundRemoveEntitiesPacket(id));
            if (hiddenNameTeam == null) return;
            send(viewer, ClientboundSetPlayerTeamPacket.createRemovePacket(hiddenNameTeam));
            Player source = Bukkit.getPlayer(sourceUuid);
            if (source == null) return;
            PlayerTeam realTeam = ((CraftEntity) source).getHandle().getTeam();
            if (realTeam != null) {
                send(viewer, ClientboundSetPlayerTeamPacket.createPlayerPacket(realTeam, playerName,
                        ClientboundSetPlayerTeamPacket.Action.ADD));
            }
        }
    }

    private enum Side { EXTERIOR, INTERIOR }
    private record SourceEntityKey(UUID worldId, int entityId) {}
    private record PacketIdentity(Object handle) {
        @Override
        public boolean equals(Object other) {
            return other instanceof PacketIdentity identity && handle == identity.handle;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(handle);
        }
    }
    private record MirrorUpdate(Mirror mirror, Entity source, Location sourceLocation,
                                double scale, CoordinateMapping mapping) {}
    private record MirrorKey(UUID jarId, Side side, UUID sourceId) {}
    private record RelationshipState(List<Integer> passengers, int leashHolderId) {}
    private record RelayTarget(int entityId, List<Player> viewers) {}
    private record ScaledRelayTarget(int entityId, double worldScale, List<Player> viewers) {}
    private record DamageRelayTarget(int entityId, int causeId, int directId, Optional<Vec3> sourcePosition,
                                     List<Player> viewers) {}
    private record CollectRelayTarget(int entityId, int collectorId, int amount, List<Player> viewers) {}
    private record MetadataRelayTarget(int entityId, List<SynchedEntityData.DataValue<?>> metadata,
                                       List<Player> viewers) {}
    private record CoordinateMapping(double scale, double offsetX, double offsetY, double offsetZ) {
        private double x(double source) { return source * scale + offsetX; }
        private double y(double source) { return source * scale + offsetY; }
        private double z(double source) { return source * scale + offsetZ; }
    }
    private record SpawnState(double sourceX, double sourceY, double sourceZ, CoordinateMapping mapping,
                              float pitch, float yaw, net.minecraft.world.entity.EntityType<?> type, int data,
                              Vec3 velocity, double headYaw, double worldScale) {}
    private record MovementState(double sourceX, double sourceY, double sourceZ, CoordinateMapping mapping,
                                 Vec3 velocity, float yaw, float pitch, boolean onGround,
                                 PacketContainer headRotation, double worldScale) {}
    private record EntityState(List<SynchedEntityData.DataValue<?>> metadata, MovementState movement,
                               Packet<ClientGamePacketListener> attributes,
                               List<Pair<EquipmentSlot, ItemStack>> equipment,
                               double sourceScale, boolean supportsAttributes) {}
    private record StateDelivery(List<Player> viewers, EntityState state) {}
    private record MovementDelivery(List<Player> viewers, MovementState state) {}
}
