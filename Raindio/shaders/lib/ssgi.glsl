// ============================================================================
// Screen-space global illumination (ray-marched, single source of truth for
// SSGI config). The accumulation/reprojection loop lives in the SSGI pass
// (composite3.fsh); only ray_trace_ssgi is shared here.
// Requires ScreenToView (/lib/projection.glsl).
// Requires depthtex0, colortex0, colortex1, viewWidth, viewHeight,
// gbufferProjectionInverse uniforms.
// ============================================================================

#define SSGI_ENABLED 1
#define SSGI_STRENGTH 0.8
#define SSGI_BOUNCE_BOOST 1.5
#define SSGI_SAMPLES 8
#define SSGI_STEPS 8
#define SSGI_RADIUS 1.5

vec3 ray_trace_ssgi(vec3 startViewPos, vec3 rayDir, vec3 surfaceNormal, vec2 invProjDiag, float dither) {
    vec3 testPoint = startViewPos + surfaceNormal * (0.08 + 0.02 * length(startViewPos));
    float stepLen = SSGI_RADIUS / float(SSGI_STEPS);
    testPoint += rayDir * (stepLen * (0.1 + 0.9 * dither));

    for (int i = 0; i < SSGI_STEPS; i++) {
        if (testPoint.z >= -0.1) break;

        vec2 sampleUV = (testPoint.xy / (-testPoint.z * invProjDiag)) * 0.5 + 0.5;
        if (sampleUV.x <= 0.005 || sampleUV.x >= 0.995 || sampleUV.y <= 0.005 || sampleUV.y >= 0.995) break;

        float rawDepth = texture(depthtex0, sampleUV).r;
        if (rawDepth < 0.9999) {
            vec3 sampleViewPos = ScreenToView(sampleUV, rawDepth);
            float diff = sampleViewPos.z - testPoint.z;

            if (diff > -0.05 && diff < (stepLen * 1.2 + 0.12)) {
                vec2 sampleEncN = texelFetch(colortex1, ivec2(sampleUV * vec2(viewWidth, viewHeight)), 0).rg;
                vec2 sn2 = sampleEncN * 2.0 - 1.0;
                float snz = sqrt(max(0.0, 1.0 - dot(sn2, sn2)));
                vec3 sampleNormal = normalize(vec3(sn2, snz));

                float normalWeight = max(0.0, dot(sampleNormal, -rayDir));
                float dist = length(sampleViewPos - startViewPos);
                float distFade = max(0.0, 1.0 - (dist / SSGI_RADIUS));

                vec3 hitColor = max(vec3(0.0), texture(colortex0, sampleUV).rgb);
                vec2 edge = smoothstep(vec2(0.0), vec2(0.08), sampleUV) * smoothstep(vec2(1.0), vec2(0.92), sampleUV);

                return hitColor * edge.x * edge.y * normalWeight * distFade * SSGI_BOUNCE_BOOST;
            }
        }
        testPoint += rayDir * stepLen;
    }
    return vec3(0.0);
}
