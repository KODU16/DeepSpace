#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec4 vertexColor;
in vec2 cloudUv;
in vec3 cloudNormal;
in vec3 cloudLightDirection;

out vec4 fragColor;

void main() {
    // Two slowly separating samples retain the original texture without a static shell.
    float primary = texture(Sampler0, cloudUv).r;
    float secondary = texture(Sampler0, cloudUv * 1.65 + vec2(0.17, -0.11)).r;
    float density = smoothstep(0.08, 0.34, max(primary, secondary * 0.78));

    float solarDiffuse = max(dot(normalize(cloudNormal), normalize(cloudLightDirection)), 0.0);
    // Inner cloud shell uses the same day/night curve as the planet surface.
    float illumination = 0.045 + solarDiffuse * 0.70;
    vec3 configuredColor = ColorModulator.rgb * vertexColor.rgb;
    vec3 cloudColor = mix(vec3(1.0), configuredColor, 0.42) * illumination;
    // Clouds remain visible without washing out the terrain below them.
    float alpha = density * 0.18 * ColorModulator.a * vertexColor.a;
    fragColor = vec4(cloudColor, alpha);
    // Transparent shells must test against the same depth encoding as their planet.
    float viewDepth = 1.0 / max(gl_FragCoord.w, 1.0e-7);
    gl_FragDepth = clamp(1.0 - log2(1.0 + viewDepth) / 24.0, 0.0, 1.0);
}
