package world.landfall.deepspace.integration;

import world.landfall.deepspace.planet.StarIdentity;

import java.util.Random;

/** Deterministic probability and spacing rules for generated multi-star systems. */
public final class InfiniteStarSystemLayout {
    public static final double BASE_STAR_RADIUS = StarIdentity.BASE_STAR_RADIUS;
    public static final double BODY_CLEARANCE = 300.0;

    private InfiniteStarSystemLayout() {
    }

    public static int randomStarCount(Random random) {
        int roll = random.nextInt(100);
        if (roll < 5) {
            return 3;
        }
        return roll < 20 ? 2 : 1;
    }

    public static SpectralClass randomSpectralClass(Random random) {
        int roll = random.nextInt(100);
        int cumulative = 0;
        for (SpectralClass spectralClass : SpectralClass.values()) {
            cumulative += spectralClass.weight;
            if (roll < cumulative) {
                return spectralClass;
            }
        }
        throw new IllegalStateException("Spectral-class weights must total 100");
    }

    public static double randomRadius(Random random, SpectralClass spectralClass) {
        double scale = spectralClass.minScale
                + random.nextDouble() * (spectralClass.maxScale - spectralClass.minScale);
        return BASE_STAR_RADIUS * scale;
    }

    public static boolean overlaps(
            double firstX,
            double firstZ,
            double firstRadius,
            double secondX,
            double secondZ,
            double secondRadius
    ) {
        double x = firstX - secondX;
        double z = firstZ - secondZ;
        double minimum = firstRadius + secondRadius + BODY_CLEARANCE;
        return x * x + z * z < minimum * minimum;
    }

    public enum SpectralClass {
        // Hot stars are deliberately saturated past the washed-out values BSL's exposure
        // otherwise turns pure white. A and B keep their blue-white hue instead of 0xFFFFFF.
        O(5, 2.0, 3.0, 0x8FB5FF),
        B(10, 1.5, 12.0, 0xA9C7FF),
        A(10, 1.0, 1.5, 0xD3E3FF),
        F(15, 1.0, 1.5, 0xFFE8A8),
        G(40, 0.8, 1.0, 0xFFB247),
        K(10, 0.5, 1.5, 0xFF7B3F),
        M(10, 0.8, 3.0, 0xFF4A3D);

        private final int weight;
        private final double minScale;
        private final double maxScale;
        private final int color;

        SpectralClass(int weight, double minScale, double maxScale, int color) {
            this.weight = weight;
            this.minScale = minScale;
            this.maxScale = maxScale;
            this.color = color;
        }

        public int weight() {
            return weight;
        }

        public double minScale() {
            return minScale;
        }

        public double maxScale() {
            return maxScale;
        }

        public int color() {
            return color;
        }
    }
}
