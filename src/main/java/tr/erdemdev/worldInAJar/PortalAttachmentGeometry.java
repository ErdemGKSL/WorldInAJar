package tr.erdemdev.worldInAJar;

import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

/** Pure geometry for blocks touching the playable side of an interior portal surface. */
final class PortalAttachmentGeometry {
    private PortalAttachmentGeometry() {}

    static List<BlockPosition> touchingInteriorFace(int tileX, int tileY, int tileZ,
                                                    int scale, BlockFace portalFace) {
        if (scale <= 2) return List.of();
        List<BlockPosition> positions = new ArrayList<>((scale - 2) * (scale - 2));
        for (int vertical = 1; vertical < scale - 1; vertical++) {
            for (int lateral = 1; lateral < scale - 1; lateral++) {
                positions.add(switch (portalFace) {
                    case NORTH -> new BlockPosition(tileX + lateral, tileY + vertical, tileZ + 1);
                    case SOUTH -> new BlockPosition(tileX + lateral, tileY + vertical,
                            tileZ + scale - 2);
                    case WEST -> new BlockPosition(tileX + 1, tileY + vertical, tileZ + lateral);
                    case EAST -> new BlockPosition(tileX + scale - 2, tileY + vertical,
                            tileZ + lateral);
                    default -> throw new IllegalArgumentException(
                            "Portal face must be horizontal: " + portalFace);
                });
            }
        }
        return positions;
    }

    record BlockPosition(int x, int y, int z) {}
}
