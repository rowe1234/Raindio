#version 450

#include "common/raindio_uniforms.glsl"
#include "lib/common.glsl"

layout(std140, set = 0, binding = 1) uniform HandUniforms {
    mat4 handProjection;
    vec4 handEmissiveColorAndStrength; // rgb: held light color, a: held emissive strength [0..1]
    vec4 handPtDirAndStr;              // xyz: dominant RT point-light dir, w: active (0/1)
    vec4 handPtColor;                  // rgb: dominant RT point-light radiance
    vec4 handPtAmb;                    // rgb: omni RT point-light fill at hand
};

layout(set = 0, binding = 2) uniform sampler2D Sampler0;
layout(set = 0, binding = 3) uniform sampler2D shadowtex0;
layout(set = 0, binding = 4) uniform sampler2D autoExposureTex;

#ifndef EXPOSURE
#define EXPOSURE 0.95
#endif
#ifndef SATURATION
#define SATURATION 1.16
#endif
#define LUMA_GAMMA 1.02
#define WHITE_CLIP 1.0

layout(location = 0) in vec2 vTexCoord;
layout(location = 1) in vec4 vColor;
layout(location = 2) in vec2 vLightmap;
layout(location = 3) in vec3 vWorldRel;
layout(location = 4) in vec3 vWorldNormal;
layout(location = 5) in vec3 vSkyColor;
layout(location = 6) in vec3 vSunlight;
layout(location = 7) in vec4 vParams;

layout(location = 0) out vec4 fragColor;

vec2 vogelDiskSample(int sampleIdx, int totalSamples, float phi) {
    float r = sqrt((float(sampleIdx) + 0.5) / float(totalSamples));
    float theta = float(sampleIdx) * 2.399963229728653 + phi;
    return vec2(cos(theta), sin(theta)) * r;
}

float handD_GGX(float NdotH, float rough) {
    float a = rough * rough;
    float a2 = a * a;
    float denom = NdotH * NdotH * (a2 - 1.0) + 1.0;
    return a2 / max(3.14159265 * denom * denom, 1e-5);
}

float handG_Smith(float NdotV, float NdotL, float rough) {
    float k = (rough + 1.0);
    k = (k * k) * 0.125;
    float gv = NdotV / (NdotV * (1.0 - k) + k);
    float gl = NdotL / (NdotL * (1.0 - k) + k);
    return gv * gl;
}

vec3 handF_Schlick(float cosTheta, vec3 F0) {
    return F0 + (1.0 - F0) * pow(clamp(1.0 - cosTheta, 0.0, 1.0), 5.0);
}

// Soft world shadow sampled slightly above/toward sun from player eye (avoids player-body self-shadowing)
float sampleHandWorldShadow(vec3 worldLightDir) {
    vec3 probePos = vec3(0.0, 0.16, 0.0) + worldLightDir * 0.45;
    vec4 shadowClip = shadowProjection * shadowModelView * vec4(probePos, 1.0);
    vec3 shadowNDC = shadowClip.xyz / max(abs(shadowClip.w), 1e-5);

    float l = length(shadowNDC.xy);
    float dFac = (l > 0.00001) ? (l * 0.8 + 0.2) : 0.2;
    shadowNDC.xy /= dFac;

    vec3 shadowCoord = vec3(shadowNDC.xy * 0.5 + 0.5, shadowNDC.z);
    if (shadowCoord.x < 0.002 || shadowCoord.x > 0.998 ||
        shadowCoord.y < 0.002 || shadowCoord.y > 0.998 ||
        shadowCoord.z < 0.0005 || shadowCoord.z > 0.9995) {
        return 1.0;
    }

    float oneTexel = 1.0 / max(SHADOW_MAP_RES, 512.0);
    float invDistort = clamp(1.0 / max(dFac, 0.2), 1.0, 4.0);
    float radius = 2.8 * oneTexel * mix(1.0, invDistort * 0.36, 0.55);
    float receiverZ = shadowCoord.z - 0.00055;
    float phi = interleavedGradientNoise(gl_FragCoord.xy) * 6.2831853;

    float vis = 0.0;
    for (int i = 0; i < 6; i++) {
        vec2 tapUV = clamp(shadowCoord.xy + vogelDiskSample(i, 6, phi) * radius, vec2(0.001), vec2(0.999));
        float mapZ = textureLod(shadowtex0, tapUV, 0.0).r;
        vis += (receiverZ <= mapZ) ? 1.0 : 0.0;
    }
    return vis * (1.0 / 6.0);
}

