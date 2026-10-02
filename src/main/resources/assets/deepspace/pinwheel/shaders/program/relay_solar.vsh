#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;
layout(location = 5) in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
// Zero defaults to uncompressed geometry for other users of this vertex shader.
uniform float GeometryDepthScale;
uniform vec3 ChunkOffset;
uniform vec3 SunPosition;

out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    // Compare the star, vertex and normal after the same model-view transform.
    vec3 viewPosition = (ModelViewMat * vec4(Position + ChunkOffset, 1.0)).xyz;
    vec3 viewNormal = normalize(mat3(ModelViewMat) * Normal);
    float sunlight = max(dot(normalize(SunPosition - viewPosition), viewNormal), 0.0);
    vertexColor = Color * vec4(vec3(0.045 + 0.70 * sunlight), 1.0);
    texCoord0 = UV0;
    gl_Position = ProjMat * vec4(viewPosition, 1.0);
    // Homogeneous rescaling preserves NDC and clipping but restores physical depth.
    gl_Position /= GeometryDepthScale > 0.0 ? GeometryDepthScale : 1.0;
}
