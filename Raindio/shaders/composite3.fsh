#version 120
/* DRAWBUFFERS:07 */

varying vec2 texCoord;

uniform sampler2D colortex0;
uniform sampler2D colortex1;
uniform sampler2D colortex3;
uniform sampler2D colortex4;
uniform sampler2D colortex6;
uniform sampler2D colortex7;
uniform sampler2D depthtex0; // 水面/半透明深度
uniform sampler2D depthtex1; // 不透明水底深度
uniform sampler2D noisetex;

uniform mat4 gbufferProjectionInverse;
uniform mat4 gbufferModelViewInverse;
uniform mat4 gbufferModelView;
uniform vec3 sunPosition;
uniform vec3 cameraPosition;
uniform float rainStrength;
uniform float frametime;
uniform float frameTimeCounter;
uniform float near;
uniform float far;

// 引入水下相机状态判定
uniform int isEyeInWater;

float saturate(float x) { return clamp(x, 0.0, 1.0); }

// =============================================================================
// Atmospheric Scattering & Helper Functions
// =============================================================================

const float ATM_R       = 1.0;
const float ATM_R_INNER = 0.985;
const float ATM_SCALE_H = 4.0 / (ATM_R - ATM_R_INNER);
const float ATM_SCALE_L = 1.0 / (ATM_R - ATM_R_INNER);
const float ATM_K_R     = 0.166;
const float ATM_K_M     = 0.0025;
const float ATM_E       = 14.3;
const vec3  ATM_C_R     = vec3(0.3, 0.52, 1.0);
const float ATM_G_M     = -0.85;

float phase_rayleigh(float cc) { return 0.75 * (1.0 + cc); }

float phase_mie(float g, float c, float cc) {
    float gg = g * g;
    float a = (1.0 - gg) * (1.0 + cc);
    float b = 1.0 + gg - 2.0 * g * c;
    b *= sqrt(b); b *= 2.0 + gg;
    return 1.5 * a / b;
}

float atm_density(vec3 p) { return exp(-(length(p) - ATM_R_INNER) * ATM_SCALE_H) * 2.0; }

vec2 RaySphereIntersect(vec3 p, vec3 dir, float r) {
    float b = dot(p, dir);
    float d = b * b - dot(p, p) + r * r;
    if (d < 0.0) return vec2(10000.0, -10000.0);
    d = sqrt(d);
    return vec2(-b - d, -b + d);
}

float atm_optic(vec3 p, vec3 q) {
    vec3 step = (q - p) / 4.0;
    vec3 v = p + step * 0.5;
    float sum = 0.0;
    for (int i = 0; i < 4; i++) { sum += atm_density(v); v += step; }
    return sum * length(step) * ATM_SCALE_L;
}

vec3 atm_in_scatter(vec3 o, vec3 dir, vec2 e, vec3 l, float mie, float rayAmt) {
    float boosty = saturate(l.y + 0.1) * 0.95 + 0.05;
    boosty = clamp(1.0 / sin(boosty), 1.0, 2.0);
    float len = e.y / 8.0;
    vec3 step = dir * len * 2.0;
    vec3 p = o; vec3 v = p + dir * len * 0.5;
    float rayK = ATM_K_R * rayAmt;
    float mieK = ATM_K_M * 0.045 * mie;
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        vec2 f = RaySphereIntersect(v, l, ATM_R);
        float n = (atm_optic(p, v) + atm_optic(v, v + l * f.y)) * 12.56637;
        sum += atm_density(v) * exp(-n * (rayK * ATM_C_R + mieK));
        v += step;
    }
    sum *= len * ATM_SCALE_L;
    float c = dot(dir, -l);
    return sum * (rayK * ATM_C_R * phase_rayleigh(c*c) + mieK * phase_mie(ATM_G_M, c, c*c)) * ATM_E * boosty;
}