// AgX Filmic Tone Mapping (matches composite5.frag 1:1)
const mat3 AGX_INSET = mat3(
    0.842479062253094,  0.0423282422610123, 0.0423756549057051,
    0.0784335999999992, 0.878468636469772,  0.0784336,
    0.0792237451477643, 0.0791661274605434, 0.879142973793104
);

const mat3 AGX_OUTSET = mat3(
     1.19687900512017,   -0.0528968517574562, -0.0529716355144438,
    -0.0980208811401368,  1.15190312990417,   -0.0980434501171241,
    -0.0990297440797205, -0.0989611768448433,  1.15107367264116
);

vec3 agxDefaultContrastApprox(vec3 x) {
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    return + 15.5     * x4 * x2
           - 40.14    * x4 * x
           + 31.96    * x4
           - 6.868    * x2 * x
           + 0.4298   * x2
           + 0.1191   * x
           - 0.00232;
}

vec3 AgXFilmic(vec3 x) {
    const float minEv = -12.47393;
    const float maxEv =   4.026069;
    vec3 v = AGX_INSET * max(x, vec3(0.0));
    v = clamp(log2(max(v, vec3(1e-10))), minEv, maxEv);
    v = (v - minEv) / (maxEv - minEv);
    v = agxDefaultContrastApprox(v);
    v = AGX_OUTSET * v;
    return clamp(v, 0.0, 1.0);
}

