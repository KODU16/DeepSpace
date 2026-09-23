package world.landfall.deepspace.planet;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Pure deterministic layout algorithm kept separate from Minecraft surface sampling.
 *
 * <p>Generates seamless equirectangular planet textures from a palette of sampled surface
 * colors. Continent shapes are produced by fractal gradient (Perlin) noise instead of value
 * noise: gradient noise yields smooth, organic coastlines with no grid-aligned "diamond"
 * artifacts, and a deliberately low octave persistence keeps land and sea masses connected
 * rather than breaking them into scattered speckles. Colors are still assigned by sorting the
 * noise field and splitting it at each palette weight, so the exact surface proportions are
 * preserved while the shapes stay continental.
 */
public final class PlanetTextureLayout {
    // Sample one 3x2 rectangle with six 160x160 faces, each spanning ten chunks per side.
    public static final int FACE_SIZE = 160;
    public static final int WIDTH = FACE_SIZE * 3;
    public static final int HEIGHT = FACE_SIZE * 2;

    /** Base lattice size grows from broad masses (low fragmentation) to many regions (high). */
    private static final int BASE_CELLS_MIN = 3;
    private static final int BASE_CELLS_SPAN = 6;
    private static final int OCTAVE_COUNT = 3;
    private static final double OCTAVE_PERSISTENCE = 0.4;
    private static final long OCTAVE_SEED_SALT = 0x9E3779B97F4A7C15L;

    /** Broad, smooth albedo variation layered over the palette. */
    private static final int TONAL_CELLS_X = 4;
    private static final int TONAL_OCTAVE_COUNT = 2;
    private static final double TONAL_PERSISTENCE = 0.5;
    private static final long TONAL_SEED_SALT = 0xD1B54A32D192ED03L;

    private PlanetTextureLayout() {
    }

