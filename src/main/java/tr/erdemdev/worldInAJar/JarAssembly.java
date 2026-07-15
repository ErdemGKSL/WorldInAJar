package tr.erdemdev.worldInAJar;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record JarAssembly(List<JarPart> parts) {
    public JarAssembly {
        if (parts == null || parts.isEmpty()) throw new IllegalArgumentException("A jar assembly needs at least one part");
        parts = List.copyOf(parts);
        Set<Tile> occupied = new LinkedHashSet<>();
        for (JarPart part : parts) {
            for (int x = part.x(); x < part.x() + part.width(); x++) {
                for (int z = part.z(); z < part.z() + part.depth(); z++) {
                    if (!occupied.add(new Tile(x, z))) throw new IllegalArgumentException("Jar parts cannot overlap");
                }
            }
        }
    }

    public static JarAssembly single() { return new JarAssembly(List.of(new JarPart(0, 0, 1, 1))); }
    public static JarAssembly rectangle(int width, int depth) {
        return new JarAssembly(List.of(new JarPart(0, 0, width, depth)));
    }

    public int minX() { return parts.stream().mapToInt(JarPart::x).min().orElseThrow(); }
    public int minZ() { return parts.stream().mapToInt(JarPart::z).min().orElseThrow(); }
    public int maxX() { return parts.stream().mapToInt(part -> part.x() + part.width()).max().orElseThrow(); }
    public int maxZ() { return parts.stream().mapToInt(part -> part.z() + part.depth()).max().orElseThrow(); }
    public int width() { return maxX() - minX(); }
    public int depth() { return maxZ() - minZ(); }

    public JarAssembly normalized() { return translated(-minX(), -minZ()); }

    public JarAssembly translated(int dx, int dz) {
        if (dx == 0 && dz == 0) return this;
        return new JarAssembly(parts.stream().map(part -> part.translated(dx, dz)).toList());
    }

    public Set<Tile> tiles() {
        Set<Tile> result = new LinkedHashSet<>();
        for (JarPart part : parts) {
            for (int x = part.x(); x < part.x() + part.width(); x++) {
                for (int z = part.z(); z < part.z() + part.depth(); z++) result.add(new Tile(x, z));
            }
        }
        return Set.copyOf(result);
    }

    public boolean contains(int tileX, int tileZ) {
        return parts.stream().anyMatch(part -> part.contains(tileX, tileZ));
    }

    public JarPart partAt(int tileX, int tileZ) {
        return parts.stream().filter(part -> part.contains(tileX, tileZ)).findFirst().orElse(null);
    }

    public JarAssembly without(JarPart removed) {
        List<JarPart> remaining = new ArrayList<>(parts);
        if (!remaining.remove(removed) || remaining.isEmpty()) return null;
        return new JarAssembly(remaining).normalized();
    }

    public record Tile(int x, int z) {}
}
