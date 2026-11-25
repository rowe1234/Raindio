package com.raindio;

import com.raindio.client.RaindioKeybinds;
import com.raindio.pipeline.RaindioFrameController;
import com.raindio.pipeline.RaindioPipelineSpec;
import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RaindioClientMod implements ClientModInitializer {
    public static final String MOD_ID = "raindio";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        RaindioFrameController.INSTANCE.onClientInitialize();
        RaindioKeybinds.register();
        LOGGER.info(
                "[Raindio] Client initialized (v1.0.0 Vulkan native | Preset: {}, Shadow: {}x{}, Passes: {}).",
                RaindioFrameController.INSTANCE.config().preset.displayName,
                RaindioFrameController.INSTANCE.config().shadowResolution,
                RaindioFrameController.INSTANCE.config().shadowResolution,
                RaindioPipelineSpec.ACTIVE_PASS_ORDER.size()
        );
    }
}
