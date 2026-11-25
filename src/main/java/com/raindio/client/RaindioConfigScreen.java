package com.raindio.client;

import com.raindio.config.RaindioConfig;
import com.raindio.pipeline.RaindioFrameController;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Categorized tabbed in-game configuration screen for Raindio Shaders (opened with 'J' key).
 * Split into 4 compact tabs (General / Lighting / Clouds / Color) so all buttons fit cleanly
 * at every resolution and GUI scale.
 */
public final class RaindioConfigScreen extends Screen {
    private static final int TAB_GENERAL = 0;
    private static final int TAB_LIGHTING = 1;
    private static final int TAB_CLOUDS = 2;
    private static final int TAB_COLOR = 3;
    private static final String[] TAB_NAMES = { "General", "Lighting", "Clouds", "Color" };

    private static final int[] SHADOW_RES_OPTIONS = { 1024, 2048, 4096 };
    private static final float[] RENDER_SCALE_OPTIONS = { 0.50f, 0.60f, 0.70f, 0.75f, 0.80f, 0.90f, 1.00f };
    private static final int[] POINT_LIGHT_TAP_OPTIONS = { 1, 2, 4, 8 };
    private static final int[] GI_BOUNCE_OPTIONS = { 2, 4, 6, 8 };
    private static final int[] CLOUD_STEP_OPTIONS = { 24, 40, 64 };

    private final Screen parent;
    private final RaindioConfig draft;
    private int draftDebugView;
    private int activeTab = TAB_GENERAL;

    public RaindioConfigScreen(Screen parent) {
        super(Component.literal("Raindio Shaders Settings (Vulkan 26.3)"));
        this.parent = parent;
        this.draft = RaindioFrameController.INSTANCE.config().copy();
        this.draftDebugView = RaindioFrameController.INSTANCE.getDebugViewMode();
    }

    @Override
    protected void init() {
        clearWidgets();

        int colW = 156;
        int gap = 8;
        int totalW = colW * 2 + gap; // 320px
        int leftX = this.width / 2 - totalW / 2;
        int rightX = leftX + colW + gap;
        int rowStep = 24;

        // Compact vertical centering (only 4-5 rows max -> never overflows even at GUI Scale 4!)
        int tabY = Math.max(30, this.height / 2 - 86);

        // Top Category Tab Bar: [General] [Lighting] [Clouds] [Color]
        int tabGap = 4;
        int tabW = (totalW - tabGap * (TAB_NAMES.length - 1)) / TAB_NAMES.length;
        for (int i = 0; i < TAB_NAMES.length; i++) {
            final int tabIdx = i;
            boolean selected = (i == activeTab);
            String label = selected ? ("[ " + TAB_NAMES[i] + " ]") : TAB_NAMES[i];
            Button tabBtn = Button.builder(
                    Component.literal(label),
                    btn -> {
                        activeTab = tabIdx;
                        init();
                    }
            ).bounds(leftX + i * (tabW + tabGap), tabY, tabW, 20).build();
            tabBtn.active = !selected;
            addRenderableWidget(tabBtn);
        }

        int y = tabY + 28;

        switch (activeTab) {
            case TAB_GENERAL -> buildGeneralTab(leftX, rightX, y, colW, rowStep);
            case TAB_LIGHTING -> buildLightingTab(leftX, rightX, y, colW, rowStep);
            case TAB_CLOUDS -> buildCloudsTab(leftX, rightX, y, colW, rowStep);
            case TAB_COLOR -> buildColorTab(leftX, rightX, y, colW, rowStep);
            default -> buildGeneralTab(leftX, rightX, y, colW, rowStep);
        }

        // Fixed Bottom Row: Reset | Cancel | Apply & Save (always reachable)
        int bottomY = tabY + 28 + rowStep * 4 + 8;
        int btnW = 100;
        int startX = this.width / 2 - (btnW * 3 + 16) / 2;

        addRenderableWidget(Button.builder(
                Component.literal("Reset"),
                btn -> {
                    draft.resetDefaults();
                    draftDebugView = 0;
                    init();
                }
        ).bounds(startX, bottomY, btnW, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Cancel"),
                btn -> onClose()
        ).bounds(startX + btnW + 8, bottomY, btnW, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Apply & Save"),
                btn -> {
                    RaindioFrameController.INSTANCE.applyConfig(draft, draftDebugView);
                    onClose();
                }
        ).bounds(startX + (btnW + 8) * 2, bottomY, btnW, 20).build());
    }

