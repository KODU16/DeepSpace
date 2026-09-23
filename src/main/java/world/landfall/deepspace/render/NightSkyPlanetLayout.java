package world.landfall.deepspace.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Pure deterministic selection and placement calculations for surface night skies.
 */
public final class NightSkyPlanetLayout {
    private static final float MIN_PITCH_DEGREES = 12.0f;
    private static final float MAX_PITCH_DEGREES = 72.0f;
    private static final float MIN_RENDERED_DIAMETER = 0.6f;
    private static final float MAX_RENDERED_DIAMETER = 8.0f;

    public record Candidate(String planetId, double distance, double diameter) {
        public Candidate {
            if (planetId == null || planetId.isBlank() || distance <= 0.0 || diameter <= 0.0) {
                throw new IllegalArgumentException("Night-sky candidates require an ID and positive dimensions");
            }
        }
    }

    public record SkyBody(String planetId, float yawDegrees, float pitchDegrees, float renderedDiameter) {
    }

    private NightSkyPlanetLayout() {
    }

    public static List<SkyBody> select(
            long observerSeed,
            long lunarEpoch,
            int moonPhase,
            List<Candidate> candidates,
            double minimumFraction,
            double maximumFraction
    ) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        long layoutSeed = mix64(observerSeed
                ^ mix64(lunarEpoch * 0x9E3779B97F4A7C15L)
                ^ mix64((long) moonPhase * 0xD1B54A32D192ED03L));
        SplittableRandom random = new SplittableRandom(layoutSeed);
        double clampedMinimum = Math.clamp(Math.min(minimumFraction, maximumFraction), 0.0, 1.0);
        double clampedMaximum = Math.clamp(Math.max(minimumFraction, maximumFraction), 0.0, 1.0);
        List<Candidate> shuffled = new ArrayList<>(candidates);
        shuffled.sort(Comparator.comparing(Candidate::planetId));
        for (int index = shuffled.size() - 1; index > 0; index--) {
            int swapIndex = random.nextInt(index + 1);
            Candidate previous = shuffled.get(index);
            shuffled.set(index, shuffled.get(swapIndex));
            shuffled.set(swapIndex, previous);
        }

        double selectedFraction = clampedMinimum == clampedMaximum
                ? clampedMinimum
                : random.nextDouble(clampedMinimum, clampedMaximum);
        int visibleCount = Math.clamp(
                (int) Math.ceil(selectedFraction * shuffled.size()),
                0,
                shuffled.size()
        );
        List<SkyBody> selected = new ArrayList<>(visibleCount);
        for (int index = 0; index < visibleCount; index++) {
            Candidate candidate = shuffled.get(index);
            float yaw = random.nextFloat() * 360.0f;
            float pitch = MIN_PITCH_DEGREES
                    + random.nextFloat() * (MAX_PITCH_DEGREES - MIN_PITCH_DEGREES);
            float sizeVariation = 0.8f + random.nextFloat() * 0.4f;
            float diameter = Math.clamp(
                    apparentDiameter(100.0f, candidate.diameter(), candidate.distance()) * sizeVariation,
                    MIN_RENDERED_DIAMETER,
                    MAX_RENDERED_DIAMETER
            );
            selected.add(new SkyBody(candidate.planetId(), yaw, pitch, diameter));
        }
        return List.copyOf(selected);
    }

    public static float apparentDiameter(float renderDistance, double sourceDiameter, double sourceDistance) {
        return renderDistance * (float) (sourceDiameter / Math.max(sourceDistance, 1.0)) / 3.0f;
    }

    /**
     * Uses the same celestial convention as the sun renderer: 0 is midday and 0.5 is midnight.
     */
    public static float nightVisibility(float timeOfDay) {
        float darkness = Math.max(0.0f, -(float) Math.cos(timeOfDay * Math.PI * 2.0));
        if (darkness < 0.00001f) {
            return 0.0f;
        }
        return darkness * darkness * (3.0f - 2.0f * darkness);
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
