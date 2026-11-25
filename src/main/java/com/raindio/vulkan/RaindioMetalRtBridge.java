package com.raindio.vulkan;

import com.raindio.RaindioClientMod;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * JNI bridge to Raindio's native Apple Silicon Metal 3.1 Zero-Stall Async Hardware Ray-Tracing engine.
 * Automatically detects Apple M3 / M4 / M5+ dedicated hardware RT cores via MTLGPUFamilyApple9 + supportsRaytracing.
 * Safely returns unavailable on M1/M2, Intel Macs, Windows, and Linux so Raindio falls back to Vulkan Voxel RT.
 */
public final class RaindioMetalRtBridge {
    private static final String RESOURCE_PATH = "/natives/macos-arm64/libraindio_metal_rt.dylib";
    public static final int CHANNEL_REFLECTIONS = 0;
    public static final int CHANNEL_GI_BOUNCE   = 1;

    private static volatile boolean probed = false;
    private static volatile boolean libraryLoaded = false;
    private static volatile boolean hwRtAvailable = false;
    private static volatile String deviceName = "None";

    private RaindioMetalRtBridge() {}

    public static synchronized boolean initIfSupported() {
        if (probed) return hwRtAvailable;
        probed = true;

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("mac") || (!arch.contains("aarch64") && !arch.contains("arm64"))) {
            deviceName = "Non-Apple-Silicon (" + os + "/" + arch + ")";
            return false;
        }

        try (InputStream in = RaindioMetalRtBridge.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                RaindioClientMod.LOGGER.info("[Raindio Metal RT] Native dylib not bundled ({}); using Vulkan Voxel RT.", RESOURCE_PATH);
                return false;
            }
            Path tmp = Files.createTempFile("libraindio_metal_rt_", ".dylib");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            System.load(tmp.toAbsolutePath().toString());
            libraryLoaded = true;

            boolean ok = nativeInit();
            deviceName = nativeGetDeviceName();
            hwRtAvailable = ok && nativeHasApple9HwRt();

            if (hwRtAvailable) {
                RaindioClientMod.LOGGER.info(
                        "[Raindio Metal RT] Detected Apple Silicon Hardware RT Cores (Apple9+) on '{}' — Full Async Metal Hardware RT Takeover active!",
                        deviceName
                );
            } else {
                RaindioClientMod.LOGGER.info(
                        "[Raindio Metal RT] GPU '{}' does not expose Apple9+ Hardware RT Cores — using Vulkan Voxel RT fallback.",
                        deviceName
                );
            }
            return hwRtAvailable;
        } catch (Throwable t) {
            RaindioClientMod.LOGGER.warn("[Raindio Metal RT] Failed to load native Metal RT bridge; falling back to Vulkan Voxel RT.", t);
            hwRtAvailable = false;
            return false;
        }
    }

    public static boolean isHardwareRtAvailable() { return hwRtAvailable; }
    public static String deviceName() { return deviceName; }

    public static boolean queueSectionMesh(
            long sectionKey, float originX, float originY, float originZ,
            ByteBuffer vertsDirectBuf, int vertexCount,
            ByteBuffer primMetaDirectBuf, int primCount
    ) {
        if (!hwRtAvailable) return false;
        return nativeQueueSectionMesh(sectionKey, originX, originY, originZ, vertsDirectBuf, vertexCount, primMetaDirectBuf, primCount);
    }

    public static void removeSection(long sectionKey) {
        if (hwRtAvailable) nativeRemoveSection(sectionKey);
    }

    public static void clearSections() {
        if (hwRtAvailable) nativeClearSections();
    }

    public static int commitBatchAndRebuildTlasAsync() {
        if (!hwRtAvailable) return 0;
        return nativeCommitBatchAndRebuildTlasAsync();
    }

    public static boolean traceAsync(int channel, int rayCount, ByteBuffer rayInDirectBuf, ByteBuffer hitOutDirectBuf) {
        if (!hwRtAvailable) return false;
        return nativeTraceAsync(channel, rayCount, rayInDirectBuf, hitOutDirectBuf);
    }

    public static void shutdown() {
        if (libraryLoaded) {
            try {
                nativeShutdown();
            } catch (Throwable ignored) {
            }
        }
        hwRtAvailable = false;
    }

    private static native boolean nativeInit();
    private static native String nativeGetDeviceName();
    private static native boolean nativeHasApple9HwRt();
    private static native boolean nativeQueueSectionMesh(
            long sectionKey, float originX, float originY, float originZ,
            ByteBuffer vertsDirectBuf, int vertexCount,
            ByteBuffer primMetaDirectBuf, int primCount
    );
    private static native void nativeRemoveSection(long sectionKey);
    private static native void nativeClearSections();
    private static native int nativeCommitBatchAndRebuildTlasAsync();
    private static native boolean nativeTraceAsync(int channel, int rayCount, ByteBuffer rayInDirectBuf, ByteBuffer hitOutDirectBuf);
    private static native void nativeShutdown();
}