vec3 AtmosphericScattering(vec3 rayDir, vec3 lightVector, float mieAmount) {
    vec3 eye = vec3(0.0, ATM_R_INNER + 0.00025, 0.0);
    vec3 dir = rayDir;
    if (dir.y < 0.0) dir.y = 0.0;
    vec2 e = RaySphereIntersect(eye, dir, ATM_R);
    vec3 atmosphere = atm_in_scatter(eye, dir, e, lightVector, mieAmount, 1.0);
    atmosphere *= vec3(0.78, 0.85, 1.0);
    return max(atmosphere, vec3(0.0));
}

// 补全缺失的日月圆盘渲染函数
float SunDisc(vec3 worldDir, vec3 sunDir) {
    float d = dot(worldDir, sunDir);
    float disc = saturate((d - 0.99805) * 1000.0);
    disc = pow(disc * disc * (3.0 - 2.0 * disc), 2.0);
    return disc * saturate(worldDir.y * 30.0);
}

vec3 ACESFilm(vec3 x) {
    float a = 2.51; float b = 0.03; float c = 2.43; float d = 0.59; float e = 0.14;
    x = x * 0.9;
    return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
}

// =============================================================================
// Screen <-> View conversions
// =============================================================================

vec3 ScreenToView(vec2 uv, float depth) {
    vec4 ndc = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = gbufferProjectionInverse * ndc;
    return view.xyz / view.w;
}

vec2 ViewToScreen(vec3 viewPos) {
    float rcpZ = 1.0 / (-viewPos.z);
    float fx = rcpZ / gbufferProjectionInverse[0][0];
    float fy = rcpZ / gbufferProjectionInverse[1][1];
    return vec2(viewPos.x * fx, viewPos.y * fy) * 0.5 + 0.5;
}

// =============================================================================
// Water Wave System
// =============================================================================

#define SEA_HEIGHT 0.8
#define SEA_CHOPPY 8.0
#define SEA_SPEED 0.85
#define SEA_FREQ 0.25

float hash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.2031);
    p3 += dot(p3, p3.yzx + 19.19);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = (f * f) * (vec2(3.0) - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y) * 2.0 - 1.0;
}

float sea_octave_micro(vec2 uv, float choppy) {
    uv += noise(uv);
    vec2 wv = 1.0 - abs(sin(uv));
    vec2 swv = abs(cos(uv));
    wv = mix(wv, swv, wv);
    return pow(1.0 - pow(wv.x * wv.y, 0.75), choppy);
}

const mat2 octave_m = mat2(1.4, 1.1, -1.2, 1.4);
const float height_mul[4] = float[4](0.32, 0.24, 0.20, 0.22);
const float total_height = 0.32 + 0.32*0.24 + 0.32*0.24*0.20 + 0.32*0.24*0.20*0.22;
const float rcp_total_height = 1.0 / total_height;

float getwave2(vec3 p, float lod) {
    float freq = SEA_FREQ;
    float amp = SEA_HEIGHT;
    float choppy = SEA_CHOPPY;
    vec2 uv = p.xz - vec2(frameTimeCounter * 0.5, 0.0); uv.x *= 0.75;
    float wave_speed = frameTimeCounter * SEA_SPEED;
    float d, h = 0.0;
    for (int i = 0; i < 4; i++) {
        d = sea_octave_micro((uv + wave_speed) * freq, choppy);
        h += d * amp;
        uv *= octave_m; freq *= 1.9; amp *= height_mul[i]; wave_speed *= -1.1;
        choppy = mix(choppy, 1.0, 0.2);
    }
    return h * rcp_total_height * lod;
}

vec3 get_water_normal(vec3 wwpos, float lod) {
    float eps = 0.05;
    float h0 = getwave2(wwpos, lod);
    float hx = getwave2(wwpos + vec3(eps, 0.0, 0.0), lod);
    float hz = getwave2(wwpos + vec3(0.0, 0.0, eps), lod);
    
    vec3 dPdx = vec3(eps, (hx - h0) * 0.25, 0.0);
    vec3 dPdz = vec3(0.0, (hz - h0) * 0.25, eps);
    
    return normalize(cross(dPdz, dPdx));
}

