// ============================================================================
// Photon-Optimized + Physically-Lit Volumetric Clouds with Rich Curling Wisps
//   - Full, lush cloud coverage with rich 3D curl-warped wispy silk tendrils
//   - Physical atmospheric skylight from `colortex6` LUT (zenith + horizon + sun-side bounce)
//   - Smooth horizon fade-out & Beer-Lambert aerial perspective (zero hard horizon shelf)
//   - Hardware `noisetex` 3D lookups, exponential stepping, 2x empty-space skip,
//     and 4-step coarse light march with 4-octave Wrenninge/Photon multi-scattering
// ============================================================================

#ifndef VOL_CLOUDS_ENABLED
#define VOL_CLOUDS_ENABLED 1
#endif
#ifndef VOL_CLOUD_STEPS
#define VOL_CLOUD_STEPS 40
#endif
#ifndef VOL_CLOUD_COVERAGE
#define VOL_CLOUD_COVERAGE 0.52
#endif
#ifndef VOL_CLOUD_DENSITY
#define VOL_CLOUD_DENSITY 1.00
#endif

#define CLOUD_SPEED 0.03

const float CLOUD_PLANET_RADIUS  = 6371000.0;
const float CLOUD_ALT_MIN        = 1100.0;
const float CLOUD_ALT_MAX        = 4100.0;
const float CLOUD_MAX_TRACE_DIST = 32000.0;
const float INV_NOISE_RES        = 1.0 / 256.0;

float cloudRemap(float v, float lo, float hi, float newLo, float newHi) {
    return newLo + clamp((v - lo) / max(hi - lo, 1e-4), 0.0, 1.0) * (newHi - newLo);
}

// Photon / IQ hardware bilinear 3D noise using 256x256 RGBA8 `noisetex`
// Horizontal (x, z) is the smooth bilinear plane; altitude (y) is the slice axis
vec4 cloudTex3D_RGBA(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    vec2 uv0 = (i.xz + vec2(37.0, 239.0) * i.y) + f.xz;
    vec2 uv1 = uv0 + vec2(37.0, 239.0);
    vec4 a = textureLod(noisetex, (uv0 + 0.5) * INV_NOISE_RES, 0.0);
    vec4 b = textureLod(noisetex, (uv1 + 0.5) * INV_NOISE_RES, 0.0);
    return mix(a, b, f.y);
}

float cloudTex3D(vec3 p) {
    return cloudTex3D_RGBA(p).r;
}

// Smooth 2D hardware-filtered weather FBM
vec2 cloudWeatherMap(vec2 p) {
    const mat2 rot = mat2(0.80, -0.60, 0.60, 0.80);
    vec2 s = textureLod(noisetex, (p + 0.5) * INV_NOISE_RES, 0.0).rg * 0.58;
    p = rot * p * 2.03 + vec2(17.1, 9.3);
    s += textureLod(noisetex, (p + 0.5) * INV_NOISE_RES, 0.0).rg * 0.28;
    p = rot * p * 2.05 + vec2(11.4, 23.7);
    s += textureLod(noisetex, (p + 0.5) * INV_NOISE_RES, 0.0).rg * 0.14;
    return s;
}

// Analytical billow dome shaping: sculpted 3D cauliflower cumulus domes
float cloudBillow3D(vec3 p) {
    vec4 n0 = cloudTex3D_RGBA(p);
    float b0 = 1.0 - abs(n0.r * 2.0 - 1.0);
    b0 = b0 * b0 * (3.0 - 2.0 * b0);

    vec4 n1 = cloudTex3D_RGBA(p * 2.06 + vec3(13.7, 7.3, 19.1));
    float b1 = 1.0 - abs(n1.g * 2.0 - 1.0);
    b1 = b1 * b1;

    float b2 = 1.0 - abs(cloudTex3D(p * 4.14 + vec3(5.9, 11.4, 3.7)) * 2.0 - 1.0);
    float billowFbm = b0 * 0.56 + b1 * 0.30 + b2 * 0.14;
    return cloudRemap(n0.b * 0.42 + billowFbm * 0.58, 0.16, 0.90, 0.0, 1.0);
}

