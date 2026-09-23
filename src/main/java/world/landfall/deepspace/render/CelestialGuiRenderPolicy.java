package world.landfall.deepspace.render;

/** Defines the solid, double-sided depth policy used by celestial GUI cubes. */
public final class CelestialGuiRenderPolicy {
    private CelestialGuiRenderPolicy() {
    }

    public static boolean cullSurfaceFaces() {
        return false;
    }

    public static boolean blendSurfaceFaces() {
        return false;
    }

    public static boolean writeSurfaceDepth() {
        return true;
    }
}
