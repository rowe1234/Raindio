// ============================================================================
// Screen-space ray-traced reflections (SSR) + Shadow-Map (RSM) off-screen
// reflection fallback (eliminates screen-edge missing reflection cut-offs).
// Requires /lib/common.glsl, /lib/projection.glsl, /lib/fog.glsl, /lib/lighting.glsl.
// Requires depthtex0, colortex0, colortex1, shadowtex0, shadowcolor0,
// gbufferProjection, gbufferProjectionInverse, gbufferModelViewInverse,
// shadowModelView, shadowProjection, viewWidth, viewHeight, rainStrength uniforms.
// ============================================================================

#ifndef SSR_MAX_STEPS
#define SSR_MAX_STEPS 36      // [18 36 48 64]
#endif
#ifndef SSR_BINARY_STEPS
#define SSR_BINARY_STEPS 4    // [3 4 5 6]
#endif
#ifndef SHADOW_REFL_STEPS
#define SHADOW_REFL_STEPS 16  // [0 8 16 24 32]
#endif

vec2 viewToScreenUV(vec3 viewPos) {
    vec4 clip = gbufferProjection * vec4(viewPos, 1.0);
    return (clip.xy / max(clip.w, 1e-5)) * 0.5 + 0.5;
}

vec4 ray_trace_ssr(vec3 direction, vec3 start, float dither, vec2 hitUVPerturb) {
    // Early-out rays pointing strongly back toward camera (cannot validly hit screen-space geometry)
    if (direction.z > 0.78) return vec4(0.0);
    vec3 p0 = start + direction * (0.04 + 0.03 * dither);
    if (p0.z >= -0.05) return vec4(0.0);

    float maxRayDist = 120.0;
    if (direction.z > 0.001) {
        maxRayDist = min(maxRayDist, (-0.1 - p0.z) / direction.z);
    }
    if (maxRayDist <= 0.05) return vec4(0.0);

    vec3 p1 = p0 + direction * maxRayDist;

    vec2 uv0 = viewToScreenUV(p0);
    vec2 uv1 = viewToScreenUV(p1);

    // Adaptive linear screen-space DDA: finer stride (8.5px) in near field, 11px in far field
    vec2 screenDelta = uv1 - uv0;
    float screenLen = length(screenDelta * vec2(viewWidth, viewHeight));
    if (screenLen < 1.0) return vec4(0.0);

    float targetStride = mix(8.5, 11.0, smoothstep(10.0, 45.0, -p0.z));
    int steps = clamp(int(ceil(screenLen / targetStride)), 10, SSR_MAX_STEPS);
    float invSteps = 1.0 / float(steps);

    float invZ0 = 1.0 / (-p0.z);
    float invZ1 = 1.0 / (-p1.z);

    float prevT = 0.0;
    float prevRayZ = p0.z;

    for (int i = 1; i <= SSR_MAX_STEPS; i++) {
        if (i > steps) break;

        float t = (float(i) - 0.5 + 0.5 * dither) * invSteps;
        vec2 uv = mix(uv0, uv1, t);
        if (uv.x <= 0.001 || uv.x >= 0.999 || uv.y <= 0.001 || uv.y >= 0.999) break;

        float rayInvZ = mix(invZ0, invZ1, t);
        float rayZ = -1.0 / max(rayInvZ, 1e-6);

        float rawDepth0 = texture(depthtex0, uv).r;
        if (isSceneDepth(rawDepth0)) {
            vec3 sv = ScreenToView(uv, rawDepth0);
            float diff = sv.z - rayZ;

            // Depth-scaled thickness threshold (widens gently with distance)
            float stepZ = abs(rayZ - prevRayZ);
            float maxThickness = max(stepZ * 1.8, 0.14 + abs(rayZ) * 0.018);

            if (diff > -0.06 && diff < maxThickness) {
                // Skip water pixels and KEEP MARCHING instead of aborting
                int sampleBlockId = int(texelFetch(colortex1, ivec2(uv * vec2(viewWidth, viewHeight)), 0).z * 255.0 + 0.5);
                if (isMatWater(sampleBlockId)) {
                    prevT = t;
                    prevRayZ = rayZ;
                    continue;
                }

                // Binary refinement (strict Reverse-Z sky-depth guard prevents NaN/Inf at silhouette edges)
                float lo = prevT;
                float hi = t;
                vec2 hitUV = uv;
                float hitRayZ = rayZ;

                for (int j = 0; j < SSR_BINARY_STEPS; j++) {
                    float mid = 0.5 * (lo + hi);
                    vec2 midUV = mix(uv0, uv1, mid);
                    float midRayZ = -1.0 / max(mix(invZ0, invZ1, mid), 1e-6);
                    float midDepth = texture(depthtex0, midUV).r;

                    if (isSceneDepth(midDepth)) {
                        vec3 midSv = ScreenToView(midUV, midDepth);
                        if (midSv.z > midRayZ) {
                            hi = mid;
                            hitUV = midUV;
                            hitRayZ = midRayZ;
                        } else {
                            lo = mid;
                        }
                    } else {
                        lo = mid;
                    }
                }

                // Apply wave perturbation at hit UV; fall back to unperturbed hitUV if perturbed lands on sky/water
                vec2 finalUV = clamp(hitUV + hitUVPerturb, vec2(0.002), vec2(0.998));
                float finalDepth = texture(depthtex0, finalUV).r;
                int finalBlockId = int(texelFetch(colortex1, clamp(ivec2(finalUV * vec2(viewWidth, viewHeight)), ivec2(0), ivec2(viewWidth, viewHeight) - ivec2(1)), 0).z * 255.0 + 0.5);
                if (isSkyDepth(finalDepth) || isMatWater(finalBlockId)) {
                    finalUV = hitUV;
                    finalDepth = texture(depthtex0, finalUV).r;
                }
                if (isSkyDepth(finalDepth)) {
                    prevT = t;
                    prevRayZ = rayZ;
                    continue;
                }

                vec3 finalSv = ScreenToView(finalUV, finalDepth);

                // Tight 3.5% side/bottom & 5% top border feathering so valid SSR stays rich right up to viewport edge
                float edgeX = smoothstep(0.0, 0.035, finalUV.x) * (1.0 - smoothstep(0.965, 1.0, finalUV.x));
                float edgeY = smoothstep(0.0, 0.035, finalUV.y) * (1.0 - smoothstep(0.950, 1.0, finalUV.y));
                float edgeAlpha = edgeX * edgeY;

                vec3 hitColor = max(vec3(0.0), texture(colortex0, finalUV).rgb);
                hitColor = apply_terrain_fog(hitColor, finalSv);

                float depthWeight = 1.0 - smoothstep(0.0, maxThickness * 1.25, max(0.0, abs(finalSv.z - hitRayZ)));
                return vec4(hitColor, edgeAlpha * depthWeight);
            }
        }

        prevT = t;
        prevRayZ = rayZ;
    }

    return vec4(0.0);
}

