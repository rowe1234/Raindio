# Raindio 阶段 1 依赖清单与移植基线

> 扫描目录：`/Applications/.minecraft/versions/26.2-Fabric/shaderpacks/Raindio/shaders/`（共 55 个文件：38 个 `.vsh/.fsh`、12 个 `lib/*.glsl`、`shaders.properties`、`block.properties`、2 个 `.lang`）。
> 说明：当前磁盘上的 Raindio 目录为 **SSGI + PTGI 水面 + 新海诚风大气** 版本（含 `lib/ssgi.glsl`、`DEBUG_VIEW 0–15`、`clear.colortex8=false`），不包含此前独立分支的 `lib/rsm.glsl` 与 `shadowcolor1`。

---

## 1. 完整 Pass 执行顺序与读写依赖

### 1.1 Shadow 阶段
| 顺序 | Pass | 源版本 | 输出附件 | 采样输入 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 1 | `shadow` | `330 compatibility` | `shadowtex0` (D32F), `shadowcolor0` (RGBA8) | `gtexture` | `4096x4096`，`shadowDistance=120.0`，`sunPathRotation=-30.0`，`shadowHardwareFiltering=false`；`shadowcolor0` 写入 `(albedo.rgb, shadowNdotL)` 供水面阴影反射回退使用 |

### 1.2 G-Buffer 阶段（场景几何）
| 顺序 | Pass | 源版本 | DRAWBUFFERS / 输出 | 采样输入 | 混合与说明 |
| --- | --- | --- | --- | --- | --- |
| 2 | `gbuffers_skybasic` / `skytextured` / `clouds` | `330 compatibility` | 空 Pass（4L） | 无 | 原版天空与云已被关闭（`clouds=off`），完全由 `composite` + `composite3` 程序化渲染 |
| 3 | `gbuffers_terrain` | `330 compatibility` | `0, 1` (`colortex0`, `colortex1`) | `gtexture`, `lightmap`, `normals`, `specular`, `shadowtex0` | 主地形与方块光照；`colortex0` 存直照颜色 + alpha，`colortex1` 存 `(octNormal.rg, blockFlag, skyLight)`；依赖 `mc_Entity`（`block.properties` 13 类 ID） |
| 4 | `gbuffers_block` | `330 compatibility` | `0, 1` | `gtexture`, `lightmap` | 方块实体 |
| 5 | `gbuffers_entities` | `410 core` | `0, 1` | `gtexture`, `lightmap`, `specular`, `shadowtex0` | 实体渲染，额外使用 `entityColor` 与 `normalMatrix` |
| 6 | `gbuffers_textured` | `330 compatibility` | `0, 1` | `gtexture`, `lightmap` | 粒子与带纹理图元 |
| 7 | `gbuffers_basic` | `330 compatibility` | `0, 1` | 无 | 线框/选框等基础图元 |
| 8 | `gbuffers_beaconbeam` | `330 compatibility` | `0, 1` | `gtexture` | 信标光柱 |
| 9 | `gbuffers_hand` | `330 compatibility` | `0, 1` | `gtexture`, `lightmap` | 第一人称手持物 |
| — | **深度快照拷贝** | — | `depthtex0` -> `depthtex1` | — | 在 `gbuffers_water` 之前拷贝不透明深度供折射与水下雾使用 |
| 10 | `gbuffers_water` | `330 compatibility` | `1, 2` (`colortex1`, `colortex2`) | `gtexture` | `blend.gbuffers_water=ONE ZERO ONE ZERO`（覆写不混合）；`colortex1` 写水面法线/标记/天空光，`colortex2` 写生物群系水色或半透明材质反照率 |
| 11 | `gbuffers_weather` | `410 core` | `0` (`colortex0`) | `gtexture`, `lightmap` | 雨雪天气 |

### 1.3 Composite 与后处理阶段（全屏 Pass）
| 顺序 | Pass | 源版本 | DRAWBUFFERS / 输出 | 采样输入 | 职责 |
| --- | --- | --- | --- | --- | --- |
| 12 | `composite` | `410 core` | `6` (`colortex6`, **128x64**) | 无 | 预计算天空大气散射 LUT |
| 13 | `composite1` | `410 core` | `3, 8` (`colortex3`, `colortex8`) | `colortex0`, `colortex1`, `colortex8` (上帧历史), `depthtex0`, `noisetex` | 水面法线/视差数据写入 `colortex3`；时域累积 SSGI 写入持久化 `colortex8` (`accumSSGI.rgb, linearDepth.a`) |
| 14 | `composite2` | `410 core` | `9` (`colortex9`) | `colortex0`, `colortex1`, `colortex3`, `colortex6`, `depthtex0`, `depthtex1`, `noisetex`, `shadowtex0`, `shadowcolor0` | SSR 屏幕空间反射 + 阴影贴图离线反射回退 + GTAO/接触阴影 |
| 15 | `composite3` | `410 core` | `0` (`colortex0`) | `colortex0`, `colortex1`, `colortex2`, `colortex3`, `colortex6`, `colortex8`, `colortex9`, `depthtex0`, `depthtex1`, `noisetex`, `shadowtex0` | 主光照合成：天空/2D云、水面折射/色散焦散/反射合成、体积光、雾效、SSGI 合成、`DEBUG_VIEW` 调试分支 |
| 16 | `composite4` | `410 core` | `3, 4` (`colortex3`, `colortex4`) | `colortex0` | 双路 Bloom 提取与模糊（Tight -> `colortex3`，Wide -> `colortex4`，复用 `colortex3`） |
| 17 | `composite5` | `410 core` | `0` (`colortex0`) | `colortex0`, `colortex3`, `colortex4` | Bloom 叠加、自动曝光适配、对比度曲线与 HDR 合成 |
| 18 | `final` | `410 core` | Swapchain Backbuffer | `colortex0` | 色调映射、色彩分级（`SATURATION`、`LUMA_GAMMA`、`WHITE_CLIP`）、FXAA |

