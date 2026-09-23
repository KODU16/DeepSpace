#version 150
#line 0 1
#veil:buffer veil:camera VeilCamera

in vec3 Position;
in vec4 Color;
in vec2 UV0;
layout(location = 5) in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 ChunkOffset;
uniform float Time;
uniform vec3 SunPosition;

out vec4 vertexColor;
out vec2 cloudUv;
out vec3 cloudNormal;
out vec3 cloudLightDirection;

void main() {
    vec3 pos = Position + ChunkOffset + VeilCamera.CameraBobOffset;
    gl_Position = ProjMat * ModelViewMat * vec4(pos + Normal * 0.15, 1.0);

    vertexColor = Color;
    cloudUv = UV0 + vec2(Time / 5200.0, Time / 7600.0);
    cloudNormal = normalize(Normal);
    cloudLightDirection = SunPosition - pos;
}
