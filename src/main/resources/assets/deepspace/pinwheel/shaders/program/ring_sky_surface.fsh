#version 150

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 texel = texture(Sampler0, texCoord0);
    if (texel.a < 0.1) {
        discard;
    }
    // Sky terrain is reflective scenery: reduce fullbright colour and ignore inherited sky alpha/tint.
    vec3 color = texel.rgb * vertexColor.rgb * 0.72;
    fragColor = vec4(color, 1.0);
    // Match healthy and broken frames exactly, retaining precision across the projected sky ring.
    float viewDepth = 1.0 / max(gl_FragCoord.w, 1.0e-7);
    gl_FragDepth = clamp(1.0 - log2(1.0 + viewDepth) / 24.0, 0.0, 1.0);
}