    private void buildGeneralTab(int leftX, int rightX, int y, int colW, int rowStep) {
        // Row 1: Shaders ON/OFF | Quality Preset
        addRenderableWidget(Button.builder(
                Component.literal("Shaders: " + (draft.shadersEnabled ? "ON" : "OFF")),
                btn -> {
                    draft.shadersEnabled = !draft.shadersEnabled;
                    btn.setMessage(Component.literal("Shaders: " + (draft.shadersEnabled ? "ON" : "OFF")));
                }
        ).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Preset: " + draft.preset.displayName),
                btn -> {
                    draft.applyPreset(draft.preset.next());
                    init();
                }
        ).bounds(rightX, y, colW, 20).build());

        // Row 2: Render Scale | Upscaling
        y += rowStep;
        addRenderableWidget(Button.builder(
                Component.literal("Render Scale: " + Math.round(draft.renderScale * 100.0f) + "%"),
                btn -> {
                    draft.renderScale = nextRenderScale(draft.renderScale);
                    btn.setMessage(Component.literal("Render Scale: " + Math.round(draft.renderScale * 100.0f) + "%"));
                }
        ).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Upscaling: " + upscaleLabel(draft)),
                btn -> {
                    draft.upscaleMode = (draft.upscaleMode + 1) % 3;
                    btn.setMessage(Component.literal("Upscaling: " + upscaleLabel(draft)));
                }
        ).bounds(rightX, y, colW, 20).build());

        // Row 3: Anti-Aliasing | RCAS Sharpening
        y += rowStep;
        addRenderableWidget(Button.builder(
                Component.literal("AA: " + aaLabel(draft)),
                btn -> {
                    if (draft.taaEnabled) {
                        draft.taaEnabled = false;
                        draft.fxaaEnabled = true;
                    } else if (draft.fxaaEnabled) {
                        draft.taaEnabled = false;
                        draft.fxaaEnabled = false;
                    } else {
                        draft.taaEnabled = true;
                        draft.fxaaEnabled = true;
                    }
                    init();
                }
        ).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(new FloatSlider(
                rightX, y, colW, 20, 0.00f, 1.00f, draft.fsrSharpness,
                v -> String.format(Locale.ROOT, "Sharpening: %.2f", Math.round(v * 20.0f) / 20.0f),
                v -> draft.fsrSharpness = Math.round(v * 20.0f) / 20.0f
        ));

        // Row 4: Shadow Resolution | AO/SSR Resolution
        y += rowStep;
        addRenderableWidget(Button.builder(
                Component.literal("Shadow Map: " + draft.shadowResolution + "x" + draft.shadowResolution),
                btn -> {
                    draft.shadowResolution = nextShadowRes(draft.shadowResolution);
                    btn.setMessage(Component.literal("Shadow Map: " + draft.shadowResolution + "x" + draft.shadowResolution));
                }
        ).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("AO/SSR Res: " + (draft.halfResColortex9 ? "Half (1/2)" : "Full (1/1)")),
                btn -> {
                    draft.halfResColortex9 = !draft.halfResColortex9;
                    btn.setMessage(Component.literal("AO/SSR Res: " + (draft.halfResColortex9 ? "Half (1/2)" : "Full (1/1)")));
                }
        ).bounds(rightX, y, colW, 20).build());
    }

    private void buildLightingTab(int leftX, int rightX, int y, int colW, int rowStep) {
        // Row 1: Point Light Quality | GI Bounces
        addRenderableWidget(Button.builder(
                Component.literal("Point Lights: " + pointLightLabel(draft.pointLightDdaTaps)),
                btn -> {
                    draft.pointLightDdaTaps = nextPointLightTaps(draft.pointLightDdaTaps);
                    draft.pointLightBilateral = (draft.pointLightDdaTaps <= 2);
                    btn.setMessage(Component.literal("Point Lights: " + pointLightLabel(draft.pointLightDdaTaps)));
                }
        ).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("GI Bounces: " + draft.lpvStepsPerFrame + "x"),
                btn -> {
                    draft.lpvStepsPerFrame = nextGiBounces(draft.lpvStepsPerFrame);
                    btn.setMessage(Component.literal("GI Bounces: " + draft.lpvStepsPerFrame + "x"));
                }
        ).bounds(rightX, y, colW, 20).build());

        // Row 2: Point Light Intensity | Point Light Color Mode
        y += rowStep;
        addRenderableWidget(new FloatSlider(
                leftX, y, colW, 20, 0.25f, 2.50f, draft.pointLightIntensity,
                v -> String.format(Locale.ROOT, "Light Power: %.2fx", v),
                v -> draft.pointLightIntensity = v
        ));

        addRenderableWidget(Button.builder(
                Component.literal("Light Color: " + draft.pointLightColorLabel()),
                btn -> {
                    draft.pointLightColorMode = (draft.pointLightColorMode + 1) % RaindioConfig.POINT_LIGHT_COLOR_MODES;
                    btn.setMessage(Component.literal("Light Color: " + draft.pointLightColorLabel()));
                }
        ).bounds(rightX, y, colW, 20).build());

        // Row 3: Bloom Strength | Top-K Point Lights ON/OFF
        y += rowStep;
        addRenderableWidget(new FloatSlider(
                leftX, y, colW, 20, 0.00f, 0.06f, draft.bloomStrength,
                v -> String.format(Locale.ROOT, "Bloom: %.3f", v),
                v -> draft.bloomStrength = v
        ));

        addRenderableWidget(Button.builder(
                Component.literal("Top-K Lights: " + (draft.pointLightTopK ? "ON" : "OFF")),
                btn -> {
                    draft.pointLightTopK = !draft.pointLightTopK;
                    btn.setMessage(Component.literal("Top-K Lights: " + (draft.pointLightTopK ? "ON" : "OFF")));
                }
        ).bounds(rightX, y, colW, 20).build());
    }


    private void buildCloudsTab(int leftX, int rightX, int y, int colW, int rowStep) {
        // Row 1: Volumetric Clouds ON/OFF | Cloud Raymarch Quality
        addRenderableWidget(Button.builder(
                Component.literal("Vol Clouds: " + (draft.volumetricCloudsEnabled ? "ON" : "OFF")),
                btn -> {
                    draft.volumetricCloudsEnabled = !draft.volumetricCloudsEnabled;
                    btn.setMessage(Component.literal("Vol Clouds: " + (draft.volumetricCloudsEnabled ? "ON" : "OFF")));
                }
        ).bounds(leftX, y, colW, 20).build());

        addRenderableWidget(Button.builder(
                Component.literal("Cloud Quality: " + cloudQualityLabel(draft.volumetricCloudSteps)),
                btn -> {
                    draft.volumetricCloudSteps = nextCloudSteps(draft.volumetricCloudSteps);
                    btn.setMessage(Component.literal("Cloud Quality: " + cloudQualityLabel(draft.volumetricCloudSteps)));
                }
        ).bounds(rightX, y, colW, 20).build());

        // Row 2: Cloud Coverage | Cloud Density
        y += rowStep;
        addRenderableWidget(new FloatSlider(
                leftX, y, colW, 20, 0.10f, 0.90f, draft.volumetricCloudCoverage,
                v -> String.format(Locale.ROOT, "Cloud Cover: %.2f", v),
                v -> draft.volumetricCloudCoverage = v
        ));

        addRenderableWidget(new FloatSlider(
                rightX, y, colW, 20, 0.30f, 2.00f, draft.volumetricCloudDensity,
                v -> String.format(Locale.ROOT, "Cloud Density: %.2f", v),
                v -> draft.volumetricCloudDensity = v
        ));
    }

    private void buildColorTab(int leftX, int rightX, int y, int colW, int rowStep) {
        // Row 1: Exposure | Saturation
        addRenderableWidget(new FloatSlider(
                leftX, y, colW, 20, 0.60f, 2.00f, draft.exposure,
                v -> String.format(Locale.ROOT, "Exposure: %.2f", v),
                v -> draft.exposure = v
        ));

        addRenderableWidget(new FloatSlider(
                rightX, y, colW, 20, 0.60f, 1.60f, draft.saturation,
                v -> String.format(Locale.ROOT, "Saturation: %.2f", v),
                v -> draft.saturation = v
        ));

        // Row 2: Water Clarity
        y += rowStep;
        addRenderableWidget(new FloatSlider(
                leftX, y, colW, 20, 0.50f, 1.50f, draft.waterClarity,
                v -> String.format(Locale.ROOT, "Water Clarity: %.2f", v),
                v -> draft.waterClarity = v
        ));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor gui, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(gui, mouseX, mouseY, partialTick);
        int tabY = Math.max(30, this.height / 2 - 86);
        gui.centeredText(this.font, this.title, this.width / 2, Math.max(6, tabY - 24), 0xFFFFFFFF);
        String subtitle = String.format(
                Locale.ROOT,
                "Preset: %s | Scale %d%% | GI %dx | Clouds: %s",
                draft.preset.displayName,
                Math.round(draft.renderScale * 100.0f),
                draft.lpvStepsPerFrame,
                draft.volumetricCloudsEnabled ? cloudQualityLabel(draft.volumetricCloudSteps) : "OFF"
        );
        gui.centeredText(this.font, Component.literal(subtitle), this.width / 2, Math.max(16, tabY - 13), 0xFFB0C8D8);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && this.minecraft.gui != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String aaLabel(RaindioConfig c) {
        if (c.taaEnabled) return "HDR-TAA";
        return c.fxaaEnabled ? "FXAA" : "OFF";
    }

    private static String upscaleLabel(RaindioConfig c) {
        return switch (c.effectiveUpscaleMode()) {
            case RaindioConfig.UPSCALE_TAAU -> "TAAU";
            case RaindioConfig.UPSCALE_FSR1 -> (c.upscaleMode == RaindioConfig.UPSCALE_TAAU ? "FSR 1.0 (Auto)" : "FSR 1.0");
            default -> "OFF";
        };
    }

    private static String pointLightLabel(int taps) {
        return switch (taps) {
            case 1 -> "Low (1-tap)";
            case 2 -> "Balanced (2-tap)";
            case 4 -> "High (4-tap)";
            default -> "Ultra (8-tap)";
        };
    }

    private static String cloudQualityLabel(int steps) {
        if (steps <= 24) return "Low (24)";
        if (steps <= 40) return "High (40)";
        return "Ultra (64)";
    }

    private static int nextCloudSteps(int current) {
        for (int i = 0; i < CLOUD_STEP_OPTIONS.length; i++) {
            if (current < CLOUD_STEP_OPTIONS[i]) {
                return CLOUD_STEP_OPTIONS[i];
            }
        }
        return CLOUD_STEP_OPTIONS[0];
    }

    private static float nextRenderScale(float current) {
        for (int i = 0; i < RENDER_SCALE_OPTIONS.length; i++) {
            if (current < RENDER_SCALE_OPTIONS[i] - 0.01f) {
                return RENDER_SCALE_OPTIONS[i];
            }
        }
        return RENDER_SCALE_OPTIONS[0];
    }

    private static int nextPointLightTaps(int current) {
        for (int i = 0; i < POINT_LIGHT_TAP_OPTIONS.length; i++) {
            if (POINT_LIGHT_TAP_OPTIONS[i] == current) {
                return POINT_LIGHT_TAP_OPTIONS[(i + 1) % POINT_LIGHT_TAP_OPTIONS.length];
            }
        }
        return 2;
    }

    private static int nextGiBounces(int current) {
        for (int i = 0; i < GI_BOUNCE_OPTIONS.length; i++) {
            if (current < GI_BOUNCE_OPTIONS[i]) {
                return GI_BOUNCE_OPTIONS[i];
            }
        }
        return GI_BOUNCE_OPTIONS[0];
    }

    private static int nextShadowRes(int current) {
        for (int i = 0; i < SHADOW_RES_OPTIONS.length; i++) {
            if (SHADOW_RES_OPTIONS[i] == current) {
                return SHADOW_RES_OPTIONS[(i + 1) % SHADOW_RES_OPTIONS.length];
            }
        }
        return 2048;
    }

    private static final class FloatSlider extends AbstractSliderButton {
        private final float min;
        private final float max;
        private final Function<Float, String> formatter;
        private final Consumer<Float> setter;

        FloatSlider(
                int x, int y, int width, int height,
                float min, float max, float initial,
                Function<Float, String> formatter,
                Consumer<Float> setter
        ) {
            super(x, y, width, height, Component.empty(), normalize(initial, min, max));
            this.min = min;
            this.max = max;
            this.formatter = formatter;
            this.setter = setter;
            updateMessage();
        }

        private static double normalize(float val, float min, float max) {
            return Math.max(0.0, Math.min(1.0, (val - min) / Math.max(max - min, 1e-5f)));
        }

        private float denormalize() {
            return (float) (min + this.value * (max - min));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(formatter.apply(denormalize())));
        }

        @Override
        protected void applyValue() {
            setter.accept(denormalize());
        }
    }
}
