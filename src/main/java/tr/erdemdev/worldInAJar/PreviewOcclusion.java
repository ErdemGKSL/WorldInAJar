package tr.erdemdev.worldInAJar;

import java.util.ArrayList;
import java.util.List;

/**
 * View-independent occlusion culling for the outside blocks shown to jar occupants.
 *
 * <p>Minecraft does no client-side occlusion culling for block display entities, so every block
 * the interior preview spawns is drawn every frame no matter what stands in front of it. Each
 * display is a full jar-scale cube, so a jar placed underground or inside a building would render
 * thousands of screen-filling cubes for terrain nobody can see.
 *
 * <p>Visibility is decided from a set of eye points spread through the jar's own cells rather than
 * from a single occupant, so the answer depends only on the surrounding blocks: it can be cached,
 * shared by every occupant, and never changes as players walk around inside.
 *
 * <p>Pure geometry with no Bukkit state, so it is unit-testable and safe off the server thread.
 */
final class PreviewOcclusion {
    /** Keeps a face target just inside its block so the ray march ends on the block itself. */
    private static final double SURFACE_INSET = 1e-4;
    /** Corner targets are pulled in from the face edges so they stay on the intended block. */
    private static final double CORNER_INSET = .05;

    private PreviewOcclusion() {}

    /**
     * Eye points spread over the shell of a jar assembly, in outside-block offsets. Tracing from
     * the whole volume an occupant can stand in, rather than one point, keeps blocks that are only
     * visible from a corner of the jar, so nothing pops in as players walk about inside.
     *
     * @param spacing wanted gap between eye points, in interior blocks
     * @param maximumPoints upper bound on eye points; the spacing widens until it fits
     * @return flattened {@code x, y, z} triples
     */
    static double[] eyePoints(JarAssembly assembly, int scale, int spacing, int maximumPoints) {
        double margin = .5 / Math.max(1, scale);
        double step = Math.max(1, spacing) / (double) Math.max(1, scale);
        List<double[]> points = List.of();
        // Two points per axis is the floor: the eight corners of the volume, which no sane cap
        // rejects. Anything above that is widened until it fits.
        for (int attempt = 0; attempt < 32; attempt++) {
            double[] xs = axisPoints(assembly.minX() + margin, assembly.maxX() - margin, step);
            double[] ys = axisPoints(assembly.minY() + margin, assembly.maxY() - margin, step);
            double[] zs = axisPoints(assembly.minZ() + margin, assembly.maxZ() - margin, step);
            points = shellPoints(assembly, xs, ys, zs);
            if (points.size() <= maximumPoints
                    || (xs.length == 2 && ys.length == 2 && zs.length == 2)) break;
            step *= 1.5;
        }
        double[] result = new double[points.size() * 3];
        for (int index = 0; index < points.size(); index++) {
            System.arraycopy(points.get(index), 0, result, index * 3, 3);
        }
        return result;
    }

    private static List<double[]> shellPoints(JarAssembly assembly, double[] xs, double[] ys,
                                              double[] zs) {
        List<double[]> points = new ArrayList<>();
        for (int i = 0; i < xs.length; i++) {
            for (int j = 0; j < ys.length; j++) {
                for (int k = 0; k < zs.length; k++) {
                    // A point inside the volume sees no more than its shell does, so skip it. The
                    // centre is kept because it is the cheapest hit for anything in the open.
                    boolean shell = i == 0 || i == xs.length - 1 || j == 0 || j == ys.length - 1
                            || k == 0 || k == zs.length - 1;
                    boolean centre = i == xs.length / 2 && j == ys.length / 2 && k == zs.length / 2;
                    if (!shell && !centre) continue;
                    // Assemblies are not always cuboid; never look out of a cell that is not there.
                    if (!assembly.contains((int) Math.floor(xs[i]), (int) Math.floor(ys[j]),
                            (int) Math.floor(zs[k]))) continue;
                    points.add(new double[] {xs[i], ys[j], zs[k]});
                }
            }
        }
        return points;
    }

