// ============================================================================
// ScreenToView: reconstruct view-space position from screen UV + linear depth.
// Requires `uniform mat4 gbufferProjectionInverse;` declared by the including shader.
// ============================================================================

vec3 ScreenToView(vec2 uv, float depth) {
    vec4 ndc = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = gbufferProjectionInverse * ndc;
    return view.xyz / view.w;
}