float cloudHG(float cosTheta, float g) {
    float g2 = g * g;
    float d = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (12.5663706 * pow(max(d, 1e-4), 1.5));
}

vec2 cloudRaySphere(vec3 ro, vec3 rd, float radius) {
    float b = dot(ro, rd);
    float c = dot(ro, ro) - radius * radius;
    float disc = b * b - c;
    if (disc < 0.0) return vec2(-1.0);
    float s = sqrt(disc);
    return vec2(-b - s, -b + s);
}

// Nubis cumulus vertical profile (thick, towering cumulus domes)
float cloudHeightProfile(float h, float cloudType) {
    float topH = mix(0.62, 0.98, cloudType);
    float bottom = smoothstep(0.0, 0.09, h);
    float top = 1.0 - smoothstep(topH * 0.58, topH, h);
    float anvil = smoothstep(0.60, 0.90, h) * smoothstep(0.54, 0.90, cloudType) * 0.22;
    return clamp(bottom * top + anvil * (1.0 - smoothstep(0.88, 0.99, h)), 0.0, 1.0);
}

// Evaluates thick, dense volumetric clouds with rich 3D curling wisps and smooth horizon fade
float sampleVolumetricCloud(vec3 pos, float hNorm, float rayDist, float timeVal, float rainAmt, bool withDetail) {
    if (hNorm <= 0.001 || hNorm >= 0.999) return 0.0;

    // Smooth distance fade so clouds near the far horizon dissolve gently out of sight
    float distFade = 1.0 - smoothstep(13000.0, 30000.0, rayDist);
    if (distFade <= 0.002) return 0.0;

    float tSec = mod(timeVal, 7200.0);
    float drift = tSec * (CLOUD_SPEED * 22.0);
    vec3 wind = vec3(drift * 1.0, 0.0, drift * 0.38);
    vec3 samplePos = pos + wind + vec3(hNorm * 195.0, 0.0, hNorm * 82.0);

    // 1. Fuller Weather Map: generous, lush cloud presence across the sky
    vec2 weather = cloudWeatherMap(samplePos.xz * 0.00032);
    float wCovRaw = weather.x;
    float wType   = smoothstep(0.22, 0.76, weather.y);

    float userCov   = clamp(VOL_CLOUD_COVERAGE, 0.10, 0.90);
    float targetCov = mix(clamp(userCov * 1.15, 0.14, 0.95), 0.94, rainAmt * 0.85);
    float localCov  = cloudRemap(wCovRaw, 1.0 - targetCov * 0.95, 1.0, 0.0, 1.0);
    if (localCov <= 0.002) return 0.0;

    float heightMask = cloudHeightProfile(hNorm, mix(wType, 0.90, rainAmt * 0.5));
    if (heightMask <= 0.002) return 0.0;

    // 2. Sculpted 3D Cumulus Body (thick towering billow structure)
    vec3 baseCoord = samplePos * vec3(0.00088, 0.00095, 0.00088);
    float billowShape = cloudBillow3D(baseCoord);

    float baseCloud = cloudRemap(billowShape * heightMask, 1.0 - localCov * 0.93, 1.0, 0.0, 1.0);
    if (baseCloud <= 0.002) return 0.0;

    // 3. Rich Dual-Scale 3D Curl-Warped Wispy Filaments (strong curling tendrils & wind-swept edges)
    float detailFade = 1.0 - smoothstep(10500.0, 22000.0, rayDist);
    if (withDetail && detailFade > 0.02) {
        // Two-stage 3D curl warp creates rich, swirling painterly cloud wisps
        vec3 curl1 = (cloudTex3D_RGBA(samplePos * 0.0011).rgb - 0.5) * 195.0 * (1.0 - hNorm * 0.45);
        vec3 curledPos = samplePos + curl1 + vec3(drift * 0.28, -drift * 0.16, 0.0);

        vec4 d1 = cloudTex3D_RGBA(curledPos * 0.0038);
        vec4 d2 = cloudTex3D_RGBA((curledPos + curl1 * 0.45) * 0.0074);

        float wisp1 = 1.0 - abs(d1.r * 2.0 - 1.0);
        float wisp2 = 1.0 - abs(d2.g * 2.0 - 1.0);
        float hfNoise = (wisp1 * wisp1) * 0.55 + (wisp2 * wisp2) * 0.30 + d1.b * 0.15;

        float hfMod = mix(hfNoise, 1.0 - hfNoise, clamp(hNorm * 1.9, 0.0, 1.0));
        float erosion = hfMod * 0.44 * detailFade * (1.0 - baseCloud * 0.52);
        baseCloud = cloudRemap(baseCloud, erosion, 1.0, 0.0, 1.0);
    }

    float densityGrad = hNorm * smoothstep(0.0, 0.18, hNorm) * 0.68 + 0.62;
    return clamp(baseCloud, 0.0, 1.0) * densityGrad * distFade * 0.0092 * clamp(VOL_CLOUD_DENSITY, 0.30, 2.00);
}