    private static double[] axisPoints(double minimum, double maximum, double step) {
        int count = Math.max(2, (int) Math.floor((maximum - minimum) / step) + 1);
        double[] values = new double[count];
        for (int index = 0; index < count; index++) {
            values[index] = minimum + (maximum - minimum) * index / (count - 1);
        }
        return values;
    }

    /**
     * Reports whether the block at the given outside-block offset can be seen from any eye point.
     *
     * @param eyes flattened {@code x, y, z} triples, in the same offset space as the block
     */
    static boolean visible(OccluderGrid grid, double[] eyes, int x, int y, int z) {
        // Sealed on all six sides: the face loop below would reject every eye point anyway, and
        // buried blocks are the bulk of the candidates whenever a jar stands on the ground.
        if (grid.occluding(x + 1, y, z) && grid.occluding(x - 1, y, z)
                && grid.occluding(x, y + 1, z) && grid.occluding(x, y - 1, z)
                && grid.occluding(x, y, z + 1) && grid.occluding(x, y, z - 1)) return false;
        for (int eye = 0; eye + 2 < eyes.length; eye += 3) {
            double eyeX = eyes[eye], eyeY = eyes[eye + 1], eyeZ = eyes[eye + 2];
            for (int axis = 0; axis < 3; axis++) {
                double eyeOnAxis = axis == 0 ? eyeX : axis == 1 ? eyeY : eyeZ;
                int minimum = axis == 0 ? x : axis == 1 ? y : z;
                // Only faces turned towards this eye can be seen, and a face covered by a solid
                // neighbour never can. That neighbour test subsumes the old six-way exposure check.
                int step = eyeOnAxis > minimum + 1 ? 1 : eyeOnAxis < minimum ? -1 : 0;
                if (step == 0) continue;
                if (grid.occluding(x + (axis == 0 ? step : 0), y + (axis == 1 ? step : 0),
                        z + (axis == 2 ? step : 0))) continue;
                if (faceVisible(grid, eyeX, eyeY, eyeZ, x, y, z, axis, step)) return true;
            }
        }
        return false;
    }

    private static boolean faceVisible(OccluderGrid grid, double eyeX, double eyeY, double eyeZ,
                                       int x, int y, int z, int axis, int step) {
        double onAxis = (axis == 0 ? x : axis == 1 ? y : z) + (step > 0 ? 1 - SURFACE_INSET : SURFACE_INSET);
        int first = axis == 0 ? 1 : 0;
        int second = axis == 2 ? 1 : 2;
        double firstMinimum = first == 0 ? x : first == 1 ? y : z;
        double secondMinimum = second == 0 ? x : second == 1 ? y : z;

        if (reaches(grid, eyeX, eyeY, eyeZ, target(axis, onAxis, first, firstMinimum + .5,
                second, secondMinimum + .5), x, y, z)) return true;
        // A block can be visible only past the edge of whatever hides its middle; peek at the
        // corners before giving up, otherwise grazing surfaces are culled while still on screen.
        for (int corner = 0; corner < 4; corner++) {
            double firstOffset = (corner & 1) == 0 ? CORNER_INSET : 1 - CORNER_INSET;
            double secondOffset = (corner & 2) == 0 ? CORNER_INSET : 1 - CORNER_INSET;
            if (reaches(grid, eyeX, eyeY, eyeZ, target(axis, onAxis, first, firstMinimum + firstOffset,
                    second, secondMinimum + secondOffset), x, y, z)) return true;
        }
        return false;
    }

    private static double[] target(int axis, double onAxis, int first, double onFirst,
                                   int second, double onSecond) {
        double[] point = new double[3];
        point[axis] = onAxis;
        point[first] = onFirst;
        point[second] = onSecond;
        return point;
    }

