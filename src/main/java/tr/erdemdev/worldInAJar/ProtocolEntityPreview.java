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
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/** Same-type, client-only entity mirrors transmitted through ProtocolLib. */
final class ProtocolEntityPreview implements EntityPreviewBackend {
    private static final AtomicInteger IDS = new AtomicInteger(1_900_000_000);
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
                PacketType.Play.Server.ENTITY_EQUIPMENT, PacketType.Play.Server.ENTITY_METADATA)) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Integer sourceId = event.getPacket().getIntegers().readSafely(0);
                if (sourceId == null) return;
                int token = System.identityHashCode(event.getPacket().getHandle());
                if (!forwardedHandles.add(token)) return;
                PacketContainer packet = event.getPacket().deepClone();
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    forwardedHandles.remove(token);
                    relay(sourceId, packet);
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
        for (Entity source : sources) {
            MirrorKey key = new MirrorKey(jar.id(), side, source.getUniqueId());
            present.add(key);
            Mirror mirror = mirrors.computeIfAbsent(key, ignored -> new Mirror(source));
            mirror.update(source, mapped(jar, side, source.getLocation()), scale(jar, side), viewers);
        }
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
        return entity.isValid() && !(entity instanceof Display) && !(entity instanceof Mannequin);
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
        for (Mirror mirror : mirrors.values()) mirror.viewers.remove(playerId);
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

    private void relay(int sourceId, PacketContainer sourcePacket) {
        for (Mirror mirror : mirrors.values()) {
            if (mirror.sourceEntityId != sourceId) continue;
            PacketContainer packet = sourcePacket.deepClone();
            packet.getIntegers().writeSafely(0, mirror.id);
            for (UUID viewerId : mirror.viewers) {
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) protocol.sendServerPacket(viewer, packet, false);
            }
        }
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
        private final UUID uuid;
        private final UUID sourceUuid;
        private final String playerName;
        private final PlayerTeam hiddenNameTeam;
        private final Set<UUID> viewers = new HashSet<>();
        private VirtualNametag nametag;
        private int sourceEntityId;

        private Mirror(Entity source) {
            this.uuid = source instanceof Player ? source.getUniqueId() : UUID.randomUUID();
            this.sourceUuid = source.getUniqueId();
            this.playerName = source instanceof Player player ? player.getName() : null;
            this.hiddenNameTeam = playerName == null ? null : hiddenNameTeam(playerName);
            this.sourceEntityId = source.getEntityId();
        }

        private void update(Entity source, Location location, double scale, Collection<Player> desired) {
            sourceEntityId = source.getEntityId();
            double renderScale = renderScale(source, scale);
            Location labelLocation = null;
            if (playerName != null) {
                labelLocation = nametagLocation(source, location, renderScale);
                if (nametag == null) {
                    nametag = new VirtualNametag(labelLocation, playerName, (float) renderScale);
                }
            }
            Set<UUID> desiredIds = new HashSet<>();
            for (Player viewer : desired) desiredIds.add(viewer.getUniqueId());
            for (UUID viewerId : new HashSet<>(viewers)) {
                if (desiredIds.contains(viewerId)) continue;
                Player viewer = Bukkit.getPlayer(viewerId);
                if (viewer != null) destroy(viewer);
                viewers.remove(viewerId);
            }
            for (Player viewer : desired) {
                if (!viewers.contains(viewer.getUniqueId()) && spawn(viewer, source, location)) {
                    viewers.add(viewer.getUniqueId());
                }
            }
            for (Player viewer : desired) {
                if (viewers.contains(viewer.getUniqueId())) syncState(viewer, source, location, scale, renderScale);
            }
            if (nametag != null && labelLocation != null) {
                nametag.update(labelLocation, (float) renderScale, online(viewers));
            }
        }

        private boolean spawn(Player viewer, Entity source, Location location) {
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            ChunkMap.TrackedEntity tracked = handle.moonrise$getTrackedEntity();
            if (tracked == null) return false;
            Packet<ClientGamePacketListener> nativePacket = handle.getAddEntityPacket(tracked.serverEntity);
            if (!(nativePacket instanceof ClientboundAddEntityPacket original)) return false;
            if (hiddenNameTeam != null) {
                send(viewer, ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(hiddenNameTeam, true));
            }
            send(viewer, new ClientboundAddEntityPacket(id, uuid, location.getX(), location.getY(), location.getZ(),
                    location.getPitch(), location.getYaw(), handle.getType(), original.getData(), Vec3.ZERO,
                    location.getYaw()));
            if (nametag != null) nametag.spawn(viewer);
            return true;
        }

        private void syncState(Player viewer, Entity source, Location location, double scale, double renderScale) {
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            send(viewer, new ClientboundSetEntityDataPacket(id, handle.getEntityData().packAll()));
            send(viewer, ClientboundTeleportEntityPacket.teleport(id,
                    new PositionMoveRotation(new Vec3(location.getX(), location.getY(), location.getZ()),
                            handle.getDeltaMovement(), location.getYaw(), location.getPitch()), Set.<Relative>of(), false));
            send(viewer, new ClientboundSetEntityMotionPacket(id, handle.getDeltaMovement().scale(scale)));
            if (handle instanceof LivingEntity living) {
                Collection<AttributeInstance> attributes = living.getAttributes().getSyncableAttributes();
                if (!attributes.isEmpty()) send(viewer, new ClientboundUpdateAttributesPacket(id, attributes));
                AttributeInstance scaled = new AttributeInstance(Attributes.SCALE, ignored -> {});
                scaled.setBaseValue(renderScale);
                send(viewer, new ClientboundUpdateAttributesPacket(id, List.of(scaled)));
                send(viewer, equipment(living));
            }
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

        private Location nametagLocation(Entity source, Location feet, double renderScale) {
            net.minecraft.world.entity.Entity handle = ((CraftEntity) source).getHandle();
            double sourceScale = handle instanceof LivingEntity living ? living.getScale() : 1.0;
            double baseHeight = handle.getBbHeight() / Math.max(.0625, sourceScale);
            return feet.clone().add(0, (baseHeight + .3) * renderScale, 0);
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
        }

        private void destroy(Player viewer) {
            send(viewer, new ClientboundRemoveEntitiesPacket(id));
            if (nametag != null) nametag.destroy(viewer);
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
    private record MirrorKey(UUID jarId, Side side, UUID sourceId) {}
}
