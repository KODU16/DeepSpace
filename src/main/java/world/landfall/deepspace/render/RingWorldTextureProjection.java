package world.landfall.deepspace.render;

/** Lays six configured cube faces into one cropped horizontal ring-world strip. */
public final class RingWorldTextureProjection {
    private static final int FACE_COUNT = 6;

    private RingWorldTextureProjection() {
    }

    public static Sample stitchedSample(
            int x,
            int y,
            int outputWidth,
            int outputHeight,
            int sourceWidth,
            int sourceHeight
    ) {
        int safeWidth = Math.max(1, outputWidth);
        int safeHeight = Math.max(1, outputHeight);
        int face = stitchedFace(x, safeWidth);
        int panelStart = face * safeWidth / FACE_COUNT;
        int panelEnd = (face + 1) * safeWidth / FACE_COUNT;
        int panelWidth = Math.max(1, panelEnd - panelStart);
        double u = panelWidth == 1 ? 0.5D : (double) (x - panelStart) / (panelWidth - 1);
        double v = safeHeight == 1 ? 0.5D : (double) y / (safeHeight - 1);

        double sourceAspect = (double) Math.max(1, sourceWidth) / Math.max(1, sourceHeight);
        double targetAspect = (double) panelWidth / safeHeight;
        if (sourceAspect > targetAspect) {
            double visibleWidth = targetAspect / sourceAspect;
            u = 0.5D + (u - 0.5D) * visibleWidth;
        } else {
            double visibleHeight = sourceAspect / targetAspect;
            v = 0.5D + (v - 0.5D) * visibleHeight;
        }
        return new Sample(face, clamp01(u), clamp01(v));
    }

    static int stitchedFace(int x, int outputWidth) {
        int safeWidth = Math.max(1, outputWidth);
        int clampedX = Math.clamp(x, 0, safeWidth - 1);
        return Math.min(FACE_COUNT - 1,
                (int) ((((long) clampedX + 1L) * FACE_COUNT - 1L) / safeWidth));
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    public record Sample(int face, double u, double v) {
    }
}
