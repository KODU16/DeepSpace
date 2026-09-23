#version 150

#line 0 1
/*#version 150*/

vec4 linear_fog(vec4 inColor, float vertexDistance, float fogStart, float fogEnd, vec4 fogColor) {
    if (vertexDistance <= fogStart) {
        return inColor;
    }

    float fogValue = vertexDistance < fogEnd ? smoothstep(fogStart, fogEnd, vertexDistance) : 1.0;
    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
}

float linear_fog_fade(float vertexDistance, float fogStart, float fogEnd) {
    if (vertexDistance <= fogStart) {
        return 1.0;
    } else if (vertexDistance >= fogEnd) {
        return 0.0;
    }

    return smoothstep(fogEnd, fogStart, vertexDistance);
}

float fog_distance(vec3 pos, int shape) {
    if (shape == 0) {
        return length(pos);
    } else {
        float distXZ = length(pos.xz);
        float distY = abs(pos.y);
        return max(distXZ, distY);
    }
}
#line 3 0

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform float Time;

in float vertexDistance;
in vec4 vertexColor;
in vec3 atmosphereNormal;
in vec3 viewDirection;
in vec3 lightDirection;

out vec4 fragColor;
void main() {
    vec3 normal = normalize(atmosphereNormal);
    vec3 toCamera = normalize(viewDirection);
    vec3 toSun = normalize(lightDirection);

    // Grazing views form the thin shell while direct sunlight selects the bright limb.
    float rim = smoothstep(0.08, 0.92, 1.0 - abs(dot(normal, toCamera)));
    float solarDiffuse = max(dot(normal, toSun), 0.0);
    float forwardScatter = pow(max(dot(-toCamera, toSun), 0.0), 8.0) * rim;
    // Match planet.vsh so the outer shell follows the lit/dark surface.
    float illumination = 0.045 + solarDiffuse * 0.70;

    vec3 baseColor = ColorModulator.rgb * vertexColor.rgb;
    float whitening = clamp(solarDiffuse * 0.22 + forwardScatter * 0.35, 0.0, 0.5);
    vec3 scatteredColor = mix(baseColor, vec3(1.0), whitening) * illumination;
    // Face centers stay clear; scattering is confined to grazing angles.
    float alpha = clamp(pow(rim, 1.35) * (0.28 + illumination * 0.62)
            + forwardScatter * 0.16, 0.0, 0.62)
            * ColorModulator.a * vertexColor.a;
    fragColor = vec4(max(scatteredColor, vec3(0.0)), alpha);
    // Transparent shells must test against the same depth encoding as their planet.
    float viewDepth = 1.0 / max(gl_FragCoord.w, 1.0e-7);
    gl_FragDepth = clamp(1.0 - log2(1.0 + viewDepth) / 24.0, 0.0, 1.0);
}
