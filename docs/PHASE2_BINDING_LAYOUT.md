# Raindio 阶段 2 着色器 UBO 与 Descriptor Set 绑定规范（GLSL 450 / MoltenVK）

> 全部 17 个活跃 Pass（34 个 `.vert`/`.frag`）+ 共用 `fullscreen.vert` + 12 个 `lib/*.glsl` 已迁移为标准 GLSL 450，并通过 `glslc --target-env=vulkan1.2 --target-spv=spv1.3 -O` 编译校验。

## 1. Vulkan 坐标系与深度约定（阶段 3 CPU 侧矩阵必须严格对齐）

- **NDC 深度范围**：所有投影矩阵（`gbufferProjection`、`gbufferPreviousProjection`、`shadowProjection`、`projectionMatrix`）输出 Vulkan 标准深度 `Z ∈ [0, 1]`（非 OpenGL `[-1, 1]`）。
- **Y 轴方向**：采用 Vulkan 标准 Y-down 裁剪空间（CPU 侧对投影矩阵 `proj[1][1]` 取反），使屏幕 UV `(0, 0)` 位于左上角、`(1, 1)` 位于右下角，与 `gl_FragCoord.xy`、`texelFetch` 及 Vulkan 纹理采样坐标系完全一致。
- **阴影深度比较**：`gbuffers_terrain.frag`、`gbuffers_entities.frag`、`lib/volumetric.glsl`、`lib/ssr.glsl` 均直接使用 `shadowNDC.z ∈ [0, 1]` 与 `shadowtex0` 比较。

## 2. Descriptor Set 0 — Uniform Buffers (`std140`)

- **`set = 0, binding = 0` (`FrameUniforms`, 592 bytes)**：所有 Pass 共享，每帧更新一次：
  - Offset `0..511` (`8 × mat4`)：`gbufferModelView`, `gbufferModelViewInverse`, `gbufferProjection`, `gbufferProjectionInverse`, `gbufferPreviousModelView`, `gbufferPreviousProjection`, `shadowModelView`, `shadowProjection`
  - Offset `512` (`vec4`)：`cameraPosition.xyz`, `far`
  - Offset `528` (`vec4`)：`previousCameraPosition.xyz`, `frameTimeCounter`
  - Offset `544` (`vec4`)：`sunPosition.xyz`, `rainStrength`
  - Offset `560` (`vec4`)：`viewWidth`, `viewHeight`, `float(isEyeInWater)`, `pad`
  - Offset `576` (`ivec4`)：`eyeBrightnessSmooth.xy`, `pad.zw`
- **`set = 0, binding = 1` (`DrawUniforms`, 336 bytes)**：仅 `shadow` 与 `gbuffers_*` 几何 Pass 绑定（`#define RAINDIO_HAS_DRAW_UBO`）：
  - Offset `0..319` (`5 × mat4`)：`modelViewMatrix`, `projectionMatrix`, `normalMatrix4`, `textureMatrix0`, `textureMatrix1`
  - Offset `320` (`vec4`)：`entityColor`

## 3. 几何 Pass 统一顶点输入布局

| Location | 属性名 | 类型 | 说明 |
| --- | --- | --- | --- |
| `0` | `vaPosition` | `vec3` | 顶点位置 |
| `1` | `vaColor` | `vec4` | 顶点颜色 / 生物群系染色 |
| `2` | `vaUV0` | `vec2` | 主纹理坐标 |
| `3` | `vaUV2` | `ivec2` | 光照贴图坐标（0..240） |
| `4` | `vaNormal` | `vec3` | 顶点法线 |
| `5` | `mc_Entity` | `float` | `block.properties` 方块材质 ID |

## 4. Descriptor Set 1 — 各 Pass 纹理采样器绑定表

| Pass (`frag`) | `set = 1` Bindings |
| --- | --- |
| `composite1.frag` | `0`: `depthtex0`, `1`: `colortex0`, `2`: `colortex1`, `3`: `colortex8`, `4`: `noisetex` |
| `composite2.frag` | `0`: `colortex0`, `1`: `colortex1`, `2`: `colortex3`, `3`: `colortex6`, `4`: `depthtex0`, `5`: `depthtex1`, `6`: `noisetex`, `7`: `shadowtex0`, `8`: `shadowcolor0` |
| `composite3.frag` | `0`: `colortex0`, `1`: `colortex1`, `2`: `colortex2`, `3`: `colortex3`, `4`: `colortex6`, `5`: `colortex8`, `6`: `colortex9`, `7`: `depthtex0`, `8`: `depthtex1`, `9`: `noisetex`, `10`: `shadowtex0` |
| `composite4.frag` | `0`: `colortex0` |
| `composite5.frag` | `0`: `colortex0`, `1`: `colortex3`, `2`: `colortex4` |
| `final.frag` | `0`: `colortex0` |
| `gbuffers_beaconbeam.frag` | `0`: `gtexture` |
| `gbuffers_block.frag` | `0`: `gtexture`, `1`: `lightmap` |
| `gbuffers_entities.frag` | `0`: `gtexture`, `1`: `lightmap`, `2`: `shadowtex0`, `3`: `specular` |
| `gbuffers_hand.frag` | `0`: `gtexture`, `1`: `lightmap` |
| `gbuffers_terrain.frag` | `0`: `gtexture`, `1`: `lightmap`, `2`: `shadowtex0`, `3`: `specular`, `4`: `normals` |
| `gbuffers_textured.frag` | `0`: `gtexture`, `1`: `lightmap` |
| `gbuffers_water.frag` | `0`: `gtexture` |
| `gbuffers_weather.frag` | `0`: `gtexture`, `1`: `lightmap` |
| `shadow.frag` | `0`: `gtexture` |
