package tr.erdemdev.worldInAJar;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.joml.Matrix4f;

import java.util.Collection;

public interface VirtualEntityFactory {
    VirtualBlockDisplay createBlockDisplay(Location location, BlockData blockData, Matrix4f matrix);

    VirtualMannequin createMannequin(Player source, Location location, float scale);

    interface VirtualEntity {
        void spawn(Player viewer);

        void destroy(Player viewer);
    }

    interface VirtualBlockDisplay extends VirtualEntity {
        void spawn(Player viewer, Matrix4f matrix);

        void transform(Player viewer, Matrix4f matrix);
    }

    interface VirtualMannequin extends VirtualEntity {
        void update(Player source, Location location, Collection<Player> viewers);

        void sleep(Location location, Collection<Player> viewers);
    }
}
