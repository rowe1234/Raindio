#version 410 core
/* DRAWBUFFERS:9 */

in vec2 texCoord;

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D depthtex0;
uniform sampler2D depthtex1;
uniform sampler2D noisetex;

uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferModelView;

uniform vec3 sunPosition;
uniform vec3 cameraPosition;
uniform float rainStrength;
uniform float frameTimeCounter;

uniform int isEyeInWater;

#include "/lib/common.glsl"
#include "/lib/projection.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/water.glsl"
#include "/lib/fog.glsl"
#include "/lib/ssr.glsl"

#define BLOCK_ID_WATER 1
#define BLOCK_ID_GLASS 2

layout(location = 0) out vec4 fragData0;

void main() {
    vec2 uv = texCoord;
    ivec2 pixelCoord = ivec2(gl_FragCoord.xy);

    float waterDepth  = texture(depthtex0, uv).r;
    float seabedDepth = texture(depthtex1, uv).r;

    vec4 colortex1_data = texelFetch(colortex1, pixelCoord, 0);
    int blockId = int(colortex1_data.z * 255.0 + 0.5);
    vec2 encodedDataN = colortex1_data.rg;
    float skyLightData = colortex1_data.a;

    float ssrDither = rand(gl_FragCoord.xy);

    vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float sunHeight = realSunDir.y;
    float nightDim = mix(0.05, 1.0, saturate(sunHeight * 4.0 + 0.5));
    bool isDay = realSunDir.y > 0.0;
    float mainLightVis = isDay ? saturate(realSunDir.y * 3.0) : saturate(-realSunDir.y * 3.0);

    bool isWater = (blockId == BLOCK_ID_WATER) && (waterDepth < 0.9999);
    bool isGlass = (blockId == BLOCK_ID_GLASS) && (waterDepth < 0.9999);
    bool underwater = (isEyeInWater == 1);

    vec3 reflection = vec3(0.0);

    // ==================== 水面反射 ====================
    if (isWater) {
        vec3 waterViewPos  = ScreenToView(uv, waterDepth);
        vec3 seabedViewPos = ScreenToView(uv, seabedDepth);
        vec3 waterViewDir  = normalize(waterViewPos);

        float viewDist = length(waterViewPos);
        float waveLod = clamp(1.0 - viewDist * 0.005, 0.15, 1.0);

        vec3 rawWaterWorldPos = (gbufferModelViewInverse * vec4(waterViewPos, 1.0)).xyz + cameraPosition;
        vec3 worldViewDir = normalize((gbufferModelViewInverse * vec4(waterViewDir, 0.0)).xyz);

        vec3 waterWorldPos = underwater ? rawWaterWorldPos : raymarch_water_pom(rawWaterWorldPos, worldViewDir, waveLod);
        waterViewPos = (gbufferModelView * vec4(waterWorldPos - cameraPosition, 1.0)).xyz;

        float waterThickness = max(0.0, abs(seabedViewPos.z - waterViewPos.z));

        // 几何法线（来自 colortex1）：区分水平湖面与垂直流下的水（瀑布）
        vec2 gN2 = encodedDataN * 2.0 - 1.0;
        float gNz = sqrt(max(0.0, 1.0 - dot(gN2, gN2)));
        vec3 geometricWorldNormal = normalize((gbufferModelViewInverse * vec4(normalize(vec3(gN2, gNz)), 0.0)).xyz);
        geometricWorldNormal.y = underwater ? -abs(geometricWorldNormal.y) : abs(geometricWorldNormal.y);
        geometricWorldNormal = normalize(geometricWorldNormal);

        vec3 flatWorldNormal = underwater ? vec3(0.0, -1.0, 0.0) : vec3(0.0, 1.0, 0.0);
        vec3 flatViewNormal  = normalize(mat3(gbufferModelView) * flatWorldNormal);
        float flatNdotV      = max(0.0, dot(flatViewNormal, -waterViewDir));

        vec3 rawWorldNormal = get_water_normal(waterWorldPos, waveLod, underwater);

        float surfaceHoriz = saturate(abs(geometricWorldNormal.y));
        float waveWeight = smoothstep(0.01, 0.22, flatNdotV) * clamp(1.0 - viewDist * 0.003, 0.2, 1.0);
        vec3 waterWorldNormal = normalize(mix(geometricWorldNormal, rawWorldNormal, waveWeight * surfaceHoriz));
        vec3 waterNormal = normalize(mat3(gbufferModelView) * waterWorldNormal);

        float NdotV_raw = dot(waterNormal, -waterViewDir);
        if (NdotV_raw < 0.001) {
            waterNormal = normalize(waterNormal - waterViewDir * (0.001 - NdotV_raw));
        }

        vec3 reflectViewDir = normalize(reflect(waterViewDir, waterNormal));
        vec3 startWaterPos = waterViewPos + waterNormal * (0.08 + 0.02 * length(waterViewPos));
        vec4 ssr = ray_trace_ssr(reflectViewDir, startWaterPos, ssrDither);
        reflection = ssr.rgb;

        if (ssr.a < 0.95) {
            if (!underwater) {
                vec3 reflectWorldDir = normalize((gbufferModelViewInverse * vec4(reflectViewDir, 0.0)).xyz);
                vec3 skyReflDir = reflectWorldDir;
                skyReflDir.y = max(skyReflDir.y, 0.08);

                vec3 skyRefl;
                if (isDay) {
                    skyRefl = AtmosphericScattering(skyReflDir, realSunDir, 1.0);
                    vec3 zenithSky = AtmosphericScattering(vec3(skyReflDir.x, 0.45, skyReflDir.z), realSunDir, 1.0);
                    float horizonFade = smoothstep(0.25, 0.02, reflectWorldDir.y);
                    skyRefl = mix(skyRefl, zenithSky * 0.85, horizonFade * 0.5);

vec3 fogColor = AtmosphericScattering(skyReflDir, realSunDir, 0.5);
    float reflFogFactor = 1.0 - exp(-viewDist * TERRAIN_FOG_DENSITY * 1.5);
    skyRefl = mix(skyRefl, fogColor, reflFogFactor);
                } else {
                    vec3 moonWorldDir = -realSunDir;
                    skyRefl = AtmosphericScattering(skyReflDir, moonWorldDir, 0.1) * 0.05 + vec3(0.001, 0.005, 0.018) * nightDim;
                    float moonDiscInRefl = SunDisc(skyReflDir, moonWorldDir);
                    skyRefl += moonDiscInRefl * vec3(0.5, 0.75, 1.0) * 4.0 * mainLightVis;
                }

                float horizonFade = smoothstep(-0.25, 0.12, reflectWorldDir.y);
                vec3 groundAmbient = vec3(0.005, 0.012, 0.02) * nightDim;
                skyRefl = mix(groundAmbient, skyRefl, horizonFade);

                reflection = mix(skyRefl, ssr.rgb, ssr.a);
            } else {
                vec2 refractOffset = waterNormal.xy * 0.02 * clamp(waterThickness, 0.0, 1.5);
                vec2 refractUV = clamp(uv + refractOffset, vec2(0.001), vec2(0.999));
                if (texture(depthtex1, refractUV).r <= waterDepth) refractUV = uv;
                vec3 bgRefract = texture(colortex0, refractUV).rgb;

                vec3 ambientRefl = mix(vec3(0.008, 0.06, 0.14) * nightDim, bgRefract * 0.6, 0.5);
                reflection = mix(ambientRefl, ssr.rgb, ssr.a);
            }
        }
    }
    // ==================== 玻璃反射 ====================
    else if (isGlass) {
        vec3 glassViewPos = ScreenToView(uv, waterDepth);
        vec3 glassViewDir = normalize(glassViewPos);

        float skyLightFactor = smoothstep(0.05, 0.85, skyLightData);

        vec2 n2 = encodedDataN * 2.0 - 1.0;
        float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
        vec3 glassNormal = normalize(vec3(n2, nz));

        vec3 reflectViewDir = normalize(reflect(glassViewDir, glassNormal));
        vec3 startGlassPos = glassViewPos + glassNormal * (0.15 + 0.02 * length(glassViewPos));
        vec4 ssr = ray_trace_ssr(reflectViewDir, startGlassPos, ssrDither);
        reflection = ssr.rgb;

        if (ssr.a < 0.95) {
            vec3 reflectWorldDir = normalize((gbufferModelViewInverse * vec4(reflectViewDir, 0.0)).xyz);
            reflectWorldDir.y = max(reflectWorldDir.y, 0.05);
            vec3 skyRefl = isDay ? AtmosphericScattering(reflectWorldDir, realSunDir, 1.0) :
                                   AtmosphericScattering(reflectWorldDir, -realSunDir, 0.1) * 0.05 + vec3(0.001, 0.005, 0.018);

            vec3 indoorAmbient = vec3(0.005, 0.008, 0.012) * nightDim;
            skyRefl = mix(indoorAmbient, skyRefl, skyLightFactor);

            reflection = mix(skyRefl, ssr.rgb, ssr.a);
        }
    }
    // ==================== 湿地面反射 ====================
    else if (waterDepth < 0.9999) {
        vec3 viewPos = ScreenToView(uv, waterDepth);
        vec3 viewDir = normalize(viewPos);

        vec2 n2 = encodedDataN * 2.0 - 1.0;
        float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
        vec3 viewNormal = normalize(vec3(n2, nz));

        vec3 worldNormal = normalize((gbufferModelViewInverse * vec4(viewNormal, 0.0)).xyz);

        float isFloor = smoothstep(0.30, 0.70, worldNormal.y);
        float skyLightFactor = smoothstep(0.85, 0.98, skyLightData);
        float wetness = clamp(rainStrength, 0.0, 1.0) * skyLightFactor * isFloor;

        if (wetness > 0.01) {
            float NdotV = max(0.0, dot(viewNormal, -viewDir));
            vec3 startPos = viewPos + viewNormal * (0.06 + 0.02 * length(viewPos));

            vec3 reflectViewDir = normalize(reflect(viewDir, viewNormal));
            vec4 ssr = ray_trace_ssr(reflectViewDir, startPos, ssrDither);

            vec3 reflectWorldDir = normalize((gbufferModelViewInverse * vec4(reflectViewDir, 0.0)).xyz);
            reflectWorldDir.y = max(reflectWorldDir.y, 0.05);

            vec3 skyRefl = AtmosphericScattering(reflectWorldDir, realSunDir, 0.8);

            vec3 rainSkyAmbient = mix(vec3(0.35, 0.40, 0.48), skyRefl, 0.6) * 0.85 * skyLightFactor;
            reflection = mix(rainSkyAmbient, ssr.rgb, ssr.a);
        }
    }
    // 在文件最底部 fragData0 输出前
reflection = clamp(reflection, vec3(0.0), vec3(10.0));
    fragData0 = vec4(reflection, 1.0);
}
