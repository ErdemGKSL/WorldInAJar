package tr.erdemdev.worldInAJar;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.BlockFace;
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
    private final Map<UUID, Long> cooldowns = new HashMap<>();
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
        if (entity instanceof Player || onCooldown(entity)) return;
        if (to.getWorld() == interiors.world()) {
            JarRecord jar = containingJar(from, to);
            if (jar != null && approachesInteriorDoor(entity, jar, from, to)) transferOutside(entity, jar);
            return;
        }
        for (JarRecord jar : repository.all()) {
            if (!jar.placed() || !sameWorld(jar, to)) continue;
            if (approachesExteriorDoor(entity, jar, from, to)) {
                transferInside(entity, jar);
                return;
            }
        }
    }

    public boolean insertFromCursor(ItemStack cursor, JarRecord jar) {
        if (cursor == null || cursor.getType().isAir() || jar == null || jar.placed()) return false;
        if (jar.id().equals(items.id(cursor))) return false;
        interiors.ensureBuilt(jar);
        Item inserted = interiors.world().dropItem(insideDestination(jar, null), cursor.clone());
        inserted.setVelocity(inwardVelocity(jar.door(), 0.18));
        return true;
    }

    private void tickItems() {
        tick++;
        Set<UUID> seen = new HashSet<>();
        for (JarRecord jar : repository.all()) {
            if (jar.placed()) scanExteriorItems(jar, seen);
            scanInteriorItems(jar, seen);
        }
        previousItemLocations.keySet().retainAll(seen);
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= tick);
    }

    private void scanExteriorItems(JarRecord jar, Set<UUID> seen) {
        Location center = jar.outsideLocation();
        if (center == null) return;
        BoundingBox bounds = BoundingBox.of(center.clone().add(.5, .5, .5), 2.0, 1.75, 2.0);
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
        for (JarRecord jar : repository.all()) {
            if (interiors.contains(jar, to) || interiors.contains(jar, from)) return jar;
        }
        return null;
    }

    private boolean approachesExteriorDoor(Entity entity, JarRecord jar, Location from, Location to) {
        Location outside = jar.outsideLocation();
        if (outside == null || to.getWorld() != outside.getWorld()) return false;
        BlockFace door = jar.door();
        double planeX = jar.x() + (door == BlockFace.EAST ? 1.0 : door == BlockFace.WEST ? 0.0 : .5);
        double planeZ = jar.z() + (door == BlockFace.SOUTH ? 1.0 : door == BlockFace.NORTH ? 0.0 : .5);
        double distance = outwardDistance(to, planeX, planeZ, door);
        double maximum = Math.max(.85, entity.getWidth() / 2.0 + .45);
        if (distance < -.1 || distance > maximum || !overlapsExteriorOpening(entity, jar, to)) return false;
        return outwardProgress(from, to, door) < -APPROACH_EPSILON
                || outwardVelocity(entity, door) < -APPROACH_EPSILON;
    }

    private boolean approachesInteriorDoor(Entity entity, JarRecord jar, Location from, Location to) {
        if (to.getWorld() != interiors.world() || !overlapsInteriorOpening(entity, jar, to)) return false;
        CellLayout.Cell cell = interiors.cell(jar);
        BlockFace door = jar.door();
        double planeX = door == BlockFace.WEST ? cell.minX() + 1.0
                : door == BlockFace.EAST ? cell.minX() + jar.scale() - 1.0 : to.getX();
        double planeZ = door == BlockFace.NORTH ? cell.minZ() + 1.0
                : door == BlockFace.SOUTH ? cell.minZ() + jar.scale() - 1.0 : to.getZ();
        double distance = -outwardDistance(to, planeX, planeZ, door);
        double maximum = Math.max(.85, entity.getWidth() / 2.0 + .45);
        if (distance < -.1 || distance > maximum) return false;
        return outwardProgress(from, to, door) > APPROACH_EPSILON
                || outwardVelocity(entity, door) > APPROACH_EPSILON;
    }

    private boolean overlapsExteriorOpening(Entity entity, JarRecord jar, Location location) {
        double halfWidth = entity.getWidth() / 2.0;
        boolean lateral = jar.door() == BlockFace.NORTH || jar.door() == BlockFace.SOUTH
                ? overlaps(location.getX() - halfWidth, location.getX() + halfWidth, jar.x(), jar.x() + 1.0)
                : overlaps(location.getZ() - halfWidth, location.getZ() + halfWidth, jar.z(), jar.z() + 1.0);
        return lateral && overlaps(location.getY(), location.getY() + entity.getHeight(), jar.y(), jar.y() + 1.0);
    }

    private boolean overlapsInteriorOpening(Entity entity, JarRecord jar, Location location) {
        CellLayout.Cell cell = interiors.cell(jar);
        double halfWidth = entity.getWidth() / 2.0;
        boolean lateral = jar.door() == BlockFace.NORTH || jar.door() == BlockFace.SOUTH
                ? overlaps(location.getX() - halfWidth, location.getX() + halfWidth,
                cell.minX() + 1.0, cell.minX() + jar.scale() - 1.0)
                : overlaps(location.getZ() - halfWidth, location.getZ() + halfWidth,
                cell.minZ() + 1.0, cell.minZ() + jar.scale() - 1.0);
        return lateral && overlaps(location.getY(), location.getY() + entity.getHeight(),
                cell.minY() + 1.0, cell.minY() + jar.scale() - 1.0);
    }

    private BoundingBox interiorDoorBounds(JarRecord jar) {
        CellLayout.Cell cell = interiors.cell(jar);
        int size = jar.scale();
        return switch (jar.door()) {
            case NORTH -> new BoundingBox(cell.minX() + 1, cell.minY() + 1, cell.minZ() + 1,
                    cell.minX() + size - 1, cell.minY() + size - 1, cell.minZ() + 1.01);
            case SOUTH -> new BoundingBox(cell.minX() + 1, cell.minY() + 1, cell.minZ() + size - 1,
                    cell.minX() + size - 1, cell.minY() + size - 1, cell.minZ() + size - .99);
            case WEST -> new BoundingBox(cell.minX() + 1, cell.minY() + 1, cell.minZ() + 1,
                    cell.minX() + 1.01, cell.minY() + size - 1, cell.minZ() + size - 1);
            case EAST -> new BoundingBox(cell.minX() + size - 1, cell.minY() + 1, cell.minZ() + 1,
                    cell.minX() + size - .99, cell.minY() + size - 1, cell.minZ() + size - 1);
            default -> throw new IllegalStateException("Jar door must be horizontal");
        };
    }

    private void transferInside(Entity entity, JarRecord jar) {
        interiors.ensureBuilt(jar);
        Vector velocity = entity.getVelocity().clone();
        if (!entity.teleport(insideDestination(jar, entity))) return;
        markTransferred(entity);
        entity.setVelocity(velocity);
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
        double x = cell.centerX(jar.scale()) + .5;
        double z = cell.centerZ(jar.scale()) + .5;
        double offset = entity == null ? .35 : entity.getWidth() / 2.0 + .25;
        switch (jar.door()) {
            case NORTH -> z = cell.minZ() + 1.0 + offset;
            case SOUTH -> z = cell.minZ() + jar.scale() - 1.0 - offset;
            case WEST -> x = cell.minX() + 1.0 + offset;
            case EAST -> x = cell.minX() + jar.scale() - 1.0 - offset;
            default -> throw new IllegalStateException("Jar door must be horizontal");
        }
        double y = cell.minY() + (entity instanceof LivingEntity ? 1.05 : 1.4);
        return new Location(interiors.world(), x, y, z,
                entity == null ? 0 : entity.getYaw(), entity == null ? 0 : entity.getPitch());
    }

    private Location outsideDestination(JarRecord jar, Entity entity) {
        BlockFace door = jar.door();
        double offset = .5 + entity.getWidth() / 2.0 + .2;
        double y = jar.y() + (entity instanceof LivingEntity ? .05 : .5);
        return new Location(entityWorld(jar), jar.x() + .5 + door.getModX() * offset, y,
                jar.z() + .5 + door.getModZ() * offset, entity.getYaw(), entity.getPitch());
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
        return cooldowns.getOrDefault(entity.getUniqueId(), 0L) > tick;
    }

    private void markTransferred(Entity entity) {
        cooldowns.put(entity.getUniqueId(), tick + TRANSFER_COOLDOWN_TICKS);
        previousItemLocations.remove(entity.getUniqueId());
    }

    private static boolean sameWorld(JarRecord jar, Location location) {
        return location.getWorld() != null && location.getWorld().getName().equals(jar.world());
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
