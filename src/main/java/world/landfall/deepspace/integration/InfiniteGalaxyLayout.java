package world.landfall.deepspace.integration;

import java.util.Random;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure deterministic calculations for the optional Infinite Dimensions galaxy. */
public final class InfiniteGalaxyLayout {
    public static final int NAME_MIN_LENGTH = 4;
    public static final int NAME_MAX_LENGTH = 10;
    public static final double PLANET_MIN_SCALE = 0.2;
    public static final double PLANET_MAX_SCALE = 2.0;
    public static final double BASE_PLANET_RADIUS = 100.0;
    private static final int[][] TRIANGULAR_NEIGHBOR_OFFSETS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, -1}, {-1, 1}
    };
    private static final double GRAPH_JITTER = 0.22D;

    private InfiniteGalaxyLayout() {
    }

    /** Chooses a stable random dimension salt independently from generated display names. */
    public static long randomDimensionSeed(Random random) {
        return random.nextLong() & Long.MAX_VALUE;
    }

    public static int randomPlanetCount(Random random) {
        return 2 + random.nextInt(5);
    }

    /** Chooses the generated planet's permanent visual and collision scale. */
    public static double randomPlanetScale(Random random) {
        return PLANET_MIN_SCALE + random.nextDouble() * (PLANET_MAX_SCALE - PLANET_MIN_SCALE);
    }

    /** Keeps entry inside the build range and exit ten blocks above its upper edge. */
    public static AtmosphereHeights atmosphereHeights(int minBuildHeight, int maxBuildHeight) {
        if (maxBuildHeight <= minBuildHeight) {
            throw new IllegalArgumentException("Maximum build height must exceed minimum build height");
        }
        return new AtmosphereHeights(maxBuildHeight - 1, maxBuildHeight + 10);
    }

    /** Requires generated planets to expose exactly the same number of vertical sections as the overworld. */
    public static boolean matchesSectionCount(int candidateHeight, int overworldHeight) {
        return candidateHeight > 0
                && overworldHeight > 0
                && (candidateHeight & 15) == 0
                && (overworldHeight & 15) == 0
                && candidateHeight / 16 == overworldHeight / 16;
    }

    /** Restricts filtering to Infinity's floating fixed-geometry feature implementations. */
    public static boolean isFixedShapeFeatureType(String type) {
        return "infinity:random_cube".equals(type) || "infinity:random_shape".equals(type);
    }

    /** Selects a radial arrival point between the innermost and outermost planet orbits. */
    public static double interplanetaryArrivalRadius(Random random, double firstOrbitRadius, double outerOrbitRadius) {
        double min = Math.min(firstOrbitRadius, outerOrbitRadius);
        double max = Math.max(firstOrbitRadius, outerOrbitRadius);
        return min + random.nextDouble() * (max - min);
    }

    public static double wormholeOrbitRadius(double outerPlanetRadius, double safetyOffset, double wormholeSize) {
        return outerPlanetRadius + Math.max(safetyOffset, wormholeSize);
    }

    public static double cappedStarRadius(double desiredRadius, double firstOrbitRadius) {
        return Math.min(desiredRadius, firstOrbitRadius * 0.45);
    }

    /** Assigns every galaxy a stable integer coordinate along the graph's square spiral backbone. */
    public static GraphPoint wormholeGraphPoint(int chainIndex) {
        int sequence = chainIndex + 1;
        if (sequence < 0) {
            throw new IllegalArgumentException("Galaxy chain index must be -1 or greater");
        }
        if (sequence == 0) {
            return new GraphPoint(0, 0);
        }
        int ring = (int) Math.ceil((Math.sqrt(sequence + 1.0D) - 1.0D) * 0.5D);
        int sideLength = ring * 2;
        int ringMaximum = (ring * 2 + 1) * (ring * 2 + 1) - 1;
        int reverseOffset = ringMaximum - sequence;
        if (reverseOffset < sideLength) {
            return new GraphPoint(ring - reverseOffset, -ring);
        }
        if (reverseOffset < sideLength * 2) {
            return new GraphPoint(-ring, -ring + reverseOffset - sideLength);
        }
        if (reverseOffset < sideLength * 3) {
            return new GraphPoint(-ring + reverseOffset - sideLength * 2, ring);
        }
        return new GraphPoint(ring, ring - reverseOffset + sideLength * 3);
    }

    /** Returns the unjittered ecliptic direction retained for old callers and saved tests. */
    public static double wormholeGraphAngle(int fromChainIndex, int toChainIndex) {
        GraphPoint from = wormholeGraphPoint(fromChainIndex);
        GraphPoint to = wormholeGraphPoint(toChainIndex);
        requireNeighbors(from, to);
        double eclipticX = to.x - from.x + (to.y - from.y) * 0.5D;
        double eclipticY = (to.y - from.y) * Math.sqrt(3.0D) * 0.5D;
        return Math.atan2(eclipticY, eclipticX);
    }

    /** Returns a stable irregular bearing while keeping both ends of an edge exactly opposite. */
    public static double wormholeGraphAngle(int fromChainIndex, int toChainIndex, long worldSeed) {
        GraphPoint from = wormholeGraphPoint(fromChainIndex);
        GraphPoint to = wormholeGraphPoint(toChainIndex);
        requireNeighbors(from, to);
        GraphPosition fromPosition = wormholeGraphPosition(fromChainIndex, worldSeed);
        GraphPosition toPosition = wormholeGraphPosition(toChainIndex, worldSeed);
        return Math.atan2(toPosition.y - fromPosition.y, toPosition.x - fromPosition.x);
    }

    private static void requireNeighbors(GraphPoint from, GraphPoint to) {
        int deltaX = to.x - from.x;
        int deltaY = to.y - from.y;
        boolean neighboring = false;
        for (int[] offset : TRIANGULAR_NEIGHBOR_OFFSETS) {
            neighboring |= deltaX == offset[0] && deltaY == offset[1];
        }
        if (!neighboring) {
            throw new IllegalArgumentException("Wormhole graph edges must connect neighboring graph coordinates");
        }
    }

    /** Maps the triangular lattice to an irregular but non-overlapping world-seeded graph. */
    public static GraphPosition wormholeGraphPosition(int chainIndex, long worldSeed) {
        GraphPoint point = wormholeGraphPoint(chainIndex);
        double baseX = point.x + point.y * 0.5D;
        double baseY = point.y * Math.sqrt(3.0D) * 0.5D;
        long nodeHash = mix64(worldSeed ^ chainIndex * 0xD1B54A32D192ED03L ^ 0x6A09E667F3BCC909L);
        double jitterX = unitSample(nodeHash) * GRAPH_JITTER;
        double jitterY = unitSample(mix64(nodeHash ^ 0xBB67AE8584CAA73BL)) * GRAPH_JITTER;
        return new GraphPosition(baseX + jitterX, baseY + jitterY);
    }

    /** Returns deterministic reciprocal edges with a connected backbone and optional planar shortcuts. */
    public static List<Integer> wormholeGraphNeighbors(int chainIndex, long worldSeed, double density) {
        GraphPoint source = wormholeGraphPoint(chainIndex);
        List<Integer> neighbors = new ArrayList<>();
        for (int[] offset : TRIANGULAR_NEIGHBOR_OFFSETS) {
            GraphPoint targetPoint = new GraphPoint(source.x + offset[0], source.y + offset[1]);
            int targetIndex = wormholeGraphIndex(targetPoint);
            boolean backbone = Math.abs(targetIndex - chainIndex) == 1;
            if (backbone || includeOptionalEdge(chainIndex, targetIndex, worldSeed, density)) {
                neighbors.add(targetIndex);
            }
        }
        neighbors.sort(Comparator.naturalOrder());
        return List.copyOf(neighbors);
    }

    /** Converts a graph coordinate back to the stable generated-galaxy index. */
    public static int wormholeGraphIndex(GraphPoint point) {
        int ring = Math.max(Math.abs(point.x), Math.abs(point.y));
        if (ring == 0) {
            return -1;
        }
        int sideLength = ring * 2;
        int ringMaximum = (ring * 2 + 1) * (ring * 2 + 1) - 1;
        int reverseOffset;
        if (point.y == -ring) {
            reverseOffset = ring - point.x;
        } else if (point.x == -ring) {
            reverseOffset = sideLength + point.y + ring;
        } else if (point.y == ring) {
            reverseOffset = sideLength * 2 + point.x + ring;
        } else {
            reverseOffset = sideLength * 3 + ring - point.y;
        }
        return ringMaximum - reverseOffset - 1;
    }

    public static double targetAverageWormholes(double density) {
        return Math.max(2.0D, Math.min(6.0D, density * 2.5D));
    }

    private static boolean includeOptionalEdge(int firstIndex, int secondIndex, long worldSeed, double density) {
        double probability = (targetAverageWormholes(density) - 2.0D) / 4.0D;
        int low = Math.min(firstIndex, secondIndex);
        int high = Math.max(firstIndex, secondIndex);
        long edgeHash = mix64(worldSeed ^ low * 0xD1B54A32D192ED03L ^ high * 0x94D049BB133111EBL);
        double sample = (edgeHash >>> 11) * 0x1.0p-53;
        return sample < probability;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static double unitSample(long value) {
        return ((value >>> 11) * 0x1.0p-53) * 2.0D - 1.0D;
    }

    public record AtmosphereHeights(int entryHeight, int exitHeight) {
    }

    public record GraphPoint(int x, int y) {
    }

    public record GraphPosition(double x, double y) {
    }
}
