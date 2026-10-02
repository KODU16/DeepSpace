package world.landfall.deepspace.render;

import world.landfall.deepspace.planet.RingWorldDimensions;

/** Regression checks for the shared complete-ring scale, surfaces and section collision envelopes. */
public final class RingWorldRenderGeometryContractTest {
    private static final double EPSILON = 1.0E-3D;
    private static final double SEGMENT_HALF_LENGTH = (52.375D - (-24.4375D)) * 0.5D;
    private static final double MODEL_SCALE = 622.2222222222222D * 2.0D / 7.0D;
    private static final double[][] CENTERS = {
            {6528.0D, -412.0D},
            {412.0D, 6528.0D},
            {-6528.0D, 412.0D},
            {-412.0D, -6528.0D}
    };

    private RingWorldRenderGeometryContractTest() {
    }

    public static void main(String[] arguments) {
        assertNear(MODEL_SCALE, RingWorldDimensions.MODEL_SCALE, "locked complete-ring scale");
        assertNear(622.2222222222222D, RingWorldDimensions.SURFACE_HALF_HEIGHT,
                "locked surface half height");
        assertNear(528.0D / 16.0D * MODEL_SCALE, RingWorldDimensions.WORLD_SURFACE_HALF_LENGTH,
                "fixed world-surface half length");
        assertNear(39.01D / 16.0D * MODEL_SCALE, RingWorldDimensions.WORLD_SURFACE_INWARD_OFFSET,
                "surface retains its established face depth");
        assertNear(-40.0D / 16.0D * MODEL_SCALE, RingWorldDimensions.WORLD_SURFACE_TANGENT_OFFSET,
                "surface follows the off-centre authored texture slot");
        // GeckoLib mirrors Bedrock X before cube rotations; these are the baked section centers.
        assertNear(40.0D / 16.0D * MODEL_SCALE, RingWorldDimensions.NORTH_SURFACE_X,
                "north surface tangent center");
        assertNear(-624.0D / 16.0D * MODEL_SCALE, RingWorldDimensions.NORTH_SURFACE_Z,
                "north section center");
        assertNear(-40.0D / 16.0D * MODEL_SCALE, RingWorldDimensions.SOUTH_SURFACE_X,
                "south surface tangent center");
        assertNear(624.0D / 16.0D * MODEL_SCALE, RingWorldDimensions.SOUTH_SURFACE_Z,
                "south section center");
        assertNear(-624.0D / 16.0D * MODEL_SCALE, RingWorldDimensions.WEST_SURFACE_X,
                "west section center after Gecko X mirroring");
        assertNear(-40.0D / 16.0D * MODEL_SCALE, RingWorldDimensions.WEST_SURFACE_Z,
                "west surface tangent center");
        assertNear(624.0D / 16.0D * MODEL_SCALE, RingWorldDimensions.EAST_SURFACE_X,
                "east section center after Gecko X mirroring");
        assertNear(40.0D / 16.0D * MODEL_SCALE, RingWorldDimensions.EAST_SURFACE_Z,
                "east surface tangent center");
        assertNear(Math.toDegrees(Math.atan2(412.0D, 6528.0D)), RingWorldDimensions.ROTATION_DEGREES,
                "fixed complete-ring rotation");
        assertSectionBounds();
        assertDamageSectionTransforms();
        double fullModelHeight = RingWorldRenderGeometry.MODEL_TOTAL_HEIGHT_BLOCKS * MODEL_SCALE;
        assertNear(MODEL_SCALE, RingWorldRenderGeometry.modelScale(-fullModelHeight * 0.5D, fullModelHeight * 0.5D),
                "complete model scale");
        assertNear(720.0D, RingWorldRenderGeometry.inwardSurfaceDistance(-1.0D, 0.0D, 0.0D, 720.0D, 620.0D, 6_000.0D),
                "local segment below-ground offset");
        assertNear(0.025D, RingWorldRenderGeometry.reachableProjectionScale(10_000.0D, 6_000.0D, 250.0D, 4.0D),
                "distant complete-ring compression");
        assertNear(0.04D, RingWorldRenderGeometry.reachableProjectionScale(10_000.0D, 100.0D, 250.0D, 4.0D),
                "approach protection scale");
        assertNear(1.0D, RingWorldRenderGeometry.reachableProjectionScale(10_000.0D, 4.0D, 250.0D, 4.0D),
                "reachable section true scale");
        assertEquals(0b1011, RingWorldRenderGeometry.skyFallbackHiddenSections(2, 4),
                "observer section remains visible in fallback pass");
        assertNear(1.0D, RingWorldRenderGeometry.adaptiveNearPlane(4.0D, 0.05F, 1.0F),
                "distant ring near plane");
        assertNear(0.5D, RingWorldRenderGeometry.adaptiveNearPlane(2.0D, 0.05F, 1.0F),
                "approaching ring near plane");
        assertNear(0.05D, RingWorldRenderGeometry.adaptiveNearPlane(0.0D, 0.05F, 1.0F),
                "contact ring near plane");
        assertFalse(RingWorldRenderGeometry.guiSurfaceForwardWinding(true),
                "GUI projection reverses an inward-facing model-space surface");
        assertTrue(RingWorldRenderGeometry.guiSurfaceForwardWinding(false),
                "GUI projection reverses the opposite model-space surface");
        assertEquals(0, RingWorldRenderGeometry.frameVertexIndex(0), "frame first vertex");
        assertEquals(1, RingWorldRenderGeometry.frameVertexIndex(1), "frame second vertex");
        assertEquals(2, RingWorldRenderGeometry.frameVertexIndex(2), "frame third vertex");
        assertEquals(3, RingWorldRenderGeometry.frameVertexIndex(3), "frame fourth vertex");
        assertFalse(RingWorldRenderGeometry.cullFrameFaces(),
                "mixed GeckoLib frame faces stay visible from the ring interior and exterior");
        for (int sectionIndex = 0; sectionIndex < RingWorldDimensions.SECTION_COUNT; sectionIndex++) {
            double x = sectionIndex == 0 ? -584.0D / 16.0D : sectionIndex == 2 ? 584.0D / 16.0D : 0.0D;
            double z = sectionIndex == 1 ? 584.0D / 16.0D : sectionIndex == 3 ? -584.0D / 16.0D : 0.0D;
            assertNear(584.0D / 16.0D,
                    RingWorldRenderGeometry.guiOutwardCoordinate(sectionIndex, x, z),
                    "section outward coordinate " + sectionIndex);
        }
        assertTrue(RingWorldRenderGeometry.guiInteriorFrameFace(584.0D / 16.0D),
                "terminal frame keeps its star-facing wall and rim");
        assertFalse(RingWorldRenderGeometry.guiInteriorFrameFace(664.0D / 16.0D),
                "terminal frame hides the outer radial wall");
        double innerFrameRadius = 584.0D / 16.0D * MODEL_SCALE;
        double guiTextureRadius = 624.0D / 16.0D * MODEL_SCALE
                - RingWorldDimensions.WORLD_SURFACE_INWARD_OFFSET
                - RingWorldDimensions.WORLD_SURFACE_HALF_THICKNESS;
        assertTrue(guiTextureRadius < innerFrameRadius,
                "terminal world texture must lie between its ring edge and the star");
        assertTrue(RingWorldRenderGeometry.surfaceAfterFrame(true),
                "far-depth local fallback draws its frame before the world surface");
        assertFalse(RingWorldRenderGeometry.surfaceAfterFrame(false),
                "normal ring passes retain surface-before-frame ordering");
        for (double[] center : CENTERS) {
            float rotation = RingWorldRenderGeometry.rotationDegrees(center[0], center[1]);
            double radians = Math.toRadians(rotation);
            // Local -X must point exactly from the segment center toward the star.
            double inwardX = -Math.cos(radians);
            double inwardZ = Math.sin(radians);
            double radius = Math.hypot(center[0], center[1]);
            assertNear(-center[0] / radius, inwardX, "inward X");
            assertNear(-center[1] / radius, inwardZ, "inward Z");

            float longScale = RingWorldRenderGeometry.longAxisScale(
                    center[0], center[1], CENTERS, rotation, MODEL_SCALE, SEGMENT_HALF_LENGTH, 0.25D
            );
            assertNear(radius + 0.25D, SEGMENT_HALF_LENGTH * MODEL_SCALE * longScale, "connected half length");
        }
    }

