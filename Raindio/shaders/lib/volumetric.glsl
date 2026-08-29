// ============================================================================
// Volumetric light scattering (air + underwater).
// Requires getwave2 (from /lib/water.glsl) included first.
// Requires shadowtex0, shadowProjection, shadowModelView, gbufferModelViewInverse,
// cameraPosition, rainStrength uniforms.
// ============================================================================

#define VL_STEPS 16
#define VL_MAX_DIST 80.0
#define VOLUMETRIC_FOG_BASE_DENSITY 0.002
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

    vec3 shadowCoord = shadowNDC * 0.5 + 0.5;

    if (shadowCoord.x < 0.001 || shadowCoord.x > 0.999 ||
        shadowCoord.y < 0.001 || shadowCoord.y > 0.999 ||
        shadowCoord.z < 0.001 || shadowCoord.z > 1.000) {
        return 1.0;
    }

    vec2 texelSize = vec2(1.0 / 4096.0);
    float shadow = 0.0;
    float bias = 0.0015;

    shadow += (shadowCoord.z - bias > texture(shadowtex0, shadowCoord.xy + vec2(-0.5, -0.5) * texelSize).r) ? 0.0 : 1.0;
    shadow += (shadowCoord.z - bias > texture(shadowtex0, shadowCoord.xy + vec2( 0.5, -0.5) * texelSize).r) ? 0.0 : 1.0;
    shadow += (shadowCoord.z - bias > texture(shadowtex0, shadowCoord.xy + vec2(-0.5,  0.5) * texelSize).r) ? 0.0 : 1.0;
    shadow += (shadowCoord.z - bias > texture(shadowtex0, shadowCoord.xy + vec2( 0.5,  0.5) * texelSize).r) ? 0.0 : 1.0;

    return shadow * 0.25;
}

float getVolumetricFogDensity(vec3 worldPos) {
    float heightDiff = worldPos.y - VOLUMETRIC_FOG_HEIGHT_BASE;
    float density = VOLUMETRIC_FOG_BASE_DENSITY * exp(-max(0.0, heightDiff) * VOLUMETRIC_FOG_HEIGHT_FALLOFF);
    return density * mix(1.0, 3.5, saturate(rainStrength));
}

vec4 CalculateVolumetricFogAndLight(vec3 viewPos, float dither, vec3 mainLightViewDir, vec3 mainLightColor, float mainLightVis, float mainLightPower) {
    float rayLength = length(viewPos);
    float maxDist = min(rayLength, VL_MAX_DIST);
    vec3 rayDir = viewPos / rayLength;

    vec3 accumulatedLight = vec3(0.0);
    float transmittance = 1.0;

    float cosTheta = dot(rayDir, mainLightViewDir);
    float phase = mix(hgPhase(cosTheta, 0.6), hgPhase(cosTheta, -0.3), 0.25);

    float prevDist = 0.0;

    mat4 shadowMat = shadowProjection * shadowModelView * gbufferModelViewInverse;
    vec3 worldStartPos = (gbufferModelViewInverse * vec4(0.0, 0.0, 0.0, 1.0)).xyz + cameraPosition;
    vec3 worldRayDir = (gbufferModelViewInverse * vec4(rayDir, 0.0)).xyz;

    for (int i = 0; i < VL_STEPS; i++) {
        float progress = (float(i) + dither) / float(VL_STEPS);
        float currentDist = maxDist * pow(progress, 1.25);

        float stepLen = currentDist - prevDist;
        prevDist = currentDist;

        if (currentDist > maxDist) break;

        vec3 currentViewPos = rayDir * currentDist;
        vec3 worldPos = worldStartPos + worldRayDir * currentDist;
        float density = getVolumetricFogDensity(worldPos);

        if (density > 0.00001) {
            float shadowVis = getSampleShadow(currentViewPos, shadowMat);

            vec3 directScattering = mainLightColor * phase * shadowVis * mainLightVis * (mainLightPower * 0.35);
            vec3 ambientScattering = mainLightColor * 0.2 + vec3(0.0034, 0.0139, 0.0034);

            vec3 stepScattering = (directScattering + ambientScattering) * density * stepLen;
            float stepExtinction = exp(-density * stepLen * 1.5);

            accumulatedLight += stepScattering * transmittance;
            transmittance *= stepExtinction;

            if (transmittance < 0.01) break;
        }
    }

    return vec4(accumulatedLight, transmittance);
}

vec4 CalculateUnderwaterVolumetricLight(vec3 startViewPos, vec3 endViewPos, float dither, vec3 mainLightViewDir, vec3 realSunDir, vec3 mainLightColor, float mainLightVis, float mainLightPower) {
    vec3 rayVec = endViewPos - startViewPos;
    float rayLength = length(rayVec);
    if (rayLength < 0.001) return vec4(0.0, 0.0, 0.0, 1.0);

    float maxDist = min(rayLength, VL_MAX_DIST);
    vec3 rayDir = rayVec / rayLength;

    vec3 accumulatedLight = vec3(0.0);
    float transmittance = 1.0;

    float cosTheta = dot(rayDir, mainLightViewDir);
    float phase = mix(hgPhase(cosTheta, 0.65), hgPhase(cosTheta, -0.25), 0.20);

    float prevDist = 0.0;
    vec3 waterScatterColor = vec3(0.20, 0.65, 0.95);

    bool isDay = realSunDir.y > 0.0;
    vec3 lightWorldDir = isDay ? normalize(realSunDir) : normalize(-realSunDir);

    float waterSurfaceY = max(cameraPosition.y, 62.0) + 1.0;

    mat4 shadowMat = shadowProjection * shadowModelView * gbufferModelViewInverse;
    vec3 worldStartPos = (gbufferModelViewInverse * vec4(startViewPos, 1.0)).xyz + cameraPosition;
    vec3 worldRayDir = (gbufferModelViewInverse * vec4(rayDir, 0.0)).xyz;

    for (int i = 0; i < VL_STEPS; i++) {
        float progress = (float(i) + dither) / float(VL_STEPS);
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

        float density = 0.018;
        float lightIntensity = mainLightPower * 0.10 * mainLightVis;
        vec3 directScattering = mainLightColor * waterScatterColor * phase * shadowVis
                               * lightIntensity * (0.05 + 0.95 * lightBeamMask);
        vec3 ambientScattering = waterScatterColor * 0.008 * mainLightVis;

        vec3 stepScattering = (directScattering + ambientScattering) * density * stepLen;
        accumulatedLight += stepScattering * transmittance;

        transmittance *= exp(-density * stepLen * 1.2);

        if (transmittance < 0.01) break;
    }

    return vec4(accumulatedLight, transmittance);
}
