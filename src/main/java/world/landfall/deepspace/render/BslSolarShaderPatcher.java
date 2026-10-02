package world.landfall.deepspace.render;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Patches BSL 10 shader sources for Deep Space point sunlight and planet sky colors.
 */
public final class BslSolarShaderPatcher {
    private static final String LIGHTING_FUNCTION_ANCHOR = "void GetLighting(";
    private static final String BLOCK_LIGHTING_ANCHOR =
            "vec3 blockLighting = blocklightCol * newLightmap * newLightmap;";
    private static final String BSL_LIGHTING_SIGNATURE =
            "albedo *= max(sceneLighting + blockLighting + emissiveLighting + nightVisionLighting + minLighting";
    private static final String INVERSE_MODEL_VIEW_ANCHOR = "gbufferModelViewInverse";
    private static final String DESATURATION_ANCHOR =
            "albedo = mix(desatAlbedo, albedo, desatAmount);";
    private static final String PATCH_MARKER = "DEEPSPACE_BSL_POINT_SOLAR";
    private static final String SKY_PATCH_MARKER = "DEEPSPACE_BSL_PLANET_SKY";
    private static final String MOON_PATCH_MARKER = "DEEPSPACE_BSL_MANAGED_MOON";
    private static final Pattern MOON_SIDE = Pattern.compile(
            "\\bfloat\\s+isMoon\\s*=\\s*float\\s*\\(\\s*VoL\\s*<\\s*0\\.0\\s*\\)\\s*;");
    // Iris expands option macros before this hook; match declarations instead of option text.
    private static final Pattern SKY_COLOR_DECLARATION = Pattern.compile(
            "\\bvec3\\s+(skyCol|fogCol)\\s*=\\s*([^;]+);");
    private static final Pattern BSL_SKY_INITIALIZER = Pattern.compile(
            "(?s)(?:skyColSqrt\\s*\\*\\s*skyColSqrt|pow\\s*\\(\\s*skyColor\\s*,.*)");
    private static final Pattern SKY_FUNCTION = Pattern.compile(
            "\\bvec3\\s+GetSkyColor\\s*\\(\\s*vec3\\s+viewPos\\s*,\\s*bool\\s+isReflection\\s*\\)\\s*\\{");
    private static final String SOLAR_DECLARATIONS = """
            // DEEPSPACE_BSL_POINT_SOLAR: physical center-sun lighting, applied before BSL tone mapping.
            uniform vec3 deepspaceSolarPosition0;
            uniform vec3 deepspaceSolarPosition1;
            uniform vec3 deepspaceSolarPosition2;
            uniform vec3 deepspaceSolarColor0;
            uniform vec3 deepspaceSolarColor1;
            uniform vec3 deepspaceSolarColor2;
            uniform float deepspaceSolarCount;
            uniform float deepspaceSolarActive;
            uniform float deepspaceSolarIntensity;
            uniform float deepspaceSolarReferenceDistance;

            vec3 deepspacePointSolarLighting(vec3 worldPos, vec3 viewNormal, vec3 lightPosition, float active, vec3 solarColor) {
                vec3 toLight = lightPosition - worldPos;
                float distanceSquared = dot(toLight, toLight);
                float normalLengthSquared = dot(viewNormal, viewNormal);
                if (distanceSquared < 0.0001 || normalLengthSquared < 0.0001) {
                    return vec3(0.0);
                }

                vec3 worldLightDirection = normalize(toLight);
                vec3 normalizedViewNormal = viewNormal * inversesqrt(normalLengthSquared);
                vec3 worldNormal = normalize(mat3(gbufferModelViewInverse) * normalizedViewNormal);
                float lambert = max(dot(worldNormal, worldLightDirection), 0.0);
                float referenceSquared = max(
                    deepspaceSolarReferenceDistance * deepspaceSolarReferenceDistance,
                    1.0
                );
                float attenuation = referenceSquared / (referenceSquared + distanceSquared);
                float brightness = active * deepspaceSolarActive * deepspaceSolarIntensity * lambert * attenuation;
                brightness = clamp(brightness, 0.0, 0.35);
                return vec3(brightness) * solarColor;
            }

            """;
    private static final String SKY_DECLARATIONS = """
            // DEEPSPACE_BSL_PLANET_SKY: planet atmosphere sky color, applied after BSL option colors.
            uniform vec3 deepspacePlanetSkyColor;
            uniform float deepspacePlanetSkyActive;
            
            vec3 deepspacePlanetSkyLinear() {
                return pow(max(deepspacePlanetSkyColor, vec3(0.0)), vec3(2.2));
            }
            
            """;
    private static final String SKY_RETURN_OVERRIDE = """
            // Keep BSL's day/night and horizon brightness while removing its Earth-specific hue.
            if (deepspacePlanetSkyActive > 0.5) {
                vec3 atmosphere = deepspacePlanetSkyLinear();
                float brightness = dot(max(sky, vec3(0.0)), vec3(0.299, 0.587, 0.114));
                float atmosphereBrightness = dot(atmosphere, vec3(0.299, 0.587, 0.114));
                sky = atmosphere * (brightness / max(atmosphereBrightness, 0.0001));
            }
            return sky;""";
    private static final String BLOCK_LIGHTING_PATCH = BLOCK_LIGHTING_ANCHOR + """

                // Replace weaker block light so solar energy cannot stack into overexposure.
                vec3 deepspaceSolarLighting =
                    deepspacePointSolarLighting(worldPos, normal, deepspaceSolarPosition0, step(1.0, deepspaceSolarCount), deepspaceSolarColor0) +
                    deepspacePointSolarLighting(worldPos, normal, deepspaceSolarPosition1, step(2.0, deepspaceSolarCount), deepspaceSolarColor1) +
                    deepspacePointSolarLighting(worldPos, normal, deepspaceSolarPosition2, step(3.0, deepspaceSolarCount), deepspaceSolarColor2);
                deepspaceSolarLighting = min(deepspaceSolarLighting, vec3(0.55));
                blockLighting = max(blockLighting, deepspaceSolarLighting);""";
    private static final String DESATURATION_PATCH = """
            // A lit surface retains its material hue instead of BSL's fixed-midnight desaturation.
                float deepspaceMaterialColor = clamp(max(max(deepspaceSolarLighting.r, deepspaceSolarLighting.g), deepspaceSolarLighting.b), 0.0, 1.0);
                albedo = mix(desatAlbedo, albedo, max(desatAmount, deepspaceMaterialColor));""";

