#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 surface = texture(Sampler0, texCoord0);
    surface.rgb = mix(surface.rgb, vec3(1.0), 0.16);
    fragColor = vec4(surface.rgb * vertexColor.rgb * ColorModulator.rgb, 1.0);
    // Galaxy stars use the same forward logarithmic depth as planets and ring worlds.
    float viewDepth = 1.0 / max(gl_FragCoord.w, 1.0e-7);
    gl_FragDepth = clamp(1.0 - log2(1.0 + viewDepth) / 24.0, 0.0, 1.0);
}
