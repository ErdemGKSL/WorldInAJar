package tr.erdemdev.worldInAJar;

import io.papermc.paper.event.entity.EntityMoveEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.Material;
import org.bukkit.Location;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class JarListener implements Listener {
    private final WorldInAJar plugin;
    private final JarRepository repository;
    private final JarItems items;
    private final InteriorService interiors;
    private final PreviewService previews;
    private final PortalTransferService transfers;

    public JarListener(WorldInAJar plugin, JarRepository repository, JarItems items,
                       InteriorService interiors, PreviewService previews, PortalTransferService transfers) {
        this.plugin = plugin; this.repository = repository; this.items = items;
        this.interiors = interiors; this.previews = previews; this.transfers = transfers;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (containsJar(event.getInventory().getMatrix())) event.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (!containsJar(event.getInventory().getMatrix())) return;
        event.setCancelled(true);
        event.setCurrentItem(null);
    }

    private boolean containsJar(ItemStack[] matrix) {
        for (ItemStack item : matrix) if (items.isJar(item)) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (event instanceof BlockMultiPlaceEvent multiPlace) {
            for (org.bukkit.block.BlockState state : multiPlace.getReplacedBlockStates()) {
                previews.transportBlock(state.getLocation());
            }
        } else {
            previews.transportBlock(event.getBlockPlaced().getLocation());
        }
        if (!items.isJar(event.getItemInHand())) {
            return;
        }
        JarAssembly assembly = items.assembly(event.getItemInHand());
        if (assembly == null) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cThis jar has invalid assembly data.");
            return;
        }
        int scale = items.scale(event.getItemInHand());
        UUID storedId = items.id(event.getItemInHand());
        JarRecord jar = storedId == null ? null : repository.byId(storedId).orElse(null);
        if (jar != null) {
            assembly = jar.assembly();
            scale = jar.scale();
        }
        JarAssembly.Tile placementTile = assembly.tiles().stream()
                .min(java.util.Comparator.comparingInt(JarAssembly.Tile::z).thenComparingInt(JarAssembly.Tile::x))
                .orElseThrow();
        Location origin = event.getBlockPlaced().getLocation().clone().subtract(placementTile.x(), 0, placementTile.z());
        if (!canPlaceFootprint(origin.getBlock(), assembly, event.getBlockPlaced())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cThere is not enough empty space for this "
                    + assembly.width() + "x" + assembly.depth() + " jar assembly.");
            return;
        }
        BlockFace door = horizontalOpposite(event.getPlayer().getFacing());
        for (JarRecord stale : new java.util.ArrayList<>(repository.all())) {
            if (stale.isAt(event.getBlockPlaced().getLocation())
                    && (jar == null || !stale.id().equals(jar.id()))) deleteJar(stale.id());
        }
        if (jar == null || jar.placed()) {
            jar = repository.create(event.getPlayer().getUniqueId(), origin, door, scale, assembly);
        } else {
            jar = jar.movedTo(origin, door);
            repository.put(jar);
        }
        placeFootprint(jar, Material.GLASS);
        interiors.ensureBuilt(jar);
        previews.invalidate(jar);
        previews.refresh(jar);
        event.getPlayer().sendMessage("§aJar placed. Right-click the side facing you to enter.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;
        JarRecord jar = repository.at(event.getBlock().getLocation()).orElse(null);
        if (jar == null) {
            if (repository.all().stream().anyMatch(j -> interiors.isBoundary(j, event.getBlock().getLocation()))) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cThe glass boundary of a jar cannot be broken.");
                return;
            }
            return;
        }
        event.setDropItems(false);
        boolean detach = event.getPlayer().isSneaking();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (event.isCancelled()) return;
            JarRecord current = repository.byId(jar.id()).orElse(null);
            if (current == null || !current.placed() || !current.isAt(event.getBlock().getLocation())) return;
            if (detach && current.parts().size() > 1) {
                detachPart(event.getPlayer(), current, event.getBlock());
                return;
            }
            JarRecord carried = current.pickedUp();
            repository.put(carried);
            previews.seal(carried);
            placeFootprint(current, Material.AIR);
            event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(),
                    items.create(current.id(), current.assembly(), current.scale()));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakPreview(BlockBreakEvent event) {
        previews.transportBlock(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSneakInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        if (!event.getPlayer().isSneaking()) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        JarRecord jar = repository.at(block.getLocation()).orElse(null);
        if (jar == null) return;
        boolean clickedPortal = jar.hasPortal() && event.getBlockFace() == jar.door()
                && block.equals(jar.doorBlockLocation().getBlock());
        if (clickedPortal && empty(event.getItem())) {
            event.setCancelled(true);
            removePortal(event.getPlayer(), jar, block);
            return;
        }
        if (horizontal(event.getBlockFace()) && items.isJar(event.getItem())) {
            event.setCancelled(true);
            attach(event.getPlayer(), jar, block, event.getBlockFace(), event.getItem());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        JarRecord jar = repository.at(block.getLocation()).orElse(null);
        boolean clickedPortal = jar != null && jar.hasPortal() && event.getBlockFace() == jar.door()
                && block.equals(jar.doorBlockLocation().getBlock());
        if (jar != null && horizontal(event.getBlockFace()) && items.isPortalSide(event.getItem())) {
            event.setCancelled(true);
            installPortal(event.getPlayer(), jar, block, event.getBlockFace());
            return;
        }
        if (clickedPortal) {
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
        previews.preparePlayerTransition(event.getPlayer());
        interiors.syncSession(event.getPlayer(), event.getRespawnLocation(), repository);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getFrom().getWorld() != event.getTo().getWorld()) {
            previews.preparePlayerTransition(event.getPlayer());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        interiors.syncSession(event.getPlayer(), event.getPlayer().getLocation(), repository);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteriorWeather(WeatherChangeEvent event) {
        if (event.getWorld() == interiors.world() && event.toWeatherState()) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteriorThunder(ThunderChangeEvent event) {
        if (event.getWorld() == interiors.world() && event.toThunderState()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        if (items.isJar(event.getEntity().getItemStack()) || items.isJar(event.getTarget().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityMove(EntityMoveEvent event) {
        transfers.move(event.getEntity(), event.getFrom(), event.getTo());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        UUID id = items.id(event.getCurrentItem());
        if (id == null) return;
        JarRecord jar = repository.byId(id).orElse(null);
        if (!transfers.insertFromCursor(event.getCursor(), jar)) return;
        event.setCancelled(true);
        event.getView().setCursor(null);
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

    private void removePortal(Player player, JarRecord jar, Block block) {
        JarRecord updated = jar.withoutPortal();
        repository.put(updated);
        previews.refresh(updated);
        Location drop = block.getLocation().add(.5 + jar.door().getModX() * .6, .5,
                .5 + jar.door().getModZ() * .6);
        block.getWorld().dropItemNaturally(drop, items.createPortalSide());
        player.sendMessage("§aPortal side removed.");
    }

    private void installPortal(Player player, JarRecord jar, Block block, BlockFace face) {
        if (jar.hasPortal()) {
            player.sendMessage("§cThis jar already has a portal side. Remove it first.");
            return;
        }
        int tileX = block.getX() - jar.x(), tileZ = block.getZ() - jar.z();
        if (jar.assembly().contains(tileX + face.getModX(), tileZ + face.getModZ())) {
            player.sendMessage("§cA portal side can only be installed on an exposed face.");
            return;
        }
        JarRecord updated = jar.withPortal(tileX, tileZ, face);
        repository.put(updated);
        consumeMainHand(player);
        previews.refresh(updated);
        player.sendMessage("§aPortal side installed.");
    }

    private void consumeMainHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else {
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().setItemInMainHand(hand);
        }
    }

    private void attach(Player player, JarRecord target, Block clicked, BlockFace face, ItemStack held) {
        JarAssembly targetAssembly = target.assembly();
        int targetTileX = clicked.getX() - target.x(), targetTileZ = clicked.getZ() - target.z();
        if (targetAssembly.contains(targetTileX + face.getModX(), targetTileZ + face.getModZ())) {
            player.sendMessage("§cThat side is already joined to this jar.");
            return;
        }
        if (target.hasPortal() && target.doorX() == targetTileX && target.doorZ() == targetTileZ
                && target.door() == face) {
            player.sendMessage("§cRemove the portal side before attaching a jar there.");
            return;
        }

        UUID sourceId = items.id(held);
        JarRecord source = sourceId == null ? null : repository.byId(sourceId).orElse(null);
        if (sourceId != null && (source == null || source.placed() || source.id().equals(target.id()))) {
            player.sendMessage("§cThat jar cannot be attached.");
            return;
        }
        JarAssembly sourceAssembly = source == null ? items.assembly(held) : source.assembly();
        if (sourceAssembly == null) return;
        if (source != null && source.scale() != target.scale()) {
            player.sendMessage("§cJars created with different interior scales cannot be attached.");
            return;
        }

        AttachmentPlan plan = findAttachment(target, clicked, face, sourceAssembly);
        if (plan == null) {
            player.sendMessage("§cThe held jar does not fit on that side or exceeds the size limit.");
            return;
        }
        int sourceOriginX = plan.sourceOriginX(), sourceOriginZ = plan.sourceOriginZ();
        JarAssembly global = plan.global(), combined = global.normalized();

        BlockFace portalFace = target.door();
        int portalX = target.hasPortal() ? target.x() + target.doorX() - global.minX() : 0;
        int portalZ = target.hasPortal() ? target.z() + target.doorZ() - global.minZ() : 0;
        boolean sourcePortalAdopted = false;
        if (!target.hasPortal() && source != null && source.hasPortal()) {
            int globalPortalX = sourceOriginX + source.doorX();
            int globalPortalZ = sourceOriginZ + source.doorZ();
            if (!global.contains(globalPortalX + source.door().getModX(),
                    globalPortalZ + source.door().getModZ())) {
                portalFace = source.door();
                portalX = globalPortalX - global.minX();
                portalZ = globalPortalZ - global.minZ();
                sourcePortalAdopted = true;
            }
        }

        JarRecord oldTarget = target;
        JarRecord combinedRecord = repository.replaceInNewCell(oldTarget, target.world(), global.minX(), target.y(),
                global.minZ(), portalFace, portalX, portalZ, combined, true);
        List<InteriorService.InteriorCopy> copies = new ArrayList<>();
        copies.add(new InteriorService.InteriorCopy(oldTarget,
                target.x() - global.minX(), target.z() - global.minZ(), null));
        if (source != null) copies.add(new InteriorService.InteriorCopy(source,
                sourceOriginX - global.minX(), sourceOriginZ - global.minZ(), null));
        interiors.copyRegions(combinedRecord, copies);
        interiors.destroyCell(oldTarget);
        if (source != null) {
            repository.remove(source.id());
            previews.remove(source.id());
            interiors.destroy(source);
            if (source.hasPortal() && !sourcePortalAdopted) {
                clicked.getWorld().dropItemNaturally(clicked.getLocation().add(.5, .5, .5),
                        items.createPortalSide());
            }
        }
        for (JarAssembly.Tile tile : sourceAssembly.tiles()) {
            clicked.getWorld().getBlockAt(sourceOriginX + tile.x(), target.y(), sourceOriginZ + tile.z())
                    .setType(Material.GLASS, false);
        }
        player.getInventory().setItemInMainHand(null);
        previews.refresh(combinedRecord);
        player.sendMessage("§aJar attached. Shared interior walls were opened.");
    }

    private AttachmentPlan findAttachment(JarRecord target, Block clicked, BlockFace face,
                                          JarAssembly sourceAssembly) {
        List<JarAssembly.Tile> contacts = sourceAssembly.tiles().stream()
                .filter(tile -> !sourceAssembly.contains(tile.x() - face.getModX(), tile.z() - face.getModZ()))
                .sorted(java.util.Comparator.comparingInt(JarAssembly.Tile::z).thenComparingInt(JarAssembly.Tile::x))
                .toList();
        int maximum = Math.max(1, plugin.getConfig().getInt("jar.max-combined-size", 9));
        for (JarAssembly.Tile contact : contacts) {
            int sourceOriginX = clicked.getX() + face.getModX() - contact.x();
            int sourceOriginZ = clicked.getZ() + face.getModZ() - contact.z();
            List<JarPart> globalParts = new ArrayList<>();
            target.parts().forEach(part -> globalParts.add(part.translated(target.x(), target.z())));
            sourceAssembly.parts().forEach(part -> globalParts.add(part.translated(sourceOriginX, sourceOriginZ)));
            JarAssembly global;
            try {
                global = new JarAssembly(globalParts);
            } catch (IllegalArgumentException exception) {
                continue;
            }
            if (target.hasPortal()) {
                int portalX = target.x() + target.doorX();
                int portalZ = target.z() + target.doorZ();
                if (global.contains(portalX + target.door().getModX(),
                        portalZ + target.door().getModZ())) continue;
            }
            JarAssembly combined = global.normalized();
            if (combined.width() > maximum || combined.depth() > maximum
                    || combined.width() * target.scale() > interiors.maximumInteriorSize()
                    || combined.depth() * target.scale() > interiors.maximumInteriorSize()) continue;
            boolean clear = true;
            for (JarAssembly.Tile tile : sourceAssembly.tiles()) {
                Block destination = clicked.getWorld().getBlockAt(
                        sourceOriginX + tile.x(), target.y(), sourceOriginZ + tile.z());
                if (!destination.isEmpty() || repository.at(destination.getLocation()).isPresent()) {
                    clear = false;
                    break;
                }
            }
            if (clear) return new AttachmentPlan(sourceOriginX, sourceOriginZ, global);
        }
        return null;
    }

    private record AttachmentPlan(int sourceOriginX, int sourceOriginZ, JarAssembly global) {}

    private void detachPart(Player player, JarRecord oldJar, Block clicked) {
        int tileX = clicked.getX() - oldJar.x(), tileZ = clicked.getZ() - oldJar.z();
        JarPart detachedPart = oldJar.assembly().partAt(tileX, tileZ);
        if (detachedPart == null) return;

        List<JarPart> remainingParts = new ArrayList<>(oldJar.parts());
        remainingParts.remove(detachedPart);
        JarAssembly remainingRaw = new JarAssembly(remainingParts);
        int shiftX = remainingRaw.minX(), shiftZ = remainingRaw.minZ();
        JarAssembly remaining = remainingRaw.normalized();
        JarAssembly detached = JarAssembly.rectangle(detachedPart.width(), detachedPart.depth());
        Set<JarAssembly.Tile> detachedTiles = new java.util.HashSet<>();
        for (int x = detachedPart.x(); x < detachedPart.x() + detachedPart.width(); x++) {
            for (int z = detachedPart.z(); z < detachedPart.z() + detachedPart.depth(); z++) {
                detachedTiles.add(new JarAssembly.Tile(x, z));
            }
        }
        Set<JarAssembly.Tile> remainingTiles = new java.util.HashSet<>(oldJar.assembly().tiles());
        remainingTiles.removeAll(detachedTiles);

        boolean portalDetached = oldJar.hasPortal()
                && detachedTiles.contains(new JarAssembly.Tile(oldJar.doorX(), oldJar.doorZ()));
        BlockFace remainingPortal = oldJar.hasPortal() && !portalDetached ? oldJar.door() : null;
        int remainingPortalX = remainingPortal == null ? 0 : oldJar.doorX() - shiftX;
        int remainingPortalZ = remainingPortal == null ? 0 : oldJar.doorZ() - shiftZ;
        BlockFace detachedPortal = portalDetached ? oldJar.door() : null;
        int detachedPortalX = detachedPortal == null ? 0 : oldJar.doorX() - detachedPart.x();
        int detachedPortalZ = detachedPortal == null ? 0 : oldJar.doorZ() - detachedPart.z();

        JarRecord remainingRecord = repository.replaceInNewCell(oldJar, oldJar.world(),
                oldJar.x() + shiftX, oldJar.y(), oldJar.z() + shiftZ,
                remainingPortal, remainingPortalX, remainingPortalZ, remaining, true);
        JarRecord detachedRecord = repository.createCarried(oldJar.owner(), oldJar.world(), clicked.getX(),
                oldJar.y(), clicked.getZ(), detachedPortal, detachedPortalX, detachedPortalZ,
                oldJar.scale(), detached);
        interiors.copyRegions(detachedRecord, List.of(new InteriorService.InteriorCopy(
                oldJar, -detachedPart.x(), -detachedPart.z(), detachedTiles)));
        interiors.copyRegions(remainingRecord, List.of(new InteriorService.InteriorCopy(
                oldJar, -shiftX, -shiftZ, remainingTiles)));
        interiors.destroyCell(oldJar);

        Location oldOrigin = oldJar.outsideLocation();
        for (JarAssembly.Tile tile : detachedTiles) {
            Block block = oldOrigin.getBlock().getRelative(tile.x(), 0, tile.z());
            block.setType(Material.AIR, false);
            previews.transportBlock(block.getLocation());
        }
        previews.refresh(remainingRecord);
        previews.seal(detachedRecord);
        clicked.getWorld().dropItemNaturally(clicked.getLocation(),
                items.create(detachedRecord.id(), detachedRecord.assembly(), detachedRecord.scale()));
        player.sendMessage("§aJar part detached.");
    }

    private boolean canPlaceFootprint(Block origin, JarAssembly assembly, Block alreadyPlaced) {
        for (JarAssembly.Tile tile : assembly.tiles()) {
            Block block = origin.getRelative(tile.x(), 0, tile.z());
            if (!block.equals(alreadyPlaced) && !block.isEmpty()) return false;
            JarRecord occupying = repository.at(block.getLocation()).orElse(null);
            if (occupying != null) return false;
        }
        return true;
    }

    private void placeFootprint(JarRecord jar, Material material) {
        Location origin = jar.outsideLocation();
        if (origin == null) return;
        for (JarAssembly.Tile tile : jar.assembly().tiles()) {
            Block block = origin.getBlock().getRelative(tile.x(), 0, tile.z());
            block.setType(material, false);
            previews.transportBlock(block.getLocation());
        }
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

    private static boolean horizontal(BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH
                || face == BlockFace.EAST || face == BlockFace.WEST;
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