    private static final AtomicLong LOAD_GENERATION = new AtomicLong();
    private static final AtomicLong SOURCE_CALLS = new AtomicLong();
    private static final AtomicLong NON_NULL_SOURCES = new AtomicLong();
    private static final AtomicLong FUNCTION_MATCHES = new AtomicLong();
    private static final AtomicLong BLOCK_MATCHES = new AtomicLong();
    private static final AtomicLong SIGNATURE_MATCHES = new AtomicLong();
    private static final AtomicLong INVERSE_MODEL_VIEW_MATCHES = new AtomicLong();
    private static final AtomicLong DESATURATION_MATCHES = new AtomicLong();
    private static final AtomicLong SKY_COLOR_MATCHES = new AtomicLong();
    private static final AtomicLong SKY_PATCHED_SOURCES = new AtomicLong();
    private static final AtomicLong ALREADY_PATCHED_SOURCES = new AtomicLong();
    private static final AtomicLong PATCHED_SOURCES = new AtomicLong();
    private static volatile boolean bslSourcePatched;
    private static volatile boolean bslSkySourcePatched;
    private static volatile int lastSourceLength;
    private static volatile int lastPatchedLength;

    private BslSolarShaderPatcher() {
    }

    public static void beginShaderPackLoad() {
        LOAD_GENERATION.incrementAndGet();
        SOURCE_CALLS.set(0);
        NON_NULL_SOURCES.set(0);
        FUNCTION_MATCHES.set(0);
        BLOCK_MATCHES.set(0);
        SIGNATURE_MATCHES.set(0);
        INVERSE_MODEL_VIEW_MATCHES.set(0);
        DESATURATION_MATCHES.set(0);
        SKY_COLOR_MATCHES.set(0);
        SKY_PATCHED_SOURCES.set(0);
        ALREADY_PATCHED_SOURCES.set(0);
        PATCHED_SOURCES.set(0);
        bslSourcePatched = false;
        bslSkySourcePatched = false;
        lastSourceLength = 0;
        lastPatchedLength = 0;
    }

