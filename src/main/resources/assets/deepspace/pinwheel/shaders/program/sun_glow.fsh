#version 150

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

void main() {
    vec4 glow = texture(Sampler0, texCoord0);
    // Preserve radial transparency when Iris draws the glow into its sky target.
    fragColor = vec4(vertexColor.rgb * ColorModulator.rgb,
            glow.a * vertexColor.a * ColorModulator.a);
}
