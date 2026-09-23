package world.landfall.deepspace.render;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/** Adds galaxy-only logarithmic depth output to Iris fragment programs. */
public final class GalaxyLogDepthShaderPatcher {
    private static final String PATCH_MARKER = "DEEPSPACE_GALAXY_LOG_DEPTH";
    private static final Pattern MAIN_FUNCTION = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern RENDER_STAGE_UNIFORM =
            Pattern.compile("\\buniform\\s+int\\s+renderStage\\b");
    private static final String DEPTH_WRITE = """

                // DEEPSPACE_GALAXY_LOG_DEPTH: reversed logarithmic depth for the Iris custom-sky phase.
                if (renderStage == 3) {
                    float deepspaceViewDepth = 1.0 / max(gl_FragCoord.w, 1.0e-7);
                    gl_FragDepth = clamp(1.0 - log2(1.0 + deepspaceViewDepth) / 24.0, 0.0, 1.0);
                }
            """;

    private GalaxyLogDepthShaderPatcher() {
    }

    @Nullable
    public static String patchFragmentSource(@Nullable String source) {
        if (source == null
                || source.contains(PATCH_MARKER)
                || source.contains("gl_Position")
                || source.contains("local_size_")
                || !RENDER_STAGE_UNIFORM.matcher(source).find()) {
            return source;
        }
        var mainMatcher = MAIN_FUNCTION.matcher(source);
        if (!mainMatcher.find()) {
            return source;
        }

        return source.substring(0, mainMatcher.end())
                + DEPTH_WRITE
                + source.substring(mainMatcher.end());
    }
}
