package world.landfall.deepspace.worldgen;

/** Continuous domain-warped terrain recipes; no chunk coordinates or repeating masks shape the coast. */
public final class DachaTerrain {
    public static final int SEA_LEVEL = 63;
    private static final long[] SEEDS = {17, 43, 79, 127, 157, 197};
    private static final double[] SCALES = {140, 120, 180, 85, 105, 155};

    private DachaTerrain() {}

    /** Coastlines follow one smooth elevation field, so beaches and sea floors join without vertical tile walls. */
    public static int height(int style, int x, int z) {
        long seed = SEEDS[style];
        double scale = SCALES[style];
        double wx = x + 45 * fractal(seed + 101, x / 180.0, z / 180.0);
        double wz = z + 45 * fractal(seed + 211, x / 180.0, z / 180.0);
        double continent = fractal(seed, wx / scale, wz / scale);
        double detail = fractal(seed + 307, wx / 45.0, wz / 45.0);
        double elevation = continent * 26 + detail * 3 + (style == 3 ? 3 : 0);
        if (elevation > 0) {
            elevation = switch (style) {
                case 0 -> elevation * 1.15;
                case 1 -> Math.floor(elevation / 3) * 3;
                case 2 -> elevation * 2.8 + Math.abs(detail) * Math.min(elevation, 30);
                case 3 -> elevation * 1.4;
                case 4 -> elevation * 0.65;
                case 5 -> elevation * 2.1 + Math.abs(detail) * Math.min(elevation, 20);
                default -> throw new IllegalArgumentException("Unknown Dacha terrain style");
            };
        }
        return SEA_LEVEL + (int) Math.floor(elevation);
    }

    private static double fractal(long seed, double x, double z) {
        return (noise(seed, x, z) + 0.5 * noise(seed + 1, x * 2, z * 2)
                + 0.25 * noise(seed + 2, x * 4, z * 4)) / 1.75;
    }

    /** Quintic interpolation removes lattice seams while octave offsets prevent aligned rectangular coasts. */
    private static double noise(long seed, double x, double z) {
        int ix = (int) Math.floor(x), iz = (int) Math.floor(z);
        double u = fade(x - ix), v = fade(z - iz);
        return lerp(lerp(hash(seed, ix, iz), hash(seed, ix + 1, iz), u),
                lerp(hash(seed, ix, iz + 1), hash(seed, ix + 1, iz + 1), u), v);
    }

    private static double hash(long seed, int x, int z) {
        long value = seed ^ x * 0x632BE59BD9B4E019L ^ z * 0x9E3779B97F4A7C15L;
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53 * 2 - 1;
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
