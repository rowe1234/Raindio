// ============================================================================
// Volumetric light scattering (air + underwater).
// Requires /lib/common.glsl and /lib/water.glsl included first.
// Requires shadowtex0, shadowProjection, shadowModelView, gbufferModelViewInverse,
// cameraPosition, rainStrength uniforms.
// ============================================================================

#ifndef VL_STEPS
#define VL_STEPS 12        // [8 12 18 28]
#endif
#ifndef VL_TEMPORAL_JITTER
#define VL_TEMPORAL_JITTER 0
#endif
#define VL_STRENGTH 0.42   // [0.25 0.42 0.5 1.0 1.5 2.0]
#define VL_MAX_DIST 80.0
#define VOLUMETRIC_FOG_BASE_DENSITY 0.00045
#define VOLUMETRIC_FOG_HEIGHT_FALLOFF 0.015
#define VOLUMETRIC_FOG_HEIGHT_BASE 64.0

float hgPhase(float cosTheta, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (12.5663706 * pow(1.0 + g2 - 2.0 * g * cosTheta, 1.5));
}

float getSampleShadow(vec3 viewPos, mat4 shadowMat) {
    vec4 shadowClip = shadowMat * vec4(viewPos, 1.0);
    vec3 shadowNDC = shadowClip.xyz / shadowClip.w;

    float l = length(shadowNDC.xy);
    if (l > 0.00001) {
        float distortFactor = l * 0.8 + 0.2;
        shadowNDC.xy /= distortFactor;
    }

    vec3 shadowCoord = vec3(shadowNDC.xy * 0.5 + 0.5, shadowNDC.z); // Vulkan shadow NDC Z in [0, 1]

    if (shadowCoord.x < 0.001 || shadowCoord.x > 0.999 ||
        shadowCoord.y < 0.001 || shadowCoord.y > 0.999 ||
        shadowCoord.z < 0.001 || shadowCoord.z > 1.000) {
        return 1.0;
    }

    vec2 texelSize = vec2(1.0 / max(SHADOW_MAP_RES, 512.0));
    float bias = 0.00025;
    float receiverZ = shadowCoord.z - bias;

    float shadow = 0.0;
    shadow += (receiverZ <= textureLod(shadowtex0, shadowCoord.xy + vec2(-0.75, -0.75) * texelSize, 0.0).r) ? 1.0 : 0.0;
    shadow += (receiverZ <= textureLod(shadowtex0, shadowCoord.xy + vec2( 0.75, -0.75) * texelSize, 0.0).r) ? 1.0 : 0.0;
    shadow += (receiverZ <= textureLod(shadowtex0, shadowCoord.xy + vec2(-0.75,  0.75) * texelSize, 0.0).r) ? 1.0 : 0.0;
    shadow += (receiverZ <= textureLod(shadowtex0, shadowCoord.xy + vec2( 0.75,  0.75) * texelSize, 0.0).r) ? 1.0 : 0.0;

    return shadow * 0.25;
}

float getVolumetricFogDensity(vec3 worldPos) {
    float heightDiff = worldPos.y - VOLUMETRIC_FOG_HEIGHT_BASE;
    float density = VOLUMETRIC_FOG_BASE_DENSITY * exp(-max(0.0, heightDiff) * VOLUMETRIC_FOG_HEIGHT_FALLOFF);
    return density * mix(1.0, 2.0, saturate(rainStrength));
}

vec4 CalculateVolumetricFogAndLight(vec3 viewPos, float dither, vec3 mainLightViewDir, vec3 mainLightColor, float mainLightVis, float mainLightPower) {
    float rayLength = length(viewPos);
    if (rayLength < 0.1) return vec4(0.0, 0.0, 0.0, 1.0);

    float maxDist = min(rayLength, VL_MAX_DIST);
    vec3 rayDir = viewPos / rayLength;

    // Adaptive step count based on ray length
    int steps = clamp(int(ceil(maxDist * 0.14)), 4, VL_STEPS);

    vec3 accumulatedLight = vec3(0.0);
    float transmittance = 1.0;

    float cosTheta = dot(rayDir, mainLightViewDir);
    float phase = mix(hgPhase(cosTheta, 0.6), hgPhase(cosTheta, -0.3), 0.25);

    float prevDist = 0.0;

    mat4 shadowMat = shadowProjection * shadowModelView * gbufferModelViewInverse;
    vec3 worldStartPos = (gbufferModelViewInverse * vec4(0.0, 0.0, 0.0, 1.0)).xyz + cameraPosition;
    vec3 worldRayDir = (gbufferModelViewInverse * vec4(rayDir, 0.0)).xyz;

#if VL_TEMPORAL_JITTER == 1 && defined(TAA_ENABLED) && TAA_ENABLED == 1
    float effDither = fract(dither + fract(float(frameCounter & 1023) * 0.61803398875));
#else
    float effDither = dither;
#endif
    for (int i = 0; i < steps; i++) {
        float progress = (float(i) + effDither) / float(steps);
        float currentDist = maxDist * pow(progress, 1.25);

        float stepLen = currentDist - prevDist;
        prevDist = currentDist;

        if (currentDist > maxDist) break;

        vec3 currentViewPos = rayDir * currentDist;
        vec3 worldPos = worldStartPos + worldRayDir * currentDist;
        float density = getVolumetricFogDensity(worldPos);

        if (density > 0.00001) {
            float shadowVis = getSampleShadow(currentViewPos, shadowMat);

            float lightScale = saturate(mainLightPower / 80.0);
            // Reduced direct VL gain (0.22 -> 0.055 * VL_STRENGTH) so open sky stays deep cobalt blue
            vec3 directScattering = mainLightColor * phase * shadowVis * mainLightVis * (mainLightPower * 0.055 * VL_STRENGTH);
            vec3 ambientScattering = (mainLightColor * (0.065 * mainLightVis * lightScale) + vec3(0.002, 0.006, 0.002) * mix(0.15, 1.0, lightScale)) * VL_STRENGTH;

            vec3 stepScattering = (directScattering + ambientScattering) * density * stepLen;
            float stepExtinction = exp(-density * stepLen * 1.5);

            accumulatedLight += stepScattering * transmittance;
            transmittance *= stepExtinction;

            if (transmittance < 0.01) break;
        }
    }

    return vec4(accumulatedLight, transmittance);
}

