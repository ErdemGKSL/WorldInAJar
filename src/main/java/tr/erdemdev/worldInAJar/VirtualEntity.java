package tr.erdemdev.worldInAJar;

import com.mojang.datafixers.util.Pair;
import com.mojang.math.Transformation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
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
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

    protected void positionSync(Collection<Player> viewers, Location location, float headYaw,
                                Vec3 movement, boolean onGround) {
        moveHandle(location);
        handle.setYHeadRot(headYaw);
        handle.setDeltaMovement(movement);
        handle.setOnGround(onGround);
        Packet<ClientGamePacketListener> position = ClientboundEntityPositionSyncPacket.of(handle);
        Packet<ClientGamePacketListener> head = new ClientboundRotateHeadPacket(handle,
                net.minecraft.util.Mth.packDegrees(handle.getYHeadRot()));
        for (Player viewer : viewers) {
            send(viewer, position);
            send(viewer, head);
        }
    }

    protected void metadata(Collection<Player> viewers) {
        Packet<ClientGamePacketListener> packet = new ClientboundSetEntityDataPacket(
                handle.getId(), handle.getEntityData().packAll());
        for (Player viewer : viewers) send(viewer, packet);
    }

    protected void dirtyMetadata(Collection<Player> viewers) {
        List<SynchedEntityData.DataValue<?>> values = handle.getEntityData().packDirty();
        if (values == null || values.isEmpty()) return;
        Packet<ClientGamePacketListener> packet = new ClientboundSetEntityDataPacket(handle.getId(), values);
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

    void spawn(Player viewer, Matrix4f matrix) {
        display.setTransformation(new Transformation(matrix));
        super.spawn(viewer);
    }

    void transform(Player viewer, Matrix4f matrix) {
        display.setTransformation(new Transformation(matrix));
        metadata(List.of(viewer));
    }
}

final class VirtualNametag extends VirtualEntity {
    private final Display.TextDisplay display;
    private float scale;

    VirtualNametag(Location location, String name, float scale) {
        this(new Display.TextDisplay(EntityType.TEXT_DISPLAY,
                ((CraftWorld) location.getWorld()).getHandle()), location, name, scale);
    }

    private VirtualNametag(Display.TextDisplay display, Location location, String name, float scale) {
        super(display, location);
        this.display = display;
        this.scale = scale;
        display.setText(net.minecraft.network.chat.Component.literal(name));
        display.setTransformation(new Transformation(new Matrix4f().scale(scale)));
        display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
        display.setShadowRadius(0f);
        display.setViewRange(128f);
        display.setNoGravity(true);
        display.setInvulnerable(true);
    }

    void update(Location location, Collection<Player> viewers) {
        positionSync(viewers, location, 0f, Vec3.ZERO, false);
    }

    void update(Location location, float scale, Collection<Player> viewers) {
        if (this.scale != scale) {
            this.scale = scale;
            display.setTransformation(new Transformation(new Matrix4f().scale(scale)));
            metadata(viewers);
        }
        update(location, viewers);
    }
}

final class VirtualMannequin extends VirtualEntity {
    private final Mannequin mannequin;
    private final VirtualNametag nametag;
    private final float movementScale;
    private final float renderScale;
    private List<SynchedEntityData.DataValue<?>> sharedMetadata = List.of();
    private int equipmentFingerprint;
    private boolean swinging;
    private int swingTime;
    private int hurtTime;
    private MovementSnapshot lastMovement;

    private record MovementSnapshot(double x, double y, double z, float yaw, float pitch,
                                    float headYaw, Vec3 velocity, boolean onGround) {}

    VirtualMannequin(Player target, Location location, float scale) {
        this(new Mannequin(EntityType.MANNEQUIN,
                ((CraftWorld) location.getWorld()).getHandle()), target, location, scale);
    }

    private VirtualMannequin(Mannequin mannequin, Player target, Location location, float scale) {
        super(mannequin, location);
        this.mannequin = mannequin;
        this.movementScale = scale;
        this.renderScale = Math.max(.0625f, Math.min(16f, scale));
        mannequin.setProfile(ResolvableProfile.createResolved(((CraftPlayer) target).getHandle().getGameProfile()));
        mannequin.setImmovable(true);
        mannequin.setNoGravity(true);
        mannequin.setInvulnerable(true);
        AttributeInstance size = mannequin.getAttribute(Attributes.SCALE);
        if (size != null) size.setBaseValue(renderScale);
        updateState(target);
        this.nametag = new VirtualNametag(nametagLocation(location), target.getName(), renderScale);
    }

