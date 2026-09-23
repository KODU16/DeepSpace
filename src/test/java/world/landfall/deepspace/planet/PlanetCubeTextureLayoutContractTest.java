package world.landfall.deepspace.planet;

import java.util.HashSet;
import java.util.Set;

/** Standalone checks for exact atlas crops, color-preserving patches and every cube edge orientation. */
public final class PlanetCubeTextureLayoutContractTest {
    // Face/edge pairs derived from Cube's UV orientation; edges are left, right, top, bottom.
    private static final int[][] EDGES = {
            {0, 0, 2, 2, 0}, {0, 1, 4, 2, 1}, {0, 2, 3, 2, 1}, {0, 3, 1, 2, 0},
            {1, 0, 2, 1, 0}, {1, 1, 4, 0, 0}, {1, 3, 5, 2, 0}, {2, 0, 3, 1, 0},
            {2, 3, 5, 0, 1}, {3, 0, 4, 1, 0}, {3, 3, 5, 3, 1}, {4, 3, 5, 1, 0}
    };

    private PlanetCubeTextureLayoutContractTest() {
    }

    public static void main(String[] arguments) {
        assertTrue(PlanetTextureLayout.WIDTH == 3 * PlanetTextureLayout.FACE_SIZE
                        && PlanetTextureLayout.HEIGHT == 2 * PlanetTextureLayout.FACE_SIZE,
                "sampling rectangle must contain six equal square tiles");
        for (int size : new int[]{2, 3, 8, 160}) {
            verifyAtlas(size);
        }
        assertTrue(!PlanetCubeTextureLayout.isAtlas(640, 320), "legacy 2:1 maps are not 3x2 atlases");
        assertTrue(!PlanetCubeTextureLayout.isAtlas(0, 0), "empty dimensions are invalid");
        try {
            PlanetCubeTextureLayout.splitAtlas(new int[32], 8, 4);
            throw new AssertionError("legacy dimensions must not silently distort square tiles");
        } catch (IllegalArgumentException expected) {
            // Rejecting the old aspect ratio prevents accidental spherical-cache reuse.
        }
        System.out.println("Flat atlas, original-color patches and all 12 cube edges verified.");
    }

    private static void verifyAtlas(int size) {
        int width = size * 3;
        int height = size * 2;
        int[] source = new int[width * height];
        Set<Integer> sourceColors = new HashSet<>();
        for (int index = 0; index < source.length; index++) {
            source[index] = 0xFF000000 | index;
            sourceColors.add(source[index]);
        }
        int[][] raw = PlanetCubeTextureLayout.splitAtlas(source, width, height, 0);
        int[][] stitched = PlanetCubeTextureLayout.splitAtlas(source, width, height);
        int[] tiles = {5, 0, 1, 2, 4, 3};
        int overlap = Math.min(Math.max(1, size / 20), Math.max(1, size / 4));
        Set<Integer> rawColors = new HashSet<>();
        for (int face = 0; face < 6; face++) {
            int originX = tiles[face] % 3 * size;
            int originY = tiles[face] / 3 * size;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int index = y * size + x;
                    assertTrue(raw[face][index] == source[(originY + y) * width + originX + x],
                            "flat crop must preserve every source position without projection");
                    rawColors.add(raw[face][index]);
                    assertTrue(sourceColors.contains(stitched[face][index]),
                            "patch stitching must never invent a color by interpolation");
                    if (x >= overlap && y >= overlap && x < size - overlap && y < size - overlap) {
                        assertTrue(stitched[face][index] == raw[face][index],
                                "face interiors must remain unchanged");
                    }
                }
            }
        }
        assertTrue(rawColors.size() == source.length, "all six crops together must cover every sample exactly once");
        for (int[] edge : EDGES) {
            for (int t = 0; t < size; t++) {
                int otherT = edge[4] == 0 ? t : size - 1 - t;
                assertTrue(stitched[edge[0]][edgeIndex(edge[1], t, size)]
                                == stitched[edge[2]][edgeIndex(edge[3], otherT, size)],
                        "joined edges and shared corners must match, including reversed UV directions");
            }
        }
    }

    private static int edgeIndex(int edge, int t, int size) {
        return switch (edge) {
            case 0 -> t * size;
            case 1 -> t * size + size - 1;
            case 2 -> t;
            case 3 -> (size - 1) * size + t;
            default -> throw new IllegalArgumentException("Invalid test edge");
        };
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
