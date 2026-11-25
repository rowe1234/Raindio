# Raindio Shaders (`raindio-mod`)

面向 **Minecraft 26.3（原生 Vulkan 后端）** 的独立 Fabric 光影模组，无需额外第三方光影加载器，专为 Apple Silicon（M1–M5+ / MoltenVK）与桌面 Vulkan GPU 打造。

## 核心特性

### 1. Apple Silicon（M3 / M4 / M5+）Metal 3.1 硬件光追引擎
- **硬件光追核心自动识别**：通过原生 Objective-C++ / Metal JNI 动态库（`libraindio_metal_rt.dylib`）自动检测 `MTLGPUFamilyApple9` 硬件光追核心（M3 / M4 / M5 及更新芯片），在 M1 / M2、Intel Mac、Windows 与 Linux 上自动零开销回退至原生 Vulkan 体素光追管线。
- **双队列全异步零阻塞架构**：使用独立的 `buildQueue`（单 `MTLCommandBuffer` 批量异步构建区块级 BLAS 与场景 TLAS，完成后原子切换）与 `traceQueue`（1 帧双缓冲非阻塞硬件光追），主渲染线程零 `waitUntilCompleted` 等待。
- **SLC 缓存驻留与陆地零开销快筛**：采用 `VMA_ALLOCATION_CREATE_MAPPED_BIT` 持久映射缓冲与 `≤ 480×270` 紧凑离屏反射网格（约 6 MB，可完整驻留 Apple Silicon 系统级缓存），内置微秒级活跃光线预检，在无水面/玻璃/雨天水洼的陆地或室内场景完全跳过拷贝与光追调度；同时自动关闭冗余的 `128×64×128` 远景软件体素扫描与上传。

### 2. 原生 Vulkan 三阶段混合光追反射（`rt_reflections.comp`）
- **第一阶段（全视距逐像素主追踪）**：视口预裁切、透视校正 Reverse-Z 屏幕空间光线步进（72 步自适应步进 + 6 步二分精修），使用精确整数采样消除轮廓渗白，并叠加一致的远景空气雾，实现全视距（320m+）1:1 方块贴图与远山森林倒影。
- **第二阶段（硬件光追优先 / 双层级联 3D 体素光追回退）**：当光线离开视口或被前景遮挡时，M3 / M4 / M5+ 直接交由 Metal 硬件 BVH/TLAS 完成 ±128 格三角面求交并跳过软件体素 DDA；其余平台自动回退至 **LOD 0（`64³ @ 1m` + 4×4×4 子体素 + 树叶冠层 + 16 色染色玻璃透射）** 与 **LOD 1（`128×64×128 @ 2m`，水平 ±128 格 / 垂直 ±64 格）** 级联 3D 体素 DDA。
- **实时方块贴图图集采样**：通过动态调色板 LUT（`1024×3 RGBA32F`）与 `R16_UINT` 3D 调色板体积，在屏幕外 3D 光追命中面按精确局部 UV 直接采样 Minecraft 当前加载的方块贴图图集（`TextureAtlas`）与群系染色，自动兼容原版及任意高清材质包。
- **第三阶段（天空与体积云倒影）**：未命中场景几何时，结合物理大气天空 LUT 与体积云追踪同步渲染天空与云层倒影。

### 3. 原生 Vulkan ReSTIR DI + 混合光追 ReSTIR GI
- **512 动态点光源时空复用**：双通道 Compute 管线（`restir_di_gi_temporal.comp` / `restir_di_gi_spatial_resolve.comp`），支持 16×16 Tile 剔除、3D Voxel DDA + 4×4×4 子体素遮挡、动态实体阴影盒、白平衡 16 色染色玻璃透射混色、水下点光源向上透射水面、紧邻齐平发光方块包裹照明以及手持动态光源。
- **零延迟纯 GPU 混合光追 GI**：同帧完成屏幕空间 1 跳反弹（2 步二分细化 + 背面剔除）与 L2 缓存驻留的 16 步 3D 体素 DDA 离屏反弹及天空可见性追踪，并通过几何半球校验（`uCurGiPos` / `uInGiPos`）完成空间复用。

### 4. Hanning 加窗 64³ 环形网格 L1 SH-LPV 全局光照
- `lpv_propagate.comp` 实时传播多反弹彩色墙面漫反射、16 色染色玻璃球谐滤光与 RSM 太阳光地表反弹（`shadowtex0` + `shadowcolor0`）。
- 对一阶球谐方向分量施加 **Hanning 窗** 并配合求值端各向同性保底，彻底消除强发光方块（如萤石、海晶灯）紧邻非发光方块时出现的 L1 球谐负瓣振铃与黑边问题。

