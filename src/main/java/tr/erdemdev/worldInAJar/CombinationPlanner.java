package tr.erdemdev.worldInAJar;

import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure combination geometry. This class is safe to run away from the server thread. */
final class CombinationPlanner {
    private CombinationPlanner() {}

    static List<AttachmentCandidate> attachmentCandidates(
            JarRecord target, int clickedX, int clickedY, int clickedZ, BlockFace face,
            JarAssembly sourceAssembly, int maximumCombinedSize,
            int maximumInteriorSize, int maximumInteriorHeight) {
        List<JarAssembly.Cell> contacts = sourceAssembly.cells().stream()
                .filter(cell -> !sourceAssembly.contains(cell.x() - face.getModX(),
                        cell.y() - face.getModY(), cell.z() - face.getModZ()))
                .sorted(Comparator.comparingInt(JarAssembly.Cell::y)
                        .thenComparingInt(JarAssembly.Cell::z).thenComparingInt(JarAssembly.Cell::x))
                .toList();
        List<AttachmentCandidate> candidates = new ArrayList<>();
        for (JarAssembly.Cell contact : contacts) {
            int sourceOriginX = clickedX + face.getModX() - contact.x();
            int sourceOriginY = clickedY + face.getModY() - contact.y();
            int sourceOriginZ = clickedZ + face.getModZ() - contact.z();
            try {
                List<JarPart> globalParts = new ArrayList<>(
                        target.parts().size() + sourceAssembly.parts().size());
                target.parts().forEach(part -> globalParts.add(
                        part.translated(target.x(), target.y(), target.z())));
                sourceAssembly.parts().forEach(part -> globalParts.add(
                        part.translated(sourceOriginX, sourceOriginY, sourceOriginZ)));
                JarAssembly global = new JarAssembly(globalParts);
                if (target.hasPortal()) {
                    int portalX = target.x() + target.doorX();
                    int portalY = target.y() + target.doorY();
                    int portalZ = target.z() + target.doorZ();
                    if (global.contains(portalX + target.door().getModX(), portalY,
                            portalZ + target.door().getModZ())) continue;
                }
                JarAssembly combined = global.normalized();
                if (combined.width() > maximumCombinedSize
                        || combined.height() > maximumCombinedSize
                        || combined.depth() > maximumCombinedSize
                        || combined.width() * target.scale() > maximumInteriorSize
                        || combined.height() * target.scale() > maximumInteriorHeight
                        || combined.depth() * target.scale() > maximumInteriorSize) continue;
                candidates.add(new AttachmentCandidate(
                        sourceOriginX, sourceOriginY, sourceOriginZ, global));
            } catch (ArithmeticException | IllegalArgumentException ignored) {
                // This orientation overlaps or exceeds integer geometry limits.
            }
        }
        return List.copyOf(candidates);
    }

    static DetachmentPlan detach(JarRecord oldJar, int cellX, int cellY, int cellZ) {
        JarPart detachedPart = oldJar.assembly().partAt(cellX, cellY, cellZ);
        if (detachedPart == null || oldJar.parts().size() <= 1) return null;

        List<JarPart> remainingParts = new ArrayList<>(oldJar.parts());
        if (!remainingParts.remove(detachedPart) || remainingParts.isEmpty()) return null;
        JarAssembly remainingRaw = new JarAssembly(remainingParts);
        int shiftX = remainingRaw.minX();
        int shiftY = remainingRaw.minY();
        int shiftZ = remainingRaw.minZ();
        JarAssembly remaining = remainingRaw.normalized();
        JarAssembly detached = JarAssembly.cuboid(
                detachedPart.width(), detachedPart.height(), detachedPart.depth());

        Set<JarAssembly.Cell> detachedCells = new HashSet<>();
        for (int x = detachedPart.x(); x < detachedPart.x() + detachedPart.width(); x++) {
            for (int y = detachedPart.y(); y < detachedPart.y() + detachedPart.height(); y++) {
                for (int z = detachedPart.z(); z < detachedPart.z() + detachedPart.depth(); z++) {
                    detachedCells.add(new JarAssembly.Cell(x, y, z));
                }
            }
        }
        Set<JarAssembly.Cell> remainingCells = new HashSet<>(oldJar.assembly().cells());
        remainingCells.removeAll(detachedCells);

        boolean portalDetached = oldJar.hasPortal()
                && detachedCells.contains(new JarAssembly.Cell(
                oldJar.doorX(), oldJar.doorY(), oldJar.doorZ()));
        BlockFace remainingPortal = oldJar.hasPortal() && !portalDetached ? oldJar.door() : null;
        BlockFace detachedPortal = portalDetached ? oldJar.door() : null;
        return new DetachmentPlan(detachedPart, shiftX, shiftY, shiftZ, remaining, detached,
                Set.copyOf(detachedCells), Set.copyOf(remainingCells),
                remainingPortal,
                remainingPortal == null ? 0 : oldJar.doorX() - shiftX,
                remainingPortal == null ? 0 : oldJar.doorY() - shiftY,
                remainingPortal == null ? 0 : oldJar.doorZ() - shiftZ,
                detachedPortal,
                detachedPortal == null ? 0 : oldJar.doorX() - detachedPart.x(),
                detachedPortal == null ? 0 : oldJar.doorY() - detachedPart.y(),
                detachedPortal == null ? 0 : oldJar.doorZ() - detachedPart.z());
    }

    record AttachmentCandidate(int sourceOriginX, int sourceOriginY, int sourceOriginZ,
                               JarAssembly global) {}

    record DetachmentPlan(JarPart detachedPart, int shiftX, int shiftY, int shiftZ,
                          JarAssembly remaining, JarAssembly detached,
                          Set<JarAssembly.Cell> detachedCells,
                          Set<JarAssembly.Cell> remainingCells,
                          BlockFace remainingPortal, int remainingPortalX,
                          int remainingPortalY, int remainingPortalZ,
                          BlockFace detachedPortal, int detachedPortalX,
                          int detachedPortalY, int detachedPortalZ) {}
}
