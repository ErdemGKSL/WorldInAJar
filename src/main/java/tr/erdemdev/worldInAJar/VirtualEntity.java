package tr.erdemdev.worldInAJar;

import com.mojang.datafixers.util.Pair;
import com.mojang.math.Transformation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** A client-side entity definition which is never registered with a world or chunk. */
abstract class VirtualEntity {
    protected final Entity handle;

    protected VirtualEntity(Entity handle, Location location) {
        this.handle = handle;
        moveHandle(location);
    }

    int id() {
        return handle.getId();
    }

    void spawn(Player viewer) {
        send(viewer, new ClientboundAddEntityPacket(handle.getId(), handle.getUUID(),
                handle.getX(), handle.getY(), handle.getZ(), handle.getXRot(), handle.getYRot(),
                handle.getType(), 0, Vec3.ZERO, handle.getYHeadRot()));
        send(viewer, new ClientboundSetEntityDataPacket(handle.getId(), handle.getEntityData().packAll()));
    }

    void destroy(Player viewer) {
        send(viewer, new ClientboundRemoveEntitiesPacket(handle.getId()));
    }

    protected void teleport(Collection<Player> viewers, Location location) {
        moveHandle(location);
        Packet<ClientGamePacketListener> teleport = ClientboundTeleportEntityPacket.teleport(
                handle.getId(), PositionMoveRotation.of(handle), Set.of(), false);
        Packet<ClientGamePacketListener> head = new ClientboundRotateHeadPacket(handle,
                net.minecraft.util.Mth.packDegrees(handle.getYHeadRot()));
        for (Player viewer : viewers) {
            send(viewer, teleport);
            send(viewer, head);
        }
    }

    protected void metadata(Collection<Player> viewers) {
        Packet<ClientGamePacketListener> packet = new ClientboundSetEntityDataPacket(
                handle.getId(), handle.getEntityData().packAll());
        for (Player viewer : viewers) send(viewer, packet);
    }

    private void moveHandle(Location location) {
        handle.setPos(location.getX(), location.getY(), location.getZ());
        handle.setYRot(location.getYaw());
        handle.setXRot(location.getPitch());
        handle.setYHeadRot(location.getYaw());
    }

    protected static void send(Player player, Packet<? super ClientGamePacketListener> packet) {
        ((CraftPlayer) player).getHandle().connection.send(packet);
    }
}

final class VirtualBlockDisplay extends VirtualEntity {
    private final Display.BlockDisplay display;

    VirtualBlockDisplay(Location location, BlockData blockData, Matrix4f matrix) {
        this(new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, null), location, blockData, matrix);
    }

    private VirtualBlockDisplay(Display.BlockDisplay display, Location location, BlockData blockData, Matrix4f matrix) {
        super(display, location);
        this.display = display;
        display.setBlockState(((CraftBlockData) blockData).getState());
        display.setTransformation(new Transformation(matrix));
        display.setShadowRadius(0f);
        display.setViewRange(128f);
        display.setNoGravity(true);
        display.setInvulnerable(true);
    }
}

final class VirtualMannequin extends VirtualEntity {
    private final Mannequin mannequin;
    private int equipmentFingerprint;
    private net.minecraft.world.entity.Pose pose;

    VirtualMannequin(Player target, Location location, float scale) {
        this(new Mannequin(EntityType.MANNEQUIN, null), target, location, scale);
    }

    private VirtualMannequin(Mannequin mannequin, Player target, Location location, float scale) {
        super(mannequin, location);
        this.mannequin = mannequin;
        mannequin.setProfile(ResolvableProfile.createResolved(((CraftPlayer) target).getHandle().getGameProfile()));
        mannequin.setImmovable(true);
        mannequin.setNoGravity(true);
        mannequin.setInvulnerable(true);
        mannequin.setCustomName(net.minecraft.network.chat.Component.literal(target.getName()));
        mannequin.setCustomNameVisible(true);
        AttributeInstance size = mannequin.getAttribute(Attributes.SCALE);
        if (size != null) size.setBaseValue(Math.max(.0625, Math.min(16.0, scale)));
        updateState(target);
    }

    void update(Player target, Location location, Collection<Player> viewers) {
        teleport(viewers, location);
        net.minecraft.world.entity.Pose oldPose = pose;
        int oldEquipment = equipmentFingerprint;
        updateState(target);
        if (pose != oldPose) metadata(viewers);
        if (equipmentFingerprint != oldEquipment) {
            Packet<ClientGamePacketListener> packet = equipmentPacket();
            for (Player viewer : viewers) send(viewer, packet);
        }
    }

    @Override
    void spawn(Player viewer) {
        super.spawn(viewer);
        Collection<AttributeInstance> attributes = mannequin.getAttributes().getSyncableAttributes();
        if (!attributes.isEmpty()) send(viewer, new ClientboundUpdateAttributesPacket(id(), attributes));
        send(viewer, equipmentPacket());
    }

    private void updateState(Player target) {
        net.minecraft.server.level.ServerPlayer source = ((CraftPlayer) target).getHandle();
        net.minecraft.world.entity.Pose requested = net.minecraft.world.entity.Pose.valueOf(target.getPose().name());
        pose = Mannequin.VALID_POSES.contains(requested) ? requested : net.minecraft.world.entity.Pose.STANDING;
        mannequin.setPose(pose);
        int fingerprint = 1;
        for (EquipmentSlot slot : visibleSlots()) {
            ItemStack item = source.getItemBySlot(slot);
            mannequin.setItemSlot(slot, item.copy(), true);
            fingerprint = 31 * fingerprint + ItemStack.hashItemAndComponents(item);
        }
        equipmentFingerprint = fingerprint;
    }

    private ClientboundSetEquipmentPacket equipmentPacket() {
        List<Pair<EquipmentSlot, ItemStack>> equipment = new ArrayList<>();
        for (EquipmentSlot slot : visibleSlots()) equipment.add(Pair.of(slot, mannequin.getItemBySlot(slot).copy()));
        return new ClientboundSetEquipmentPacket(id(), equipment);
    }

    private static List<EquipmentSlot> visibleSlots() {
        return List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET,
                EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD);
    }
}
