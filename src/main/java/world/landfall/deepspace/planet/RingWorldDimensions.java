package world.landfall.deepspace.planet;

/** Fixed dimensions shared by ring-world generation, collision bounds and rendering. */
public final class RingWorldDimensions {
    public static final int SECTION_COUNT = 4;
    public static final double SURFACE_HALF_HEIGHT = 622.2222222222222D;
    public static final double MODEL_SCALE = SURFACE_HALF_HEIGHT * 2.0D / 7.0D;
    public static final double MODEL_HALF_HEIGHT = 4.5D * MODEL_SCALE;
    public static final double ROTATION_DEGREES = Math.toDegrees(Math.atan2(412.0D, 6528.0D));
    public static final double OUTER_RADIUS = Math.hypot(41.5D, 41.5D) * MODEL_SCALE;
    public static final double WORLD_SURFACE_HALF_LENGTH = 528.0D / 16.0D * MODEL_SCALE;
    public static final double WORLD_SURFACE_HALF_THICKNESS = 1.0D / 16.0D * MODEL_SCALE;
    // Keep the established face depth while aligning to the atlas' off-centre 1056x112 transparent slot.
    public static final double WORLD_SURFACE_INWARD_OFFSET = 39.01D / 16.0D * MODEL_SCALE;
    public static final double WORLD_SURFACE_TANGENT_OFFSET = -40.0D / 16.0D * MODEL_SCALE;
    // GeckoLib mirrors Bedrock X before applying each cube rotation; use the baked centers below.
    public static final double NORTH_SURFACE_X = 40.0D / 16.0D * MODEL_SCALE;
    public static final double NORTH_SURFACE_Z = -624.0D / 16.0D * MODEL_SCALE;
    public static final double SOUTH_SURFACE_X = -40.0D / 16.0D * MODEL_SCALE;
    public static final double SOUTH_SURFACE_Z = 624.0D / 16.0D * MODEL_SCALE;
    public static final double WEST_SURFACE_X = -624.0D / 16.0D * MODEL_SCALE;
    public static final double WEST_SURFACE_Z = -40.0D / 16.0D * MODEL_SCALE;
    public static final double EAST_SURFACE_X = 624.0D / 16.0D * MODEL_SCALE;
    public static final double EAST_SURFACE_Z = 40.0D / 16.0D * MODEL_SCALE;
    // GeckoLib mirror-aware yaw for the compact damage model at each complete-ring section.
    private static final double[] DAMAGE_SECTION_YAWS = {-90.0D, 0.0D, 90.0D, 180.0D};

    // Baked envelopes in Section1/Section4/Section3/Section2 order: west/south/east/north.
    private static final LocalBounds[] LOCAL_SECTION_BOUNDS = {
            localBounds(-664.0D, -584.0D, -664.0D, 584.0D),
            localBounds(-664.0D, 584.0D, 584.0D, 664.0D),
            localBounds(584.0D, 664.0D, -584.0D, 664.0D),
            localBounds(-584.0D, 664.0D, -664.0D, -584.0D)
    };

    /** Returns the exact axis-aligned world-space envelope of one rotated Gecko ring section. */
    public static Bounds sectionBounds(int index) {
        if (index < 0 || index >= SECTION_COUNT) {
            throw new IllegalArgumentException("Ring-world section index must be in [0, 3]: " + index);
        }
        LocalBounds local = LOCAL_SECTION_BOUNDS[index];
        double radians = Math.toRadians(ROTATION_DEGREES);
        double cosine = Math.cos(radians);
        double sine = Math.sin(radians);
        double minX = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (double x : new double[]{local.minX(), local.maxX()}) {
            for (double z : new double[]{local.minZ(), local.maxZ()}) {
                // Matches PoseStack's positive Y rotation used by the complete ring renderer.
                double rotatedX = cosine * x + sine * z;
                double rotatedZ = -sine * x + cosine * z;
                minX = Math.min(minX, rotatedX);
                maxX = Math.max(maxX, rotatedX);
                minZ = Math.min(minZ, rotatedZ);
                maxZ = Math.max(maxZ, rotatedZ);
            }
        }
        return new Bounds(minX, -MODEL_HALF_HEIGHT, minZ, maxX, MODEL_HALF_HEIGHT, maxZ);
    }

    /** Resolves a world-space planet center to the same baked section index used by model visibility. */
    public static int nearestSectionIndex(double centerX, double centerZ, double starX, double starZ) {
        int nearestIndex = 0;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (int index = 0; index < SECTION_COUNT; index++) {
            Bounds bounds = sectionBounds(index);
            double dx = centerX - (starX + bounds.centerX());
            double dz = centerZ - (starZ + bounds.centerZ());
            double distance = dx * dx + dz * dz;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = index;
            }
        }
        return nearestIndex;
    }

    /** Returns the compact damage transform from the complete section's own unrotated bounding-box centre. */
    public static SectionTransform damageSectionTransform(int index) {
        if (index < 0 || index >= SECTION_COUNT) {
            throw new IllegalArgumentException("Ring-world section index must be in [0, 3]: " + index);
        }
        LocalBounds section = LOCAL_SECTION_BOUNDS[index];
        double pixelsPerBlock = 16.0D / MODEL_SCALE;
        return new SectionTransform(
                section.centerX() * pixelsPerBlock,
                section.centerZ() * pixelsPerBlock,
                DAMAGE_SECTION_YAWS[index]
        );
    }

    private static LocalBounds localBounds(double minX, double maxX, double minZ, double maxZ) {
        double pixelScale = MODEL_SCALE / 16.0D;
        return new LocalBounds(minX * pixelScale, maxX * pixelScale, minZ * pixelScale, maxZ * pixelScale);
    }

    private record LocalBounds(double minX, double maxX, double minZ, double maxZ) {
        private double centerX() {
            return (minX + maxX) * 0.5D;
        }

        private double centerZ() {
            return (minZ + maxZ) * 0.5D;
        }
    }

    public record Bounds(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        public double centerX() {
            return (minX + maxX) * 0.5D;
        }

        public double centerZ() {
            return (minZ + maxZ) * 0.5D;
        }
    }

    public record SectionTransform(double xPixels, double zPixels, double yawDegrees) {
    }

    private RingWorldDimensions() {
    }
}
