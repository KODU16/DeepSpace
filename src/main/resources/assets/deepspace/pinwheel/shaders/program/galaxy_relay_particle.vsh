#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// Zero defaults to uncompressed geometry for other users of this vertex shader.
uniform float GeometryDepthScale;

out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    // Photon emits camera-relative geometry; use the galaxy view and projection unchanged.
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // Homogeneous rescaling preserves NDC and clipping but restores physical depth.
    gl_Position /= GeometryDepthScale > 0.0 ? GeometryDepthScale : 1.0;
    vertexColor = Color;
    texCoord0 = UV0;
}
