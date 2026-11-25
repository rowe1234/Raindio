#version 450
#include "common/raindio_uniforms.glsl"

/* DRAWBUFFERS:6 */

/*
*/

const int shadowMapResolution = 4096;
const float shadowDistance = 120.0;
// Natural moving sun path: rises in east, arcs high at noon, sets in west
const float sunPathRotation = -30.0;
const bool shadowHardwareFiltering = false;

#define COMPOSITE_LUT_ONLY
#include "lib/common.glsl"
#include "lib/atmosphere.glsl"
#include "lib/sky.glsl"

layout(location = 0) in vec2 texCoord;


layout(location = 0) out vec4 fragData0;

void main() {
    vec3 dir = skyUVToDir(texCoord);
    vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float twilightBlend = smoothstep(-0.12, 0.06, realSunDir.y);

    vec3 atmBase = ComputeAtmosphereLUT(dir, realSunDir, twilightBlend, rainStrength);
    fragData0 = vec4(atmBase, 1.0);
}
