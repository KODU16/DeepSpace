package world.landfall.deepspace.render.shapes;

/**
 * Defines how stored cube triangles are submitted for exterior culling.
 */
final class CubeExteriorWinding {
    private CubeExteriorWinding() {
    }

    static int vertexIndex(int emittedIndex) {
        return emittedIndex;
    }

    static float flatNormalSign() {
        return 1.0F;
    }
}