// =============================================================================
// SSR 屏幕空间反射系统
// =============================================================================

vec4 ray_trace_ssr(vec3 direction, vec3 start) {
    vec3 testPoint = start;
    bool hit = false;
    bool lost = false;
    vec2 uv = vec2(0.5);
    vec3 hitColor = vec3(0.0);
    float alpha = 0.0;

    float h = 0.02 * length(start);

    for (int i = 0; i < 16; i++) {
        // Only advance if still tracing
        float step = h * (lost ? 0.0 : 1.0);
        testPoint += direction * step;

        // Check near-plane clipping (cannot break, use lost flag)
        if (testPoint.z >= -0.1) { lost = true; }

        if (!lost) {
            uv = ViewToScreen(testPoint);

            bool inBounds = (uv.x > 0.0 && uv.x < 1.0 && uv.y > 0.0 && uv.y < 1.0);
            if (!inBounds) { lost = true; }
        }

        if (!lost && !hit) {
            float rawDepth = texture2D(depthtex0, uv).r;

            if (rawDepth < 0.9999) {
                vec3 sv = ScreenToView(uv, rawDepth);
                float diff = sv.z - testPoint.z;

                if (diff > -0.0001 && diff < 1.5) {
                    float waterFlag = texture2D(colortex1, uv).a;
                    if (waterFlag > 0.9) {
                        hitColor = max(vec3(0.0), texture2D(colortex0, uv).rgb);

                        vec2 edge = smoothstep(vec2(0.0), vec2(0.1), uv) * smoothstep(vec2(1.0), vec2(0.9), uv);
                        alpha = edge.x * edge.y;
                        hit = true;
                    }
                }
                float contract = 1.0 - 0.0313 * float(i + 1);
                h = max(abs(diff) * contract, 0.05);
            } else {
                h = 0.5;
            }
        }
    }

    return vec4(hitColor, alpha);
}

// =============================================================================
// Main Shader Function
// =============================================================================

