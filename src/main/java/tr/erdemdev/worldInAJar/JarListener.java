package tr.erdemdev.worldInAJar;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class JarListener implements Listener {
    private final WorldInAJar plugin;
    private final JarRepository repository;
    private final JarItems items;
    private final InteriorService interiors;
    private final PreviewService previews;

    public JarListener(WorldInAJar plugin, JarRepository repository, JarItems items,
                       InteriorService interiors, PreviewService previews) {
        this.plugin = plugin; this.repository = repository; this.items = items;
        this.interiors = interiors; this.previews = previews;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!items.isJar(event.getItemInHand())) {
            invalidateContaining(event.getBlockPlaced().getLocation());
            return;
        }
        BlockFace door = horizontalOpposite(event.getPlayer().getFacing());
        UUID storedId = items.id(event.getItemInHand());
        JarRecord jar = storedId == null ? null : repository.byId(storedId).orElse(null);
        for (JarRecord stale : new java.util.ArrayList<>(repository.all())) {
            if (stale.isAt(event.getBlockPlaced().getLocation())
                    && (jar == null || !stale.id().equals(jar.id()))) deleteJar(stale.id());
        }
        if (jar == null || jar.placed()) {
            int scale = Math.max(16, Math.min(256, plugin.getConfig().getInt("jar.scale", 30)));
            jar = repository.create(event.getPlayer().getUniqueId(), event.getBlockPlaced().getLocation(), door, scale);
        } else {
            jar = jar.movedTo(event.getBlockPlaced().getLocation(), door);
            repository.put(jar);
        }
        interiors.ensureBuilt(jar);
        previews.invalidate(jar);
        previews.refresh(jar);
        event.getPlayer().sendMessage("§aJar placed. Right-click the side facing you to enter.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        JarRecord jar = repository.at(event.getBlock().getLocation()).orElse(null);
        if (jar == null) {
            if (repository.all().stream().anyMatch(j -> interiors.isBoundary(j, event.getBlock().getLocation()))) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cThe glass boundary of a jar cannot be broken.");
                return;
            }
            invalidateContaining(event.getBlock().getLocation());
            return;
        }
        Player player = event.getPlayer();
        boolean allowed = player.hasPermission("worldinajar.admin")
                || (jar.owner().equals(player.getUniqueId()) && player.isSneaking());
        if (!allowed) {
            event.setCancelled(true);
            player.sendMessage("§cOnly the owner can pick up this jar while sneaking.");
            return;
        }
        event.setDropItems(false);
        JarRecord carried = jar.pickedUp();
        repository.put(carried);
        previews.seal(carried);
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), items.create(jar.id()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        JarRecord jar = repository.at(block.getLocation()).orElse(null);
        if (jar != null && event.getBlockFace() == jar.door()) {
            if (!event.getPlayer().hasPermission("worldinajar.enter")) {
                event.getPlayer().sendMessage("§cYou do not have permission to enter jars."); return;
            }
            if (interiors.isClogged(jar)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cThe jar door is clogged."); return;
            }
            event.setCancelled(true); interiors.enter(event.getPlayer(), jar); return;
        }
        if (interiors.isExitWall(event.getPlayer(), block.getLocation(), repository)) {
            event.setCancelled(true);
            if (interiors.exit(event.getPlayer(), repository) == InteriorService.ExitResult.CLOGGED)
                event.getPlayer().sendMessage("§cThe jar door is clogged. You cannot leave.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        interiors.forget(event.getPlayer());
        previews.forget(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        interiors.syncSession(event.getPlayer(), event.getPlayer().getLocation(), repository);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        org.bukkit.Location from = event.getFrom(), to = event.getTo();
        if (from.getWorld() == to.getWorld()
                && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) return;
        interiors.syncSession(event.getPlayer(), to, repository);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        interiors.syncSession(event.getPlayer(), event.getRespawnLocation(), repository);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        interiors.syncSession(event.getPlayer(), event.getPlayer().getLocation(), repository);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        if (items.isJar(event.getEntity().getItemStack()) || items.isJar(event.getTarget().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemRemoved(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof Item item) || !destructive(event.getCause())) return;
        UUID id = items.id(item.getItemStack());
        if (id != null) checkDeletedNextTick(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventorySlotChange(PlayerInventorySlotChangeEvent event) {
        UUID previous = items.id(event.getOldItemStack());
        if (previous == null || previous.equals(items.id(event.getNewItemStack()))) return;
        checkDeletedNextTick(previous);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onExplosion(BlockExplodeEvent event) { event.blockList().removeIf(b -> repository.at(b.getLocation()).isPresent()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) { event.blockList().removeIf(b -> repository.at(b.getLocation()).isPresent()); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) { if (event.getBlocks().stream().anyMatch(b -> repository.at(b.getLocation()).isPresent())) event.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) { if (event.getBlocks().stream().anyMatch(b -> repository.at(b.getLocation()).isPresent())) event.setCancelled(true); }

    private void invalidateContaining(org.bukkit.Location location) {
        for (JarRecord jar : repository.all()) {
            if (interiors.contains(jar, location)) { previews.invalidate(jar); return; }
        }
    }

    private void checkDeletedNextTick(UUID id) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            JarRecord jar = repository.byId(id).orElse(null);
            if (jar != null && !jar.placed() && !itemExists(id)) deleteJar(id);
        });
    }

    private boolean itemExists(UUID id) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (contains(player.getInventory().getContents(), id)
                    || contains(player.getEnderChest().getContents(), id)
                    || id.equals(items.id(player.getItemOnCursor()))
                    || contains(player.getOpenInventory().getTopInventory().getContents(), id)) return true;
        }
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                if (entity instanceof Item item && id.equals(items.id(item.getItemStack()))) return true;
                if (entity instanceof InventoryHolder holder && contains(holder.getInventory().getContents(), id)) return true;
            }
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (org.bukkit.block.BlockState state : chunk.getTileEntities(false)) {
                    if (state instanceof InventoryHolder holder && contains(holder.getInventory().getContents(), id)) return true;
                }
            }
        }
        return false;
    }

    private boolean contains(org.bukkit.inventory.ItemStack[] contents, UUID id) {
        for (org.bukkit.inventory.ItemStack item : contents) if (id.equals(items.id(item))) return true;
        return false;
    }

    private void deleteJar(UUID id) {
        JarRecord jar = repository.remove(id).orElse(null);
        if (jar == null) return;
        previews.remove(id);
        interiors.destroy(jar);
        plugin.getLogger().info("Deleted jar world " + id + ".");
    }

    private static boolean destructive(EntityRemoveEvent.Cause cause) {
        return switch (cause) {
            case DEATH, DESPAWN, EXPLODE, OUT_OF_WORLD, PLUGIN, DISCARD -> true;
            default -> false;
        };
    }

    private static BlockFace horizontalOpposite(BlockFace facing) {
        return switch (facing) {
            case NORTH, SOUTH, EAST, WEST -> facing.getOppositeFace();
            default -> BlockFace.NORTH;
        };
    }
}
