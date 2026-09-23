package world.landfall.deepspace.render;

/** Ensures Iris log depth is limited to programs that expose its render-stage uniform. */
public final class GalaxyLogDepthShaderPatcherContractTest {
    private GalaxyLogDepthShaderPatcherContractTest() {
    }

    public static void main(String[] arguments) {
        String entitySource = """
                #version 150
                uniform int renderStage;
                void main() {
                    fragColor = vec4(1.0);
                }
                """;
        String patchedEntity = GalaxyLogDepthShaderPatcher.patchFragmentSource(entitySource);
        require(patchedEntity.contains("DEEPSPACE_GALAXY_LOG_DEPTH"),
                "Iris entity programs with renderStage must receive logarithmic depth");
        require(count(patchedEntity, "uniform int renderStage;") == 1,
                "The existing Iris renderStage uniform must not be redeclared");

        String skySource = """
                #version 150
                void main() {
                    fragColor = vec4(1.0);
                }
                """;
        require(GalaxyLogDepthShaderPatcher.patchFragmentSource(skySource).equals(skySource),
                "Shaders without renderStage, including sky_textured, must remain untouched");
    }

    private static int count(String source, String fragment) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(fragment, offset)) >= 0) {
            count++;
            offset += fragment.length();
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
