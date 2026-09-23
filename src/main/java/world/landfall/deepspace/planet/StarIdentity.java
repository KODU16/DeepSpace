package world.landfall.deepspace.planet;

import java.util.Locale;

/** Centralizes generated star names and spectral size labels shown to players. */
public final class StarIdentity {
    public static final double BASE_STAR_RADIUS = 200.0;

    private StarIdentity() {
    }

    public static String name(String galaxyName, int starCount, int starIndex) {
        if (starCount <= 0 || starIndex < 0 || starIndex >= starCount) {
            throw new IllegalArgumentException("Star index must belong to a non-empty system");
        }
        return starCount == 1 ? galaxyName : galaxyName + "-" + (char) ('A' + starIndex);
    }

    /** Converts a class-relative radius into its upward-rounded spectral tenth. */
    public static String spectralSize(String spectralClass, double radiusScale) {
        String normalized = spectralClass == null ? "G" : spectralClass.toUpperCase(Locale.ROOT);
        // 已包含亚型（例如初始星系的固定 G4）时直接保留，避免 valueOf("G4") 抛异常。
        if (normalized.matches("[OBAFGKM]\\d")) {
            return normalized;
        }
        SpectralRange range = SpectralRange.valueOf(normalized);
        double tenths = (radiusScale - range.minimum) / (range.maximum - range.minimum) * 10.0;
        int subtype = Math.max(0, Math.min(9, (int) Math.ceil(tenths - 1.0E-9)));
        return range.name() + subtype;
    }

    private enum SpectralRange {
        O(2.0, 3.0),
        B(1.5, 12.0),
        A(1.0, 1.5),
        F(1.0, 1.5),
        G(0.8, 1.0),
        K(0.5, 1.5),
        M(0.8, 3.0);

        private final double minimum;
        private final double maximum;

        SpectralRange(double minimum, double maximum) {
            this.minimum = minimum;
            this.maximum = maximum;
        }
    }
}
