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
import java.util.concurrent.atomic.AtomicInteger;
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
    private final Set<Integer> forwardedHandles = ConcurrentHashMap.newKeySet();
    private final PacketAdapter eventPipe;

    ProtocolEntityPreview(JavaPlugin plugin, InteriorService interiors) {
        this.plugin = plugin;
        this.interiors = interiors;
        this.protocol = ProtocolLibrary.getProtocolManager();
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
                UUID sourceWorldId = event.getPlayer().getWorld().getUID();
                PacketType packetType = event.getPacketType();
                int token = System.identityHashCode(event.getPacket().getHandle());
                if (!forwardedHandles.add(token)) return;
                PacketContainer packet = MOVEMENT_PACKETS.contains(packetType)
                        || RELATIONSHIP_PACKETS.contains(packetType) ? null : event.getPacket().deepClone();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    forwardedHandles.remove(token);
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
                });
            }
        };
        protocol.addPacketListener(eventPipe);
    }

    @Override
    public void update(JarRecord jar, Set<UUID> exteriorViewers, Set<UUID> interiorViewers) {
        Set<MirrorKey> present = new HashSet<>();
        if (!exteriorViewers.isEmpty()) {
            int maximum = bounded("preview.exterior.max-entities", 64, 0, 256);
            sync(jar, Side.EXTERIOR, interiorEntities(jar, maximum), exteriorViewers, present);
        }
        if (!interiorViewers.isEmpty()) {
            int maximum = bounded("preview.interior.max-entities", 64, 0, 256);
            sync(jar, Side.INTERIOR, outsideEntities(jar, maximum), interiorViewers, present);
        }
        for (MirrorKey key : new ArrayList<>(mirrors.keySet())) {
            if (key.jarId.equals(jar.id()) && !present.contains(key)) removeMirror(key);
        }
    }

    private void sync(JarRecord jar, Side side, List<Entity> sources, Set<UUID> viewerIds, Set<MirrorKey> present) {
        Collection<Player> viewers = online(viewerIds);
        List<MirrorUpdate> updates = new ArrayList<>(sources.size());
        for (Entity source : sources) {
            MirrorKey key = new MirrorKey(jar.id(), side, source.getUniqueId());
            present.add(key);
            Mirror mirror = mirrors.computeIfAbsent(key, ignored -> new Mirror(key, source));
            updates.add(new MirrorUpdate(mirror, source, mapped(jar, side, source.getLocation()), scale(jar, side)));
        }
        updates.sort((left, right) -> Integer.compare(spawnPriority(left.source), spawnPriority(right.source)));
        for (MirrorUpdate update : updates) update.mirror.prepare(jar, update.source, update.scale, viewers);
        for (MirrorUpdate update : updates) update.mirror.spawnFor(update.source, update.location, viewers);
        for (MirrorUpdate update : updates) update.mirror.syncFor(update.source, update.location, viewers);
    }

    private static int spawnPriority(Entity source) {
        return ((CraftEntity) source).getHandle() instanceof Projectile ? 1 : 0;
    }

    private List<Entity> interiorEntities(JarRecord jar, int maximum) {
        if (maximum == 0) return List.of();
        CellLayout.Cell cell = interiors.cell(jar);
        int size = jar.scale();
        BoundingBox bounds = new BoundingBox(cell.minX(), cell.minY(), cell.minZ(),
                cell.minX() + size, cell.minY() + size, cell.minZ() + size);
        List<Entity> result = new ArrayList<>();
        for (Entity entity : interiors.world().getNearbyEntities(bounds, this::eligible)) {
            result.add(entity);
            if (result.size() >= maximum) break;
        }
        return result;
    }

    private List<Entity> outsideEntities(JarRecord jar, int maximum) {
        if (maximum == 0) return List.of();
        int radius = bounded("preview.interior.outside-radius", 6, 1, 24);
        Location center = jar.outsideLocation().clone().add(.5, .5, .5);
        List<Entity> result = new ArrayList<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius, this::eligible)) {
            if (entity.getLocation().distanceSquared(center) > radius * radius) continue;
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

    private static double scale(JarRecord jar, Side side) {
        return side == Side.EXTERIOR ? 1.0 / jar.scale() : jar.scale();
    }

    @Override
    public void remove(UUID jarId) {
        for (MirrorKey key : new ArrayList<>(mirrors.keySet())) if (key.jarId.equals(jarId)) removeMirror(key);
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
    }

    private void removeMirror(MirrorKey key) {
        Mirror mirror = mirrors.remove(key);
        if (mirror != null) mirror.destroyAll();
    }

    private void relay(UUID sourceWorldId, int sourceId, PacketContainer sourcePacket) {
        for (Mirror mirror : mirrors.values()) {
            if (!mirror.matchesStoredSource(sourceWorldId, sourceId)) continue;
            PacketContainer packet = sourcePacket.deepClone();
            packet.getIntegers().writeSafely(0, mirror.id);
            for (UUID viewerId : mirror.viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) protocol.sendServerPacket(viewer, packet, false);
            }
        }
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
        for (Mirror mirror : mirrors.values()) {
            if (!mirror.matchesSource(sourceWorldId, sourceId)) continue;
            Packet<ClientGamePacketListener> packet = new ClientboundProjectilePowerPacket(
                    mirror.id, original.getAccelerationPower() * mirror.worldScale);
            for (Player viewer : online(mirror.viewers)) send(viewer, packet);
        }
    }

    private void relayDamage(UUID sourceWorldId, int sourceId, PacketContainer sourcePacket) {
        if (!(sourcePacket.getHandle() instanceof ClientboundDamageEventPacket original)) return;
        for (Mirror mirror : mirrors.values()) {
            if (!mirror.matchesSource(sourceWorldId, sourceId)) continue;
            int causeId = remappedId(mirror.key, sourceWorldId, original.sourceCauseId());
            int directId = remappedId(mirror.key, sourceWorldId, original.sourceDirectId());
            Packet<ClientGamePacketListener> packet = new ClientboundDamageEventPacket(mirror.id,
                    original.sourceType(), causeId, directId, original.sourcePosition().map(mirror::mappedPoint));
            for (Player viewer : online(mirror.viewers)) send(viewer, packet);
        }
    }

    private void relayCollect(UUID sourceWorldId, int sourceId, PacketContainer sourcePacket) {
        if (!(sourcePacket.getHandle() instanceof ClientboundTakeItemEntityPacket original)) return;
        for (Mirror item : mirrors.values()) {
            if (!item.matchesStoredSource(sourceWorldId, sourceId)) continue;
            Mirror collector = mirrorBySourceId(item.key, sourceWorldId, original.getPlayerId());
            if (collector == null) continue;
            Packet<ClientGamePacketListener> packet = new ClientboundTakeItemEntityPacket(
                    item.id, collector.id, original.getAmount());
            for (Player viewer : online(item.viewers)) {
                if (collector.viewers.contains(viewer.getUniqueId())) send(viewer, packet);
            }
        }
    }

    private void relayMetadata(UUID sourceWorldId, int sourceId, PacketContainer sourcePacket) {
        if (!(sourcePacket.getHandle() instanceof ClientboundSetEntityDataPacket original)) return;
        for (Mirror mirror : mirrors.values()) {
            if (!mirror.matchesSource(sourceWorldId, sourceId)) continue;
            Entity source = Bukkit.getEntity(mirror.sourceUuid);
            if (source == null) continue;
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            Packet<ClientGamePacketListener> packet = new ClientboundSetEntityDataPacket(
                    mirror.id, hiddenNametagMetadata(handle, original.packedItems()));
            for (Player viewer : online(mirror.viewers)) send(viewer, packet);
        }
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
        protocol.sendServerPacket(viewer, PacketContainer.fromPacket(packet), false);
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
        }

        private void prepare(JarRecord jar, Entity source, double scale, Collection<Player> desired) {
            this.jar = jar;
            this.worldScale = scale;
            sourceEntityId = source.getEntityId();
            sourceWorldId = source.getWorld().getUID();
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

        private void spawnFor(Entity source, Location location, Collection<Player> desired) {
            for (Player viewer : desired) {
                if (!viewers.contains(viewer.getUniqueId()) && spawn(viewer, source, location)) {
                    viewers.add(viewer.getUniqueId());
                }
            }
        }

        private void syncFor(Entity source, Location location, Collection<Player> desired) {
            double renderScale = renderScale(source, worldScale);
            for (Player viewer : desired) {
                if (!viewers.contains(viewer.getUniqueId())) continue;
                syncState(viewer, source, location, worldScale, renderScale);
                syncRelationships(source, List.of(viewer));
            }
        }

        private boolean spawn(Player viewer, Entity source, Location location) {
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            ChunkMap.TrackedEntity tracked = handle.moonrise$getTrackedEntity();
            if (tracked == null) return false;
            Packet<ClientGamePacketListener> nativePacket = handle.getAddEntityPacket(tracked.serverEntity);
            if (!(nativePacket instanceof ClientboundAddEntityPacket original)) return false;
            Integer data = spawnData(viewer, handle, original.getData());
            if (data == null) return false;
            if (hiddenNameTeam != null) {
                send(viewer, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(hiddenNameTeam, true));
            }
            send(viewer, new ClientboundAddEntityPacket(id, uuid, location.getX(), location.getY(), location.getZ(),
                    handle.getXRot(), handle.getYRot(), handle.getType(), data,
                    handle.getDeltaMovement().scale(worldScale),
                    handle.getYHeadRot()));
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

        private void syncState(Player viewer, Entity source, Location location, double scale, double renderScale) {
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            send(viewer, new ClientboundSetEntityDataPacket(id,
                    hiddenNametagMetadata(handle, handle.getEntityData().packAll())));
            sendMovement(viewer, handle, location, scale);
            if (handle instanceof LivingEntity living) {
                Collection<AttributeInstance> attributes = living.getAttributes().getSyncableAttributes();
                if (!attributes.isEmpty()) send(viewer, new ClientboundUpdateAttributesPacket(id, attributes));
                AttributeInstance scaled = new AttributeInstance(Attributes.SCALE, ignored -> {});
                scaled.setBaseValue(renderScale);
                send(viewer, new ClientboundUpdateAttributesPacket(id, List.of(scaled)));
                send(viewer, equipment(living));
            }
        }

        private void sendMovement(Player viewer, net.minecraft.world.entity.Entity handle,
                                  Location location, double scale) {
            send(viewer, ClientboundTeleportEntityPacket.teleport(id,
                    new PositionMoveRotation(new Vec3(location.getX(), location.getY(), location.getZ()),
                            handle.getDeltaMovement().scale(scale), handle.getYRot(), handle.getXRot()),
                    Set.<Relative>of(), handle.onGround()));
            PacketContainer headRotation = PacketContainer.fromPacket(
                    new ClientboundRotateHeadPacket(handle, Mth.packDegrees(handle.getYHeadRot())));
            headRotation.getIntegers().write(0, id);
            protocol.sendServerPacket(viewer, headRotation, false);
            send(viewer, new ClientboundSetEntityMotionPacket(id, handle.getDeltaMovement().scale(scale)));
        }

        private void syncMovement(Entity source) {
            if (jar == null) return;
            Location location = mapped(jar, key.side, source.getLocation());
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            for (Player viewer : online(viewers)) sendMovement(viewer, handle, location, worldScale);
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
                        protocol.sendServerPacket(viewer, packet, false);
                    }
                }
                if (current.leashHolderId != -1
                        && (previous == null || current.leashHolderId != previous.leashHolderId)) {
                    if (current.leashHolderId != 0 || previous != null && previous.leashHolderId > 0) {
                        PacketContainer packet = PacketContainer.fromPacket(new ClientboundSetEntityLinkPacket(handle, null));
                        packet.getIntegers().write(0, id).write(1, current.leashHolderId);
                        protocol.sendServerPacket(viewer, packet, false);
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

        private double renderScale(Entity source, double worldScale) {
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            double sourceScale = handle instanceof LivingEntity living ? living.getScale() : 1.0;
            return Math.max(.0625, Math.min(16.0, sourceScale * worldScale));
        }

        private ClientboundSetEquipmentPacket equipment(LivingEntity living) {
            List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
            for (EquipmentSlot slot : List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET,
                    EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD)) {
                equipment.add(Pair.of(slot, living.getItemBySlot(slot).copy()));
            }
            return new ClientboundSetEquipmentPacket(id, equipment);
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
    private record MirrorUpdate(Mirror mirror, Entity source, Location location, double scale) {}
    private record MirrorKey(UUID jarId, Side side, UUID sourceId) {}
    private record RelationshipState(List<Integer> passengers, int leashHolderId) {}
}
