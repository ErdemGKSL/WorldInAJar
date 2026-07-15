package tr.erdemdev.worldInAJar;

import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CombinationPlannerTest {
    @Test
    void calculatesAttachmentWithoutWorldAccess() {
        JarRecord target = jar(List.of(new JarPart(0, 0, 0, 1, 1, 1)));

        List<CombinationPlanner.AttachmentCandidate> candidates =
                CombinationPlanner.attachmentCandidates(target, 10, 64, 10, BlockFace.EAST,
                        JarAssembly.single(), 9, 320, 256);

        assertEquals(1, candidates.size());
        CombinationPlanner.AttachmentCandidate candidate = candidates.getFirst();
        assertEquals(11, candidate.sourceOriginX());
        assertEquals(64, candidate.sourceOriginY());
        assertEquals(10, candidate.sourceOriginZ());
        assertEquals(2, candidate.global().width());
    }

    @Test
    void rejectsAttachmentBeyondConfiguredInteriorLimit() {
        JarRecord target = jar(List.of(new JarPart(0, 0, 0, 1, 1, 1)));

        List<CombinationPlanner.AttachmentCandidate> candidates =
                CombinationPlanner.attachmentCandidates(target, 10, 64, 10, BlockFace.EAST,
                        JarAssembly.single(), 9, 32, 256);

        assertTrue(candidates.isEmpty());
    }

    @Test
    void calculatesDetachmentAndPortalOwnership() {
        JarRecord jar = new JarRecord(UUID.randomUUID(), UUID.randomUUID(), "world",
                10, 64, 10, BlockFace.EAST, 1, 0, 0, 0, 32,
                List.of(new JarPart(0, 0, 0, 1, 1, 1),
                        new JarPart(1, 0, 0, 1, 1, 1)), true);

        CombinationPlanner.DetachmentPlan plan = CombinationPlanner.detach(jar, 1, 0, 0);

        assertNotNull(plan);
        assertEquals(BlockFace.EAST, plan.detachedPortal());
        assertNull(plan.remainingPortal());
        assertEquals(1, plan.detachedCells().size());
        assertEquals(1, plan.remainingCells().size());
        assertEquals(JarAssembly.single(), plan.remaining());
        assertEquals(JarAssembly.single(), plan.detached());
    }

    private static JarRecord jar(List<JarPart> parts) {
        return new JarRecord(UUID.randomUUID(), UUID.randomUUID(), "world",
                10, 64, 10, null, 0, 0, 0, 0, 32, parts, true);
    }
}
