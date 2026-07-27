package tr.erdemdev.worldInAJar.adapter.v1_21_11;

import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

final class NmsCamera {
    private NmsCamera() {
    }

    static void set(Player viewer, Entity target) {
        ((CraftPlayer) viewer).getHandle().connection.send(
                new ClientboundSetCameraPacket(((CraftEntity) target).getHandle()));
    }
}
