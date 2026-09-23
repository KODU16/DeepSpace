#version 150

uniform vec4 ColorModulator;

in vec4 vertexColor;
in vec3 atmosphereNormal;
in vec3 viewDirection;
in vec3 lightDirection;

out vec4 fragColor;

void main() {
    vec3 normal = normalize(atmosphereNormal);
    vec3 toCamera = normalize(viewDirection);
    vec3 toSun = normalize(lightDirection);

    // Only the narrow, sun-lit limb contributes to the bloom target.
    float rim = smoothstep(0.78, 0.98, 1.0 - abs(dot(normal, toCamera)));
    float illumination = 0.045 + max(dot(normal, toSun), 0.0) * 0.70;
    vec3 configuredColor = ColorModulator.rgb * vertexColor.rgb;
    vec3 bloomColor = mix(configuredColor, vec3(0.62, 0.86, 1.0), 0.28);
    fragColor = vec4(bloomColor * (0.72 + illumination * 0.28), rim * illumination * 0.24);
    // Bloom fragments keep the same galaxy depth as the visible atmosphere pass.
    float viewDepth = 1.0 / max(gl_FragCoord.w, 1.0e-7);
    gl_FragDepth = clamp(1.0 - log2(1.0 + viewDepth) / 24.0, 0.0, 1.0);
}
