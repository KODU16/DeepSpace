#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 glow = texture(Sampler0, texCoord0);
    fragColor = vec4(vertexColor.rgb * ColorModulator.rgb,
            glow.a * vertexColor.a * ColorModulator.a);
    // Match the reversed log depth used by the ring surface and opaque star.
    float viewDepth = 1.0 / max(gl_FragCoord.w, 1.0e-7);
    gl_FragDepth = clamp(1.0 - log2(1.0 + viewDepth) / 24.0, 0.0, 1.0);
}