    /**
     * Patches only BSL 10 sources with the expected native lighting structure.
     */
    @Nullable
    public static String patchFragmentSource(@Nullable String source) {
        ensureDiagnosticSession();
        SOURCE_CALLS.incrementAndGet();
        if (source == null) {
            return null;
        }

        // Procedural moons bypass mesh interception; patch their own contribution before any early return.
        source = patchProceduralMoon(source);

        NON_NULL_SOURCES.incrementAndGet();
        lastSourceLength = source.length();
        if (source.contains(PATCH_MARKER) && source.contains(SKY_PATCH_MARKER)) {
            bslSourcePatched = true;
            bslSkySourcePatched = true;
            ALREADY_PATCHED_SOURCES.incrementAndGet();
            return source;
        }
        if (source.contains(PATCH_MARKER)) {
            bslSourcePatched = true;
        }
        if (source.contains(SKY_PATCH_MARKER)) {
            bslSkySourcePatched = true;
        }

        String patched = source;
        boolean functionMatch = source.contains(LIGHTING_FUNCTION_ANCHOR);
        boolean blockMatch = source.contains(BLOCK_LIGHTING_ANCHOR);
        boolean signatureMatch = source.contains(BSL_LIGHTING_SIGNATURE);
        boolean inverseModelViewMatch = source.contains(INVERSE_MODEL_VIEW_ANCHOR);
        boolean desaturationMatch = source.contains(DESATURATION_ANCHOR);
        if (functionMatch) {
            FUNCTION_MATCHES.incrementAndGet();
        }
        if (blockMatch) {
            BLOCK_MATCHES.incrementAndGet();
        }
        if (signatureMatch) {
            SIGNATURE_MATCHES.incrementAndGet();
        }
        if (inverseModelViewMatch) {
            INVERSE_MODEL_VIEW_MATCHES.incrementAndGet();
        }
        if (desaturationMatch) {
            DESATURATION_MATCHES.incrementAndGet();
        }
        if (!source.contains(PATCH_MARKER)
                && functionMatch && blockMatch && signatureMatch && inverseModelViewMatch) {
            patched = patched.replaceFirst(
                    java.util.regex.Pattern.quote(LIGHTING_FUNCTION_ANCHOR),
                    java.util.regex.Matcher.quoteReplacement(SOLAR_DECLARATIONS + LIGHTING_FUNCTION_ANCHOR)
            );
            patched = patched.replace(
                    BLOCK_LIGHTING_ANCHOR,
                    BLOCK_LIGHTING_PATCH
            );
            patched = patched.replace(
                    DESATURATION_ANCHOR,
                    DESATURATION_PATCH
            );
            PATCHED_SOURCES.incrementAndGet();
            bslSourcePatched = true;
        }
        patched = patchSkyColorSource(patched);
        patched = patchCloudSource(patched);
        if (!patched.equals(source)) {
            lastPatchedLength = patched.length();
        }
        return patched;
    }

    /** Disables BSL skybox and volumetric clouds, including their reflection calls, in vacuum. */
    private static String patchCloudSource(String source) {
        if (source.contains("DEEPSPACE_BSL_NO_CLOUDS")) {
            return source;
        }
        var functions = Pattern.compile("\\bvec4\\s+(DrawCloudSkybox|DrawCloudVolumetric)\\s*\\([^)]*\\)\\s*\\{")
                .matcher(source);
        if (!functions.find()) {
            return source;
        }
        String patched = functions.replaceAll(match -> match.group()
                + "\n    if (deepspaceCloudsDisabled > 0.5) return vec4(0.0);\n");
        int insertion = globalDeclarationInsertion(patched);
        return patched.substring(0, insertion)
                + "\n// DEEPSPACE_BSL_NO_CLOUDS: current dimension has no atmosphere.\n"
                + "uniform float deepspaceCloudsDisabled;\n"
                + patched.substring(insertion);
    }

