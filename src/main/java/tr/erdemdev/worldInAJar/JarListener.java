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
import org.bukkit.event.player.PlayerToggleSneakEvent;
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
    private final SpectatorService spectators;
    private final Set<UUID> combinationsInProgress = new java.util.HashSet<>();

    public JarListener(WorldInAJar plugin, JarRepository repository, JarItems items,
                       InteriorService interiors, PreviewService previews, PortalTransferService transfers,
                       SpectatorService spectators) {
        this.plugin = plugin; this.repository = repository; this.items = items;
        this.interiors = interiors; this.previews = previews; this.transfers = transfers;
        this.spectators = spectators;
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
        if (jar != null && combinationsInProgress.contains(jar.id())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cThat jar is still being changed.");
            return;
        }
        if (jar != null) {
            assembly = jar.assembly();
            scale = jar.scale();
        }
        JarAssembly.Cell placementCell = assembly.cells().stream()
                .min(java.util.Comparator.comparingInt(JarAssembly.Cell::y)
                        .thenComparingInt(JarAssembly.Cell::z).thenComparingInt(JarAssembly.Cell::x))
                .orElseThrow();
        Location origin = event.getBlockPlaced().getLocation().clone()
                .subtract(placementCell.x(), placementCell.y(), placementCell.z());
        if (!canPlaceFootprint(origin.getBlock(), assembly, event.getBlockPlaced())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cThere is not enough empty space for this "
                    + assembly.width() + "x" + assembly.height() + "x" + assembly.depth() + " jar assembly.");
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
        JarRecord placedJar = jar;
        interiors.ensureBuiltAsync(placedJar, () -> {
            previews.invalidate(placedJar);
            previews.refresh(placedJar);
            spectators.placed(placedJar);
        });
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
        if (combinationsInProgress.contains(jar.id()) || !interiors.isReady(jar)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cThat jar world is not ready yet. Please wait.");
            return;
        }
        boolean detach = event.getPlayer().isSneaking();
        if (detach && jar.parts().size() > 1 && !interiors.ensureBuilt(jar)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cThat jar world is not ready yet. Please wait.");
            return;
        }
        event.setDropItems(false);
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
        if (combinationsInProgress.contains(jar.id()) || !interiors.isReady(jar)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cThat jar world is not ready yet. Please wait.");
            return;
        }
        boolean clickedPortal = jar.hasPortal() && event.getBlockFace() == jar.door()
                && block.equals(jar.doorBlockLocation().getBlock());
        if (clickedPortal && empty(event.getItem())) {
            event.setCancelled(true);
            removePortal(event.getPlayer(), jar, block);
            return;
        }
        if (directional(event.getBlockFace()) && items.isJar(event.getItem())) {
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
        if (jar != null && (combinationsInProgress.contains(jar.id()) || !interiors.isReady(jar))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cThat jar world is not ready yet. Please wait.");
            return;
        }
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
            if (!interiors.ensureBuilt(jar)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cThat jar world is not ready yet. Please wait."); return;
            }
            if (interiors.isClogged(jar)) {
                event.setCancelled(true);
                event.getPlayer().sendMessage("§cThe jar door is clogged."); return;
            }
            event.setCancelled(true); interiors.enter(event.getPlayer(), jar); return;
        }
        if (interiors.isExitWall(event.getPlayer(), block.getLocation(), repository)) {
            event.setCancelled(true);
            JarRecord inside = interiors.syncSession(event.getPlayer(), event.getPlayer().getLocation(), repository);
            if (inside != null && !inside.placed()) {
                SpectatorService.StartResult result = spectators.tryStart(event.getPlayer(), inside);
                if (result == SpectatorService.StartResult.STARTED) return;
                if (result == SpectatorService.StartResult.UNAVAILABLE) {
                    event.getPlayer().sendMessage("§cSpectator mode is not available right now.");
                    return;
                }
            }
            if (interiors.exit(event.getPlayer(), repository) == InteriorService.ExitResult.CLOGGED)
                event.getPlayer().sendMessage("§cThe jar door is clogged. You cannot leave.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        spectators.onQuit(event.getPlayer());
        interiors.forget(event.getPlayer());
        previews.forget(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (spectators.hasRecovery(event.getPlayer())) {
            spectators.onJoin(event.getPlayer());
            return;
        }
        interiors.syncSession(event.getPlayer(), event.getPlayer().getLocation(), repository);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking() || !spectators.hasRecovery(event.getPlayer())) return;
        event.setCancelled(true);
        spectators.restore(event.getPlayer());
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
        if (!event.hasChangedPosition()) return;
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
            if (combinationsInProgress.contains(id)) return;
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
        JarRecord jar = repository.byId(id).orElse(null);
        if (jar == null) return;
        spectators.deleted(jar);
        repository.remove(id);
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
        int cellX = block.getX() - jar.x(), cellY = block.getY() - jar.y();
        int cellZ = block.getZ() - jar.z();
        if (jar.assembly().contains(cellX + face.getModX(), cellY, cellZ + face.getModZ())) {
            player.sendMessage("§cA portal side can only be installed on an exposed face.");
            return;
        }
        JarRecord updated = jar.withPortal(cellX, cellY, cellZ, face);
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
        if (!interiors.ensureBuilt(target)) {
            player.sendMessage("§cThe target jar world is not ready yet. Please wait.");
            return;
        }
        JarAssembly targetAssembly = target.assembly();
        int targetCellX = clicked.getX() - target.x(), targetCellY = clicked.getY() - target.y();
        int targetCellZ = clicked.getZ() - target.z();
        if (targetAssembly.contains(targetCellX + face.getModX(), targetCellY + face.getModY(),
                targetCellZ + face.getModZ())) {
            player.sendMessage("§cThat side is already joined to this jar.");
            return;
        }
        if (target.hasPortal() && target.doorX() == targetCellX && target.doorY() == targetCellY
                && target.doorZ() == targetCellZ
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
        if (source != null && !interiors.ensureBuilt(source)) {
            player.sendMessage("§cThe held jar world is not ready yet. Please wait before combining it.");
            return;
        }
        if (!reserveCombination(target.id(), sourceId)) {
            player.sendMessage("§cOne of those jars is already being changed.");
            return;
        }
        ItemStack heldSnapshot = held.clone();
        int maximum = Math.max(1, plugin.getConfig().getInt("jar.max-combined-size", 9));
        int maximumInteriorSize = interiors.maximumInteriorSize();
        int maximumInteriorHeight = interiors.maximumInteriorHeight();
        int clickedX = clicked.getX(), clickedY = clicked.getY(), clickedZ = clicked.getZ();
        JarRecord plannedSource = source;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<CombinationPlanner.AttachmentCandidate> candidates;
            try {
                candidates = CombinationPlanner.attachmentCandidates(target,
                        clickedX, clickedY, clickedZ, face, sourceAssembly,
                        maximum, maximumInteriorSize, maximumInteriorHeight);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not calculate jar attachment: " + exception.getMessage());
                candidates = List.of();
            }
            List<CombinationPlanner.AttachmentCandidate> completed = candidates;
            plugin.getServer().getScheduler().runTask(plugin, () -> applyAttachment(player, target,
                    plannedSource, sourceId, sourceAssembly, clicked, heldSnapshot, completed));
        });
    }

    private void applyAttachment(Player player, JarRecord target, JarRecord source, UUID sourceId,
                                 JarAssembly sourceAssembly, Block clicked, ItemStack heldSnapshot,
                                 List<CombinationPlanner.AttachmentCandidate> candidates) {
        JarRecord currentTarget = repository.byId(target.id()).orElse(null);
        JarRecord currentSource = sourceId == null ? null : repository.byId(sourceId).orElse(null);
        if (!player.isOnline() || !target.equals(currentTarget)
                || sourceId != null && !source.equals(currentSource)
                || !heldSnapshot.isSimilar(player.getInventory().getItemInMainHand())) {
            releaseCombination(target.id(), sourceId);
            return;
        }
        CombinationPlanner.AttachmentCandidate plan = candidates.stream()
                .filter(candidate -> attachmentSpaceClear(clicked, sourceAssembly, candidate))
                .findFirst().orElse(null);
        if (plan == null) {
            releaseCombination(target.id(), sourceId);
            player.sendMessage("§cThe held jar does not fit on that side or exceeds the size limit.");
            return;
        }

        int sourceOriginX = plan.sourceOriginX();
        int sourceOriginY = plan.sourceOriginY();
        int sourceOriginZ = plan.sourceOriginZ();
        JarAssembly global = plan.global();
        JarAssembly combined = global.normalized();
        BlockFace portalFace = target.door();
        int portalX = target.hasPortal() ? target.x() + target.doorX() - global.minX() : 0;
        int portalY = target.hasPortal() ? target.y() + target.doorY() - global.minY() : 0;
        int portalZ = target.hasPortal() ? target.z() + target.doorZ() - global.minZ() : 0;
        boolean sourcePortalAdopted = false;
        if (!target.hasPortal() && source != null && source.hasPortal()) {
            int globalPortalX = sourceOriginX + source.doorX();
            int globalPortalY = sourceOriginY + source.doorY();
            int globalPortalZ = sourceOriginZ + source.doorZ();
            if (!global.contains(globalPortalX + source.door().getModX(), globalPortalY,
                    globalPortalZ + source.door().getModZ())) {
                portalFace = source.door();
                portalX = globalPortalX - global.minX();
                portalY = globalPortalY - global.minY();
                portalZ = globalPortalZ - global.minZ();
                sourcePortalAdopted = true;
            }
        }

        JarRecord combinedRecord = repository.replaceInNewCell(target, target.world(), global.minX(),
                global.minY(), global.minZ(), portalFace, portalX, portalY, portalZ, combined, true);
        List<InteriorService.InteriorCopy> copies = new ArrayList<>();
        copies.add(new InteriorService.InteriorCopy(target,
                target.x() - global.minX(), target.y() - global.minY(),
                target.z() - global.minZ(), null));
        int sourceOffsetX = sourceOriginX - global.minX();
        int sourceOffsetY = sourceOriginY - global.minY();
        int sourceOffsetZ = sourceOriginZ - global.minZ();
        if (source != null) copies.add(new InteriorService.InteriorCopy(source,
                sourceOffsetX, sourceOffsetY, sourceOffsetZ, null));
        for (JarAssembly.Cell cell : sourceAssembly.cells()) {
            clicked.getWorld().getBlockAt(sourceOriginX + cell.x(), sourceOriginY + cell.y(),
                            sourceOriginZ + cell.z()).setType(Material.GLASS, false);
        }
        if (source != null) spectators.prepareAttachment(source, combinedRecord,
                sourceOffsetX, sourceOffsetY, sourceOffsetZ);
        player.getInventory().setItemInMainHand(null);
        previews.remove(target.id());
        boolean returnPortalSide = source != null && source.hasPortal() && !sourcePortalAdopted;
        interiors.copyRegionsAsync(combinedRecord, copies, () -> {
            interiors.destroyCell(target);
            if (source != null) {
                spectators.placed(combinedRecord);
                repository.remove(source.id());
                previews.remove(source.id());
                interiors.destroy(source);
            }
            releaseCombination(target.id(), sourceId);
            previews.refresh(combinedRecord);
        });
        if (returnPortalSide) {
            clicked.getWorld().dropItemNaturally(clicked.getLocation().add(.5, .5, .5),
                    items.createPortalSide());
        }
        player.sendMessage("§aJar attached. Its interior is being prepared in the background.");
    }

    private boolean attachmentSpaceClear(Block clicked, JarAssembly sourceAssembly,
                                         CombinationPlanner.AttachmentCandidate candidate) {
        for (JarAssembly.Cell cell : sourceAssembly.cells()) {
            int destinationY = candidate.sourceOriginY() + cell.y();
            if (destinationY < clicked.getWorld().getMinHeight()
                    || destinationY >= clicked.getWorld().getMaxHeight()) return false;
            Block destination = clicked.getWorld().getBlockAt(candidate.sourceOriginX() + cell.x(),
                    destinationY, candidate.sourceOriginZ() + cell.z());
            if (!destination.isEmpty()) return false;
        }
        return true;
    }

    private void detachPart(Player player, JarRecord oldJar, Block clicked) {
        int cellX = clicked.getX() - oldJar.x(), cellY = clicked.getY() - oldJar.y();
        int cellZ = clicked.getZ() - oldJar.z();
        if (!reserveCombination(oldJar.id(), null)) {
            player.sendMessage("§cThat jar is already being changed.");
            return;
        }
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            CombinationPlanner.DetachmentPlan plan;
            try {
                plan = CombinationPlanner.detach(oldJar, cellX, cellY, cellZ);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not calculate jar detachment: " + exception.getMessage());
                plan = null;
            }
            CombinationPlanner.DetachmentPlan completed = plan;
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> applyDetachment(player, oldJar, clicked, completed));
        });
    }

    private void applyDetachment(Player player, JarRecord oldJar, Block clicked,
                                 CombinationPlanner.DetachmentPlan plan) {
        if (plan == null || !oldJar.equals(repository.byId(oldJar.id()).orElse(null))) {
            releaseCombination(oldJar.id(), null);
            return;
        }
        JarPart detachedPart = plan.detachedPart();
        JarRecord remainingRecord = repository.replaceInNewCell(oldJar, oldJar.world(),
                oldJar.x() + plan.shiftX(), oldJar.y() + plan.shiftY(), oldJar.z() + plan.shiftZ(),
                plan.remainingPortal(), plan.remainingPortalX(), plan.remainingPortalY(),
                plan.remainingPortalZ(), plan.remaining(), true);
        JarRecord detachedRecord = repository.createCarried(oldJar.owner(), oldJar.world(),
                oldJar.x() + detachedPart.x(), oldJar.y() + detachedPart.y(),
                oldJar.z() + detachedPart.z(), plan.detachedPortal(), plan.detachedPortalX(),
                plan.detachedPortalY(), plan.detachedPortalZ(), oldJar.scale(), plan.detached());

        Location oldOrigin = oldJar.outsideLocation();
        for (JarAssembly.Cell cell : plan.detachedCells()) {
            Block block = oldOrigin.getBlock().getRelative(cell.x(), cell.y(), cell.z());
            block.setType(Material.AIR, false);
            previews.transportBlock(block.getLocation());
        }
        previews.remove(oldJar.id());

        int[] pendingCopies = {2};
        Runnable copied = () -> {
            if (--pendingCopies[0] != 0) return;
            interiors.destroyCell(oldJar);
            releaseCombination(oldJar.id(), null);
            previews.refresh(remainingRecord);
            previews.seal(detachedRecord);
        };
        interiors.copyRegionsAsync(detachedRecord, List.of(new InteriorService.InteriorCopy(
                oldJar, -detachedPart.x(), -detachedPart.y(), -detachedPart.z(),
                plan.detachedCells())), copied);
        interiors.copyRegionsAsync(remainingRecord, List.of(new InteriorService.InteriorCopy(
                oldJar, -plan.shiftX(), -plan.shiftY(), -plan.shiftZ(),
                plan.remainingCells())), copied);
        clicked.getWorld().dropItemNaturally(clicked.getLocation(),
                items.create(detachedRecord.id(), detachedRecord.assembly(), detachedRecord.scale()));
        player.sendMessage("§aJar part detached. Its interior is being prepared in the background.");
    }

    private boolean reserveCombination(UUID first, UUID second) {
        if (combinationsInProgress.contains(first)
                || second != null && combinationsInProgress.contains(second)) return false;
        combinationsInProgress.add(first);
        if (second != null) combinationsInProgress.add(second);
        return true;
    }

    private void releaseCombination(UUID first, UUID second) {
        combinationsInProgress.remove(first);
        if (second != null) {
            combinationsInProgress.remove(second);
            JarRecord source = repository.byId(second).orElse(null);
            if (source != null && !source.placed()) checkDeletedNextTick(second);
        }
    }

    private boolean canPlaceFootprint(Block origin, JarAssembly assembly, Block alreadyPlaced) {
        for (JarAssembly.Cell cell : assembly.cells()) {
            int blockY = origin.getY() + cell.y();
            if (blockY < origin.getWorld().getMinHeight() || blockY >= origin.getWorld().getMaxHeight()) return false;
            Block block = origin.getRelative(cell.x(), cell.y(), cell.z());
            if (!block.equals(alreadyPlaced) && !block.isEmpty()) return false;
            JarRecord occupying = repository.at(block.getLocation()).orElse(null);
            if (occupying != null) return false;
        }
        return true;
    }

    private void placeFootprint(JarRecord jar, Material material) {
        Location origin = jar.outsideLocation();
        if (origin == null) return;
        for (JarAssembly.Cell cell : jar.assembly().cells()) {
            Block block = origin.getBlock().getRelative(cell.x(), cell.y(), cell.z());
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

    private static boolean directional(BlockFace face) {
        return horizontal(face) || face == BlockFace.UP || face == BlockFace.DOWN;
    }

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir();
    }
}
