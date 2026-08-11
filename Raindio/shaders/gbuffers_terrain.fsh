#version 120

varying vec3 vNormal;
varying vec4 vColor;
varying vec4 vTexCoord;
varying vec3 vEyePos;
varying vec2 vLightmap;
varying float vBlockId;   // 新增

uniform sampler2D texture;
uniform sampler2D lightmap;
uniform vec3 sunPosition;
uniform mat4 gbufferModelViewInverse;
uniform ivec2 eyeBrightnessSmooth;

uniform sampler2D shadowtex0;
uniform mat4 shadowProjection;
uniform mat4 shadowModelView;
uniform mat4 shadowModelViewInverse;
uniform mat4 gbufferModelView;

// ---- SEUS-style Lighting Functions ----

float OrenNayar(vec3 normal, vec3 eyeDir, vec3 lightDir) {
    const float roughness = 0.55;

    float NdotL = dot(normal, lightDir);
    float NdotV = dot(normal, eyeDir);

    if (NdotL <= 0.0 && NdotV <= 0.0) return 0.0;

    float angleVN = acos(clamp(NdotV, -1.0, 1.0));
    float angleLN = acos(clamp(NdotL, -1.0, 1.0));

    float alpha = max(angleVN, angleLN);
    float beta  = min(angleVN, angleLN);

    vec3 vPerp = eyeDir - normal * NdotV;
    vec3 lPerp = lightDir - normal * NdotL;
    float gamma = dot(vPerp, lPerp);

    float r2 = roughness * roughness;
    float A = 1.0 - 0.5 * r2 / (r2 + 0.57);
    float B = 0.45 * r2 / (r2 + 0.09);

    float C = sin(alpha) * tan(beta);

    return max(0.0, NdotL) * (A + B * max(0.0, gamma) * C);
}

float CurveBlockLightTorch(float blockLight) {
    float decoded = pow(blockLight, 4.0);
    float result  = pow(decoded, 2.0) * 5.0;
          result += pow(decoded, 0.4) * 0.1;
    return result;
}

float CurveBlockLightSky(float blockLight) {
    blockLight = 1.0 - pow(1.0 - blockLight, 0.55);
    blockLight *= blockLight * blockLight;
    return blockLight;
}

float getShadow(vec3 worldPos, vec3 eyeNormal, float NdotL) {
    if (NdotL <= 0.0) return 1.0;

    const float shadowMapBiasLocal = 1.0 - 25.6 / 256.0;
    float shadowRes = float(textureSize(shadowtex0, 0).x);

    vec4 shadowClip = shadowProjection * shadowModelView * vec4(worldPos, 1.0);
    vec3 rawShadowPos = shadowClip.xyz / shadowClip.w;

    float distb = sqrt(dot(rawShadowPos.xy, rawShadowPos.xy));
    float distortFactor = distb * shadowMapBiasLocal + (1.0 - shadowMapBiasLocal);

    float distortNBias = distortFactor * 256.0 / 256.0;
    distortNBias *= distortNBias;
    vec3 worldNormal = (gbufferModelViewInverse * vec4(eyeNormal, 0.0)).xyz;
    worldPos += worldNormal * distortNBias * 5.0 * (2048.0 / shadowRes);
    shadowClip = shadowProjection * shadowModelView * vec4(worldPos, 1.0);
    rawShadowPos = shadowClip.xyz / shadowClip.w;
    distb = sqrt(dot(rawShadowPos.xy, rawShadowPos.xy));
    distortFactor = distb * shadowMapBiasLocal + (1.0 - shadowMapBiasLocal);

    float shadowFade = clamp(100.0 - 100.0 * max(abs(rawShadowPos.x), abs(rawShadowPos.y)), 0.0, 1.0);

    rawShadowPos.xy /= distortFactor;
    rawShadowPos.z *= 0.2;
    vec3 shadowPos = rawShadowPos * 0.5 + 0.5;
    if (shadowFade < 0.00001) return 1.0;

    float biasFactor = sqrt(1.0 - NdotL * NdotL) / NdotL;
    float distortBias = distortFactor * 256.0 / 256.0;
    distortBias *= 8.0 * distortBias;
    float distanceBias = sqrt(dot(worldPos.xyz, worldPos.xyz)) * 0.005;
    float bias = (distortBias * biasFactor + distanceBias + 0.05) / shadowRes;
    shadowPos.z -= bias;

    float offset = 1.0 / shadowRes;
    float shadow = 0.0;
    vec2 offsets[9];
    offsets[0] = vec2( 0.0,  0.0);
    offsets[1] = vec2( 0.0,  1.0);
    offsets[2] = vec2( 0.7,  0.7);
    offsets[3] = vec2( 1.0,  0.0);
    offsets[4] = vec2( 0.7, -0.7);
    offsets[5] = vec2( 0.0, -1.0);
    offsets[6] = vec2(-0.7, -0.7);
    offsets[7] = vec2(-1.0,  0.0);
    offsets[8] = vec2(-0.7,  0.7);

    for (int i = 0; i < 9; i++) {
        vec2 uv = shadowPos.st + offsets[i] * offset;
        if (uv.x >= 0.0 && uv.x <= 1.0 && uv.y >= 0.0 && uv.y <= 1.0) {
            shadow += (shadowPos.z > texture2D(shadowtex0, uv).r) ? 0.0 : 1.0;
        } else {
            shadow += 1.0;
        }
    }
    shadow /= 9.0;

    return mix(1.0, shadow, shadowFade);
}

