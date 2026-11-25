package com.raindio.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.raindio.RaindioClientMod;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Persistent JSON configuration for Raindio Mod, stored at config/raindio.json.
 */
public final class RaindioConfig {
    public static final int UPSCALE_OFF = 0;
    public static final int UPSCALE_TAAU = 1;
    public static final int UPSCALE_FSR1 = 2;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "raindio.json";

    public boolean shadersEnabled = true;
    public QualityPreset preset = QualityPreset.BALANCED;
    public float renderScale = 0.75f;
    public int upscaleMode = UPSCALE_TAAU;
    public float fsrSharpness = 0.25f;
    public int shadowResolution = 2048;
    public float entityShadowDistance = 48.0f;
    public int pcssBlockerTaps = 4;
    public int pcssPcfTaps = 8;
    public boolean halfResColortex9 = true;
    public int gtaoDirs = 4;
    public int gtaoSteps = 3;
    public boolean gtaoTemporalJitter = false;
    public int ssrMaxSteps = 36;
    public int ssrBinarySteps = 4;
    public int shadowReflSteps = 16;
    public int bloomDivisor = 4;
    public int pointLightDdaTaps = 2;
    public boolean pointLightBilateral = true;
    public boolean pointLightTopK = true;
    public int vlSteps = 12;
    public boolean vlTemporalJitter = false;
    public int lpvStepsPerFrame = 4;
    public float pointLightIntensity = 1.00f;
    public int pointLightColorMode = 0; // 0=Default, 1=Warm, 2=Neutral, 3=Cool, 4=Rose, 5=Emerald

    public boolean volumetricCloudsEnabled = true;
    public int volumetricCloudSteps = 40;       // 24 (Low), 40 (High), 64 (Ultra)
    public float volumetricCloudCoverage = 0.52f; // 0.10 .. 0.90
    public float volumetricCloudDensity = 1.00f;  // 0.30 .. 2.00

    public boolean taaEnabled = true;
    public boolean fxaaEnabled = true;
    public float exposure = 0.95f;
    public float saturation = 1.16f;
    public float waterClarity = 1.00f;
    public float bloomStrength = 0.016f;

    public static final int POINT_LIGHT_COLOR_MODES = 6;

    public float[] pointLightTintRgb() {
        return switch (pointLightColorMode) {
            case 1 -> new float[] { 1.12f, 0.86f, 0.58f }; // Warm
            case 2 -> new float[] { 1.00f, 1.06f, 1.16f }; // Neutral
            case 3 -> new float[] { 0.68f, 0.94f, 1.24f }; // Cool
            case 4 -> new float[] { 1.16f, 0.72f, 0.88f }; // Rose
            case 5 -> new float[] { 0.64f, 1.16f, 0.80f }; // Emerald
            default -> new float[] { 1.00f, 1.00f, 1.00f }; // Default
        };
    }

    public String pointLightColorLabel() {
        return switch (pointLightColorMode) {
            case 1 -> "Warm";
            case 2 -> "Neutral";
            case 3 -> "Cool";
            case 4 -> "Rose";
            case 5 -> "Emerald";
            default -> "Default";
        };
    }

