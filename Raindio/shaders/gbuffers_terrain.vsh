#version 330 compatibility

// 顶点输出变量
out vec3 vNormal;
out vec4 vColor;
out vec4 vTexCoord;
out vec3 vEyePos;
out vec2 vLightmap;
out float vBlockId;

out vec3 vSkyColor;
out vec3 vSunlight;
out vec4 vParams; // 打包传输：x: vDayFactor, y: vSunIntensity, z: vEmissive

in float mc_Entity;

uniform vec3 sunPosition;
uniform mat4 gbufferModelViewInverse;

void main() {
    gl_Position = ftransform();

    vTexCoord = gl_TextureMatrix[0] * gl_MultiTexCoord0;
    vLightmap = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    vColor = gl_Color;
    vBlockId = mc_Entity;

    vec4 viewPos = gl_ModelViewMatrix * gl_Vertex;
    vEyePos = viewPos.xyz;
    vNormal = normalize(gl_NormalMatrix * gl_Normal);

    // 修改：同步降低 Y 轴，保持点光照与表面计算方向一致
    vec3 rawSunDir = (gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz;
    rawSunDir.y *= 0.4;
    vec3 worldSunDir = normalize(rawSunDir);

    float sunHeight = worldSunDir.y;

    float dayFactor = smoothstep(-0.05, 0.15, sunHeight);
    float noonFactor = smoothstep(0.00, 0.60, sunHeight);

    const vec3 skyNight  = vec3(0.002, 0.010, 0.040);
    const vec3 skySunset = vec3(0.55, 0.25, 0.06);
    const vec3 skyNoon   = vec3(0.18, 0.35, 0.75);
    const vec3 sunNight  = vec3(0.00, 0.00, 0.00);
    const vec3 sunSunset = vec3(0.90, 0.40, 0.08);
    const vec3 sunNoon   = vec3(1.00, 0.92, 0.75);

    vSkyColor = mix(skyNight, mix(skySunset, skyNoon, noonFactor), dayFactor);
    vSunlight = (sunHeight > 0.0) ? mix(sunNight, mix(sunSunset, sunNoon, noonFactor), dayFactor) : vec3(0.18, 0.25, 0.40);
    float sunIntensity = (sunHeight > 0.0) ? 1.0 : 0.4;

    float emissive = 0.0;
    if (mc_Entity == 10000.0) {
        emissive = 0.7;
        vLightmap.x = 1.0;
    } else if (mc_Entity == 10001.0) {
        emissive = 0.35;
        vLightmap.x = max(vLightmap.x, 0.85);
    } else {
        emissive = smoothstep(0.93, 0.98, vLightmap.x) * 0.3;
    }

    vParams = vec4(dayFactor, sunIntensity, emissive, 0.0);
}