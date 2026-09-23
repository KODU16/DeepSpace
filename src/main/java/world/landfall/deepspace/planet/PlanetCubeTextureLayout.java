package world.landfall.deepspace.planet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cuts a flat 3x2 atlas into cube faces and joins edge patches without mixing pixel colors. */
public final class PlanetCubeTextureLayout {
    // Atlas rows: north, west, south / up, east, down; follow them in a serpentine path.
    // Returned faces match Cube: down, north, west, south, east, up.
    private static final int[] FACE_TILES = {5, 0, 1, 2, 4, 3};
    private static final int[][] NORMALS = {{0, -1, 0}, {0, 0, -1}, {-1, 0, 0},
            {0, 0, 1}, {1, 0, 0}, {0, 1, 0}};
    // These axes follow Cube's actual UVs, including the reversed direction on opposite faces.
    private static final int[][] U_AXES = {{1, 0, 0}, {1, 0, 0}, {0, 0, -1},
            {-1, 0, 0}, {0, 0, 1}, {1, 0, 0}};
    private static final int[][] V_AXES = {{0, 0, -1}, {0, 1, 0}, {0, 1, 0},
            {0, 1, 0}, {0, 1, 0}, {0, 0, 1}};

    private PlanetCubeTextureLayout() {
    }

    public static boolean isAtlas(int width, int height) {
        return width >= 3 && height >= 2 && width % 3 == 0 && height % 2 == 0
                && width / 3 == height / 2;
    }

    /** Uses a narrow patch overlap; neither interiors nor selected colors are resampled. */
    public static int[][] splitAtlas(int[] source, int width, int height) {
        return splitAtlas(source, width, height, Math.max(1, width / 3 / 20));
    }

    /** A zero overlap exposes the exact six source tiles for layout validation. */
    public static int[][] splitAtlas(int[] source, int width, int height, int overlap) {
        if (!isAtlas(width, height) || source.length != width * height || overlap < 0) {
            throw new IllegalArgumentException("Expected a 3x2 atlas with equal square tiles");
        }
        int size = width / 3;
        int[][] faces = new int[6][size * size];
        for (int face = 0; face < faces.length; face++) {
            int tile = FACE_TILES[face];
            int originX = tile % 3 * size;
            int originY = tile / 3 * size;
            for (int y = 0; y < size; y++) {
                System.arraycopy(source, (originY + y) * width + originX, faces[face], y * size, size);
            }
        }
        if (overlap > 0 && size > 1) {
            stitchPatches(faces, size, Math.min(overlap, Math.max(1, size / 4)));
        }
        return faces;
    }

    /** Matches cube-space edge texels, rather than assuming that neighboring atlas cells share a seam. */
    private static void stitchPatches(int[][] faces, int size, int overlap) {
        int[][] original = new int[6][];
        Map<EdgePoint, List<Texel>> boundary = new HashMap<>();
        for (int face = 0; face < 6; face++) {
            original[face] = faces[face].clone();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (x == 0 || y == 0 || x == size - 1 || y == size - 1) {
                        boundary.computeIfAbsent(point(face, x, y, size), ignored -> new ArrayList<>(3))
                                .add(new Texel(face, x, y));
                    }
                }
            }
        }
        if (size > 2) {
            for (int face = 0; face < 6; face++) {
                for (int edge = 0; edge < 4; edge++) {
                    quiltEdge(faces, original, boundary, face, edge, size, overlap);
                }
            }
        }
        // Commit identical original pixels on all twelve edges and all eight three-face corners.
        for (List<Texel> shared : boundary.values()) {
            Texel donor = shared.getFirst();
            int color = original[donor.face()][donor.y() * size + donor.x()];
            for (Texel texel : shared) {
                faces[texel.face()][texel.y() * size + texel.x()] = color;
            }
        }
    }

    /** Finds a minimum-error cut through an overlapping patch, then copies pixels on one side of it. */
    private static void quiltEdge(int[][] faces, int[][] original, Map<EdgePoint, List<Texel>> boundary,
                                  int face, int edge, int size, int overlap) {
        Texel middle = edgeTexel(face, edge, size / 2, 0, size);
        List<Texel> shared = boundary.get(point(face, middle.x(), middle.y(), size));
        Texel donor = shared.getFirst();
        if (donor.face() == face) {
            return;
        }
        int donorEdge = donor.x() == 0 ? 0 : donor.x() == size - 1 ? 1 : donor.y() == 0 ? 2 : 3;
        int[][] patch = new int[size][overlap];
        long[][] costs = new long[size][overlap];
        int[][] previous = new int[size][overlap];
        for (int t = 0; t < size; t++) {
            Texel border = edgeTexel(face, edge, t, 0, size);
            Texel source = boundary.get(point(face, border.x(), border.y(), size)).stream()
                    .filter(texel -> texel.face() == donor.face()).findFirst().orElseThrow();
            int donorT = donorEdge < 2 ? source.y() : source.x();
            for (int depth = 0; depth < overlap; depth++) {
                Texel from = edgeTexel(donor.face(), donorEdge, donorT, depth, size);
                Texel to = edgeTexel(face, edge, t, depth, size);
                int color = original[from.face()][from.y() * size + from.x()];
                patch[t][depth] = color;
                long cost = colorDistance(color, original[face][to.y() * size + to.x()]);
                if (t > 0) {
                    int best = depth;
                    for (int candidate = Math.max(0, depth - 1); candidate <= Math.min(overlap - 1, depth + 1); candidate++) {
                        if (costs[t - 1][candidate] < costs[t - 1][best]) {
                            best = candidate;
                        }
                    }
                    previous[t][depth] = best;
                    cost += costs[t - 1][best];
                }
                costs[t][depth] = cost;
            }
        }
        int cut = 0;
        for (int depth = 1; depth < overlap; depth++) {
            if (costs[size - 1][depth] < costs[size - 1][cut]) {
                cut = depth;
            }
        }
        for (int t = size - 1; t >= 0; t--) {
            for (int depth = 0; depth <= cut; depth++) {
                Texel to = edgeTexel(face, edge, t, depth, size);
                faces[face][to.y() * size + to.x()] = patch[t][depth];
            }
            cut = previous[t][cut];
        }
    }

    private static long colorDistance(int first, int second) {
        long distance = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int delta = (first >>> shift & 255) - (second >>> shift & 255);
            distance += (long) delta * delta;
        }
        return distance;
    }

    private static Texel edgeTexel(int face, int edge, int t, int depth, int size) {
        return switch (edge) {
            case 0 -> new Texel(face, depth, t);
            case 1 -> new Texel(face, size - 1 - depth, t);
            case 2 -> new Texel(face, t, depth);
            case 3 -> new Texel(face, t, size - 1 - depth);
            default -> throw new IllegalArgumentException("Invalid cube edge");
        };
    }

    private static EdgePoint point(int face, int x, int y, int size) {
        int last = size - 1;
        int u = x * 2 - last;
        int v = y * 2 - last;
        return new EdgePoint(NORMALS[face][0] * last + U_AXES[face][0] * u + V_AXES[face][0] * v,
                NORMALS[face][1] * last + U_AXES[face][1] * u + V_AXES[face][1] * v,
                NORMALS[face][2] * last + U_AXES[face][2] * u + V_AXES[face][2] * v);
    }

    private record EdgePoint(int x, int y, int z) {
    }

    private record Texel(int face, int x, int y) {
    }
}