    void update(Player target, Location location, Collection<Player> viewers) {
        net.minecraft.server.level.ServerPlayer source = ((CraftPlayer) target).getHandle();
        int oldEquipment = equipmentFingerprint;
        boolean oldSwinging = swinging;
        int oldSwingTime = swingTime;
        int oldHurtTime = hurtTime;
        List<SynchedEntityData.DataValue<?>> oldSharedMetadata = sharedMetadata;
        updateState(source);

        location.setYaw(source.yBodyRot);
        location.setPitch(source.getXRot());
        // Update calls arrive every tick; idle mannequins must not rebroadcast their position.
        MovementSnapshot movement = new MovementSnapshot(location.getX(), location.getY(),
                location.getZ(), location.getYaw(), location.getPitch(), source.getYHeadRot(),
                source.getDeltaMovement().scale(movementScale), source.onGround());
        if (!movement.equals(lastMovement)) {
            lastMovement = movement;
            positionSync(viewers, location, movement.headYaw, movement.velocity, movement.onGround);
            nametag.update(nametagLocation(location), viewers);
        }
        if (!sharedMetadata.equals(oldSharedMetadata)) {
            sendTo(viewers, new ClientboundSetEntityDataPacket(id(), sharedMetadata));
        }
        dirtyMetadata(viewers);
        if (equipmentFingerprint != oldEquipment) {
            Packet<ClientGamePacketListener> packet = equipmentPacket();
            for (Player viewer : viewers) send(viewer, packet);
        }
        if (swinging && (!oldSwinging || swingTime < oldSwingTime)) {
            int action = source.swingingArm == InteractionHand.OFF_HAND
                    ? ClientboundAnimatePacket.SWING_OFF_HAND : ClientboundAnimatePacket.SWING_MAIN_HAND;
            sendTo(viewers, new ClientboundAnimatePacket(mannequin, action));
        }
        if (hurtTime > oldHurtTime) {
            sendTo(viewers, new ClientboundHurtAnimationPacket(id(), source.getHurtDir()));
        }
    }

    void sleep(Location location, Collection<Player> viewers) {
        Location sleeping = location.clone();
        sleeping.setPitch(90f);
        positionSync(viewers, sleeping, sleeping.getYaw(), Vec3.ZERO, true);
        nametag.update(nametagLocation(sleeping), viewers);
    }

    @Override
    void spawn(Player viewer) {
        super.spawn(viewer);
        Collection<AttributeInstance> attributes = mannequin.getAttributes().getSyncableAttributes();
        if (!attributes.isEmpty()) send(viewer, new ClientboundUpdateAttributesPacket(id(), attributes));
        send(viewer, equipmentPacket());
        nametag.spawn(viewer);
    }

    @Override
    void destroy(Player viewer) {
        super.destroy(viewer);
        nametag.destroy(viewer);
    }

    private Location nametagLocation(Location feet) {
        return feet.clone().add(0, mannequin.getBbHeight() + .3f * renderScale, 0);
    }

    private void updateState(Player target) {
        updateState(((CraftPlayer) target).getHandle());
    }

    private void updateState(net.minecraft.server.level.ServerPlayer source) {
        net.minecraft.world.entity.Pose requested = source.getPose();
        mannequin.setPose(Mannequin.VALID_POSES.contains(requested)
                ? requested : net.minecraft.world.entity.Pose.STANDING);
        mannequin.setMainArm(source.getMainArm());
        mannequin.getEntityData().set(Avatar.DATA_PLAYER_MODE_CUSTOMISATION,
                source.getEntityData().get(Avatar.DATA_PLAYER_MODE_CUSTOMISATION));

        mannequin.setSharedFlagOnFire(source.isOnFire());
        mannequin.setShiftKeyDown(source.isShiftKeyDown());
        // Sprinting mini mirrors (interior occupants shown outside, movementScale < 1) spawn sprint
        // dust particles sized for a full-scale player; the block directly below them is usually the
        // jar itself, so faking that block invisible to suppress the particle risks flickering the
        // jar away instead. Giant mirrors (outside players seen from inside) keep the flag, since a
        // real interior floor tile is a safe thing to have particles land on.
        mannequin.setSprinting(movementScale >= 1f && source.isSprinting());
        mannequin.setSwimming(source.isSwimming());
        mannequin.setInvisible(source.isInvisible());
        mannequin.setGlowingTag(source.hasGlowingTag());
        mannequin.setSharedFlag(7, source.getSharedFlag(7));
        mannequin.setLivingEntityFlag(1, source.isUsingItem());
        mannequin.setLivingEntityFlag(2,
                source.isUsingItem() && source.getUsedItemHand() == InteractionHand.OFF_HAND);
        mannequin.setLivingEntityFlag(4, source.isAutoSpinAttack());
        mannequin.setAirSupply(source.getAirSupply());
        mannequin.setTicksFrozen(source.getTicksFrozen());
        mannequin.setHealth(source.getHealth());
        mannequin.setArrowCount(source.getArrowCount(), false);
        mannequin.setStingerCount(source.getStingerCount());
        copySharedMetadata(source);

        int fingerprint = 1;
        for (EquipmentSlot slot : visibleSlots()) {
            ItemStack item = source.getItemBySlot(slot);
            mannequin.setItemSlot(slot, item.copy(), true);
            fingerprint = 31 * fingerprint + ItemStack.hashItemAndComponents(item);
        }
        equipmentFingerprint = fingerprint;
        swinging = source.swinging;
        swingTime = source.swingTime;
        hurtTime = source.hurtTime;
    }

    private void copySharedMetadata(net.minecraft.server.level.ServerPlayer source) {
        int firstLivingValue = LivingEntity.DATA_HEALTH_ID.id();
        int lastAvatarValue = Avatar.DATA_PLAYER_MODE_CUSTOMISATION.id();
        List<SynchedEntityData.DataValue<?>> values = source.getEntityData().packAll().stream()
                .filter(value -> value.id() >= firstLivingValue && value.id() <= lastAvatarValue)
                .toList();
        if (values.equals(sharedMetadata)) return;
        mannequin.getEntityData().assignValues(values);
        sharedMetadata = values;
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

    private static void sendTo(Collection<Player> viewers, Packet<ClientGamePacketListener> packet) {
        for (Player viewer : viewers) send(viewer, packet);
    }
}