    /** Returns active upscale mode: TAAU automatically falls back to FSR 1.0 when TAA is disabled. */
    public int effectiveUpscaleMode() {
        if (upscaleMode == UPSCALE_TAAU && !taaEnabled) {
            return UPSCALE_FSR1;
        }
        return upscaleMode;
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static RaindioConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                RaindioConfig loaded = GSON.fromJson(json, RaindioConfig.class);
                if (loaded != null) {
                    loaded.sanitize();
                    return loaded;
                }
            } catch (Exception e) {
                RaindioClientMod.LOGGER.warn("[Raindio] Failed to read {}, falling back to defaults.", path, e);
            }
        }
        RaindioConfig defaults = new RaindioConfig();
        defaults.applyPreset(QualityPreset.BALANCED);
        defaults.save();
        return defaults;
    }

    public void save() {
        sanitize();
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            RaindioClientMod.LOGGER.error("[Raindio] Failed to write config file {}", path, e);
        }
    }

    public void applyPreset(QualityPreset target) {
        QualityPreset p = target != null ? target : QualityPreset.BALANCED;
        this.preset = p;
        this.renderScale = p.renderScale;
        this.upscaleMode = p.upscaleMode;
        this.fsrSharpness = p.fsrSharpness;
        this.shadowResolution = p.shadowResolution;
        this.entityShadowDistance = p.entityShadowDistance;
        this.pcssBlockerTaps = p.pcssBlockerTaps;
        this.pcssPcfTaps = p.pcssPcfTaps;
        this.halfResColortex9 = p.halfResColortex9;
        this.gtaoDirs = p.gtaoDirs;
        this.gtaoSteps = p.gtaoSteps;
        this.gtaoTemporalJitter = p.gtaoTemporalJitter;
        this.ssrMaxSteps = p.ssrMaxSteps;
        this.ssrBinarySteps = p.ssrBinarySteps;
        this.shadowReflSteps = p.shadowReflSteps;
        this.bloomDivisor = p.bloomDivisor;
        this.pointLightDdaTaps = p.pointLightDdaTaps;
        this.pointLightBilateral = p.pointLightBilateral;
        this.vlSteps = p.vlSteps;
        this.vlTemporalJitter = p.vlTemporalJitter;
        this.lpvStepsPerFrame = p.lpvStepsPerFrame;
        this.bloomStrength = p.bloomStrength;
    }

    public void resetDefaults() {
        this.shadersEnabled = true;
        this.pointLightTopK = true;
        this.pointLightIntensity = 1.00f;
        this.pointLightColorMode = 0;
        this.volumetricCloudsEnabled = true;
        this.volumetricCloudSteps = 40;
        this.volumetricCloudCoverage = 0.52f;
        this.volumetricCloudDensity = 1.00f;
        this.taaEnabled = true;
        this.fxaaEnabled = true;
        this.exposure = 0.95f;
        this.saturation = 1.16f;
        this.waterClarity = 1.00f;
        applyPreset(QualityPreset.BALANCED);
    }

    public void sanitize() {
        if (preset == null) preset = QualityPreset.BALANCED;
        renderScale = clamp(renderScale, 0.50f, 1.00f);
        upscaleMode = clampInt(upscaleMode, UPSCALE_OFF, UPSCALE_FSR1);
        fsrSharpness = clamp(fsrSharpness, 0.00f, 1.00f);
        if (shadowResolution != 1024 && shadowResolution != 2048 && shadowResolution != 4096) {
            shadowResolution = preset.shadowResolution;
        }
        entityShadowDistance = clamp(entityShadowDistance, 16.0f, 120.0f);
        pcssBlockerTaps = clampInt(pcssBlockerTaps, 4, 16);
        pcssPcfTaps = clampInt(pcssPcfTaps, 8, 32);
        gtaoDirs = clampInt(gtaoDirs, 4, 8);
        gtaoSteps = clampInt(gtaoSteps, 3, 5);
        ssrMaxSteps = clampInt(ssrMaxSteps, 12, 64);
        ssrBinarySteps = clampInt(ssrBinarySteps, 3, 6);
        shadowReflSteps = clampInt(shadowReflSteps, 0, 32);
        if (bloomDivisor != 2 && bloomDivisor != 4) {
            bloomDivisor = preset.bloomDivisor;
        }
        if (pointLightDdaTaps != 1 && pointLightDdaTaps != 2 && pointLightDdaTaps != 4 && pointLightDdaTaps != 8) {
            pointLightDdaTaps = preset.pointLightDdaTaps;
        }
        vlSteps = clampInt(vlSteps, 6, 32);
        lpvStepsPerFrame = clampInt(lpvStepsPerFrame, 1, 8);
        pointLightIntensity = clamp(pointLightIntensity, 0.25f, 2.50f);
        pointLightColorMode = clampInt(pointLightColorMode, 0, POINT_LIGHT_COLOR_MODES - 1);
        if (volumetricCloudSteps != 24 && volumetricCloudSteps != 40 && volumetricCloudSteps != 64) {
            volumetricCloudSteps = 40;
        }
        volumetricCloudCoverage = clamp(volumetricCloudCoverage, 0.10f, 0.90f);
        volumetricCloudDensity = clamp(volumetricCloudDensity, 0.30f, 2.00f);
        exposure = clamp(exposure, 0.50f, 2.20f);
        saturation = clamp(saturation, 0.40f, 1.80f);
        waterClarity = clamp(waterClarity, 0.50f, 1.50f);
        bloomStrength = clamp(bloomStrength, 0.00f, 0.08f);
    }

    public String shaderDefineSignature(int debugView) {
        return shadowResolution
                + "|" + pcssBlockerTaps + "x" + pcssPcfTaps
                + "|h9=" + (halfResColortex9 ? 1 : 0)
                + "|ao=" + gtaoDirs + "x" + gtaoSteps + "t" + (gtaoTemporalJitter ? 1 : 0)
                + "|ssr=" + ssrMaxSteps + "b" + ssrBinarySteps + "r" + shadowReflSteps
                + "|plb=" + (pointLightBilateral ? 1 : 0)
                + "|vl=" + vlSteps + "t" + (vlTemporalJitter ? 1 : 0)
                + "|vc=" + (volumetricCloudsEnabled ? 1 : 0) + "s" + volumetricCloudSteps
                + "c" + Math.round(volumetricCloudCoverage * 100)
                + "d" + Math.round(volumetricCloudDensity * 100)
                + "|aa=" + (taaEnabled ? 1 : 0) + (fxaaEnabled ? 1 : 0)
                + "|up=" + effectiveUpscaleMode()
                + "|sh=" + Math.round(fsrSharpness * 1000)
                + "|" + Math.round(exposure * 1000)
                + "|" + Math.round(saturation * 1000)
                + "|" + Math.round(waterClarity * 1000)
                + "|" + Math.round(bloomStrength * 10000)
                + "|" + debugView;
    }

    public RaindioConfig copy() {
        RaindioConfig c = new RaindioConfig();
        c.copyFrom(this);
        return c;
    }

    public void copyFrom(RaindioConfig other) {
        this.shadersEnabled = other.shadersEnabled;
        this.preset = other.preset;
        this.renderScale = other.renderScale;
        this.upscaleMode = other.upscaleMode;
        this.fsrSharpness = other.fsrSharpness;
        this.shadowResolution = other.shadowResolution;
        this.entityShadowDistance = other.entityShadowDistance;
        this.pcssBlockerTaps = other.pcssBlockerTaps;
        this.pcssPcfTaps = other.pcssPcfTaps;
        this.halfResColortex9 = other.halfResColortex9;
        this.gtaoDirs = other.gtaoDirs;
        this.gtaoSteps = other.gtaoSteps;
        this.gtaoTemporalJitter = other.gtaoTemporalJitter;
        this.ssrMaxSteps = other.ssrMaxSteps;
        this.ssrBinarySteps = other.ssrBinarySteps;
        this.shadowReflSteps = other.shadowReflSteps;
        this.bloomDivisor = other.bloomDivisor;
        this.pointLightDdaTaps = other.pointLightDdaTaps;
        this.pointLightBilateral = other.pointLightBilateral;
        this.pointLightTopK = other.pointLightTopK;
        this.vlSteps = other.vlSteps;
        this.vlTemporalJitter = other.vlTemporalJitter;
        this.lpvStepsPerFrame = other.lpvStepsPerFrame;
        this.pointLightIntensity = other.pointLightIntensity;
        this.pointLightColorMode = other.pointLightColorMode;
        this.volumetricCloudsEnabled = other.volumetricCloudsEnabled;
        this.volumetricCloudSteps = other.volumetricCloudSteps;
        this.volumetricCloudCoverage = other.volumetricCloudCoverage;
        this.volumetricCloudDensity = other.volumetricCloudDensity;
        this.taaEnabled = other.taaEnabled;
        this.fxaaEnabled = other.fxaaEnabled;
        this.exposure = other.exposure;
        this.saturation = other.saturation;
        this.waterClarity = other.waterClarity;
        this.bloomStrength = other.bloomStrength;
        sanitize();
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
