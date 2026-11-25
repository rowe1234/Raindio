package com.raindio.pipeline;

import java.util.List;

/**
 * Hardcoded pipeline specification audited directly from legacy Raindio shaders.
 * No generic shaders.properties parser; used by Phase 2-4 Vulkan resource & renderpass builders.
 */
public final class RaindioPipelineSpec {
    private RaindioPipelineSpec() {}

    public static final int SHADOW_MAP_RESOLUTION = 4096;
    public static final float SHADOW_DISTANCE = 120.0f;
    public static final float SUN_PATH_ROTATION_DEG = -30.0f;
    public static final boolean SHADOW_HARDWARE_FILTERING = false;

    public static final int ATMOSPHERE_LUT_WIDTH = 128;
    public static final int ATMOSPHERE_LUT_HEIGHT = 64;

    /**
     * Color attachments actually read/written by the audited Raindio shaderpack.
     * Note: colortex5 and colortex7 are declared RGBA16F in composite.fsh but unused in this snapshot.
     */
    public enum Attachment {
        COLORTEX0("colortex0", "VK_FORMAT_R16G16B16A16_SFLOAT", false, true),
        COLORTEX1("colortex1", "VK_FORMAT_R16G16B16A16_SFLOAT", false, true),
        COLORTEX2("colortex2", "VK_FORMAT_R16G16B16A16_SFLOAT", false, true),
        COLORTEX3("colortex3", "VK_FORMAT_R16G16B16A16_SFLOAT", false, true),
        COLORTEX4("colortex4", "VK_FORMAT_R16G16B16A16_SFLOAT", false, true),
        COLORTEX6("colortex6", "VK_FORMAT_R16G16B16A16_SFLOAT", true, true),   // Fixed 128x64 Atmosphere LUT
        COLORTEX8("colortex8", "VK_FORMAT_R16G16B16A16_SFLOAT", false, false), // clear=false (SSGI temporal history)
        COLORTEX9("colortex9", "VK_FORMAT_R16G16B16A16_SFLOAT", false, true),
        SHADOW_DEPTH("shadowtex0", "VK_FORMAT_D32_SFLOAT", true, true),        // Fixed 4096x4096
        SHADOW_COLOR0("shadowcolor0", "VK_FORMAT_R8G8B8A8_UNORM", true, true), // Fixed 4096x4096
        SCENE_DEPTH("depthtex0", "VK_FORMAT_D32_SFLOAT", false, true),         // Includes translucent (water)
        OPAQUE_DEPTH("depthtex1", "VK_FORMAT_D32_SFLOAT", false, true);        // Pre-water opaque depth snapshot

        public final String legacyName;
        public final String vkFormat;
        public final boolean fixedSize;
        public final boolean clearEachFrame;

        Attachment(String legacyName, String vkFormat, boolean fixedSize, boolean clearEachFrame) {
            this.legacyName = legacyName;
            this.vkFormat = vkFormat;
            this.fixedSize = fixedSize;
            this.clearEachFrame = clearEachFrame;
        }
    }

    /** Exact pass execution order audited from legacy Raindio shaders. */
    public static final List<String> ACTIVE_PASS_ORDER = List.of(
            "shadow",
            "gbuffers_terrain",
            "gbuffers_block",
            "gbuffers_entities",
            "gbuffers_textured",
            "gbuffers_basic",
            "gbuffers_beaconbeam",
            "gbuffers_hand",
            "gbuffers_water",
            "gbuffers_weather",
            "composite",
            "composite1",
            "composite2",
            "composite3",
            "composite4",
            "composite5",
            "final"
    );
}
