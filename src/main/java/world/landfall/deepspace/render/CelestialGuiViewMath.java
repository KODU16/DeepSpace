package world.landfall.deepspace.render;

/** Centralizes the camera, depth, and directional-light math used by celestial GUI cubes. */
public final class CelestialGuiViewMath {
    private static final float GUI_DEPTH_ORIGIN = 256.0F;
    private static final float GUI_DEPTH_RANGE = 6_000.0F;
    private static final float GUI_FOREGROUND_GAP = 400.0F;
    private static final double DRAG_RADIANS_PER_PIXEL = 0.01D;

    private CelestialGuiViewMath() {
    }

    /** Interprets dragging as orbiting the camera around the displayed system. */
    public static double yawAfterDrag(double yaw, double dragX) {
        return yaw + dragX * DRAG_RADIANS_PER_PIXEL;
    }

    public static double pitchAfterDrag(double pitch, double dragY) {
        return pitch - dragY * DRAG_RADIANS_PER_PIXEL;
    }

    public static double projectedHorizontal(double x, double z, double yaw) {
        return x * Math.cos(yaw) - z * Math.sin(yaw);
    }

    public static double projectedDepth(double x, double y, double z, double yaw, double pitch) {
        double yawDepth = x * Math.sin(yaw) + z * Math.cos(yaw);
        return y * Math.sin(pitch) + yawDepth * Math.cos(pitch);
    }

    /** Gives every depth-sorted model-label pair a non-overlapping GUI depth interval. */
    public static DepthGroup depthGroup(int farToNearIndex, int bodyCount, float modelRadius) {
        int safeCount = Math.max(1, bodyCount);
        if (farToNearIndex < 0 || farToNearIndex >= safeCount) {
            throw new IllegalArgumentException("Depth-group index must identify an existing body");
        }
        float step = GUI_DEPTH_RANGE / (safeCount + 1.0F);
        float modelLayer = GUI_DEPTH_ORIGIN + (farToNearIndex + 1) * step;
        float modelHalfDepth = Math.min(Math.max(1.0F, modelRadius), step * 0.20F);
        float labelLayer = modelLayer + step * 0.30F;
        return new DepthGroup(modelLayer, modelHalfDepth, labelLayer);
    }

    /** Places hover information beyond every depth-sorted celestial model and label. */
    public static float foregroundLayer() {
        return GUI_DEPTH_ORIGIN + GUI_DEPTH_RANGE + GUI_FOREGROUND_GAP;
    }

    public static ViewRotation viewRotation(double yaw, double pitch) {
        return new ViewRotation((float) yaw, (float) -pitch);
    }

    /** Turns probe planets half a screen rotation so two visible faces sit below the third. */
    public static ProbeRotation paradiseProbeRotation() {
        return new ProbeRotation(0.65F, -0.35F, (float) Math.PI);
    }

    public static float faceBrightness(float lightDotNormal) {
        // GUI previews are small and unlit by world skylight, so keep the night side readable.
        return 0.62F + 0.38F * Math.clamp(lightDotNormal, 0.0F, 1.0F);
    }

    public record ViewRotation(float yaw, float pitch) {
    }

    public record ProbeRotation(float yaw, float pitch, float roll) {
    }

    public record DepthGroup(float modelLayer, float modelHalfDepth, float labelLayer) {
    }
}
