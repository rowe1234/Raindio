# Raindio Shaders (`raindio-mod`)

面向 **Minecraft 26.3（原生 RenderPearl Vulkan 后端）** 的独立 Fabric 光影模组，无需 Iris、Sodium 或 VulkanMod 依赖，专为 Apple Silicon（MoltenVK）与桌面 Vulkan GPU 打造。

## 核心特性

- **原生 Vulkan ReSTIR DI + 混合光追 ReSTIR GI**
  - 双通道 Compute 管线（`restir_di_gi_temporal.comp` / `restir_di_gi_spatial_resolve.comp`），支持最多 **512 个动态点光源**与 16×16 Tile 剔除。
  - **3D Voxel DDA + 4×4×4 子体素遮挡 + 动态实体阴影盒**，支持 16 色染色玻璃透射混色与手持动态光源。
  - **混合光追 GI**：屏幕空间反弹（2 步二分细化 + 背面剔除）结合 16 步 3D 体素 DDA 离屏反弹与天空可见性追踪，并通过几何半球校验（`uCurGiPos` / `uInGiPos`）完成空间复用。
- **64³ 环形网格 L1 SH-LPV 全局光照**
  - `lpv_propagate.comp` 实时传播多反弹彩色墙面漫反射、16 色染色玻璃滤光及 RSM 太阳光地表反弹（`shadowtex0` + `shadowcolor0`）。
- **高质量阴影、水体与大气渲染**
  - 接触硬化 **PCSS 软阴影**（动态分辨率 `1024 / 2048 / 4096`，Vogel Disk 阻塞搜索与 PCF）。
  - **SEUS PTGI 风格水体**：Snell 折射、Beer-Lambert 光谱吸收、色散与海底焦散、波峰次表面散射、SSR + 阴影贴图 RSM 离屏反射回退。
  - 128×64 大气散射 LUT、体积云、空气与水下体积光束、雨天湿滑地面水洼反射与 GTAO。
- **超分、抗锯齿与后期调色**
  - 原生 HDR 运动矢量 **TAA / TAAU**（Halton 抖动 + YCoCg 方差裁剪 + Catmull-Rom 历史采样）、**FSR 1.0 EASU + RCAS** 与 FXAA 回退，支持 `0.50x–1.00x` 内部分辨率缩放同时保持 UI 清晰。
  - GPU 亮度直方图自动曝光（`histogram_build.comp` + `histogram_adapt.comp`）、双层 Bloom 与 **AgX Filmic** 色调映射。

## 运行环境要求

| 组件 | 要求 |
| :--- | :--- |
| Minecraft | `26.3`（需在视频设置中启用 **Vulkan** 图形后端） |
| Fabric Loader | `>= 0.19.5` |
| Fabric API | `0.166.2+26.3` |
| Java | `>= 25` |
| 着色器编译工具（仅从源码构建需要） | `glslc`（Vulkan SDK 或 `brew install shaderc`） |

> 注意：本模组仅支持 **Vulkan** 后端，若游戏当前运行在 OpenGL 后端，模组会自动提示并安全禁用渲染管线。

## 快捷键与画质预设

- **`F9`**：打开 Raindio 配置界面（支持 `Low` / `Balanced` / `High` / `Cinematic` 四档预设、渲染缩放、阴影分辨率、超分模式、体积云、点光源色温与强度等参数，自动持久化至 `config/raindio.json`）。
- **`K`**：一键开关光影管线（出错后也可按 `K` 重试）。
- **`F8`**：循环切换内置调试视图（`DEBUG_VIEW 0..16`，包含法线、水体、SSR/RSM、GTAO、Compute GI、点光源、自动曝光 HUD 等）。

## 从源码构建

```bash
# 确保已安装 glslc（支持自动检测 PATH、/usr/local/bin、/opt/homebrew/bin 或 $VULKAN_SDK/bin）
./run-gradle.sh build
```

构建完成后，GLSL 450 着色器会自动编译为 Vulkan 1.2 / SPIR-V 1.3（`.spv`）并打包至 `build/libs/raindio-mod-1.2.4+mc26.3.jar`。

## 目录结构

```text
src/main/
├── java/com/raindio/
│   ├── client/      # F9 配置界面与快捷键
│   ├── config/      # 画质预设与 JSON 配置持久化
│   ├── mixin/       # RenderPearl / LevelRenderer / GameRenderer 钩子
│   ├── pipeline/    # 帧生命周期调度
│   └── vulkan/      # Vulkan 附件、UBO、LPV、ReSTIR DI/GI、自动曝光与管线管理
└── shaders/
    ├── chunks/      # 阴影、地形、水体、透明层、实体与第一人称手部 G-Buffer
    ├── common/      # 全局 Uniform 与全屏顶点着色器
    ├── composite/   # 大气 LUT、水体、SSR/GTAO、主合成、Bloom、TAAU、FSR、AgX/RCAS
    ├── compute/     # ReSTIR DI/GI、LPV 传播与亮度直方图自动曝光计算着色器
    └── lib/         # 公共数学、投影、光照、PBR、水体、SSR、体积光与天空库
```
