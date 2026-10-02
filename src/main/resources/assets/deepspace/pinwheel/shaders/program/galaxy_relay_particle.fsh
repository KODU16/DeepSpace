#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform float DiscardThreshold;
uniform vec4 HDR;
uniform int HDRMode;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
    if (color.a < DiscardThreshold) discard;
    if (HDRMode == 0) color.rgb += HDR.a * HDR.rgb;
    else color.rgb *= HDR.a * HDR.rgb;
    fragColor = color;
    // Match opaque galaxy bodies without writing depth from translucent particles.
    float viewDepth = 1.0 / max(gl_FragCoord.w, 1.0e-7);
    gl_FragDepth = clamp(1.0 - log2(1.0 + viewDepth) / 24.0, 0.0, 1.0);
}
