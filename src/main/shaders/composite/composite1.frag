#version 450
#include "common/raindio_uniforms.glsl"

/* DRAWBUFFERS:3 */

layout(location = 0) in vec2 texCoord;

layout(set = 0, binding = 1) uniform sampler2D depthtex0;
layout(set = 0, binding = 2) uniform sampler2D colortex1;
layout(set = 0, binding = 3) uniform sampler2D noisetex;

#include "lib/common.glsl"
#include "lib/projection.glsl"
#include "lib/water.glsl"

layout(location = 0) out vec4 fragWaterData; // colortex3: oct(waterWorldN).rg, waveH_raw.b, roughness.a

void main() {
    vec2 uv = texCoord;
    ivec2 pixelCoord = ivec2(gl_FragCoord.xy);

    float waterDepth = texture(depthtex0, uv).r;
    vec4 colortex1_data = texelFetch(colortex1, pixelCoord, 0);
    int blockId = int(colortex1_data.z * 255.0 + 0.5);
    vec2 encodedDataN = colortex1_data.rg;
    float skyLightData = colortex1_data.a;

    bool isNotSky = isSceneDepth(waterDepth);
    bool isFlowingWater = (blockId == MAT_WATER_FLOWING);
    bool isBubbleCol = (blockId == MAT_BUBBLE_COLUMN);
    bool isWater = isMatWater(blockId) && isNotSky;
    bool underwater = (isEyeInWater == 1);

    // Precompute PTGI Water Surface (with Parallax)
    vec4 waterOut = vec4(0.5, 0.5, 0.0, 0.02);
    if (isWater) {
        vec3 waterViewPos = ScreenToView(uv, waterDepth);
        vec3 waterViewDir = normalize(waterViewPos);
        float viewDist = length(waterViewPos);

        vec3 waterWorldPos = (gbufferModelViewInverse * vec4(waterViewPos, 1.0)).xyz + cameraPosition;
        vec3 worldViewDir = normalize((gbufferModelViewInverse * vec4(waterViewDir, 0.0)).xyz);

        vec3 geometricWorldNormal = octDecode(encodedDataN);
        geometricWorldNormal.y = underwater ? -abs(geometricWorldNormal.y) : abs(geometricWorldNormal.y);
        geometricWorldNormal = normalize(geometricWorldNormal);

        float surfaceHoriz = saturate(abs(geometricWorldNormal.y));

        vec3 sampleWorldPos = waterWorldPos;
        if (surfaceHoriz >= 0.35) {
            sampleWorldPos.xz = compute_water_parallax(
                waterWorldPos.xz, worldViewDir, viewDist, rainStrength, isFlowingWater, isBubbleCol
            );
        }

        vec3 flatWorldNormal = underwater ? vec3(0.0, -1.0, 0.0) : vec3(0.0, 1.0, 0.0);
        vec3 flatViewNormal = normalize(mat3(gbufferModelView) * flatWorldNormal);
        float flatNdotV = max(0.0, dot(flatViewNormal, -waterViewDir));

        float waveH_raw = 0.0;
        float waterRoughness = 0.018;
        vec3 rawWorldNormal = get_water_surface(
            sampleWorldPos, geometricWorldNormal, viewDist, underwater, rainStrength, skyLightData, isFlowingWater, isBubbleCol,
            waveH_raw, waterRoughness
        );

        float waveWeight = (surfaceHoriz < 0.35)
            ? clamp(1.0 - viewDist * 0.0035, 0.25, 1.0)
            : (smoothstep(0.004, 0.14, flatNdotV) * clamp(1.0 - viewDist * 0.0020, 0.22, 1.0));
        vec3 waterWorldNormal = normalize(mix(geometricWorldNormal, rawWorldNormal, waveWeight));

        waterOut = vec4(octEncode(waterWorldNormal), waveH_raw, waterRoughness);
    }

    fragWaterData = waterOut;
}