void main() {
    // Water blocks are NOT discarded — they render as terrain fallback.
    // If gbuffers_water runs, it overwrites colortex0/1 with water data (ONE ZERO blend).
    // This ensures water is always visible, even if gbuffers_water isn't dispatched.

    vec4 albedo = texture2D(texture, vTexCoord.st) * vColor;

    vec3 N = normalize(vNormal);
    vec3 V = normalize(-vEyePos);

    //vec3 worldSunDir = normalize((shadowModelViewInverse * vec4(0.0, 0.0, 1.0, 0.0)).xyz);
    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float sunHeight = worldSunDir.y;
    vec3 L = normalize((gbufferModelView * vec4(worldSunDir, 0.0)).xyz);

    // ---- Sky & Sun colors ----
    vec3 skyNight   = vec3(0.008, 0.010, 0.045);
    vec3 skySunset  = vec3(0.55, 0.25, 0.06);
    vec3 skyNoon    = vec3(0.18, 0.35, 0.75);

    vec3 sunNight   = vec3(0.00, 0.00, 0.00);
    vec3 sunSunset  = vec3(0.90, 0.40, 0.08);
    vec3 sunNoon    = vec3(1.00, 0.92, 0.75);

    float dayFactor  = smoothstep(-0.05, 0.15, sunHeight);
    float noonFactor = smoothstep(0.00, 0.60, sunHeight);

    vec3 skyColor = mix(skyNight, mix(skySunset, skyNoon, noonFactor), dayFactor);

    vec3 sunlight;
    float sunIntensity;
    if (sunHeight > 0.0) {
        sunlight = mix(sunNight, mix(sunSunset, sunNoon, noonFactor), dayFactor);
        sunIntensity = 1.0;
    } else {
        sunlight = vec3(0.15, 0.20, 0.35);
        sunIntensity = 0.4;
    }

    vec3 lightDir = (sunHeight >= 0.0) ? L : -L;

    vec3 worldPos = (gbufferModelViewInverse * vec4(vEyePos, 1.0)).xyz;
    float NdotL_shadow = dot(N, L);
    float shadow = 1.0;
    if (sunHeight > 0.05 && NdotL_shadow > 0.0) {
        shadow = getShadow(worldPos, N, NdotL_shadow);
    }

    vec4 lm = texture2D(lightmap, vLightmap);
    float skyLightRaw   = lm.y;
    float blockLightRaw = lm.x;

    // Sky light contribution (ambient + directional)
    float skyAmbient = CurveBlockLightSky(skyLightRaw);
    float skyDirect  = skyLightRaw * skyLightRaw;

    // Torch / block light: warm glow with steeper falloff for cozier look
    float blockLight = pow(blockLightRaw, 2.5);

    // ---- Directional sunlight ----
    float sunlightDiffuse = OrenNayar(N, V, lightDir);

    // Shadow-aware sun contribution
    float NdotV = dot(N, V);
    float ambientOcclusion = 0.5 + 0.5 * max(0.0, NdotV);

    // Ambient: should be visibly darker than sky color
    // Sky noon ~0.20 → ambient at full sky ≈ 0.025 (about 12% of sky)
    vec3 ambientColor = skyColor * 0.12;
    vec3 ambient = ambientColor * skyAmbient;
    ambient *= (0.85 + 0.15 * ambientOcclusion);

    // Direct sun: light color blends toward sky at sunset
    vec3 directLightColor = mix(sunlight, skyColor, 0.15 * (1.0 - dayFactor));
    vec3 directLight = shadow * sunlightDiffuse * directLightColor * skyDirect * 0.65 * sunIntensity;

    // ---- Torch / emissive block lighting ----
    // Warm torch color, peaks near the light source
    vec3 torchColor = vec3(1.0, 0.52, 0.18);
    float torchRange = 1.0 - blockLight;  // 0=near torch, 1=far
    // Shaped falloff: bright center, rapid drop-off for natural glow
    float torchShape = blockLight * (1.0 + torchRange * 0.3);
    vec3 torchLight = torchColor * torchShape * 0.18;

    // ---- Minimum light (so nothing is pure black) ----
    float minLightStr = 0.0025 * (1.0 - skyDirect) + 0.006;
    vec3 minLight = vec3(0.015, 0.020, 0.040) * minLightStr;

    vec3 lighting = ambient + directLight + torchLight + minLight;
    vec3 color = albedo.rgb * lighting;

    gl_FragData[0] = vec4(color, albedo.a);

    // Wisdom-style encoding: colortex1 = (normal.rg, waterFlag, 1.0)
    // waterFlag: 0.79 = water/ice, 1.0 = solid terrain
    float waterFlag = (vBlockId == 8.0 || vBlockId == 9.0 || vBlockId == 79.0) ? 0.79 : 1.0;
    gl_FragData[1] = vec4(N.xy * 0.5 + 0.5, waterFlag, 1.0);
}