    private static String patchSkyColorSource(String source) {
        if (source.contains(SKY_PATCH_MARKER)) {
            return source;
        }

        var declarations = SKY_COLOR_DECLARATION.matcher(source);
        boolean bslSky = false;
        boolean fog = false;
        while (declarations.find()) {
            if (declarations.group(1).equals("skyCol")) {
                bslSky |= BSL_SKY_INITIALIZER.matcher(declarations.group(2).trim()).matches();
            } else {
                fog = true;
            }
        }
        // Limit this to BSL's shared color library, including programs used for fog and reflections.
        if (!bslSky || !fog || !source.contains("sunSkyVisibility")) {
            return source;
        }

        SKY_COLOR_MATCHES.incrementAndGet();
        // Replace initializers: standalone assignments at global GLSL scope are illegal.
        String patched = SKY_COLOR_DECLARATION.matcher(source).replaceAll(match ->
                "vec3 " + match.group(1) + " = mix(" + match.group(2)
                        + ", deepspacePlanetSkyLinear(), clamp(deepspacePlanetSkyActive, 0.0, 1.0));");
        int declarationInsertAt = globalDeclarationInsertion(patched);
        patched = patched.substring(0, declarationInsertAt)
                + SKY_DECLARATIONS + patched.substring(declarationInsertAt);
        patched = patchSkyFunctionReturn(patched);
        SKY_PATCHED_SOURCES.incrementAndGet();
        bslSkySourcePatched = true;
        return patched;
    }

    /** Blocks BSL's procedural lunar disc and halo on managed skies while retaining its sun and night lighting. */
    private static String patchProceduralMoon(String source) {
        if (source.contains(MOON_PATCH_MARKER) || !source.contains("void ShaderSunMoon(")) return source;
        var moon = MOON_SIDE.matcher(source);
        if (!moon.find()) return source;
        String guarded = source.substring(0, moon.end()) + """

                // DEEPSPACE_BSL_MANAGED_MOON: actual planets replace procedural lunar scenery.
                if (deepspaceSuppressMoon > 0.5 && isMoon > 0.5) {
                    return;
                }
                """ + source.substring(moon.end());
        int insertion = globalDeclarationInsertion(guarded);
        return guarded.substring(0, insertion) + "\nuniform float deepspaceSuppressMoon;\n"
                + guarded.substring(insertion);
    }