---

## 2. 渲染目标与附件格式清单（Vulkan 映射）

| 逻辑附件 | 分辨率 | Iris 格式 | Vulkan 格式 | 每帧清空 | 备注 |
| --- | --- | --- | --- | --- | --- |
| `colortex0` | 视口全分辨率 | `RGBA16F` | `VK_FORMAT_R16G16B16A16_SFLOAT` | 是 | 主场景颜色 / HDR 合成 / 曝光输出 |
| `colortex1` | 视口全分辨率 | `RGBA16F` | `VK_FORMAT_R16G16B16A16_SFLOAT` | 是 | `oct(worldNormal).rg`、`blockFlag.b`、`skyLight.a` |
| `colortex2` | 视口全分辨率 | `RGBA16F` | `VK_FORMAT_R16G16B16A16_SFLOAT` | 是 | 仅 `gbuffers_water` 写入（水色/半透明反照率） |
| `colortex3` | 视口全分辨率 | `RGBA16F` | `VK_FORMAT_R16G16B16A16_SFLOAT` | 是 | `composite1` 存水数据，`composite4` 复用为 Bloom Tight |
| `colortex4` | 视口全分辨率 | `RGBA16F` | `VK_FORMAT_R16G16B16A16_SFLOAT` | 是 | `composite4` 存 Bloom Wide |
| `colortex6` | **固定 128×64** | `RGBA16F` | `VK_FORMAT_R16G16B16A16_SFLOAT` | 是 | 天空大气 LUT（`size.buffer.colortex6=128 64`） |
| `colortex8` | 视口全分辨率 | `RGBA16F` | `VK_FORMAT_R16G16B16A16_SFLOAT` | **否** | **跨帧持久化**（`clear.colortex8=false`），Vulkan 下需双缓冲乒乓读取防写读冒险 |
| `colortex9` | 视口全分辨率 | `RGBA16F` | `VK_FORMAT_R16G16B16A16_SFLOAT` | 是 | `composite2` 存 SSR 反射与地形遮蔽 |
| `depthtex0` | 视口全分辨率 | 深度 | `VK_FORMAT_D32_SFLOAT` | 是 | 完整场景深度（含水面） |
| `depthtex1` | 视口全分辨率 | 深度快照 | `VK_FORMAT_D32_SFLOAT` | 是 | 不透明几何深度（`gbuffers_water` 前拷贝） |
| `shadowtex0` | **固定 4096×4096** | 深度 | `VK_FORMAT_D32_SFLOAT` | 是 | 太阳阴影深度贴图 |
| `shadowcolor0` | **固定 4096×4096** | `RGBA8` | `VK_FORMAT_R8G8B8A8_UNORM` | 是 | 阴影空间反照率 + `shadowNdotL` |
| `colortex5`, `colortex7` | — | 声明但未使用 | 暂不分配 | — | 仅在 `composite.fsh` 有声明，当前无读写 |

---

## 3. Uniform 依赖清单（共 22 个数值/矩阵 + 17 个纹理采样器）