void main() {
    vec4 tex = texture(Sampler0, vTexCoord);
    vec4 albedo = tex * vColor;
    if (albedo.a < 0.1) {
        discard;
    }

    // World-space normal with geometric derivative fallback.
    // Never use !gl_FrontFacing in Vulkan Y-down clip space (inverted Y flips winding and turns hand normals backwards!);
    // instead orient double-sided flat item quads toward the camera only when strongly back-facing.
    float nLen = length(vWorldNormal);
    vec3 worldN;
    if (nLen > 1e-4) {
        worldN = vWorldNormal / nLen;
    } else {
        vec3 dpdx = dFdx(vWorldRel);
        vec3 dpdy = dFdy(vWorldRel);
        vec3 cn = cross(dpdx, dpdy);
        float cLen = length(cn);
        worldN = (cLen > 1e-8) ? (cn / cLen) : vec3(0.0, 1.0, 0.0);
    }
    vec3 toCam = normalize(-vWorldRel);
    if (dot(worldN, toCam) < -0.20) {
        worldN = -worldN;
    }

    // Rain wetness on outdoors hand/item
    float wetness = 0.0;
    if (rainStrength > 0.005) {
        float skyExposure = smoothstep(0.50, 0.90, vLightmap.y);
        float upFactor = clamp(worldN.y * 0.65 + 0.35, 0.0, 1.0);
        wetness = saturate(rainStrength) * skyExposure * upFactor;
        albedo.rgb *= mix(1.0, 0.82, wetness);
    }

    vec3 linearAlbedo = pow(max(albedo.rgb, vec3(0.0)), vec3(2.1));

    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    vec3 worldLightDir = (worldSunDir.y >= 0.0) ? worldSunDir : -worldSunDir;
    vec3 worldViewDir = normalize(-vWorldRel);

    float dayFactor = vParams.x;
    float sunIntensity = vParams.y;
    float heldEmissiveStrength = vParams.z;
    float rainClear = 1.0 - saturate(rainStrength);

    // Wrapped NdotL so side faces of held blocks/tools retain crisp sculpted relief without crushing to black
    float rawNdotL = max(dot(worldN, worldLightDir), 0.0);
    float wrapNdotL = clamp((dot(worldN, worldLightDir) + 0.25) / 1.25, 0.0, 1.0);

    float skyShadowGate = smoothstep(0.02, 0.16, vLightmap.y);
    float shadow = (skyShadowGate > 0.001) ? (sampleHandWorldShadow(worldLightDir) * skyShadowGate) : 0.0;

    float nightSkyLift = mix(1.15, 1.0, smoothstep(-0.10, 0.18, worldSunDir.y));
    vec3 sunLightCol = vSunlight * (sunIntensity * (2.85 * rainClear + 0.30) * nightSkyLift);
    vec3 directLight = sunLightCol * (mix(rawNdotL, wrapNdotL, 0.30) * shadow);

    // 3-Zone Directional Sky Ambient strictly gated by outdoor sky exposure
    float skyMask = pow(smoothstep(0.06, 1.0, vLightmap.y), 1.8);
    float upMask = saturate(worldN.y);
    float downMask = saturate(-worldN.y);
    float sunBounceAlign = saturate(dot(worldN, -worldLightDir) * 0.5 + 0.5);
    vec3 skyZenithFill = vSkyColor * vec3(0.28, 0.31, 0.36);
    vec3 horizonFill = mix(vSkyColor * 0.22, vSkyColor * 0.20 + vSunlight * (0.075 * dayFactor * rainClear), sunBounceAlign);
    vec3 groundBounceFill = vSkyColor * 0.14 + vSunlight * vec3(1.05, 0.88, 0.65) * (0.085 * dayFactor * rainClear);
    vec3 directionalAmb = mix(horizonFill, skyZenithFill, upMask);
    directionalAmb = mix(directionalAmb, groundBounceFill, downMask);
    vec3 skyAmb = directionalAmb * (skyMask * nightSkyLift);

    // Real-time colored RT point-light + room bounce GI fill on first-person hand
    vec3 blockLight = vec3(0.0);
    if (handPtDirAndStr.w > 0.5) {
        vec3 ptDir = normalize(handPtDirAndStr.xyz);
        float ptNdotL = clamp((dot(worldN, ptDir) + 0.40) / 1.40, 0.0, 1.0);
        blockLight += handPtColor.rgb * (ptNdotL * 1.15);
    }
    blockLight += handPtAmb.rgb * 1.10;
    // Indirect room bounce fallback tinted by nearby point-light hue so the hand matches LPV-lit rooms
    float bl = vLightmap.x;
    if (bl > 0.01) {
        float ambPeak = max(handPtAmb.r, max(handPtAmb.g, handPtAmb.b));
        vec3 roomHue = (ambPeak > 0.02) ? (handPtAmb.rgb / ambPeak) : vec3(1.0, 0.86, 0.68);
        blockLight += roomHue * (bl * bl * 0.42);
    }
    vec3 heldLightFill = handEmissiveColorAndStrength.rgb * (heldEmissiveStrength * 0.65);
    vec3 caveAmb = vec3(0.008, 0.010, 0.014);

    // Pixel-selective GGX PBR specular (bright/metallic pixels glint smoothly)
    vec3 H = normalize(worldLightDir + worldViewDir);
    float NdotH = max(dot(worldN, H), 0.0);
    float NdotV = max(dot(worldN, worldViewDir), 0.001);
    float texLum = luminance(tex.rgb);
    float peakTex = max(tex.r, max(tex.g, tex.b));
    float pbrHighlight = max(texLum, peakTex * 0.92);
    float specBrightMask = smoothstep(0.62, 0.90, pbrHighlight);
    float baseRough = mix(0.92, 0.22, specBrightMask);
    float rough = mix(baseRough, 0.16, wetness);
    float D = handD_GGX(NdotH, rough);
    float G = handG_Smith(NdotV, max(rawNdotL, 0.001), rough);
    vec3 highlightF0 = mix(vec3(0.04), mix(vec3(0.14), linearAlbedo * 0.65 + vec3(0.18), 0.65), specBrightMask);
    vec3 F0 = mix(highlightF0, vec3(0.06), wetness);
    vec3 F = handF_Schlick(max(dot(H, worldViewDir), 0.0), F0);
    float horizonOcc = smoothstep(0.0, 0.12, rawNdotL);
    vec3 spec = (D * G * F) / max(4.0 * NdotV * max(rawNdotL, 0.001), 0.001);
    float specGate = max(specBrightMask * 1.15, wetness);
    spec = min(spec, vec3(2.2)) * vSunlight * (rawNdotL * shadow * horizonOcc * sunIntensity * mix(rainClear, 0.55, wetness) * specGate);

    vec3 hdr = linearAlbedo * (directLight + skyAmb + caveAmb + blockLight + heldLightFill) + spec;

    // Emissive pixel boost when holding glowing blocks/items (glowstone, lanterns, torches, etc.)
    if (heldEmissiveStrength > 0.01) {
        float pixelEmMask = smoothstep(0.45, 0.80, peakTex) * smoothstep(0.32, 0.72, texLum);
        float emLum = luminance(linearAlbedo);
        vec3 vividEm = max(vec3(emLum) + (linearAlbedo - vec3(emLum)) * 1.22, vec3(0.0));
        hdr += vividEm * (heldEmissiveStrength * pixelEmMask * 2.1);
    }

    // Match Raindio's GPU Auto-Exposure (composite5.frag)
    float autoExp = texelFetch(autoExposureTex, ivec2(0, 0), 0).r;
    float exposure;
    if (autoExp > 0.01 && !isnan(autoExp) && !isinf(autoExp)) {
        exposure = autoExp * EXPOSURE * mix(1.0, 0.78, saturate(rainStrength));
    } else {
        float rainDarken = mix(1.0, 0.58, saturate(rainStrength));
        float eyeSky = clamp(float(eyeBrightnessSmooth.y) / 240.0, 0.0, 1.0);
        float eyeBlock = clamp(float(eyeBrightnessSmooth.x) / 240.0, 0.0, 1.0);
        float dayBlend = smoothstep(-0.15, 0.25, worldSunDir.y);
        float outdoorExp = mix(1.45, 1.05, dayBlend);
        float indoorExp = mix(1.75, 1.25, smoothstep(0.15, 0.75, eyeBlock));
        exposure = mix(indoorExp, outdoorExp, smoothstep(0.05, 0.85, eyeSky)) * EXPOSURE * rainDarken;
    }

    // Match AgX Filmic Tonemap + Shinkai Split-Toning (composite5.frag)
    vec3 result = AgXFilmic(max(hdr, vec3(0.0)) * exposure);
    result = pow(max(result, vec3(0.0)), vec3(1.05));

    float luma = luminance(result);
    vec3 shadowTint = vec3(0.90, 0.96, 1.07);
    vec3 highlightTint = vec3(1.02, 1.00, 0.97);
    result *= mix(shadowTint, highlightTint, smoothstep(0.14, 0.74, luma));

    // Match Color Grading (final.frag)
    const vec3 lumaWeights = vec3(0.2126, 0.7152, 0.0722);
    float cLuma = dot(result, lumaWeights);
    float maxC = max(result.r, max(result.g, result.b));
    float minC = min(result.r, min(result.g, result.b));
    float curSat = (maxC > 1e-4) ? ((maxC - minC) / maxC) : 0.0;
    float effSat = (SATURATION > 1.0)
        ? mix(SATURATION, 1.0 + (SATURATION - 1.0) * 0.35, smoothstep(0.45, 0.90, curSat))
        : SATURATION;
    result = mix(vec3(cLuma), result, effSat);
    result = pow(max(result, vec3(0.0)), vec3(1.0 / LUMA_GAMMA));
    result = clamp(result / WHITE_CLIP, 0.0, 1.0);

    fragColor = vec4(result, albedo.a);
}
