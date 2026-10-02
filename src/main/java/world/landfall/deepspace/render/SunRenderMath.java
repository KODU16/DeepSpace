package world.landfall.deepspace.render;

/**
 * Pure calculations shared by the sun renderer and its standalone contract test.
 */
public final class SunRenderMath {
    private static final double MIN_SOURCE_DISTANCE = 1.0;
    private static final float RING_WORLD_RENDER_DEPTH_SCALE = 0.25F;
    private static final float RING_WORLD_STAR_WIDTH_PADDING = 1.15F;
    private static final float MAX_RING_WORLD_STAR_VISUAL_SCALE = 4.0F;
    private static final float MULTI_STAR_GAP = 0.12F;

    private SunRenderMath() {
    }

    public static float apparentScale(float renderDistance, double sourceDistance) {
        return renderDistance / (float) Math.max(sourceDistance, MIN_SOURCE_DISTANCE);
    }

    /** Compresses distant physical stars inside the far plane without enlarging nearby stars. */
    public static float boundedCelestialScale(float renderDistance, double sourceDistance) {
        return Math.min(1.0F, apparentScale(renderDistance, sourceDistance));
    }

    /**
     * 亮面压暗、暗面接近夜侧，只给恒星保留自发光。
     */
    public static float planetSurfaceBrightness(float lightDotNormal) {
        float t = Math.max(0.0F, Math.min(1.0F, (lightDotNormal + 0.12F) / 1.12F));
        float smooth = t * t * (3.0F - 2.0F * t);
        return 0.045F + smooth * 0.70F;
    }

    /**
     * Matches the atmosphere's inner brightness to the surface, then fades outward to zero.
     */
    public static float atmosphereOpacity(float lightDotNormal, float outwardFraction) {
        float distance = Math.max(0.0F, Math.min(1.0F, outwardFraction));
        float outwardFade = (1.0F - distance) * (1.0F - distance);
        return planetSurfaceBrightness(lightDotNormal) * outwardFade;
    }

    /**
     * Compensates for the extra apparent exposure of the Iris/BSL emissive atmosphere pass.
     */
    public static float atmosphereEmissiveBrightness(float lightDotNormal) {
        float surfaceBrightness = planetSurfaceBrightness(lightDotNormal);
        return surfaceBrightness * surfaceBrightness;
    }

