package world.landfall.deepspace.planet;

/** Pure regression checks for the compact cube-cross conversion. */
public final class PlanetTextureLayoutContractTest {
    private PlanetTextureLayoutContractTest() {
    }

    public static void main(String[] arguments) {
        int width = 8;
        int height = 4;
        int[] source = new int[width * height];
        for (int i = 0; i < source.length; i++) {
            source[i] = 0xFF000000 | i;
        }
        int face = PlanetTextureLayout.cubeCrossFaceSize(width, height);
        int[] cross = PlanetTextureLayout.equirectangularToCross(source, width, height);
        assertTrue(face == 2, "face size remains bounded by source dimensions");
        assertTrue(cross.length == face * 4 * face * 3, "cross dimensions are 4x3 faces");
        assertTrue(cross[face * (face * 4) + 0] != 0, "north face receives sampled pixels");
        assertTrue(cross[(face * 2) * (face * 4) + face] != 0, "down face receives sampled pixels");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