    /** Marches the voxel grid from the eye to the target point (Amanatides and Woo). */
    private static boolean reaches(OccluderGrid grid, double eyeX, double eyeY, double eyeZ,
                                   double[] target, int blockX, int blockY, int blockZ) {
        int x = floor(eyeX), y = floor(eyeY), z = floor(eyeZ);
        double deltaX = target[0] - eyeX, deltaY = target[1] - eyeY, deltaZ = target[2] - eyeZ;
        int stepX = direction(deltaX), stepY = direction(deltaY), stepZ = direction(deltaZ);
        double nextX = boundary(eyeX, x, stepX, deltaX), spanX = span(deltaX);
        double nextY = boundary(eyeY, y, stepY, deltaY), spanY = span(deltaY);
        double nextZ = boundary(eyeZ, z, stepZ, deltaZ), spanZ = span(deltaZ);

        boolean start = true;
        for (int guard = grid.maximumSteps(); guard > 0; guard--) {
            if (x == blockX && y == blockY && z == blockZ) return true;
            if (!start && grid.occluding(x, y, z)) return false;
            start = false;
            double next = Math.min(nextX, Math.min(nextY, nextZ));
            // Overshooting the target without ever entering it means the march drifted off a
            // shared edge; treat that as visible rather than hiding a block on a rounding error.
            if (next > 1) return true;
            if (next == nextX) {
                x += stepX;
                nextX += spanX;
            } else if (next == nextY) {
                y += stepY;
                nextY += spanY;
            } else {
                z += stepZ;
                nextZ += spanZ;
            }
        }
        return true;
    }

    private static int direction(double delta) {
        return delta > 0 ? 1 : delta < 0 ? -1 : 0;
    }

    private static double boundary(double origin, int voxel, int step, double delta) {
        if (step == 0) return Double.POSITIVE_INFINITY;
        return (step > 0 ? voxel + 1 - origin : origin - voxel) / Math.abs(delta);
    }

    private static double span(double delta) {
        return delta == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(delta);
    }

    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    /**
     * Dense occupancy of the outside blocks around a jar, indexed by the same offsets the interior
     * preview samples. Blocks outside the grid are treated as empty: unknown surroundings must
     * never hide a block that is really there.
     */
    static final class OccluderGrid {
        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final long[] occluding;
        private final long[] renderable;

        OccluderGrid(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
            if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
                throw new IllegalArgumentException("An occluder grid needs a positive size");
            }
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            int words = (sizeX * sizeY * sizeZ + 63) >>> 6;
            this.occluding = new long[words];
            this.renderable = new long[words];
        }

        void setOccluding(int x, int y, int z) { set(occluding, x, y, z); }

        void setRenderable(int x, int y, int z) { set(renderable, x, y, z); }

        boolean occluding(int x, int y, int z) { return get(occluding, x, y, z); }

        boolean renderable(int x, int y, int z) { return get(renderable, x, y, z); }

        /** Bounds the ray march so a degenerate direction can never spin. */
        int maximumSteps() { return 3 * (sizeX + sizeY + sizeZ) + 8; }

        /** Identifies the block layout, so cached visibility is reused only while it holds. */
        long fingerprint() {
            long hash = 0xcbf29ce484222325L;
            hash = mix(hash, minX); hash = mix(hash, minY); hash = mix(hash, minZ);
            hash = mix(hash, sizeX); hash = mix(hash, sizeY); hash = mix(hash, sizeZ);
            for (long word : occluding) hash = mix(hash, word);
            for (long word : renderable) hash = mix(hash, word);
            return hash;
        }

        private static long mix(long hash, long value) {
            return (hash ^ value) * 0x100000001b3L;
        }

        private void set(long[] bits, int x, int y, int z) {
            int index = index(x, y, z);
            if (index < 0) return;
            bits[index >>> 6] |= 1L << index;
        }

        private boolean get(long[] bits, int x, int y, int z) {
            int index = index(x, y, z);
            return index >= 0 && (bits[index >>> 6] & 1L << index) != 0;
        }

        private int index(int x, int y, int z) {
            int localX = x - minX, localY = y - minY, localZ = z - minZ;
            if (localX < 0 || localX >= sizeX || localY < 0 || localY >= sizeY
                    || localZ < 0 || localZ >= sizeZ) return -1;
            return (localY * sizeX + localX) * sizeZ + localZ;
        }
    }
}
