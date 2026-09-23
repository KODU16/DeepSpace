package world.landfall.deepspace.render;

/** Standalone checks for six-face strip placement and aspect-preserving center cropping. */
public final class RingWorldTextureProjectionContractTest {
    private RingWorldTextureProjectionContractTest() {
    }

    public static void main(String[] arguments) {
        int width = 1055;
        int height = 112;
        for (int face = 0; face < 6; face++) {
            int start = face * width / 6;
            int end = (face + 1) * width / 6;
            var sample = RingWorldTextureProjection.stitchedSample(
                    (start + end - 1) / 2, height / 2, width, height, 1024, 1024
            );
            assertEquals(face, sample.face(), "horizontal face " + face);
            assertEquals(face, RingWorldTextureProjection.stitchedFace(start, width),
                    "face boundary " + face);
        }

        var top = RingWorldTextureProjection.stitchedSample(0, 0, width, height, 1024, 1024);
        var bottom = RingWorldTextureProjection.stitchedSample(0, height - 1, width, height, 1024, 1024);
        assertNear(0.0D, top.u(), "square face keeps full width");
        assertTrue(top.v() > 0.0D, "square face crops top");
        assertTrue(bottom.v() < 1.0D, "square face crops bottom");
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + " expected " + expected + " but got " + actual);
        }
    }

    private static void assertNear(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > 1.0E-12D) {
            throw new AssertionError(label + " expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label + " expected true");
        }
    }
}
