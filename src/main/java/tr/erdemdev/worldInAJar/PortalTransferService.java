package tr.erdemdev.worldInAJar;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PortalTransferService {
    private static final double APPROACH_EPSILON = 0.002;
    private static final long TRANSFER_COOLDOWN_TICKS = 5;

    private final JavaPlugin plugin;
    private final JarRepository repository;
    private final JarItems items;
    private final InteriorService interiors;
    private final Map<UUID, Location> previousItemLocations = new HashMap<>();
    private final Map<Entity, Long> cooldowns = new IdentityHashMap<>();
    private BukkitTask itemTask;
    private long tick;

    public PortalTransferService(JavaPlugin plugin, JarRepository repository, JarItems items,
                                 InteriorService interiors) {
        this.plugin = plugin;
        this.repository = repository;
        this.items = items;
        this.interiors = interiors;
    }

    public void start() {
        if (itemTask != null) itemTask.cancel();
        itemTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickItems, 1L, 1L);
    }

    public void stop() {
        if (itemTask != null) itemTask.cancel();
        itemTask = null;
        previousItemLocations.clear();
        cooldowns.clear();
    }

    public void move(LivingEntity entity, Location from, Location to) {
        if (onCooldown(entity)) return;
        if (to.getWorld() == interiors.world()) {
            JarRecord jar = containingJar(from, to);
            if (jar != null && approachesInteriorDoor(entity, jar, from, to)) transferOutside(entity, jar);
            return;
        }
        World world = to.getWorld();
        if (world == null) return;
        String worldName = world.getName();
        for (JarRecord jar : repository.withPortals()) {
            if (!jar.placed() || !jar.world().equals(worldName)) continue;
            if (approachesExteriorDoor(entity, jar, from, to)) {
                transferInside(entity, jar);
                return;
            }
        }
    }

    public boolean insertFromCursor(ItemStack cursor, JarRecord jar) {
        if (cursor == null || cursor.getType().isAir() || jar == null || jar.placed() || !jar.hasPortal()) return false;
        if (jar.id().equals(items.id(cursor))) return false;
        if (!interiors.ensureBuilt(jar)) return false;
        ItemStack remainder = insertIntoAttachedHoppers(jar, cursor);
        if (remainder != null) {
            Item inserted = interiors.world().dropItem(insideDestination(jar, null), remainder);
            inserted.setVelocity(inwardVelocity(jar.door(), 0.18));
        }
        return true;
    }

    private void tickItems() {
        tick++;
        Set<UUID> seen = new HashSet<>();
        for (JarRecord jar : repository.withPortals()) {
            if (jar.placed()) scanExteriorItems(jar, seen);
            scanInteriorItems(jar, seen);
        }
        previousItemLocations.keySet().retainAll(seen);
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= tick);
    }

    private void scanExteriorItems(JarRecord jar, Set<UUID> seen) {
        Location center = jar.outsideCenter();
        if (center == null) return;
        BoundingBox bounds = BoundingBox.of(center, jar.width() / 2.0 + 1.5,
                jar.height() / 2.0 + 1.75,
                jar.depth() / 2.0 + 1.5);
        for (Entity candidate : center.getWorld().getNearbyEntities(bounds, entity -> entity instanceof Item)) {
            Item item = (Item) candidate;
            Location to = item.getLocation();
            Location from = previousLocation(item, to, seen);
            if (!onCooldown(item) && approachesExteriorDoor(item, jar, from, to)) transferInside(item, jar);
        }
    }

    private void scanInteriorItems(JarRecord jar, Set<UUID> seen) {
        BoundingBox bounds = interiorDoorBounds(jar).expand(.75);
        for (Entity candidate : interiors.world().getNearbyEntities(bounds, entity -> entity instanceof Item)) {
            Item item = (Item) candidate;
            Location to = item.getLocation();
            Location from = previousLocation(item, to, seen);
            if (!onCooldown(item) && approachesInteriorDoor(item, jar, from, to)) transferOutside(item, jar);
        }
    }

    private Location previousLocation(Item item, Location current, Set<UUID> seen) {
        UUID id = item.getUniqueId();
        seen.add(id);
        Location previous = previousItemLocations.put(id, current.clone());
        if (previous != null && previous.getWorld() == current.getWorld()) return previous;
        return current.clone().subtract(item.getVelocity());
    }

    private JarRecord containingJar(Location from, Location to) {
        for (JarRecord jar : repository.withPortals()) {
            if (interiors.contains(jar, to) || interiors.contains(jar, from)) return jar;
        }
        return null;
    }

    private boolean approachesExteriorDoor(Entity entity, JarRecord jar, Location from, Location to) {
        if (!jar.hasPortal()) return false;
        BlockFace door = jar.door();
        int doorX = jar.x() + jar.doorX();
        int doorY = jar.y() + jar.doorY();
        int doorZ = jar.z() + jar.doorZ();
        double planeX = doorX + (door == BlockFace.EAST ? 1.0 : door == BlockFace.WEST ? 0.0 : .5);
        double planeZ = doorZ + (door == BlockFace.SOUTH ? 1.0 : door == BlockFace.NORTH ? 0.0 : .5);
        double distance = outwardDistance(to, planeX, planeZ, door);
        double maximum = Math.max(.85, entity.getWidth() / 2.0 + .45);
        if (distance < -.1 || distance > maximum
                || !overlapsExteriorOpening(entity, door, doorX, doorY, doorZ, to)) return false;
        return outwardProgress(from, to, door) < -APPROACH_EPSILON
                || outwardVelocity(entity, door) < -APPROACH_EPSILON;
    }

    private boolean approachesInteriorDoor(Entity entity, JarRecord jar, Location from, Location to) {
        if (!jar.hasPortal()) return false;
        if (to.getWorld() != interiors.world()) return false;
        CellLayout.Cell cell = interiors.cell(jar);
        BlockFace door = jar.door();
        if (!overlapsInteriorOpening(entity, jar, cell, to)) return false;
        double tileX = cell.minX() + jar.doorX() * jar.scale();
        double tileZ = cell.minZ() + jar.doorZ() * jar.scale();
        double planeX = door == BlockFace.WEST ? tileX + 1.0
                : door == BlockFace.EAST ? tileX + jar.scale() - 1.0 : to.getX();
        double planeZ = door == BlockFace.NORTH ? tileZ + 1.0
                : door == BlockFace.SOUTH ? tileZ + jar.scale() - 1.0 : to.getZ();
        double distance = -outwardDistance(to, planeX, planeZ, door);
        double maximum = Math.max(.85, entity.getWidth() / 2.0 + .45);
        if (distance < -.1 || distance > maximum) return false;
        return outwardProgress(from, to, door) > APPROACH_EPSILON
                || outwardVelocity(entity, door) > APPROACH_EPSILON;
    }

    private boolean overlapsExteriorOpening(Entity entity, BlockFace door,
                                            int doorX, int doorY, int doorZ, Location location) {
        double halfWidth = entity.getWidth() / 2.0;
        boolean lateral = door == BlockFace.NORTH || door == BlockFace.SOUTH
                ? overlaps(location.getX() - halfWidth, location.getX() + halfWidth,
                doorX, doorX + 1.0)
                : overlaps(location.getZ() - halfWidth, location.getZ() + halfWidth,
                doorZ, doorZ + 1.0);
        return lateral && overlaps(location.getY(), location.getY() + entity.getHeight(),
                doorY, doorY + 1.0);
    }

    private boolean overlapsInteriorOpening(Entity entity, JarRecord jar,
                                            CellLayout.Cell cell, Location location) {
        double minX = cell.minX() + jar.doorX() * jar.scale();
        double minY = cell.minY() + jar.doorY() * jar.scale();
        double minZ = cell.minZ() + jar.doorZ() * jar.scale();
        double halfWidth = entity.getWidth() / 2.0;
        boolean lateral = jar.door() == BlockFace.NORTH || jar.door() == BlockFace.SOUTH
                ? overlaps(location.getX() - halfWidth, location.getX() + halfWidth,
                minX + 1.0, minX + jar.scale() - 1.0)
                : overlaps(location.getZ() - halfWidth, location.getZ() + halfWidth,
                minZ + 1.0, minZ + jar.scale() - 1.0);
        return lateral && overlaps(location.getY(), location.getY() + entity.getHeight(),
                minY + 1.0, minY + jar.scale() - 1.0);
    }

    private BoundingBox interiorDoorBounds(JarRecord jar) {
        CellLayout.Cell cell = interiors.cell(jar);
        int minX = cell.minX() + jar.doorX() * jar.scale();
        int minY = cell.minY() + jar.doorY() * jar.scale();
        int minZ = cell.minZ() + jar.doorZ() * jar.scale();
        int size = jar.scale();
        return switch (jar.door()) {
            case NORTH -> new BoundingBox(minX + 1, minY + 1, minZ + 1,
                    minX + size - 1, minY + size - 1, minZ + 1.01);
            case SOUTH -> new BoundingBox(minX + 1, minY + 1, minZ + size - 1,
                    minX + size - 1, minY + size - 1, minZ + size - .99);
            case WEST -> new BoundingBox(minX + 1, minY + 1, minZ + 1,
                    minX + 1.01, minY + size - 1, minZ + size - 1);
            case EAST -> new BoundingBox(minX + size - 1, minY + 1, minZ + 1,
                    minX + size - .99, minY + size - 1, minZ + size - 1);
            default -> throw new IllegalStateException("Jar door must be horizontal");
        };
    }

    private void transferInside(Entity entity, JarRecord jar) {
        if (!interiors.ensureBuilt(jar)) return;
        if (entity instanceof Item item) {
            ItemStack remainder = insertIntoAttachedHoppers(jar, item.getItemStack());
            if (remainder == null) {
                item.remove();
                previousItemLocations.remove(item.getUniqueId());
                return;
            }
            item.setItemStack(remainder);
        }
        Vector velocity = entity.getVelocity().clone();
        if (!entity.teleport(insideDestination(jar, entity))) return;
        markTransferred(entity);
        entity.setVelocity(velocity);
    }

    /** Returns the portion that did not fit, or null when attached hoppers accepted everything. */
    private ItemStack insertIntoAttachedHoppers(JarRecord jar, ItemStack payload) {
        CellLayout.Cell cell = interiors.cell(jar);
        int tileX = cell.minX() + jar.doorX() * jar.scale();
        int tileY = cell.minY() + jar.doorY() * jar.scale();
        int tileZ = cell.minZ() + jar.doorZ() * jar.scale();
        ItemStack remainder = payload.clone();
        for (PortalAttachmentGeometry.BlockPosition position
                : PortalAttachmentGeometry.touchingInteriorFace(
                tileX, tileY, tileZ, jar.scale(), jar.door())) {
            BlockState state = interiors.world().getBlockAt(
                    position.x(), position.y(), position.z()).getState();
            if (!(state instanceof Hopper hopper)) continue;
            Map<Integer, ItemStack> overflow = hopper.getInventory().addItem(remainder);
            if (overflow.isEmpty()) return null;
            remainder = overflow.values().iterator().next();
        }
        return remainder;
    }

    private void transferOutside(Entity entity, JarRecord jar) {
        if (!jar.placed()) {
            transferFromCarriedJar(entity, jar);
            return;
        }
        Vector velocity = entity.getVelocity().clone();
        if (!entity.teleport(outsideDestination(jar, entity))) return;
        markTransferred(entity);
        entity.setVelocity(velocity);
    }

    private void transferFromCarriedJar(Entity entity, JarRecord jar) {
        Carrier carrier = findCarrier(jar.id());
        Location outside = carrier == null ? fallbackOutside(jar) : carrier.location();
        if (entity instanceof Item item) {
            ItemStack payload = item.getItemStack().clone();
            Map<Integer, ItemStack> overflow = carrier == null || carrier.inventory() == null
                    ? Map.of(0, payload) : carrier.inventory().addItem(payload);
            for (ItemStack remainder : overflow.values()) outside.getWorld().dropItem(outside, remainder);
            item.remove();
            previousItemLocations.remove(item.getUniqueId());
            return;
        }
        Vector velocity = entity.getVelocity().clone();
        if (!entity.teleport(outside.clone().add(0, .2, 0))) return;
        markTransferred(entity);
        entity.setVelocity(velocity);
    }

    private Location insideDestination(JarRecord jar, Entity entity) {
        CellLayout.Cell cell = interiors.cell(jar);
        double x = cell.minX() + jar.doorX() * jar.scale() + jar.scale() / 2.0;
        double z = cell.minZ() + jar.doorZ() * jar.scale() + jar.scale() / 2.0;
        double offset = entity == null ? .35 : entity.getWidth() / 2.0 + .25;
        switch (jar.door()) {
            case NORTH -> z = cell.minZ() + jar.doorZ() * jar.scale() + 1.0 + offset;
            case SOUTH -> z = cell.minZ() + (jar.doorZ() + 1) * jar.scale() - 1.0 - offset;
            case WEST -> x = cell.minX() + jar.doorX() * jar.scale() + 1.0 + offset;
            case EAST -> x = cell.minX() + (jar.doorX() + 1) * jar.scale() - 1.0 - offset;
            default -> throw new IllegalStateException("Jar door must be horizontal");
        }
        double y = cell.minY() + jar.doorY() * jar.scale()
                + (entity instanceof LivingEntity ? 1.05 : 1.4);
        return new Location(interiors.world(), x, y, z,
                entity == null ? 0 : entity.getYaw(), entity == null ? 0 : entity.getPitch());
    }

    private Location outsideDestination(JarRecord jar, Entity entity) {
        BlockFace door = jar.door();
        double offset = .5 + entity.getWidth() / 2.0 + .2;
        Location doorBlock = jar.doorBlockLocation();
        double y = doorBlock.getY() + (entity instanceof LivingEntity ? .05 : .5);
        double x = doorBlock.getX() + .5;
        double z = doorBlock.getZ() + .5;
        return new Location(entityWorld(jar), x + door.getModX() * offset, y,
                z + door.getModZ() * offset, entity.getYaw(), entity.getPitch());
    }

    private World entityWorld(JarRecord jar) {
        Location location = jar.outsideLocation();
        if (location == null) throw new IllegalStateException("The jar's outside world is not loaded");
        return location.getWorld();
    }

    private Carrier findCarrier(UUID jarId) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (contains(player.getInventory(), jarId)) return new Carrier(player.getInventory(), player.getLocation());
            if (contains(player.getEnderChest(), jarId)) return new Carrier(player.getEnderChest(), player.getLocation());
            if (jarId.equals(items.id(player.getItemOnCursor()))) return new Carrier(player.getInventory(), player.getLocation());
            Inventory top = player.getOpenInventory().getTopInventory();
            if (contains(top, jarId)) return new Carrier(top, inventoryLocation(top, player.getLocation()));
        }
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item item && jarId.equals(items.id(item.getItemStack()))) {
                    return new Carrier(null, item.getLocation());
                }
                if (entity instanceof InventoryHolder holder && contains(holder.getInventory(), jarId)) {
                    return new Carrier(holder.getInventory(), entity.getLocation());
                }
            }
            for (Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities(false)) {
                    if (state instanceof InventoryHolder holder && contains(holder.getInventory(), jarId)) {
                        return new Carrier(holder.getInventory(), state.getLocation().add(.5, 1, .5));
                    }
                }
            }
        }
        return null;
    }

    private boolean contains(Inventory inventory, UUID jarId) {
        for (ItemStack item : inventory.getContents()) if (jarId.equals(items.id(item))) return true;
        return false;
    }

    private static Location inventoryLocation(Inventory inventory, Location fallback) {
        Location location = inventory.getLocation();
        return location == null ? fallback : location.clone().add(.5, 1, .5);
    }

    private Location fallbackOutside(JarRecord jar) {
        Location oldLocation = jar.outsideLocation();
        if (oldLocation != null) return oldLocation.clone().add(.5, 1, .5);
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    private boolean onCooldown(Entity entity) {
        return cooldowns.getOrDefault(entity, 0L) > tick;
    }

    private void markTransferred(Entity entity) {
        cooldowns.put(entity, tick + TRANSFER_COOLDOWN_TICKS);
        previousItemLocations.remove(entity.getUniqueId());
    }

    static double outwardDistance(Location location, double planeX, double planeZ, BlockFace door) {
        return (location.getX() - planeX) * door.getModX() + (location.getZ() - planeZ) * door.getModZ();
    }

    static double outwardProgress(Location from, Location to, BlockFace door) {
        return (to.getX() - from.getX()) * door.getModX() + (to.getZ() - from.getZ()) * door.getModZ();
    }

    private static double outwardVelocity(Entity entity, BlockFace door) {
        Vector velocity = entity.getVelocity();
        return velocity.getX() * door.getModX() + velocity.getZ() * door.getModZ();
    }

    private static Vector inwardVelocity(BlockFace door, double speed) {
        return new Vector(-door.getModX() * speed, .05, -door.getModZ() * speed);
    }

    static boolean overlaps(double firstMin, double firstMax, double secondMin, double secondMax) {
        return firstMax > secondMin && firstMin < secondMax;
    }

    private record Carrier(Inventory inventory, Location location) {}
}
