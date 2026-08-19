#version 120

varying vec3 vNormal;
varying vec4 vColor;
varying vec4 vTexCoord;
varying vec3 vEyePos;
varying vec2 vLightmap;

varying vec3 vSkyColor;
varying vec3 vSunlight;
varying float vDayFactor;
varying float vSunIntensity;

uniform vec3 sunPosition;
uniform mat4 gbufferModelViewInverse;

void main() {
    vTexCoord = gl_MultiTexCoord0;
    vLightmap = (gl_TextureMatrix[1] * gl_MultiTexCoord1).xy;
    vColor = gl_Color;

    vec4 viewPos = gl_ModelViewMatrix * gl_Vertex;
    vEyePos = viewPos.xyz;
    vNormal = normalize(gl_NormalMatrix * gl_Normal);

    vec3 worldSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
    float sunHeight = worldSunDir.y;

    vDayFactor = smoothstep(-0.05, 0.15, sunHeight);
    float noonFactor = smoothstep(0.00, 0.60, sunHeight);

    const vec3 skyNight  = vec3(0.008, 0.010, 0.045);
    const vec3 skySunset = vec3(0.55, 0.25, 0.06);
    const vec3 skyNoon   = vec3(0.18, 0.35, 0.75);
    const vec3 sunNight  = vec3(0.00, 0.00, 0.00);
    const vec3 sunSunset = vec3(0.90, 0.40, 0.08);
    const vec3 sunNoon   = vec3(1.00, 0.92, 0.75);

    vSkyColor = mix(skyNight, mix(skySunset, skyNoon, noonFactor), vDayFactor);
    vSunlight = (sunHeight > 0.0) ? mix(sunNight, mix(sunSunset, sunNoon, noonFactor), vDayFactor) : vec3(0.15, 0.20, 0.35);
    vSunIntensity = (sunHeight > 0.0) ? 1.0 : 0.4;

    gl_Position = ftransform();
}