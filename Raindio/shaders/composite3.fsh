#version 410 core
/* DRAWBUFFERS:8 */

in vec2 texCoord;

uniform sampler2D depthtex0;
uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex8;

uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferPreviousProjection;
uniform mat4 gbufferPreviousModelView;

uniform vec3 cameraPosition;
uniform vec3 previousCameraPosition;
uniform float frameTimeCounter;
uniform float viewWidth;
uniform float viewHeight;

uniform int isEyeInWater;

#include "/lib/common.glsl"
#include "/lib/projection.glsl"
#include "/lib/ssgi.glsl"

#define BLOCK_ID_WATER 1
#define BLOCK_ID_GLASS 2

layout(location = 0) out vec4 fragData0;

void main() {
    vec2 uv = texCoord;
    ivec2 pixelCoord = ivec2(gl_FragCoord.xy);

    float waterDepth = texture(depthtex0, uv).r;
    vec4 colortex1_data = texelFetch(colortex1, pixelCoord, 0);
    int blockId = int(colortex1_data.z * 255.0 + 0.5);
    vec2 encodedDataN = colortex1_data.rg;

    bool isWater = (blockId == BLOCK_ID_WATER) && (waterDepth < 0.9999);
    bool isGlass = (blockId == BLOCK_ID_GLASS) && (waterDepth < 0.9999);
    bool underwater = (isEyeInWater == 1);

    vec3 accumulatedSSGI = vec3(0.0);

    if (waterDepth < 0.9999 && !isWater && !isGlass) {
        vec3 viewPos = ScreenToView(uv, waterDepth);

        vec2 n2 = encodedDataN * 2.0 - 1.0;
        float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
        vec3 viewNormal = normalize(vec3(n2, nz));

    #if SSGI_ENABLED == 1
        if (!underwater) {
            vec3 tangent = normalize(abs(viewNormal.z) < 0.999 ? cross(vec3(0.0, 0.0, 1.0), viewNormal) : cross(vec3(1.0, 0.0, 0.0), viewNormal));
            vec3 bitangent = cross(viewNormal, tangent);
            mat3 tbn = mat3(tangent, bitangent, viewNormal);

            vec2 invProjDiag = vec2(gbufferProjectionInverse[0][0], gbufferProjectionInverse[1][1]);

            float frameTimeMod = mod(frameTimeCounter, 100.0);
            float dither = interleavedGradientNoise(gl_FragCoord.xy + vec2(frameTimeMod * 13.0, frameTimeMod * 17.0));

            vec3 sumSSGI = vec3(0.0);
            vec3 sumSqSSGI = vec3(0.0);

            for (int i = 0; i < SSGI_SAMPLES; i++) {
                float fi = float(i);
                float fi_n = (fi + dither) / float(SSGI_SAMPLES);
                float phi = fi_n * 6.28318530718;
                float cosTheta = sqrt(1.0 - fi_n);
                float sinTheta = sqrt(fi_n);

                vec3 localDir = vec3(cos(phi) * sinTheta, sin(phi) * sinTheta, cosTheta);
                vec3 sampleDir = normalize(tbn * localDir);

                vec3 sampleResult = ray_trace_ssgi(viewPos, sampleDir, viewNormal, invProjDiag, dither);
                sumSSGI += sampleResult;
                sumSqSSGI += sampleResult * sampleResult;
            }

            vec3 rawSSGI = sumSSGI / float(SSGI_SAMPLES);
            vec3 mean = rawSSGI;
            vec3 sigma = sqrt(max(vec3(0.0), (sumSqSSGI / float(SSGI_SAMPLES)) - mean * mean));

            vec3 cameraOffset = cameraPosition - previousCameraPosition;
            vec3 worldPos = (gbufferModelViewInverse * vec4(viewPos, 1.0)).xyz + cameraOffset;
            vec4 prevViewPos = gbufferPreviousModelView * vec4(worldPos, 1.0);
            vec4 prevClipPos = gbufferPreviousProjection * prevViewPos;
            vec2 prevUV = (prevClipPos.xy / prevClipPos.w) * 0.5 + 0.5;

            accumulatedSSGI = rawSSGI;
            if (prevUV.x > 0.001 && prevUV.x < 0.999 && prevUV.y > 0.001 && prevUV.y < 0.999) {
                vec3 historySSGI = texture(colortex8, prevUV).rgb;

                vec3 minAllowed = mean - 1.2 * sigma;
                vec3 maxAllowed = mean + 2.0 * sigma + vec3(0.01);
                vec3 clampedHistory = clamp(historySSGI, max(vec3(0.0), minAllowed), maxAllowed);

                float velocity = length(prevUV - uv);
                float currentFrameWeight = mix(0.15, 0.65, clamp(velocity * 40.0, 0.0, 1.0));

                if (dot(historySSGI, vec3(1.0)) < 0.0001) {
                    accumulatedSSGI = rawSSGI;
                } else {
                    accumulatedSSGI = mix(clampedHistory, rawSSGI, currentFrameWeight);
                }
            } else {
                accumulatedSSGI = rawSSGI;
            }
        }
    #endif
    }

    fragData0 = vec4(accumulatedSSGI, 1.0);
}
