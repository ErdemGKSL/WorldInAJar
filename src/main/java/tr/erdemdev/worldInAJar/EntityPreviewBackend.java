package tr.erdemdev.worldInAJar;

import java.util.Set;
import java.util.UUID;

interface EntityPreviewBackend {
    void update(JarRecord jar, Set<UUID> exteriorViewers, Set<UUID> interiorViewers);
    void remove(UUID jarId);
    void forget(UUID playerId);
    void stop();
}