### 3.1 建议打包的 `FrameUniforms` UBO（`std140`, `layout(set = 0, binding = 0)`）
| 分组 | Uniform 名称 | 类型 | 出现文件数 | 来源说明 |
| --- | --- | --- | --- | --- |
| 相机矩阵 | `gbufferModelView` | `mat4` | 7 | 当前帧相机 View 矩阵 |
| 相机矩阵 | `gbufferModelViewInverse` | `mat4` | 18 | View 逆矩阵 |
| 相机矩阵 | `gbufferProjection` | `mat4` | 1 | 投影矩阵（Vulkan 阶段需注意 NDC Z `[0,1]` 与 Y 轴约定） |
| 相机矩阵 | `gbufferProjectionInverse` | `mat4` | 3 | 投影逆矩阵 |
| 历史帧矩阵 | `gbufferPreviousModelView` | `mat4` | 1 | 上帧 View 矩阵（SSGI 重投影） |
| 历史帧矩阵 | `gbufferPreviousProjection` | `mat4` | 1 | 上帧 Projection 矩阵 |
| 阴影矩阵 | `shadowModelView` | `mat4` | 4 | 太阳正交阴影 View 矩阵（含 `sunPathRotation=-30°`） |
| 阴影矩阵 | `shadowProjection` | `mat4` | 4 | 太阳正交阴影 Projection 矩阵（覆盖 `shadowDistance=120`） |
| 逐物体矩阵 | `modelViewMatrix` / `projectionMatrix` / `normalMatrix` | `mat4`/`mat4`/`mat3` | 9 / 9 / 1 | G-Buffer / Shadow 顶点阶段变换（可放 Push Constant 或逐 Draw UBO） |
| 相机与世界 | `cameraPosition` | `vec3` | 4 | 当前帧相机世界坐标 |
| 相机与世界 | `previousCameraPosition` | `vec3` | 1 | 上帧相机世界坐标 |
| 相机与世界 | `sunPosition` | `vec3` | 15 | 视图空间太阳方向向量 |
| 相机与世界 | `far` | `float` | 2 | 远裁剪面距离 |
| 视口与时间 | `viewWidth`, `viewHeight` | `float` | 5 / 5 | 渲染目标像素宽高 |
| 视口与时间 | `frameTimeCounter` | `float` | 7 | 累计运行秒数（波浪/云动效） |
| 环境状态 | `rainStrength` | `float` | 11 | 降雨强度 `[0, 1]` |
| 环境状态 | `isEyeInWater` | `int` | 3 | 相机流体状态（0=空气，1=水，2=岩浆） |
| 环境状态 | `eyeBrightnessSmooth` | `ivec2` | 2 | 平滑方块光/天空光亮度 `[0, 240]`（室内自动曝光） |
| 实体状态 | `entityColor` | `vec4` | 1 | 实体受伤/闪烁叠加色（仅 `gbuffers_entities`） |

### 3.2 纹理采样器清单（17 个）
- **G-Buffer / 场景输入**：`gtexture`、`lightmap`、`normals`、`specular`、`noisetex`
- **颜色附件**：`colortex0`、`colortex1`、`colortex2`、`colortex3`、`colortex4`、`colortex6`、`colortex8`、`colortex9`
- **深度与阴影附件**：`depthtex0`、`depthtex1`、`shadowtex0`、`shadowcolor0`

---

## 4. 阶段 2 着色器迁移重点（GLSL 330/410 -> GLSL 450 Vulkan）
- **兼容性语法替换**：11 个 `.vsh` 使用了 `ftransform()`，部分 G-Buffer 使用 `gl_Vertex`/`gl_Color`/`gl_Normal`/`gl_MultiTexCoord0/1`/`gl_ModelViewMatrix`/`gl_TextureMatrix`/`gl_NormalMatrix`，需全部替换为显式 `layout(location = N) in` 顶点属性与 UBO 矩阵。
- **方块材质 ID 映射**：`mc_Entity`（3 个文件使用）对应 `block.properties` 中 13 类方块 ID（静水 1、流水 10、气泡柱 13、水生植物 11、透明/染色玻璃 2/3、冰/史莱姆/蜂蜜/传送门 7/8/9/12、植物 4、金属宝石 5、细方块 6、岩浆 14、高/中自发光 10000/10001），阶段 3 需在原版区块网格构建中注入自定义顶点属性或按 RenderType 分流。
- **Vulkan 坐标系与深度范围**：修正 `lib/projection.glsl`、`lib/ssr.glsl`、`lib/ssgi.glsl`、`lib/water.glsl`、`composite1/2/3` 中默认 OpenGL NDC Z `[-1, 1]` 到 Vulkan `[0, 1]`，并统一 Y 轴方向。
- **MoltenVK 兼容性**：编译目标固定为 `--target-env=vulkan1.2 --target-spv=spv1.3`，禁用 Geometry Shader 与非标准扩展，`colortex8` 跨帧读写采用双缓冲（Ping-Pong）避免 Vulkan 未定义行为。

---

## 5. 原版 Minecraft 26.3 Vulkan 挂钩点排查步骤（需先运行 `genSources`）

> 遵守「先读源码再写代码」原则：当前本地环境尚未检测到 JDK 25，因此未臆测任何 `com.mojang.blaze3d` 内部类签名。

安装 **JDK 25** 后，在 `raindio-mod/` 目录执行：
```bash
./gradlew genSources
```
随后重点检索反编译源码中的以下真实类名与调用链，以确定阶段 3 的 Mixin 挂钩点：
1. `com.mojang.blaze3d.systems.RenderSystem` 与 `GpuDevice` 的 Vulkan 实现类（确认原生 `VkDevice` / `VkCommandBuffer` / `RenderPass` 封装位置）。
2. `net.minecraft.client.renderer.LevelRenderer`（确认地形、实体、水面半透明层、天空与后处理的渲染入口顺序）。
3. `com.mojang.blaze3d.pipeline.RenderTarget` / `TextureTarget`（确认如何创建与切换多附件 MRT `colortex0..9` 及拷贝 `depthtex1`）。