// ============================================================================
// Shadow-Map (RSM) Off-Screen Reflection Raymarcher
// Traces reflected rays through the 120m shadow height/color map (shadowtex0 +
// shadowcolor0) whenever SSR exits the viewport or is occluded.
// Lighting & gamma are matched directly to gbuffers_terrain.fsh so there is
// zero brightening/washing-out along the screen border.
// ============================================================================
vec2 shadowNDCToDistortedUV(vec2 ndcXY) {
    float l = length(ndcXY);
    vec2 distXY = (l > 0.00001) ? (ndcXY / (l * 0.8 + 0.2)) : ndcXY;
    return distXY * 0.5 + 0.5;
}

vec4 ray_trace_shadow_reflection(
    vec3 startViewPos,
    vec3 reflectViewDir,
    vec3 realSunDir,
    float dither
) {
#if SHADOW_REFL_STEPS <= 0
    return vec4(0.0);
#else
    // Start close to water surface along reflection ray to catch nearby shore banks without sky gaps
    vec3 rayOriginVS = startViewPos + reflectViewDir * 0.22;
    float maxDist = 72.0;
    vec3 rayEndVS = rayOriginVS + reflectViewDir * maxDist;

    mat4 viewToShadow = shadowProjection * shadowModelView * gbufferModelViewInverse;
    vec4 s0_4 = viewToShadow * vec4(rayOriginVS, 1.0);
    vec4 s1_4 = viewToShadow * vec4(rayEndVS, 1.0);
    vec3 s0 = s0_4.xyz / max(abs(s0_4.w), 1e-5);
    vec3 s1 = s1_4.xyz / max(abs(s1_4.w), 1e-5);

    if (abs(s0.x) > 0.98 || abs(s0.y) > 0.98 || s0.z < 0.005 || s0.z > 0.995) {
        return vec4(0.0);
    }

    float prevProg = 0.0;
    float invSteps = 1.0 / float(SHADOW_REFL_STEPS);

    // Tightened solid terrain slab thickness in normalized shadow depth [0, 1] (300m ortho range)
    const float slabBias = 0.0005;
    const float slabThickness = 0.036;

    for (int i = 1; i <= SHADOW_REFL_STEPS; i++) {
        // Non-linear step distribution: finer near shore, broader for distant cliffs/trees
        float u = (float(i) - 0.5 + 0.5 * dither) * invSteps;
        float prog = u * u * 0.80 + u * 0.20;

        vec3 sNDC = mix(s0, s1, prog);
        if (abs(sNDC.x) > 0.97 || abs(sNDC.y) > 0.97 || sNDC.z < 0.005 || sNDC.z > 0.995) {
            break;
        }

        vec2 sUV = shadowNDCToDistortedUV(sNDC.xy);
        float rayZ = sNDC.z; // Vulkan shadow NDC Z in [0, 1]
        float mapZ = texture(shadowtex0, sUV).r;

        if (mapZ < 0.9999 && rayZ > mapZ + slabBias && rayZ < mapZ + slabThickness) {
            // 3-step binary refinement for crisp reflected silhouettes
            float lo = prevProg;
            float hi = prog;
            vec2 hitUV = sUV;
            float hitProg = prog;

            for (int j = 0; j < 3; j++) {
                float mid = 0.5 * (lo + hi);
                vec3 midNDC = mix(s0, s1, mid);
                vec2 midUV = shadowNDCToDistortedUV(midNDC.xy);
                float midRayZ = midNDC.z; // Vulkan shadow NDC Z in [0, 1]
                float midMapZ = texture(shadowtex0, midUV).r;

                if (midMapZ < 0.9999 && midRayZ > midMapZ + slabBias) {
                    hi = mid;
                    hitUV = midUV;
                    hitProg = mid;
                } else {
                    lo = mid;
                }
            }

            vec4 rsm = texture(shadowcolor0, hitUV);
            // Match gbuffers_terrain.fsh exact albedo decode curve (pow 2.1) & lighting scale
            vec3 albedo = pow(max(rsm.rgb, vec3(0.0)), vec3(2.1));
            float hitNdotL = rsm.a;

            vec3 skyCol, sunCol;
            float dayFac, sunInt;
            computeUnifiedLighting(realSunDir, skyCol, sunCol, dayFac, sunInt);

            float rainSunFade = mix(1.0, 0.25, saturate(rainStrength));
            vec3 directLight = sunCol * (sunInt * (hitNdotL / PI) * 3.8 * rainSunFade * 0.78);
            vec3 ambientLight = skyCol * mix(0.24, 0.30, saturate(rainStrength)) + vec3(0.004, 0.007, 0.012);
            vec3 hitColor = albedo * (directLight + ambientLight);

            // Apply matching aerial perspective fog at the reflected hit position
            vec3 hitViewPos = mix(rayOriginVS, rayEndVS, hitProg);
            hitColor = apply_terrain_fog(hitColor, hitViewPos);

            // Full opacity (up to 1.0) inside shadow frustum so bright sky never bleeds through solid hits
            vec3 hitNDC = mix(s0, s1, hitProg);
            float maxAxis = max(abs(hitNDC.x), abs(hitNDC.y));
            float frustumFade = 1.0 - smoothstep(0.80, 0.97, maxAxis);
            float distFade = 1.0 - smoothstep(0.80, 1.0, hitProg);
            float alpha = clamp(frustumFade * distFade, 0.0, 1.0);

            return vec4(max(hitColor, vec3(0.0)), alpha);
        }

        prevProg = prog;
    }

    return vec4(0.0);
#endif
}
