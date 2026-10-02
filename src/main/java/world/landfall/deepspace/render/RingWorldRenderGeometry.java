package world.landfall.deepspace.render;

/** Pure ring-world transform calculations shared by rendering and regression checks. */
public final class RingWorldRenderGeometry {
    static final double MODEL_TOTAL_HEIGHT_BLOCKS = 9.0D;

    private RingWorldRenderGeometry() {
    }

    static double modelScale(double minY, double maxY) {
        // Planet bounds cover the complete authored frame, not only the seven-block surface slab.
        return (maxY - minY) / MODEL_TOTAL_HEIGHT_BLOCKS;
    }

    static float rotationDegrees(double radialX, double radialZ) {
        // Local -X points inward; retain the layout's small global rotation instead of snapping to cardinal axes.
        return (float) Math.toDegrees(Math.atan2(-radialZ, radialX));
    }

    /** Distance from a centered axis-aligned segment to its inward face along the star direction. */
    static double inwardSurfaceDistance(
            double directionX,
            double directionY,
            double directionZ,
            double halfSizeX,
            double halfSizeY,
            double halfSizeZ
    ) {
        double distance = Double.POSITIVE_INFINITY;
        if (Math.abs(directionX) > 1.0E-8D) {
            distance = Math.min(distance, halfSizeX / Math.abs(directionX));
        }
        if (Math.abs(directionY) > 1.0E-8D) {
            distance = Math.min(distance, halfSizeY / Math.abs(directionY));
        }
        if (Math.abs(directionZ) > 1.0E-8D) {
            distance = Math.min(distance, halfSizeZ / Math.abs(directionZ));
        }
        return Double.isFinite(distance) ? distance : 0.0D;
    }

    /** Compresses distant camera-relative geometry while restoring true scale near a reachable section. */
    static float reachableProjectionScale(
            double farthestDistance,
            double nearestDistance,
            double targetDepth,
            double protectedNearDepth
    ) {
        if (farthestDistance <= 0.0D || targetDepth <= 0.0D || protectedNearDepth <= 0.0D) {
            return 1.0F;
        }
        double farScale = targetDepth / farthestDistance;
        double nearScale = nearestDistance <= protectedNearDepth
                ? 1.0D
                : protectedNearDepth / nearestDistance;
        return (float) Math.clamp(Math.max(farScale, nearScale), 0.0D, 1.0D);
    }

    /** Hides every section except the observer's section during the terrain-fallback pass. */
    static int skyFallbackHiddenSections(int observerSection, int sectionCount) {
        if (observerSection < 0 || observerSection >= sectionCount || sectionCount <= 0 || sectionCount >= Integer.SIZE) {
            throw new IllegalArgumentException("Observer section must belong to a valid section set");
        }
        return ((1 << sectionCount) - 1) & ~(1 << observerSection);
    }

    /** Surface sky geometry anchors to the host star, not to the player's local height. */
    static double surfaceSkyObserverDistance(double inwardSurfaceDistance, double cameraY) {
        return Math.max(0.0D, inwardSurfaceDistance);
    }

    /** Places the local fallback texture just below the camera so normal depth keeps it behind terrain. */
    static float localSkySurfaceDrop() {
        return 8.0F;
    }

    /** Converts model-space surface winding to the GUI projection's winding convention. */
    static boolean guiSurfaceForwardWinding(boolean modelSpaceForwardWinding) {
        return !modelSpaceForwardWinding;
    }

    /** Preserves GeckoLib's authored quad order; two-sided frame rendering handles both viewpoints. */
    static int frameVertexIndex(int emittedIndex) {
        if (emittedIndex < 0 || emittedIndex >= 4) {
            throw new IllegalArgumentException("A frame quad contains exactly four vertices");
        }
        return emittedIndex;
    }

    /** Mixed authored frame orientations require two-sided rendering in both interior and exterior views. */
    static boolean cullFrameFaces() {
        return false;
    }

    /** Measures a vertex along its section's outward axis in the unrotated Gecko model. */
    static double guiOutwardCoordinate(int sectionIndex, double x, double z) {
        return switch (sectionIndex) {
            case 0 -> -x;
            case 1 -> z;
            case 2 -> x;
            case 3 -> -z;
            default -> throw new IllegalArgumentException("Unknown ring-world section " + sectionIndex);
        };
    }

    /** Omits only the outer wall; end caps span both sides and remain visible. */
    static boolean guiInteriorFrameFace(double minimumOutwardCoordinate) {
        return minimumOutwardCoordinate < 644.0D / 16.0D;
    }

    /** Far-depth local geometry draws its frame first so the visible world surface cannot hide it. */
    static boolean surfaceAfterFrame(boolean skyFallback) {
        return skyFallback;
    }

    /** Keeps the near clip plane behind the closest projected ring surface while retaining distant depth precision. */
    public static float adaptiveNearPlane(
            double projectedNearestDistance,
            float defaultNearPlane,
            float maximumNearPlane
    ) {
        if (!Double.isFinite(projectedNearestDistance) || projectedNearestDistance <= 0.0D) {
            return defaultNearPlane;
        }
        return (float) Math.clamp(
                projectedNearestDistance * 0.25D,
                defaultNearPlane,
                maximumNearPlane
        );
    }

    static float longAxisScale(
            double centerX,
            double centerZ,
            double[][] ringCenters,
            float rotation,
            double modelScale,
            double authoredHalfLength,
            double cornerOverlap
    ) {
        double radians = Math.toRadians(rotation);
        double longAxisX = Math.sin(radians);
        double longAxisZ = Math.cos(radians);
        double nearestPositive = Double.POSITIVE_INFINITY;
        double nearestNegative = Double.NEGATIVE_INFINITY;
        // Ignore the opposite segment, whose ideal tangent projection is zero but carries float-angle noise.
        double projectionEpsilon = Math.max(1.0E-4D, authoredHalfLength * modelScale * 0.01D);
        for (double[] other : ringCenters) {
            if (Math.abs(other[0] - centerX) < 1.0E-8D && Math.abs(other[1] - centerZ) < 1.0E-8D) {
                continue;
            }
            double projection = (other[0] - centerX) * longAxisX + (other[1] - centerZ) * longAxisZ;
            if (projection > projectionEpsilon) {
                nearestPositive = Math.min(nearestPositive, projection);
            } else if (projection < -projectionEpsilon) {
                nearestNegative = Math.max(nearestNegative, projection);
            }
        }
        double availableHalfLength = Math.min(nearestPositive, -nearestNegative) + cornerOverlap;
        if (!Double.isFinite(availableHalfLength) || availableHalfLength <= 0.0D) {
            return 1.0F;
        }
        return (float) Math.min(1.0D, availableHalfLength / (authoredHalfLength * modelScale));
    }
}