    /**
     * Stores day/night illumination in RGB because emissive shader-pack paths may preserve color despite low alpha.
     */
    public static int atmosphereVertexColor(int argb, float lightDotNormal, float outwardFraction) {
        float brightness = atmosphereEmissiveBrightness(lightDotNormal);
        float distance = Math.max(0.0F, Math.min(1.0F, outwardFraction));
        float outwardFade = (1.0F - distance) * (1.0F - distance);
        int alpha = Math.round((argb >>> 24) * outwardFade);
        int red = Math.round(((argb >>> 16) & 0xFF) * brightness);
        int green = Math.round(((argb >>> 8) & 0xFF) * brightness);
        int blue = Math.round((argb & 0xFF) * brightness);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    /**
     * Matches the rotation order used by vanilla and Iris for their sunlight direction.
     */
    public static float[] celestialDirection(float skyAngle, float sunPathRotationDegrees) {
        double orbitRadians = skyAngle * Math.PI * 2.0;
        double pathRadians = Math.toRadians(sunPathRotationDegrees);
        float orbitSin = (float) Math.sin(orbitRadians);
        float orbitCos = (float) Math.cos(orbitRadians);
        float pathSin = (float) Math.sin(pathRadians);
        float pathCos = (float) Math.cos(pathRadians);
        return new float[]{
                -orbitSin,
                pathCos * orbitCos,
                -pathSin * orbitCos
        };
    }

    /** Uses Minecraft's resolved sky angle so fixed time and the vanilla 6000-tick noon remain authoritative. */
    public static float[] bslCelestialDirection(float skyAngle, float sunPathRotationDegrees) {
        double orbitRadians = skyAngle * Math.PI * 2.0D;
        double pathRadians = Math.toRadians(sunPathRotationDegrees);
        float horizontal = (float) -Math.sin(orbitRadians);
        float vertical = (float) Math.cos(orbitRadians);
        // Non-ring worlds retain their prior BSL direction; ring worlds bypass this method entirely.
        return normalize(new float[]{
                horizontal * (float) Math.cos(pathRadians),
                vertical,
                horizontal * (float) Math.sin(pathRadians)
        });
    }

    /** Keeps ring-world sky geometry at the configured noon zenith without client time interpolation. */
    public static float[] fixedRingWorldCelestialDirection() {
        return new float[]{0.0F, 1.0F, 0.0F};
    }

    /** Keeps the visible ring-world star in front of every remote ring segment at a stable depth. */
    public static float ringWorldRenderDepthScale() {
        return RING_WORLD_RENDER_DEPTH_SCALE;
    }

    /** Enlarges the host star enough to cover the full apparent width of the opposite ring segment. */
    public static float ringWorldStarVisualScale(
            double starDiameter,
            double starDistance,
            double ringWidth,
            double oppositeRingDistance
    ) {
        if (starDiameter <= 0.0D || starDistance <= 0.0D || ringWidth <= 0.0D || oppositeRingDistance <= 0.0D) {
            return 1.0F;
        }
        double requiredScale = ringWidth * starDistance / (starDiameter * oppositeRingDistance)
                * RING_WORLD_STAR_WIDTH_PADDING;
        return (float) clamp(requiredScale, 1.0D, MAX_RING_WORLD_STAR_VISUAL_SCALE);
    }

    /** Returns a compact, deterministic binary or triple-star layout in tangent-plane coordinates. */
    public static float[][] multiStarOffsets(float[] apparentRadii, long seed) {
        if (apparentRadii.length < 2) {
            return new float[apparentRadii.length][2];
        }
        float largestRequiredSide = MULTI_STAR_GAP;
        for (int first = 0; first < apparentRadii.length; first++) {
            for (int second = first + 1; second < apparentRadii.length; second++) {
                largestRequiredSide = Math.max(
                        largestRequiredSide,
                        apparentRadii[first] + apparentRadii[second] + MULTI_STAR_GAP
                );
            }
        }
        float side = largestRequiredSide;
        double angle = new java.util.SplittableRandom(seed).nextDouble(0.0D, Math.PI * 2.0D);
        float cosine = (float) Math.cos(angle);
        float sine = (float) Math.sin(angle);
        float[][] offsets = new float[apparentRadii.length][2];
        if (apparentRadii.length == 2) {
            offsets[0] = rotateOffset(-side * 0.5F, 0.0F, cosine, sine);
            offsets[1] = rotateOffset(side * 0.5F, 0.0F, cosine, sine);
            return offsets;
        }
        if (apparentRadii.length == 3) {
            float height = side * (float) Math.sqrt(3.0D) * 0.5F;
            offsets[0] = rotateOffset(0.0F, height * 2.0F / 3.0F, cosine, sine);
            offsets[1] = rotateOffset(-side * 0.5F, -height / 3.0F, cosine, sine);
            offsets[2] = rotateOffset(side * 0.5F, -height / 3.0F, cosine, sine);
            return offsets;
        }
        float circleRadius = side / (2.0F * (float) Math.sin(Math.PI / apparentRadii.length));
        for (int index = 0; index < apparentRadii.length; index++) {
            double radialAngle = angle + index * Math.PI * 2.0D / apparentRadii.length;
            offsets[index][0] = (float) (Math.cos(radialAngle) * circleRadius);
            offsets[index][1] = (float) (Math.sin(radialAngle) * circleRadius);
        }
        return offsets;
    }

    /** Applies a tangent-plane offset without moving the star cluster far from its host direction. */
    public static float[] offsetCelestialDirection(float[] hostDirection, float offsetX, float offsetY,
                                                   float renderDistance) {
        float[] host = normalize(hostDirection);
        float[] reference = Math.abs(host[1]) < 0.9F
                ? new float[]{0.0F, 1.0F, 0.0F}
                : new float[]{1.0F, 0.0F, 0.0F};
        float[] horizontal = normalize(cross(reference, host));
        float[] vertical = normalize(cross(host, horizontal));
        return normalize(new float[]{
                host[0] * renderDistance + horizontal[0] * offsetX + vertical[0] * offsetY,
                host[1] * renderDistance + horizontal[1] * offsetX + vertical[1] * offsetY,
                host[2] * renderDistance + horizontal[2] * offsetX + vertical[2] * offsetY
        });
    }

    private static float[] rotateOffset(float x, float y, float cosine, float sine) {
        return new float[]{x * cosine - y * sine, x * sine + y * cosine};
    }

    /** Bakes a spectral RGB color into a neutral ABGR texture pixel for shader-pack-safe rendering. */
    public static int tintNeutralAbgr(int neutralAbgr, int spectralRgb) {
        int alpha = neutralAbgr >>> 24;
        int neutralRed = neutralAbgr & 0xFF;
        int neutralGreen = neutralAbgr >>> 8 & 0xFF;
        int neutralBlue = neutralAbgr >>> 16 & 0xFF;
        int red = neutralRed * (spectralRgb >>> 16 & 0xFF) / 255;
        int green = neutralGreen * (spectralRgb >>> 8 & 0xFF) / 255;
        int blue = neutralBlue * (spectralRgb & 0xFF) / 255;
        return alpha << 24 | blue << 16 | green << 8 | red;
    }

    /** Keeps the spectral hue and source detail while adding a soft full-face stellar lift. */
    public static int brightenSpectralFaceAbgr(int neutralAbgr, int spectralRgb, double u, double v) {
        double squareRadius = Math.max(Math.abs(u * 2.0D - 1.0D), Math.abs(v * 2.0D - 1.0D));
        double distance = Math.clamp(squareRadius, 0.0D, 1.0D);
        double centerLight = 1.0D - distance * distance * (3.0D - 2.0D * distance);
        double textureDetail = ((neutralAbgr & 0xFF) / 255.0D - 0.5D) * 0.15D;
        // The center lift offsets the source's bright edges without flattening its fine texture.
        double surfaceLight = Math.clamp(0.82D + centerLight * 0.14D + textureDetail, 0.0D, 0.98D);
        double whiteMix = 0.22D + centerLight * 0.08D;
        int red = brightenedChannel(spectralRgb >>> 16 & 0xFF, surfaceLight, whiteMix);
        int green = brightenedChannel(spectralRgb >>> 8 & 0xFF, surfaceLight, whiteMix);
        int blue = brightenedChannel(spectralRgb & 0xFF, surfaceLight, whiteMix);
        return (neutralAbgr & 0xFF000000) | blue << 16 | green << 8 | red;
    }

    private static int brightenedChannel(int spectral, double surfaceLight, double whiteMix) {
        double litSpectral = spectral * surfaceLight;
        return (int) Math.round(litSpectral + (255.0D - litSpectral) * whiteMix);
    }

    /** Rotates a secondary physical star direction by the same shortest arc that drives the host star's day cycle. */
    public static float[] relativeCelestialDirection(float[] hostPhysical, float[] starPhysical, float[] hostCelestial) {
        float[] from = normalize(hostPhysical);
        float[] to = normalize(hostCelestial);
        float[] value = normalize(starPhysical);
        float dot = clamp(dot(from, to), -1.0F, 1.0F);
        if (dot > 0.99999F) {
            return value;
        }
        if (dot < -0.99999F) {
            float[] axis = Math.abs(from[0]) < 0.9F
                    ? normalize(cross(from, new float[]{1.0F, 0.0F, 0.0F}))
                    : normalize(cross(from, new float[]{0.0F, 1.0F, 0.0F}));
            return rotateAroundAxis(value, axis, (float) Math.PI);
        }
        float[] axis = normalize(cross(from, to));
        return rotateAroundAxis(value, axis, (float) Math.acos(dot));
    }

    private static float[] rotateAroundAxis(float[] value, float[] axis, float angle) {
        float cosine = (float) Math.cos(angle);
        float sine = (float) Math.sin(angle);
        float axisDot = dot(axis, value);
        float[] axisCross = cross(axis, value);
        return normalize(new float[]{
                value[0] * cosine + axisCross[0] * sine + axis[0] * axisDot * (1.0F - cosine),
                value[1] * cosine + axisCross[1] * sine + axis[1] * axisDot * (1.0F - cosine),
                value[2] * cosine + axisCross[2] * sine + axis[2] * axisDot * (1.0F - cosine)
        });
    }

    private static float[] cross(float[] first, float[] second) {
        return new float[]{
                first[1] * second[2] - first[2] * second[1],
                first[2] * second[0] - first[0] * second[2],
                first[0] * second[1] - first[1] * second[0]
        };
    }

    private static float dot(float[] first, float[] second) {
        return first[0] * second[0] + first[1] * second[1] + first[2] * second[2];
    }

    private static float[] normalize(float[] value) {
        float inverseLength = (float) (1.0 / Math.sqrt(Math.max(dot(value, value), 1.0E-12F)));
        return new float[]{value[0] * inverseLength, value[1] * inverseLength, value[2] * inverseLength};
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

}
