package world.landfall.deepspace.render;

/** Covers macro-expanded BSL color inputs and shared sky output without launching Minecraft. */
public final class BslSolarShaderPatcherContractTest {
    private BslSolarShaderPatcherContractTest() {
    }

    public static void main(String[] arguments) {
        // Match the preprocessor hook: options are numeric and BSL writes gl_FragData, not fragColor.
        verifyColors("""
                vec3 skyColSqrt = vec3(140, 180, 255) * 1.0 / 255.0;
                vec3 fogColSqrt = vec3(180, 200, 255) * 1.0 / 255.0;
                vec3 skyCol = skyColSqrt * skyColSqrt;
                vec3 fogCol = fogColSqrt * fogColSqrt;
                """);
        verifyColors("""
                uniform vec3 skyColor;
                uniform vec3 fogColor;
                vec3 skyCol = pow(skyColor, vec3(2.2)) * 1.0 * 1.0;
                vec3 fogCol = pow(fogColor, vec3(2.2)) * 1.0 * 1.0;
                """);
        String unrelated = """
                #version 120
                vec3 skyCol = vec3(0.2, 0.3, 0.8);
                vec3 fogCol = vec3(0.2, 0.3, 0.8);
                void main() { gl_FragData[0] = vec4(skyCol, 1.0); }
                """;
        require(unrelated.equals(BslSolarShaderPatcher.patchFragmentSource(unrelated)),
                "Unrelated shader packs must remain untouched");
        require(BslSolarShaderPatcher.patchFragmentSource(null) == null, "Null input must remain valid");
    }

    private static void verifyColors(String colors) {
        String source = "#version 120\n" + colors + """
                float sunSkyVisibility = 1.0;
                vec3 GetSkyColor(vec3 viewPos, bool isReflection) {
                    vec3 sky = skyCol;
                    if (isReflection) { sky *= 0.5; }
                    sky = mix(vec3(0.02, 0.04, 0.1), sky, sunSkyVisibility);
                    return sky;
                }
                void main() {
                    vec3 albedo = GetSkyColor(vec3(0.0, 1.0, 0.0), false);
                    albedo += vec3(0.01);
                    gl_FragData[0] = vec4(sqrt(albedo), 1.0);
                }
                """;
        String patched = BslSolarShaderPatcher.patchFragmentSource(source);
        require(patched.contains("vec3 skyCol = mix("), "Expanded sky initializer must be patched");
        require(patched.contains("vec3 fogCol = mix("), "Expanded fog initializer must be patched");
        require(!patched.contains("\n    skyCol =") && !patched.contains("\n    fogCol ="),
                "Global colors must use legal initializers, not standalone assignments");
        require(patched.indexOf("uniform vec3 deepspacePlanetSkyColor;")
                        < patched.indexOf("vec3 skyCol ="), "Uniforms must precede global initializers");
        require(patched.contains("brightness / max(atmosphereBrightness, 0.0001)"),
                "Final sky hue must follow the atmosphere while preserving luminance");
        require(patched.indexOf("sky = atmosphere *") < patched.indexOf("return sky;"),
                "Recolor the shared sky function before its return, including reflections");
        require(patched.substring(patched.indexOf("void main()")).equals(
                        source.substring(source.indexOf("void main()"))),
                "Clouds, stars, terrain and output encoding must not be overwritten");
        require(patched.equals(BslSolarShaderPatcher.patchFragmentSource(patched)),
                "Applying the patch twice must not duplicate declarations");

        // Fog-only consumers still need the initializer but must not get a synthetic sky or main override.
        String fogOnly = "#version 120\n" + colors
                + "float sunSkyVisibility = 1.0;\nvoid main() { gl_FragData[0] = vec4(fogCol, 1.0); }\n";
        String patchedFog = BslSolarShaderPatcher.patchFragmentSource(fogOnly);
        require(patchedFog.contains("vec3 fogCol = mix("), "Fog-only programs must receive the atmosphere");
        require(!patchedFog.contains("sky = atmosphere *"), "Fog-only programs must not get sky locals");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
