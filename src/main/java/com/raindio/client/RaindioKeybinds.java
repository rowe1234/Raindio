package com.raindio.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.raindio.RaindioClientMod;
import com.raindio.pipeline.RaindioFrameController;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Registers SDL-compatible client keybinds for Raindio (Minecraft 26.3):
 * - K: Toggle Raindio post-processing pipeline on/off
 * - F8: Cycle DEBUG_VIEW (0..15)
 */
public final class RaindioKeybinds {
    private static final String[] DEBUG_VIEW_NAMES = {
            "0: Off (Final Render)",
            "1: Material ID",
            "2: World Normal",
            "3: Sky Light",
            "4: Water Normal",
            "5: Water Roughness",
            "6: Water Thickness",
            "7: SSR / Reflections",
            "8: GTAO * Contact Shadows",
            "9: Voxel LPV GI",
            "10: Raw HDR",
            "11: Water Specular",
            "12: Water Sky Reflection",
            "13: Water Refraction",
            "14: Bloom",
            "15: SSR(R) / RSM(G) / Sky(B)",
            "16: Point Lights (Clustered PBR)"
    };

    private static KeyMapping togglePipelineKey;
    private static KeyMapping cycleDebugViewKey;
    private static KeyMapping openSettingsKey;
    private static boolean announcedInWorld = false;

    private RaindioKeybinds() {}

    public static void register() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(RaindioClientMod.MOD_ID, "shaders")
        );

        togglePipelineKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.raindio.toggle_pipeline",
                InputConstants.KEY_K,
                category
        ));

        cycleDebugViewKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.raindio.cycle_debug_view",
                InputConstants.KEY_F8,
                category
        ));

        openSettingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.raindio.settings_menu",
                InputConstants.KEY_J,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(RaindioKeybinds::onEndClientTick);
    }

    private static void onEndClientTick(Minecraft mc) {
        if (mc.player == null) {
            announcedInWorld = false;
            return;
        }

        if (!announcedInWorld) {
            announcedInWorld = true;
            String fault = RaindioFrameController.INSTANCE.getLastFaultSummary();
            if (fault != null) {
                sendChatAndOverlay(mc, "[Raindio 1.2.4] 管线异常: " + fault);
            } else {
                int scalePct = Math.round(RaindioFrameController.INSTANCE.config().renderScale * 100.0f);
                sendChatAndOverlay(mc, "[Raindio 1.2.4] 已就绪 (" + RaindioFrameController.INSTANCE.config().preset.displayName + " / Scale " + scalePct + "% / Voxel LPV GI | K开关 / J设置 / F8调试)");
            }
        }

        while (togglePipelineKey.consumeClick()) {
            boolean next = !RaindioFrameController.INSTANCE.isPipelineEnabled();
            RaindioFrameController.INSTANCE.setPipelineEnabled(next);
            sendChatAndOverlay(mc, "[Raindio] Shaders " + (next ? "ON" : "OFF"));
        }

        while (cycleDebugViewKey.consumeClick()) {
            int nextMode = (RaindioFrameController.INSTANCE.getDebugViewMode() + 1) % DEBUG_VIEW_NAMES.length;
            RaindioFrameController.INSTANCE.setDebugViewMode(nextMode);
            sendChatAndOverlay(mc, "[Raindio] DEBUG_VIEW -> " + DEBUG_VIEW_NAMES[nextMode]);
        }

        while (openSettingsKey.consumeClick()) {
            if (mc.gui != null) {
                mc.gui.setScreen(new RaindioConfigScreen(null));
            }
        }
    }

    public static void sendChatAndOverlay(Minecraft mc, String message) {
        RaindioClientMod.LOGGER.info(message);
        if (mc.player != null) {
            Component text = Component.literal(message);
            mc.player.sendSystemMessage(text);
            mc.player.sendOverlayMessage(text);
        }
    }
}
