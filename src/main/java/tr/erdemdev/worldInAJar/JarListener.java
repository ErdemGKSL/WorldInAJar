package tr.erdemdev.worldInAJar;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

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
        if (jar == null) {
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
        previews.remove(jar.id());
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
            event.setCancelled(true); interiors.enter(event.getPlayer(), jar); return;
        }
        if (block.getType() == Material.GLASS && interiors.isExitWall(event.getPlayer(), block.getLocation(), repository)) {
            event.setCancelled(true); interiors.exit(event.getPlayer(), repository);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        interiors.forget(event.getPlayer());
        previews.forget(event.getPlayer());
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

    private static BlockFace horizontalOpposite(BlockFace facing) {
        return switch (facing) {
            case NORTH, SOUTH, EAST, WEST -> facing.getOppositeFace();
            default -> BlockFace.NORTH;
        };
    }
}