    /** Recolor only the shared sky result, before stars/clouds and fragment output encoding. */
    private static String patchSkyFunctionReturn(String source) {
        var function = SKY_FUNCTION.matcher(source);
        if (!function.find()) {
            return source;
        }
        int bodyStart = function.end();
        int depth = 1;
        for (int index = bodyStart; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                String body = source.substring(bodyStart, index);
                String patchedBody = Pattern.compile("\\breturn\\s+sky\\s*;").matcher(body)
                        .replaceAll(java.util.regex.Matcher.quoteReplacement(SKY_RETURN_OVERRIDE));
                return source.substring(0, bodyStart) + patchedBody + source.substring(index);
            }
        }
        return source;
    }

    /** Skips the leading GLSL header so injected declarations remain legal top-level code. */
    private static int globalDeclarationInsertion(String source) {
        int cursor = 0;
        int insertion = 0;
        boolean inBlockComment = false;
        boolean continuingDirective = false;
        while (cursor < source.length()) {
            int lineEnd = source.indexOf('\n', cursor);
            int nextLine = lineEnd < 0 ? source.length() : lineEnd + 1;
            String line = source.substring(cursor, lineEnd < 0 ? source.length() : lineEnd).trim();

            if (inBlockComment) {
                int commentEnd = line.indexOf("*/");
                if (commentEnd < 0) {
                    insertion = nextLine;
                    cursor = nextLine;
                    continue;
                }
                inBlockComment = false;
                line = line.substring(commentEnd + 2).trim();
            }
            if (line.startsWith("/*")) {
                int commentEnd = line.indexOf("*/", 2);
                if (commentEnd < 0) {
                    inBlockComment = true;
                    insertion = nextLine;
                    cursor = nextLine;
                    continue;
                }
                line = line.substring(commentEnd + 2).trim();
            }

            boolean headerLine = line.isEmpty()
                    || line.startsWith("//")
                    || line.startsWith("#")
                    || continuingDirective;
            if (!headerLine) {
                break;
            }
            continuingDirective = (line.startsWith("#") || continuingDirective) && line.endsWith("\\");
            insertion = nextLine;
            cursor = nextLine;
        }
        return insertion;
    }

    public static boolean wasBslSourcePatched() {
        return bslSourcePatched;
    }

    public static Diagnostics diagnostics() {
        return new Diagnostics(
                LOAD_GENERATION.get(),
                SOURCE_CALLS.get(),
                NON_NULL_SOURCES.get(),
                FUNCTION_MATCHES.get(),
                BLOCK_MATCHES.get(),
                SIGNATURE_MATCHES.get(),
                INVERSE_MODEL_VIEW_MATCHES.get(),
                DESATURATION_MATCHES.get(),
                SKY_COLOR_MATCHES.get(),
                SKY_PATCHED_SOURCES.get(),
                ALREADY_PATCHED_SOURCES.get(),
                PATCHED_SOURCES.get(),
                lastSourceLength,
                lastPatchedLength,
                bslSourcePatched,
                bslSkySourcePatched
        );
    }

    public static String compactDiagnostics() {
        Diagnostics diagnostics = diagnostics();
        return "generation=" + diagnostics.loadGeneration()
                + " sourceCalls=" + diagnostics.sourceCalls()
                + " nonNull=" + diagnostics.nonNullSources()
                + " anchors=" + diagnostics.functionMatches()
                + "/" + diagnostics.blockMatches()
                + "/" + diagnostics.signatureMatches()
                + " patched=" + diagnostics.patchedSources()
                + " skyPatched=" + diagnostics.skyPatchedSources()
                + " active=" + diagnostics.sourcePatched();
    }

    public record Diagnostics(
            long loadGeneration,
            long sourceCalls,
            long nonNullSources,
            long functionMatches,
            long blockMatches,
            long signatureMatches,
            long inverseModelViewMatches,
            long desaturationMatches,
            long skyColorMatches,
            long skyPatchedSources,
            long alreadyPatchedSources,
            long patchedSources,
            int lastSourceLength,
            int lastPatchedLength,
            boolean sourcePatched,
            boolean skySourcePatched
    ) {
        public List<String> lines() {
            return List.of(
                    "source.sessionGeneration=" + loadGeneration,
                    "source.calls=" + sourceCalls + " nonNull=" + nonNullSources
                            + " alreadyPatched=" + alreadyPatchedSources,
                    "source.anchorMatches=function:" + functionMatches
                            + ",block:" + blockMatches
                            + ",signature:" + signatureMatches
                            + ",inverseModelView:" + inverseModelViewMatches
                            + ",desaturation:" + desaturationMatches
                            + ",sky:" + skyColorMatches,
                    "source.patchedFragments=" + patchedSources + " sourcePatched=" + sourcePatched,
                    "source.skyPatchedFragments=" + skyPatchedSources + " skySourcePatched=" + skySourcePatched,
                    "source.lastLengths=original:" + lastSourceLength + ",patched:" + lastPatchedLength
            );
        }
    }

    /**
     * Starts diagnostics even when Iris reloads GLSL without constructing a new ShaderPack.
     */
    private static void ensureDiagnosticSession() {
        LOAD_GENERATION.compareAndSet(0L, 1L);
    }
}



