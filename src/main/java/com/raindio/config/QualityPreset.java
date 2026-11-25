package com.raindio.config;

/**
 * Built-in Raindio quality presets tuned for Apple Silicon (MoltenVK) and desktop Vulkan GPUs.
 * Preserves all low/mid-tier optimization paths while unlocking full-fidelity effects on CINEMATIC.
 */
public enum QualityPreset {
    LOW(
            "Low",
            0.60f, // renderScale
            1024,  // shadowResolution
            32.0f, // entityShadowDistance
            4, 8,  // pcssBlockerTaps, pcssPcfTaps
            true,  // halfResColortex9 (GTAO+SSR 1/2 res + exact bilateral upsample)
            4, 3, false, // gtaoDirs, gtaoSteps, gtaoTemporalJitter
            24, 4, 8,    // ssrMaxSteps, ssrBinarySteps, shadowReflSteps
            4,     // bloomDivisor (1/4 res)
            1,     // pointLightDdaTaps (1-tap)
            true,  // pointLightBilateral (3x3 edge-preserving bilateral)
            8, false,    // vlSteps, vlTemporalJitter
            2,     // lpvStepsPerFrame
            0.018f,// bloomStrength
            1,     // upscaleMode (TAAU; auto-fallback to FSR 1.0 if TAA disabled)
            0.30f  // fsrSharpness (RCAS)
    ),
    BALANCED(
            "Balanced",
            0.75f,
            2048,
            48.0f,
            4, 8,
            true,
            4, 3, false,
            36, 4, 16,
            4,
            2,     // 2-tap antithetic
            true,  // 3x3 edge-preserving bilateral
            12, false,
            4,
            0.024f,
            1,     // TAAU
            0.25f  // RCAS sharpness
    ),
    HIGH(
            "High",
            0.90f,
            2048,
            64.0f,
            8, 16,
            false, // Full-res GTAO+SSR
            6, 4, false,
            48, 5, 24,
            2,     // 1/2 res Bloom
            4,     // 4-tap Golden-Angle Vogel Disk
            false, // direct texelFetch
            18, false,
            6,
            0.024f,
            1,     // TAAU
            0.20f  // RCAS sharpness
    ),
    CINEMATIC(
            "Cinematic",
            1.00f, // Native 100% resolution
            4096,
            90.0f,
            16, 32,
            false, // Full-res GTAO+SSR
            8, 5, true,  // 8x5 GTAO + temporal golden-ratio jitter
            64, 6, 32,   // 64 SSR steps + 6 binary refinement + 32 shadow-refl steps
            2,     // 1/2 res Bloom
            8,     // 8-tap Golden-Angle Vogel Disk
            false, // direct texelFetch
            28, true,    // 28 VL steps + temporal golden-ratio jitter
            8,     // 8 LPV propagation steps/frame
            0.026f,
            1,     // TAAU (1.0x native HDR-TAA + gentle RCAS)
            0.15f  // RCAS sharpness
    );

    public final String displayName;
    public final float renderScale;
    public final int shadowResolution;
    public final float entityShadowDistance;
    public final int pcssBlockerTaps;
    public final int pcssPcfTaps;
    public final boolean halfResColortex9;
    public final int gtaoDirs;
    public final int gtaoSteps;
    public final boolean gtaoTemporalJitter;
    public final int ssrMaxSteps;
    public final int ssrBinarySteps;
    public final int shadowReflSteps;
    public final int bloomDivisor;
    public final int pointLightDdaTaps;
    public final boolean pointLightBilateral;
    public final int vlSteps;
    public final boolean vlTemporalJitter;
    public final int lpvStepsPerFrame;
    public final float bloomStrength;
    public final int upscaleMode;
    public final float fsrSharpness;

    QualityPreset(
            String displayName,
            float renderScale,
            int shadowResolution,
            float entityShadowDistance,
            int pcssBlockerTaps,
            int pcssPcfTaps,
            boolean halfResColortex9,
            int gtaoDirs,
            int gtaoSteps,
            boolean gtaoTemporalJitter,
            int ssrMaxSteps,
            int ssrBinarySteps,
            int shadowReflSteps,
            int bloomDivisor,
            int pointLightDdaTaps,
            boolean pointLightBilateral,
            int vlSteps,
            boolean vlTemporalJitter,
            int lpvStepsPerFrame,
            float bloomStrength,
            int upscaleMode,
            float fsrSharpness
    ) {
        this.displayName = displayName;
        this.renderScale = renderScale;
        this.shadowResolution = shadowResolution;
        this.entityShadowDistance = entityShadowDistance;
        this.pcssBlockerTaps = pcssBlockerTaps;
        this.pcssPcfTaps = pcssPcfTaps;
        this.halfResColortex9 = halfResColortex9;
        this.gtaoDirs = gtaoDirs;
        this.gtaoSteps = gtaoSteps;
        this.gtaoTemporalJitter = gtaoTemporalJitter;
        this.ssrMaxSteps = ssrMaxSteps;
        this.ssrBinarySteps = ssrBinarySteps;
        this.shadowReflSteps = shadowReflSteps;
        this.bloomDivisor = bloomDivisor;
        this.pointLightDdaTaps = pointLightDdaTaps;
        this.pointLightBilateral = pointLightBilateral;
        this.vlSteps = vlSteps;
        this.vlTemporalJitter = vlTemporalJitter;
        this.lpvStepsPerFrame = lpvStepsPerFrame;
        this.bloomStrength = bloomStrength;
        this.upscaleMode = upscaleMode;
        this.fsrSharpness = fsrSharpness;
    }

    public QualityPreset next() {
        QualityPreset[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }
}