vec4 CalculateUnderwaterVolumetricLight(
    vec3 startViewPos,
    vec3 endViewPos,
    float waterSurfaceY,
    float dither,
    vec3 mainLightViewDir,
    vec3 realSunDir,
    vec3 mainLightColor,
    float mainLightVis,
    float mainLightPower
) {
    vec3 rayVec = endViewPos - startViewPos;
    float rayLength = length(rayVec);
    if (rayLength < 0.001) return vec4(0.0, 0.0, 0.0, 1.0);

    float maxDist = min(rayLength, VL_MAX_DIST);
    vec3 rayDir = rayVec / rayLength;

    int steps = clamp(int(ceil(maxDist * 0.22)), 4, VL_STEPS);

    vec3 accumulatedLight = vec3(0.0);
    float transmittance = 1.0;

    float cosTheta = dot(rayDir, mainLightViewDir);
    float phase = mix(hgPhase(cosTheta, 0.65), hgPhase(cosTheta, -0.25), 0.20);

    float prevDist = 0.0;
    vec3 waterScatterColor = vec3(0.10, 0.68, 0.58);

    bool isDay = realSunDir.y > 0.0;
    vec3 lightWorldDir = isDay ? normalize(realSunDir) : normalize(-realSunDir);

    mat4 shadowMat = shadowProjection * shadowModelView * gbufferModelViewInverse;
    vec3 worldStartPos = (gbufferModelViewInverse * vec4(startViewPos, 1.0)).xyz + cameraPosition;
    vec3 worldRayDir = (gbufferModelViewInverse * vec4(rayDir, 0.0)).xyz;

#if VL_TEMPORAL_JITTER == 1 && defined(TAA_ENABLED) && TAA_ENABLED == 1
    float effDither = fract(dither + fract(float(frameCounter & 1023) * 0.61803398875));
#else
    float effDither = dither;
#endif
    for (int i = 0; i < steps; i++) {
        float progress = (float(i) + effDither) / float(steps);
        float currentDist = maxDist * pow(progress, 1.25);

        float stepLen = currentDist - prevDist;
        prevDist = currentDist;

        if (currentDist > maxDist) break;

        vec3 currentViewPos = startViewPos + rayDir * currentDist;
        vec3 worldPos = worldStartPos + worldRayDir * currentDist;

        float heightToSurface = max(0.0, waterSurfaceY - worldPos.y);
        float rayLengthToSurface = heightToSurface / max(lightWorldDir.y, 0.08);
        vec3 surfaceHitPos = worldPos + lightWorldDir * rayLengthToSurface;

        float waveCaustic = getwave2(surfaceHitPos, 1.0);
        float lightBeamMask = pow(clamp(waveCaustic * 1.8 + 0.1, 0.0, 1.0), 3.0);

        float depthFade = exp(-heightToSurface * 0.05);
        lightBeamMask = mix(0.1, lightBeamMask, depthFade);

        float shadowVis = getSampleShadow(currentViewPos, shadowMat);

        float density = 0.026;
        float lightIntensity = mainLightPower * 0.095 * mainLightVis * (isDay ? 1.0 : 0.15);
        vec3 directScattering = mainLightColor * waterScatterColor * phase * shadowVis
                               * lightIntensity * (0.05 + 0.95 * lightBeamMask);
        vec3 ambientScattering = waterScatterColor * 0.012 * mainLightVis * (isDay ? 1.0 : 0.15);

        vec3 stepScattering = (directScattering + ambientScattering) * density * stepLen;
        accumulatedLight += stepScattering * transmittance;

        transmittance *= exp(-density * stepLen * 1.2);

        if (transmittance < 0.01) break;
    }

    return vec4(accumulatedLight, transmittance);
}