### 5. 阴影、水体、大气与后期管线
- **接触硬化 PCSS 软阴影**：支持 `1024 / 2048 / 4096` 动态阴影分辨率、Vogel Disk 阻塞搜索与 PCF，支持阳光穿透室内玻璃窗投射真实光影。
- **物理水体与透明材质**：波浪微表面法线、Snell 折射、Beer-Lambert 光谱吸收、色散与海底焦散、波峰次表面散射；水体与半透明玻璃启用背面剔除以消除双层伪影。
- **大气与光效**：128×64 物理大气散射 LUT、3D 体积云、空气与水下体积光束、雨天湿滑地面水洼反射、第一人称手部独立光照渲染与 GTAO。
- **超分、抗锯齿与色调映射**：原生 HDR 运动矢量 **TAA / TAAU**（Halton 抖动 + YCoCg 方差裁剪 + Catmull-Rom 历史采样）、边缘自适应空间超分（EASU）与对比度自适应锐化（RCAS）、FXAA 回退（支持 `0.50x–1.00x` 内部分辨率缩放同时保持 UI 清晰），配合 GPU 亮度直方图自动曝光（`histogram_build.comp` + `histogram_adapt.comp`）、双层 Bloom 与 **AgX Filmic** 色调映射。

## 运行环境要求

| 组件 | 要求 |
| :--- | :--- |
| Minecraft | `26.3`（需在视频设置中启用 **Vulkan** 图形后端） |
| Fabric Loader | `>= 0.19.5` |
| Fabric API | `0.166.2+26.3` |
| Java | `>= 25` |
| 硬件光追加速（可选） | Apple Silicon M3 / M4 / M5+（macOS，自动启用；其他设备自动回退至 Vulkan 体素光追） |
| 着色器编译工具（仅从源码构建需要） | `glslc`（Vulkan SDK 或 `brew install shaderc`） |

> 注意：本模组仅支持 **Vulkan** 后端，若游戏当前运行在 OpenGL 后端，模组会自动提示并安全禁用渲染管线。

## 快捷键与画质预设

- **`J`**：打开 Raindio 配置界面（支持 `Low` / `Balanced` / `High` / `Cinematic` 四档预设、渲染缩放、阴影分辨率、超分模式、体积云、点光源色温与强度等参数，自动持久化至 `config/raindio.json`）。
- **`K`**：一键开关光影管线（出错后也可按 `K` 重试）。
- **`F8`**：循环切换内置调试视图（`DEBUG_VIEW 0..16`，包含法线、水体、光追反射、GTAO、Compute GI、点光源、自动曝光 HUD 等）。

## 从源码构建

```bash
# （可选，仅修改原生 Metal 代码时在 macOS 上执行）重新编译 Apple Silicon Metal 3.1 硬件光追动态库
bash src/main/native/macos/build_metal_rt.sh

# 编译 GLSL 450 -> Vulkan 1.2 / SPIR-V 1.3 并打包模组 JAR
./run-gradle.sh build
```

构建完成后，模组 JAR 会输出至 `build/libs/raindio-mod-1.2.4+mc26.3.jar`（已自动内嵌 `libraindio_metal_rt.dylib` 与全部 `.spv` 着色器）。

## 目录结构

```text
src/main/
├── java/com/raindio/
│   ├── client/      # F9 配置界面与快捷键
│   ├── config/      # 画质预设与 JSON 配置持久化
│   ├── mixin/       # 渲染管线与游戏渲染器钩子
│   ├── pipeline/    # 帧生命周期调度
│   └── vulkan/      # Vulkan 附件、UBO、LPV/体素调色板、ReSTIR DI/GI、混合光追反射、Metal 硬件光追桥接与自动曝光
├── native/macos/    # Apple Silicon M3/M4/M5+ Metal 3.1 硬件光追原生 JNI 源码与构建脚本
├── resources/
│   └── natives/     # 预编译 macOS arm64 Metal 硬件光追动态库 (libraindio_metal_rt.dylib)
└── shaders/
    ├── chunks/      # 阴影、地形、水体、透明层、实体与第一人称手部 G-Buffer
    ├── common/      # 全局 Uniform 与全屏顶点着色器
    ├── composite/   # 大气 LUT、水体、GTAO、主合成、Bloom、TAAU、EASU/RCAS、AgX
    ├── compute/     # 混合光追反射、ReSTIR DI/GI、LPV 传播与亮度直方图自动曝光计算着色器
    └── lib/         # 公共数学、投影、光照、PBR、水体、体积云、体积光与天空库
```
