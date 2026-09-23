package world.landfall.deepspace.client;

import net.minecraft.util.FastColor;

public final class SkyTransitionState {
    private static volatile float targetProgress;
    private static volatile int targetColor;
    private static float renderedProgress;
    private static float renderedRed;
    private static float renderedGreen;
    private static float renderedBlue;
    private static boolean initialized;

    private SkyTransitionState() {
    }

    public static void setTarget(float progress, int color) {
        targetProgress = Math.max(0.0F, Math.min(1.0F, progress));
        targetColor = color & 0xFFFFFF;
    }

    /**
     * Smooths packet steps on the render thread and returns a per-vertex ARGB tint.
     */
    public static int sampleArgb() {
        float targetRed = (targetColor >> 16) & 0xFF;
        float targetGreen = (targetColor >> 8) & 0xFF;
        float targetBlue = targetColor & 0xFF;
        if (!initialized) {
            renderedRed = targetRed;
            renderedGreen = targetGreen;
            renderedBlue = targetBlue;
            initialized = true;
        }
        renderedProgress += (targetProgress - renderedProgress) * 0.2F;
        renderedRed += (targetRed - renderedRed) * 0.2F;
        renderedGreen += (targetGreen - renderedGreen) * 0.2F;
        renderedBlue += (targetBlue - renderedBlue) * 0.2F;
        if (targetProgress == 0.0F && renderedProgress < 0.002F) {
            renderedProgress = 0.0F;
        }
        return FastColor.ARGB32.color(
                Math.round(renderedProgress * 255.0F),
                Math.round(renderedRed),
                Math.round(renderedGreen),
                Math.round(renderedBlue)
        );
    }
}
