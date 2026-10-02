package world.landfall.deepspace.render;

/** Standalone regression checks for BSL sky alignment and spectral texture tinting. */
public final class SunRenderMathContractTest {
    private static final float EPSILON = 1.0E-5F;

    private SunRenderMathContractTest() {
    }

    public static void main(String[] arguments) {
        // Non-ring BSL worlds retain the established noon/nadir convention.
        float[] zenith = SunRenderMath.bslCelestialDirection(0.0F, 37.0F);
        assertNear(0.0F, zenith[0], "zenith x");
        assertNear(1.0F, zenith[1], "zenith y");
        assertNear(0.0F, zenith[2], "zenith z");

        float[] nadir = SunRenderMath.bslCelestialDirection(0.5F, 37.0F);
        assertNear(0.0F, nadir[0], "nadir x");
        assertNear(-1.0F, nadir[1], "nadir y");
        assertNear(0.0F, nadir[2], "nadir z");

        // Ring-world geometry stays at the configured noon position and never follows time interpolation.
        float[] fixedRingDirection = SunRenderMath.fixedRingWorldCelestialDirection();
        assertNear(0.0F, fixedRingDirection[0], "fixed ring x");
        assertNear(1.0F, fixedRingDirection[1], "fixed ring y");
        assertNear(0.0F, fixedRingDirection[2], "fixed ring z");

        assertNear(0.25F, SunRenderMath.ringWorldRenderDepthScale(),
                "fixed near ring-world star render depth");

        float galaxyCompression = SunRenderMath.boundedCelestialScale(100.0F, 11_633.75D);
        assertNear(100.0F / 11_633.75F, galaxyCompression,
                "distant galaxy star compression");
        assertNear(1.0F, SunRenderMath.boundedCelestialScale(100.0F, 50.0D),
                "near galaxy star remains physical");

        float ringStarScale = SunRenderMath.ringWorldStarVisualScale(400.0D, 6_500.0D, 1_600.0D, 13_000.0D);
        assertNear(2.3F, ringStarScale, "ring-world host star width scale");
        assertTrue(400.0D * ringStarScale / 6_500.0D > 1_600.0D / 13_000.0D,
                "host star covers the apparent opposite-ring width");

        float[][] binaryOffsets = SunRenderMath.multiStarOffsets(new float[]{1.0F, 2.0F}, 42L);
        assertNear(3.12F, distance(binaryOffsets[0], binaryOffsets[1]), "binary star clearance");
        float[][] tripleOffsets = SunRenderMath.multiStarOffsets(new float[]{0.5F, 0.5F, 0.5F}, 42L);
        assertNear(1.12F, distance(tripleOffsets[0], tripleOffsets[1]), "triple star side");
        assertNear(1.12F, distance(tripleOffsets[1], tripleOffsets[2]), "triple star side 2");
        assertNear(1.12F, distance(tripleOffsets[2], tripleOffsets[0]), "triple star side 3");

        var nearestBody = CelestialGuiViewMath.depthGroup(7, 8, 34.0F);
        assertTrue(CelestialGuiViewMath.foregroundLayer() > nearestBody.labelLayer(),
                "hover information stays ahead of every celestial depth group");
        var probeRotation = CelestialGuiViewMath.paradiseProbeRotation();
        assertNear((float) Math.PI, probeRotation.roll(), "Paradise Probe screen-space half turn");

        int blueStar = SunRenderMath.tintNeutralAbgr(0xFFFFFFFF, 0x8FB5FF);
        if (blueStar != 0xFFFFB58F) {
            throw new AssertionError("Expected baked ABGR spectral tint, got 0x" + Integer.toHexString(blueStar));
        }
        // The whole face emits light while keeping its spectral hue and source texture contrast.
        int neutral = 0xFF707070;
        int orange = 0xFFB247;
        int edge = SunRenderMath.brightenSpectralFaceAbgr(neutral, orange, 0.0D, 0.0D);
        int center = SunRenderMath.brightenSpectralFaceAbgr(neutral, orange, 0.5D, 0.5D);
        assertTrue((edge & 0xFF) >= 210 && (edge >>> 16 & 0xFF) < 150,
                "the face remains luminous without washing out its orange color");
        assertTrue((center & 0xFF) > (center >>> 8 & 0xFF)
                        && (center >>> 8 & 0xFF) > (center >>> 16 & 0xFF),
                "bright G-class core retains a warm spectral tint");
        assertTrue((center & 0xFF) > (edge & 0xFF)
                        && (center >>> 8 & 0xFF) > (edge >>> 8 & 0xFF)
                        && (center >>> 16 & 0xFF) > (edge >>> 16 & 0xFF),
                "the face has a gentle center lift");
        int blueCenter = SunRenderMath.brightenSpectralFaceAbgr(neutral, 0x8FB5FF, 0.5D, 0.5D);
        assertTrue((blueCenter >>> 16 & 0xFF) > (blueCenter & 0xFF)
                        && (blueCenter >>> 16 & 0xFF) > (center >>> 16 & 0xFF),
                "blue and orange spectral classes remain visibly distinct");
        int axis = SunRenderMath.brightenSpectralFaceAbgr(neutral, orange, 0.5D, 0.1D);
        int diagonal = SunRenderMath.brightenSpectralFaceAbgr(neutral, orange, 0.1D, 0.1D);
        assertTrue(axis == diagonal, "equal square radii must have equal brightness");
        int darkSource = SunRenderMath.brightenSpectralFaceAbgr(0xFF202020, orange, 0.5D, 0.5D);
        int lightSource = SunRenderMath.brightenSpectralFaceAbgr(0xFFE0E0E0, orange, 0.5D, 0.5D);
        int redContrast = (lightSource & 0xFF) - (darkSource & 0xFF);
        assertTrue(redContrast >= 8 && redContrast <= 25,
                "source detail stays visible without dominating the broad center light");
    }

    private static void assertNear(float expected, float actual, String label) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new AssertionError(label + " expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label + " expected true");
        }
    }

    private static float distance(float[] first, float[] second) {
        float x = first[0] - second[0];
        float y = first[1] - second[1];
        return (float) Math.sqrt(x * x + y * y);
    }
}
