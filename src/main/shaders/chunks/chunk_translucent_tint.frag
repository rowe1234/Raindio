#version 450

#include "common/raindio_uniforms.glsl"
#include "lib/common.glsl"
#include "lib/lighting.glsl"

layout(set = 0, binding = 1) uniform sampler2D Sampler0;
layout(set = 0, binding = 2) uniform sampler2D Sampler2;

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec2 vLightmap;
layout(location = 3) in vec3 vWorldRel;

layout(location = 0) out vec4 fragColor; // Hardware alpha-blended (SRC_ALPHA, ONE_MINUS_SRC_ALPHA) into colortex0

void main() {
    vec4 tex = texture(Sampler0, vTexCoord);
    vec4 albedo = tex * vColor;
    if (albedo.a < 0.01) {
        discard;
    }

    // Water is shaded physically in composite3 (Snell refraction + Beer-Lambert absorption);
    // skip water here so only non-water translucent layers (stained glass, ice, slime, honey) accumulate.
    bool likelyWater = (vColor.b > vColor.r * 1.04) || (vColor.g > vColor.r * 1.04 && vColor.b > 0.25);
    if (likelyWater) {
        discard;
    }

    vec3 linearAlbedo = pow(max(albedo.rgb, vec3(0.0)), vec3(1.5));

    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    vec3 skyCol, sunCol;
    float dayFactor, sunIntensity;
    computeUnifiedLighting(worldSunDir, skyCol, sunCol, dayFactor, sunIntensity);

    float rainClear = 1.0 - saturate(rainStrength);
    float skyMask = pow(max(vLightmap.y, 0.0), 1.5);
    float bl = vLightmap.x;

    vec3 sunFill = sunCol * (0.45 * sunIntensity * rainClear * skyMask);
    vec3 skyFill = skyCol * (0.42 * skyMask) + vec3(0.012, 0.016, 0.024);
    vec3 blockFill = vec3(1.0, 0.68, 0.36) * (bl * bl * 1.25);

    vec3 litTranslucent = linearAlbedo * (sunFill + skyFill + blockFill);
    float layerAlpha = clamp(albedo.a * 0.72, 0.06, 0.85);
    fragColor = vec4(max(litTranslucent, vec3(0.0)), layerAlpha);
}