void main() {
    vec2 uv = texCoord;
    float waterDepth  = texture2D(depthtex0, uv).x; 
    float seabedDepth = texture2D(depthtex1, uv).x; 
    vec3 hdr = texture2D(colortex0, uv).rgb;

    bool isWater = (seabedDepth - waterDepth > 0.00005) && (waterDepth < 0.9999);
    bool underwater = (isEyeInWater == 1);

    if (isWater) {
        vec3 waterViewPos  = ScreenToView(uv, waterDepth);
        vec3 seabedViewPos = ScreenToView(uv, seabedDepth);
        vec3 waterViewDir  = normalize(waterViewPos);

        float waterThickness = max(0.0, abs(seabedViewPos.z - waterViewPos.z));

        vec3 waterWorldPos = (gbufferModelViewInverse * vec4(waterViewPos, 1.0)).xyz + cameraPosition;

        // ---- 1. 水上/水下视角法线方向适配与平滑防翻转 ----
        vec3 flatWorldNormal = underwater ? vec3(0.0, -1.0, 0.0) : vec3(0.0, 1.0, 0.0);
        vec3 flatViewNormal  = normalize(mat3(gbufferModelView) * flatWorldNormal);
        float flatNdotV      = max(0.0, dot(flatViewNormal, -waterViewDir));

        vec3 rawWorldNormal = get_water_normal(waterWorldPos, 1.0);
        if (underwater) {
            rawWorldNormal = vec3(rawWorldNormal.x, -rawWorldNormal.y, rawWorldNormal.z);
        }

        float waveWeight = smoothstep(0.01, 0.22, flatNdotV);
        vec3 waterWorldNormal = normalize(mix(flatWorldNormal, rawWorldNormal, waveWeight));

        vec3 waterNormal = normalize(mat3(gbufferModelView) * waterWorldNormal);

        float NdotV_raw = dot(waterNormal, -waterViewDir);
        if (NdotV_raw < 0.001) {
            waterNormal = normalize(waterNormal - waterViewDir * (0.001 - NdotV_raw));
        }

        // ---- 2. SSR 与天空/水下反射合成 ----
        vec3 reflectViewDir = normalize(reflect(waterViewDir, waterNormal));

        vec4 ssr = ray_trace_ssr(reflectViewDir, waterViewPos);
        vec3 reflection = ssr.rgb;

        if (ssr.a < 0.95) {
            if (!underwater) {
                vec3 reflectWorldDir = normalize((gbufferModelViewInverse * vec4(reflectViewDir, 0.0)).xyz);
                
                float horizonFade = smoothstep(-0.12, 0.05, reflectWorldDir.y);
                reflectWorldDir.y = max(reflectWorldDir.y, 0.02);

                vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
                
                vec3 skyRefl;
                if (realSunDir.y > -0.05) {
                    skyRefl = AtmosphericScattering(reflectWorldDir, realSunDir, 1.0);
                } else {
                    vec3 moonDir = normalize(-realSunDir);
                    skyRefl = AtmosphericScattering(reflectWorldDir, moonDir, 0.1) * 0.15;
                    skyRefl += vec3(0.002, 0.004, 0.01);
                }
                
                skyRefl = max(skyRefl, vec3(0.005, 0.01, 0.03));
                skyRefl *= horizonFade;
                reflection = mix(skyRefl, ssr.rgb, ssr.a);
            } else {
                vec3 underwaterRefl = vec3(0.005, 0.03, 0.07);
                reflection = mix(underwaterRefl, ssr.rgb, ssr.a);
            }
        }

        // ---- 3. 折射采质与 Beer-Lambert 吸光 ----
        vec2 refractOffset = waterNormal.xy * 0.015 * clamp(waterThickness, 0.0, 1.5);
        vec2 refractUV = clamp(uv + refractOffset, vec2(0.001), vec2(0.999));
        if (texture2D(depthtex1, refractUV).x <= waterDepth) refractUV = uv;
        vec3 refractionColor = texture2D(colortex0, refractUV).rgb;

        // ---- 4. 菲涅尔混合 (水上 vs 水下) ----
        float NdotV = max(0.0, dot(waterNormal, -waterViewDir));
        float fresnel = 0.02 + 0.98 * pow(1.0 - NdotV, 5.0);

        if (underwater) {
            hdr = mix(refractionColor, reflection, fresnel);
        } else {
            vec3 extinction = vec3(0.35, 0.12, 0.04);
            vec3 absorb = exp(-extinction * waterThickness);
            vec3 waterColor = refractionColor * absorb;

            vec3 deepWaterColor = vec3(0.005, 0.04, 0.08);
            float fogFactor = 1.0 - exp(-waterThickness * 0.12);
            vec3 refractedColor = mix(waterColor, deepWaterColor, fogFactor);

            hdr = mix(refractedColor, reflection, fresnel);

            // ---- 5. 太阳高光 ----
            vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);
            vec3 viewSunDir = normalize((gbufferModelView * vec4(realSunDir, 0.0)).xyz);
            vec3 halfVec = normalize(-waterViewDir + viewSunDir);
            float NdotH = max(0.0, dot(waterNormal, halfVec));
            float NdotL = max(0.0, dot(waterNormal, viewSunDir));

            float roughness = 0.1;
            float a = roughness * roughness;
            float a2 = a * a;
            float denom = NdotH * NdotH * (a2 - 1.0) + 1.0;
            float D = a2 / (3.14159265 * denom * denom);

            float specBrdf = min((D * 0.25) / max(4.0 * NdotV * NdotL, 0.01), 2.5);
            float sunVisibility = saturate(realSunDir.y * 3.0);
            hdr += specBrdf * fresnel * vec3(1.0, 0.95, 0.8) * sunVisibility * 0.2 * (1.0 - rainStrength);
        }
    }

    // =========================================================================
    // 天空合成与 Tonemapping
    // =========================================================================

    vec3 bloom = texture2D(colortex3, uv).rgb + texture2D(colortex4, uv).rgb;
    hdr += bloom * 0.05;

    float rawLum = texture2D(colortex6, vec2(0.5, 0.5)).r;
    rawLum = max(rawLum, 0.0001);
    float prevLum = texture2D(colortex7, vec2(0.5, 0.5)).r;
    if (prevLum < 0.0001) prevLum = rawLum;
    float smoothLum = prevLum + (rawLum - prevLum) * (1.0 - exp(-frametime * 3.0));
    float nightFactor = clamp(1.0 - smoothLum * 3.0, 0.0, 1.0);
    float exposure = clamp(mix(0.18, 0.04, nightFactor) / smoothLum, 0.15, 2.0);

    vec3 terrainResult = ACESFilm(hdr * exposure);

    // 天空渲染
    vec4 ndc = vec4(uv * 2.0 - 1.0, 1.0, 1.0);
    vec4 vp4 = gbufferProjectionInverse * ndc;
    vec3 viewDir = normalize(vp4.xyz / vp4.w);
    vec3 worldDir = normalize((gbufferModelViewInverse * vec4(viewDir, 0.0)).xyz);
    vec3 realSunDir = normalize((gbufferModelViewInverse * vec4(sunPosition, 0.0)).xyz);

    vec3 skyHDR;
    if (realSunDir.y > -0.05) {
        skyHDR = AtmosphericScattering(worldDir, realSunDir, 1.0);
    } else {
        skyHDR = AtmosphericScattering(worldDir, normalize(-realSunDir), 0.05) * 0.02 + vec3(0.001, 0.002, 0.005);
    }

    float sunHeight = realSunDir.y;
    float horizonFactor = saturate(sunHeight * 3.0);
    float sd = dot(worldDir, realSunDir);
    float sunSize = 0.00195 * mix(0.5, 1.0, horizonFactor);
    float sunDiscMask = saturate((sd - (1.0 - sunSize)) * 1000.0 * mix(0.3, 1.0, horizonFactor));
    sunDiscMask = pow(sunDiscMask * sunDiscMask * (3.0 - 2.0 * sunDiscMask), 2.0);
    float sunGlow = exp(-(1.0 - sd) * mix(300.0, 50.0, horizonFactor));
    vec3 sunColor = mix(vec3(1.0, 0.4, 0.05), vec3(1.0, 0.98, 0.85), saturate(sunHeight * 2.5));
    skyHDR += (sunDiscMask * sunColor * 12.0 + sunGlow * sunColor * 1.5) * smoothstep(0.0, 0.3, sunHeight) * saturate(sunHeight * 10.0);

    // 夜间月亮圆盘渲染（调用的 SunDisc 已在前文声明）
    if (realSunDir.y < 0.0) skyHDR += SunDisc(worldDir, normalize(-realSunDir)) * vec3(0.7, 0.75, 0.9) * 0.35;

    skyHDR = max(skyHDR, vec3(0.0));
    vec3 skyResult = ACESFilm(skyHDR * exposure);

    float skyFactor = smoothstep(0.998, 0.9995, waterDepth);
    vec3 result = mix(terrainResult, skyResult, skyFactor);

    float cn = length(result);
    if (cn > 0.0001) result = pow(cn, 1.0 / 0.95) * (result / cn);
    result = clamp(result * 1.02, 0.0, 1.0);
    float lum = dot(result, vec3(0.299, 0.587, 0.114));
    result = mix(vec3(lum), result, 1.0);
    result += fract(sin(dot(texCoord, vec2(12.9898, 78.233))) * 43758.5453) / 255.0;

    gl_FragData[0] = vec4(result, 1.0);
    gl_FragData[7] = vec4(smoothLum, smoothLum, smoothLum, 1.0);
}