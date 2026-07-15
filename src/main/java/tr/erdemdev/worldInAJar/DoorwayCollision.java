package tr.erdemdev.worldInAJar;

import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;

import java.util.Collection;

final class DoorwayCollision {
    private static final double STEP_HEIGHT = 0.5;
    private static final double FACE_DEPTH = 1.0 / 16.0;

    private DoorwayCollision() {}

    static boolean blocksPortal(Collection<BoundingBox> collisionBoxes, BlockFace outwardFace) {
        // Bukkit collision boxes are local to the outside block. Inspect only the shared face
        // above step height so floor coverings do not seal the portal.
        BoundingBox opening = switch (outwardFace) {
            case NORTH -> new BoundingBox(0, STEP_HEIGHT, 1 - FACE_DEPTH, 1, 1, 1);
            case SOUTH -> new BoundingBox(0, STEP_HEIGHT, 0, 1, 1, FACE_DEPTH);
            case WEST -> new BoundingBox(1 - FACE_DEPTH, STEP_HEIGHT, 0, 1, 1, 1);
            case EAST -> new BoundingBox(0, STEP_HEIGHT, 0, FACE_DEPTH, 1, 1);
            default -> throw new IllegalArgumentException("Portal face must be horizontal: " + outwardFace);
        };
        return collisionBoxes.stream().anyMatch(opening::overlaps);
    }
}