    /** Locks collision envelopes to the four baked Gecko section extents after the shared Y rotation. */
    private static void assertSectionBounds() {
        double[][] expected = {
                {-7827.835D, -800.0D, -6954.409D, -6067.286D, 800.0D, 6940.711D},
                {-6954.409D, -800.0D, 6067.286D, 6940.711D, 800.0D, 7827.835D},
                {6067.286D, -800.0D, -6940.711D, 7827.835D, 800.0D, 6954.409D},
                {-6940.711D, -800.0D, -7827.835D, 6954.409D, 800.0D, -6067.286D}
        };
        for (int index = 0; index < expected.length; index++) {
            RingWorldDimensions.Bounds bounds = RingWorldDimensions.sectionBounds(index);
            double[] values = {
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ()
            };
            for (int coordinate = 0; coordinate < values.length; coordinate++) {
                assertNear(expected[index][coordinate], values[coordinate],
                        "section " + index + " bound " + coordinate);
            }
            int resolved = RingWorldDimensions.nearestSectionIndex(
                    bounds.centerX(), bounds.centerZ(), 0.0D, 0.0D
            );
            assertEquals(index, resolved, "baked section center mapping");
        }
    }

    /** Locks every compact damage model to the complete section it replaces. */
    private static void assertDamageSectionTransforms() {
        double[][] expected = {
                {-624.0D, -40.0D, -90.0D},
                {-40.0D, 624.0D, 0.0D},
                {624.0D, 40.0D, 90.0D},
                {40.0D, -624.0D, 180.0D}
        };
        for (int index = 0; index < expected.length; index++) {
            RingWorldDimensions.SectionTransform transform = RingWorldDimensions.damageSectionTransform(index);
            assertNear(expected[index][0], transform.xPixels(), "damage section X " + index);
            assertNear(expected[index][1], transform.zPixels(), "damage section Z " + index);
            assertNear(expected[index][2], transform.yawDegrees(), "damage section yaw " + index);
            assertDamageCenterMatchesSectionBounds(index, transform);
        }
    }

    /** Reverses the shared outer ring rotation to prove each damage centre is the replaced section centre. */
    private static void assertDamageCenterMatchesSectionBounds(
            int index,
            RingWorldDimensions.SectionTransform transform
    ) {
        RingWorldDimensions.Bounds bounds = RingWorldDimensions.sectionBounds(index);
        double radians = Math.toRadians(RingWorldDimensions.ROTATION_DEGREES);
        double localX = Math.cos(radians) * bounds.centerX() - Math.sin(radians) * bounds.centerZ();
        double localZ = Math.sin(radians) * bounds.centerX() + Math.cos(radians) * bounds.centerZ();
        assertNear(transform.xPixels() / 16.0D * MODEL_SCALE, localX, "damage centre X " + index);
        assertNear(transform.zPixels() / 16.0D * MODEL_SCALE, localZ, "damage centre Z " + index);
    }

    private static void assertNear(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new AssertionError(label + " expected " + expected + " but got " + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + " expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean value, String label) {
        if (!value) {
            throw new AssertionError(label + " expected true");
        }
    }

    private static void assertFalse(boolean value, String label) {
        if (value) {
            throw new AssertionError(label + " expected false");
        }
    }

}
