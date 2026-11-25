// ============================================================================
// ScreenToView & Reverse-Z depth helpers for Minecraft 26.3 RenderPearl Vulkan.
// Scene depth (depthtex0, depthtex1) uses Reverse-Z: near = 1.0, far/sky = 0.0.
// Requires `uniform mat4 gbufferProjectionInverse;` declared by the including shader.
// ============================================================================

bool isSkyDepth(float d) {
    return d <= 0.00001;
}

bool isSceneDepth(float d) {
    return d > 0.00001;
}

vec3 ScreenToView(vec2 uv, float depth) {
    vec4 ndc = vec4(uv * 2.0 - 1.0, depth, 1.0); // Vulkan Reverse-Z NDC in [0, 1]
    vec4 view = gbufferProjectionInverse * ndc;
    return view.xyz / view.w;
}

// Fast scalar linear view-space depth (-view.z), mathematically identical to -ScreenToView(uv, depth).z
float linearizeDepth(float depth) {
    float vz = gbufferProjectionInverse[2][2] * depth + gbufferProjectionInverse[3][2];
    float vw = gbufferProjectionInverse[2][3] * depth + gbufferProjectionInverse[3][3];
    return max(-(vz / vw), 0.05);
}
