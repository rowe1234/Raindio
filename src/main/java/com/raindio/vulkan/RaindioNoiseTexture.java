package com.raindio.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Generates a deterministic 256x256 tileable RGBA8 noise texture (noisetex)
 * sampled by composite1 (SSGI), composite2 (SSR), and composite3 (water/volumetrics).
 */
public final class RaindioNoiseTexture implements AutoCloseable {
    public static final int SIZE = 256;

    private GpuTexture texture;
    private GpuTextureView view;

    public void ensureInitialized(CommandEncoder encoder) {
        if (texture != null && !texture.isClosed()) {
            return;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) return;

        int usage = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST;
        this.texture = device.createTexture("raindio_noisetex", usage, GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
        this.view = device.createTextureView(this.texture);

        ByteBuffer pixels = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder());
        long state = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < SIZE * SIZE; i++) {
            // SplitMix64 deterministic hash for reproducible 4-channel noise
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            z = z ^ (z >>> 31);
            pixels.put((byte) (z & 0xFF));
            pixels.put((byte) ((z >>> 8) & 0xFF));
            pixels.put((byte) ((z >>> 16) & 0xFF));
            pixels.put((byte) ((z >>> 24) & 0xFF));
        }
        pixels.flip();
        // FrontendCommandEncoder.writeToTexture(tex, buf, mipLevel, depthOrLayer, x, y, width, height)
        encoder.writeToTexture(this.texture, pixels, 0, 0, 0, 0, SIZE, SIZE);
    }

    public GpuTextureView view() {
        return view;
    }

    @Override
    public void close() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
    }
}