// Photon-optimized 4-step exponential coarse light march (scaled for thicker 3km cloud slab)
float marchVolumetricCloudLight(vec3 pos, vec3 lightDir, float rayDist, float timeVal, float rainAmt, bool highQuality) {
    const float DISTS[4] = float[4](100.0, 260.0, 620.0, 1550.0);
    const float SEGS[4]  = float[4](120.0, 220.0, 460.0,  980.0);
    int steps = highQuality ? 4 : 2;
    float opticalDepth = 0.0;

    for (int i = 0; i < 4; i++) {
        if (i >= steps) break;
        vec3 lPos = pos + lightDir * DISTS[i];
        float r = length(lPos + vec3(0.0, CLOUD_PLANET_RADIUS, 0.0));
        float lh = (r - (CLOUD_PLANET_RADIUS + CLOUD_ALT_MIN)) / (CLOUD_ALT_MAX - CLOUD_ALT_MIN);
        if (lh >= 1.0) break;
        if (lh > 0.0) {
            float d = sampleVolumetricCloud(lPos, lh, rayDist, timeVal, rainAmt, false);
            opticalDepth += d * SEGS[i];
            if (opticalDepth > 14.0) break;
        }
    }
    return opticalDepth;
}

// Physically-Lit Volumetric Cloud Raymarcher with real Atmosphere LUT skylight & soft horizon fade
vec4 traceVolumetricClouds(
    vec3 rayDir,
    vec3 realSunDir,
    vec3 skyAtRay,
    vec3 skyZenithLUT,
    vec3 skyHorizonLUT,
    vec3 skySunSideLUT,
    float sunHeight,
    float rainAmt,
    float timeVal,
    bool highQuality
) {
#if VOL_CLOUDS_ENABLED == 0
    return vec4(0.0, 0.0, 0.0, 1.0);
#endif
    // Smooth low-elevation fade so horizon clouds gently dissolve out of sight (no hard cutoff line)
    float elevFade = smoothstep(0.02, 0.17, rayDir.y);
    if (elevFade <= 0.002) return vec4(0.0, 0.0, 0.0, 1.0);

    vec3 ro = vec3(0.0, CLOUD_PLANET_RADIUS + clamp(cameraPosition.y, 0.0, 400.0), 0.0);
    vec2 hitInner = cloudRaySphere(ro, rayDir, CLOUD_PLANET_RADIUS + CLOUD_ALT_MIN);
    vec2 hitOuter = cloudRaySphere(ro, rayDir, CLOUD_PLANET_RADIUS + CLOUD_ALT_MAX);

    float tStart = max(0.0, hitInner.y);
    float tEnd   = min(hitOuter.y, CLOUD_MAX_TRACE_DIST);
    if (tEnd <= tStart + 1.0) return vec4(0.0, 0.0, 0.0, 1.0);

    int maxSteps = highQuality ? clamp(VOL_CLOUD_STEPS, 16, 64) : 14;
    float rayLen = tEnd - tStart;
    float baseStep = (rayLen / float(maxSteps)) * 0.76;

#if defined(TAA_ENABLED) && TAA_ENABLED == 1
    float jitter = fract(interleavedGradientNoise(gl_FragCoord.xy) + fract(float(frameCounter & 1023) * 0.61803398875));
#else
    float jitter = interleavedGradientNoise(gl_FragCoord.xy);
#endif

    bool isDay = (sunHeight > -0.06);
    vec3 lightDir = isDay ? realSunDir : -realSunDir;
    float cosTheta = dot(rayDir, lightDir);

    float ph0 = mix(cloudHG(cosTheta,  0.80), cloudHG(cosTheta, -0.28), 0.22);
    float ph1 = mix(cloudHG(cosTheta,  0.44), cloudHG(cosTheta, -0.14), 0.30);
    float ph2 = mix(cloudHG(cosTheta,  0.20), 0.079577, 0.45);
    float ph3 = 0.079577;

    float rainClear = 1.0 - rainAmt;
    float sunsetWarmth = (1.0 - smoothstep(0.02, 0.36, sunHeight)) * smoothstep(-0.14, 0.06, sunHeight);
    float sunVis = isDay ? (smoothstep(-0.06, 0.14, sunHeight) * rainClear) : (smoothstep(0.0, 0.22, -sunHeight) * rainClear);

    vec3 noonSunCol   = vec3(1.00, 0.98, 0.94) * 4.05;
    vec3 sunsetSunCol = mix(vec3(1.00, 0.45, 0.25), skySunSideLUT * 1.45 + vec3(0.45, 0.22, 0.14), 0.35) * 4.35;
    vec3 moonLightCol = vec3(0.18, 0.32, 0.58) * 0.22;
    vec3 directCol = isDay ? mix(noonSunCol, sunsetSunCol, sunsetWarmth * 0.92) : moonLightCol;
    directCol *= sunVis;

    // Physical Atmospheric Skylight from `colortex6` LUT
    vec3 physTopSky = skyZenithLUT * 1.12 + skySunSideLUT * (0.28 * sunVis);
    vec3 physBotSky = mix(skyHorizonLUT * 0.64, skyZenithLUT * 0.82, 0.42);
    vec3 topAmbCol = mix(physTopSky, physTopSky * vec3(1.05, 0.96, 1.08), sunsetWarmth * 0.5);
    vec3 botAmbCol = mix(physBotSky * vec3(0.80, 0.92, 1.14), physBotSky * vec3(1.05, 0.78, 1.08), sunsetWarmth * 0.65);

    if (!isDay) {
        topAmbCol = skyZenithLUT * 1.35 + vec3(0.006, 0.012, 0.032);
        botAmbCol = skyHorizonLUT * 0.75 + vec3(0.003, 0.007, 0.018);
    }
    vec3 rainAmb = mix(vec3(0.11, 0.15, 0.21), vec3(0.26, 0.32, 0.40), smoothstep(-0.02, 0.40, sunHeight));
    topAmbCol = mix(topAmbCol, rainAmb * 1.08, rainAmt * 0.85);
    botAmbCol = mix(botAmbCol, rainAmb * 0.46, rainAmt * 0.85);

    vec3 scattered = vec3(0.0);
    float transmittance = 1.0;
    float t = tStart + baseStep * jitter;
    float weightedDist = 0.0;
    float weightSum = 0.0;
    bool wasInCloud = false;

    for (int i = 0; i < 64; i++) {
        if (i >= maxSteps || t >= tEnd || transmittance < 0.015) break;

        float stepLen = baseStep * (1.0 + (t - tStart) * 0.000042);

        vec3 pSphere = ro + rayDir * t;
        float alt = length(pSphere) - CLOUD_PLANET_RADIUS;
        float hNorm = (alt - CLOUD_ALT_MIN) / (CLOUD_ALT_MAX - CLOUD_ALT_MIN);

        vec3 worldP = pSphere - vec3(0.0, CLOUD_PLANET_RADIUS, 0.0);
        float coarseD = sampleVolumetricCloud(worldP, hNorm, t, timeVal, rainAmt, false);

        if (coarseD > 0.00005) {
            if (!wasInCloud && i > 0) {
                t = max(tStart, t - stepLen * 0.55);
                pSphere = ro + rayDir * t;
                alt = length(pSphere) - CLOUD_PLANET_RADIUS;
                hNorm = (alt - CLOUD_ALT_MIN) / (CLOUD_ALT_MAX - CLOUD_ALT_MIN);
                worldP = pSphere - vec3(0.0, CLOUD_PLANET_RADIUS, 0.0);
            }
            wasInCloud = true;

            bool useDetail = highQuality && (t < 22000.0);
            float d = useDetail ? sampleVolumetricCloud(worldP, hNorm, t, timeVal, rainAmt, true) : coarseD;

            if (d > 0.00004) {
                float stepExt = exp(-d * stepLen);

                float lightOD = marchVolumetricCloudLight(worldP, lightDir, t, timeVal, rainAmt, highQuality);
                float powder = 1.0 - exp(-lightOD * 2.1);
                float powderMix = mix(0.36 + 1.28 * powder, 1.0, saturate(cosTheta * 0.5 + 0.5));

                float ms = 0.0;
                ms += exp(-lightOD)        * ph0 * 2.65 * powderMix;
                ms += exp(-lightOD * 0.45) * ph1 * 1.38;
                ms += exp(-lightOD * 0.20) * ph2 * 0.78;
                ms += exp(-lightOD * 0.08) * ph3 * 0.48;

                float heightAO = mix(0.34, 1.0, smoothstep(0.05, 0.82, hNorm));
                vec3 ambLight = mix(botAmbCol, topAmbCol, smoothstep(0.08, 0.85, hNorm)) * heightAO;

                vec3 luminanceSample = directCol * ms + ambLight * (0.50 + 0.50 * exp(-lightOD * 0.22));

                vec3 integScatter = (luminanceSample - luminanceSample * stepExt) / max(d, 1e-6);
                float stepWeight = transmittance * (1.0 - stepExt);
                scattered += transmittance * (integScatter * d);
                weightedDist += t * stepWeight;
                weightSum += stepWeight;

                transmittance *= stepExt;
            }
            t += stepLen;
        } else {
            wasInCloud = false;
            t += stepLen * 2.0;
        }
    }

    // Physical Aerial Perspective: smoothly dissolve horizon clouds out of sight into the sky LUT
    if (weightSum > 1e-4) {
        float avgDist = weightedDist / weightSum;
        float aerialTrans = exp(-max(avgDist - 4500.0, 0.0) * 0.000115);
        float cloudAlpha = (1.0 - transmittance) * elevFade;
        vec3 blendedCloud = mix(skyAtRay * cloudAlpha, scattered * elevFade, clamp(aerialTrans, 0.0, 1.0));
        float finalAlpha = cloudAlpha * smoothstep(30500.0, 10000.0, avgDist);
        float alphaRatio = finalAlpha / max(cloudAlpha, 1e-4);
        return vec4(max(blendedCloud * alphaRatio, vec3(0.0)), clamp(1.0 - finalAlpha, 0.0, 1.0));
    }

    return vec4(0.0, 0.0, 0.0, 1.0);
}