    /** Converts an equirectangular map into a compact 4x3 cube cross layout. */
    public static int[] equirectangularToCross(int[] source, int sourceWidth, int sourceHeight) {
        if (sourceWidth < 1 || sourceHeight < 1 || source.length != sourceWidth * sourceHeight) {
            throw new IllegalArgumentException("Invalid equirectangular texture dimensions");
        }
        int face = Math.max(1, Math.min(sourceWidth / 4, sourceHeight / 2));
        int width = face * 4;
        int height = face * 3;
        int[] result = new int[width * height];
        // Cube order: down, north, west, south, east, up.
        int[][] origins = {{face, face * 2}, {face, face}, {0, face}, {face * 2, face},
                {face * 3, face}, {face, 0}};
        double[][] normals = {{0, -1, 0}, {0, 0, -1}, {-1, 0, 0}, {0, 0, 1}, {1, 0, 0}, {0, 1, 0}};
        double[][] rights = {{1, 0, 0}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 0, -1}, {1, 0, 0}};
        double[][] ups = {{0, 0, 1}, {0, 1, 0}, {0, 1, 0}, {0, 1, 0}, {0, 1, 0}, {0, 0, -1}};
        for (int f = 0; f < 6; f++) {
            for (int y = 0; y < face; y++) {
                for (int x = 0; x < face; x++) {
                    double u = (x + 0.5) / face * 2.0 - 1.0;
                    double v = (y + 0.5) / face * 2.0 - 1.0;
                    double dx = normals[f][0] + rights[f][0] * u + ups[f][0] * -v;
                    double dy = normals[f][1] + rights[f][1] * u + ups[f][1] * -v;
                    double dz = normals[f][2] + rights[f][2] * u + ups[f][2] * -v;
                    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx /= length; dy /= length; dz /= length;
                    double longitude = Math.atan2(dz, dx) / (2.0 * Math.PI) + 0.5;
                    double latitude = 0.5 - Math.asin(dy) / Math.PI;
                    int sx = Math.floorMod((int) Math.floor(longitude * sourceWidth), sourceWidth);
                    int sy = Math.clamp((int) Math.floor(latitude * sourceHeight), 0, sourceHeight - 1);
                    result[(origins[f][1] + y) * width + origins[f][0] + x] = source[sy * sourceWidth + sx];
                }
            }
        }
        return result;
    }

    public static int cubeCrossFaceSize(int sourceWidth, int sourceHeight) {
        return Math.max(1, Math.min(sourceWidth / 4, sourceHeight / 2));
    }

    public static int[] generatePixels(
            long textureSeed,
            int[] sourceColors,
            int[] sourceWeights,
            float fragmentation
    ) {
        return generatePixels(textureSeed, sourceColors, sourceWeights, fragmentation, WIDTH, HEIGHT);
    }

    /** 按相同噪声规则生成任意分辨率的行星纹理；环世界棱使用 1055x112 的长条贴图。 */
    public static int[] generatePixels(
            long textureSeed,
            int[] sourceColors,
            int[] sourceWeights,
            float fragmentation,
            int width,
            int height
    ) {
        if (sourceColors.length == 0 || sourceColors.length != sourceWeights.length) {
            throw new IllegalArgumentException("Planet texture colors and weights must be non-empty and equal in length");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Planet texture dimensions must be positive");
        }

        int pixelCount = width * height;
        int[] pixels = new int[pixelCount];
        long totalWeight = Arrays.stream(sourceWeights).asLongStream().sum();
        if (totalWeight < 1 || Arrays.stream(sourceWeights).anyMatch(weight -> weight < 1)) {
            throw new IllegalArgumentException("Planet texture weights must be positive");
        }

        if (sourceColors.length == 1) {
            Arrays.fill(pixels, 0xFF000000 | (sourceColors[0] & 0xFFFFFF));
        } else {
            fillPaletteRegions(pixels, width, height, textureSeed, sourceColors, sourceWeights, fragmentation, totalWeight);
        }
        applyTonalVariation(pixels, width, height, textureSeed);
        return pixels;
    }

    private static void fillPaletteRegions(
            int[] pixels,
            int width,
            int height,
            long textureSeed,
            int[] sourceColors,
            int[] sourceWeights,
            float fragmentation,
            long totalWeight
    ) {
        int pixelCount = pixels.length;
        float clampedFragmentation = Math.clamp(fragmentation, 0.0f, 1.0f);
        double[] scores = new double[pixelCount];
        Integer[] order = new Integer[pixelCount];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                order[index] = index;
                scores[index] = continentNoise(
                        (x + 0.5) / width,
                        (y + 0.5) / height,
                        textureSeed,
                        clampedFragmentation,
                        width,
                        height
                );
            }
        }
        Arrays.sort(order, Comparator.comparingDouble(index -> scores[index]));

        long cumulativeWeight = 0;
        int start = 0;
        for (int colorIndex = 0; colorIndex < sourceColors.length; colorIndex++) {
            cumulativeWeight += sourceWeights[colorIndex];
            int end = colorIndex == sourceColors.length - 1
                    ? pixelCount
                    : (int) Math.round((double) cumulativeWeight * pixelCount / totalWeight);
            int argb = 0xFF000000 | (sourceColors[colorIndex] & 0xFFFFFF);
            while (start < end) {
                pixels[order[start++]] = argb;
            }
        }
    }

    /**
     * Adds broad, smooth tonal regions to every generated palette without following fragmentation.
     * A continuous large-scale brightness gradient reads as natural albedo variation rather than
     * the previous discrete shading bands.
     */
    private static void applyTonalVariation(int[] pixels, int width, int height, long textureSeed) {
        long seed = mix64(textureSeed ^ TONAL_SEED_SALT);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double noise = fbm(
                        (x + 0.5) / width,
                        (y + 0.5) / height,
                        seed,
                        TONAL_CELLS_X,
                        TONAL_OCTAVE_COUNT,
                        TONAL_PERSISTENCE,
                        width,
                        height
                );
                double brightness = Math.clamp(1.0 + noise * 0.17, 0.80, 1.20);
                int index = y * width + x;
                pixels[index] = shade(pixels[index], brightness);
            }
        }
    }

    private static int shade(int rgb, double brightness) {
        int red = Math.clamp((int) Math.round(((rgb >>> 16) & 0xFF) * brightness), 0, 255);
        int green = Math.clamp((int) Math.round(((rgb >>> 8) & 0xFF) * brightness), 0, 255);
        int blue = Math.clamp((int) Math.round((rgb & 0xFF) * brightness), 0, 255);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    /**
     * Fractal gradient noise whose lattice grows from {@code baseCellsX} cells across the width.
     * The vertical cell count follows the 2:1 texture aspect ratio so continent shapes are not
     * stretched horizontally. Low persistence keeps higher octaves from splintering regions.
     */
    private static double fbm(
            double x,
            double y,
            long seed,
            int baseCellsX,
            int octaveCount,
            double persistence,
            int width,
            int height
    ) {
        double value = 0.0;
        double amplitude = 1.0;
        double amplitudeSum = 0.0;
        int cellsX = baseCellsX;

        for (int octave = 0; octave < octaveCount; octave++) {
            // 垂直格数跟随纹理宽高比，避免长条贴图上的大陆被横向拉伸。
            int cellsY = Math.max(1, (int) Math.round(cellsX * height / (double) width));
            value += perlin(x, y, seed + octave * OCTAVE_SEED_SALT, cellsX, cellsY) * amplitude;
            amplitudeSum += amplitude;
            amplitude *= persistence;
            cellsX *= 2;
        }
        return value / amplitudeSum;
    }

    /** Continent elevation field used to order the palette regions. */
    private static double continentNoise(
            double x,
            double y,
            long seed,
            float fragmentation,
            int width,
            int height
    ) {
        int baseCellsX = BASE_CELLS_MIN + Math.round(fragmentation * BASE_CELLS_SPAN);
        // Keep feature size stable in pixels when a long ring-world strip is generated.
        double resolutionScale = Math.max(width / (double) WIDTH, height / (double) HEIGHT);
        baseCellsX = Math.max(1, (int) Math.round(baseCellsX * resolutionScale));
        return fbm(x, y, seed, baseCellsX, OCTAVE_COUNT, OCTAVE_PERSISTENCE, width, height);
    }

    /**
     * Horizontal wrapping prevents a seam where the texture meets around the planet. Gradients
     * are derived from a hashed angle per lattice point, producing an isotropic field free of
     * the axis-aligned bias that makes value noise look blocky.
     */
    private static double perlin(double x, double y, long seed, int cellsX, int cellsY) {
        double gridX = x * cellsX;
        double gridY = y * cellsY;
        int x0 = (int) Math.floor(gridX);
        int y0 = (int) Math.floor(gridY);
        double fx = gridX - x0;
        double fy = gridY - y0;
        int x0w = Math.floorMod(x0, cellsX);
        int x1w = Math.floorMod(x0 + 1, cellsX);
        int y1 = y0 + 1;

        double u = fade(fx);
        double v = fade(fy);
        double n00 = gradientDot(seed, x0w, y0, fx, fy);
        double n10 = gradientDot(seed, x1w, y0, fx - 1.0, fy);
        double n01 = gradientDot(seed, x0w, y1, fx, fy - 1.0);
        double n11 = gradientDot(seed, x1w, y1, fx - 1.0, fy - 1.0);
        double nx0 = lerp(n00, n10, u);
        double nx1 = lerp(n01, n11, u);
        return lerp(nx0, nx1, v);
    }

    /** Projects the per-point random unit gradient onto the local displacement. */
    private static double gradientDot(long seed, int gx, int gy, double dx, double dy) {
        int hash = hash(seed, gx, gy);
        double angle = (hash & 0xFFFF) * (2.0 * Math.PI / 65536.0);
        return Math.cos(angle) * dx + Math.sin(angle) * dy;
    }

    /** Deterministic 32-bit lattice hash mixed from the 64-bit seed and the lattice coordinate. */
    private static int hash(long seed, int x, int y) {
        int h = (int) (seed ^ (seed >>> 33));
        h ^= x * 0x27D4EB2D;
        h ^= y * 0x165667B1;
        h ^= h >>> 13;
        h *= 0x85EBCA6B;
        h ^= h >>> 16;
        return h;
    }

    /** Quintic fade gives continuous curvature and noticeably smoother coastlines than smoothstep. */
    private static double fade(double value) {
        return value * value * value * (value * (value * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double first, double second, double delta) {
        return first + (second - first) * delta;
    }

    /**
     * FNV-1a keeps an ID's seed stable across launches and Java versions.
     */
    public static long seedFromPlanetId(String planetId) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : planetId.getBytes(StandardCharsets.UTF_8)) {
            hash ^= value & 0xFFL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    /**
     * Mixes the save seed with the stable planet ID hash into the texture's total seed.
     */
    public static long seedFromWorldAndPlanet(long worldSeed, String planetId) {
        return mix64(worldSeed ^ seedFromPlanetId(planetId));
    }

    static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
