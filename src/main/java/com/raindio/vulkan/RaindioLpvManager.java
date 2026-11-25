package com.raindio.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.raindio.RaindioClientMod;
import com.raindio.client.RaindioKeybinds;
import com.raindio.config.RaindioConfig;
import com.raindio.mixin.VulkanCommandEncoderAccessor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Phase 2 Native Vulkan 64x64x64 Toroidal Voxel Mask + L1 SH-LPV 3D Color Light Propagation Manager:
 * - Maintains a 64x64x64 toroidal voxel mask (R8_UINT: 0=air, 1=opaque blocker, 2..17=stained glass)
 *   and emissive injection volume (RGBA8_UNORM) centered on the player, updated incrementally by
 *   newly entered slices + a 5x5x5 instant local cube around the player.
 * - Ping-pongs 3x RGBA16F 3D textures (shR, shG, shB) through compute/lpv_propagate.comp (2 rounds/frame).
 * - Exposes the latest shR/shG/shB 3D views + linear-repeat 3D sampler for point_lights.comp.
 */
public final class RaindioLpvManager implements AutoCloseable {
    private static final String LPV_SPV = "/assets/raindio/shaders/spirv/compute/lpv_propagate.comp.spv";
    public static final int GRID_SIZE = 64;
    private static final int GRID_MASK = GRID_SIZE - 1;
    private static final int VOXEL_COUNT = GRID_SIZE * GRID_SIZE * GRID_SIZE; // 262,144
    private static final int MASK_BYTES = VOXEL_COUNT;                        // 256 KB (R8_UINT)
    private static final int EMISSIVE_BYTES = VOXEL_COUNT * 4;                // 1 MB (RGBA8_UNORM)
    private static final int SUBVOXEL_BYTES = VOXEL_COUNT * 8;                // 2 MB (R32G32_UINT, 4x4x4 = 64 bits/block)
    private static final int FAR_XZ = 128;
    private static final int FAR_Y = 64;
    private static final int FAR_XZ_MASK = FAR_XZ - 1;
    private static final int FAR_Y_MASK = FAR_Y - 1;
    private static final int FAR_VOXEL_COUNT = FAR_XZ * FAR_Y * FAR_XZ;
    private static final int FAR_VOXEL_BYTES = FAR_VOXEL_COUNT * 4;           // 4 MB (RGBA8_UNORM, 2m/voxel -> 256x128x256m RT reflections)
    private static final int PALETTE_MAX = 1024;
    private static final int PALETTE_LUT_FLOATS = PALETTE_MAX * 3 * 4;        // 1024x3 RGBA32F (48 KB: row0=topUV, row1=sideUV, row2=tintRGB+flags)
    private static final int PALETTE_LUT_BYTES = PALETTE_LUT_FLOATS * 4;
    private static final int VOXEL_PALETTE_BYTES = VOXEL_COUNT * 2;           // 512 KB (R16_UINT, LOD 0 block palette IDs)
    private static final int FAR_PALETTE_BYTES = FAR_VOXEL_COUNT * 2;         // 2 MB (R16_UINT, LOD 1 block palette IDs)
    private static final int RING_SIZE = 4;
    private static final int MAX_PROPAGATION_STEPS = 8;

    // CPU toroidal mirrors
    private final byte[] cpuMask = new byte[MASK_BYTES];
    private final byte[] cpuEmissive = new byte[EMISSIVE_BYTES];
    private final int[] cpuSubVoxel = new int[VOXEL_COUNT * 2];
    private final byte[] cpuFarVoxel = new byte[FAR_VOXEL_BYTES];
    private final short[] cpuVoxelPalette = new short[VOXEL_COUNT];
    private final short[] cpuFarPalette = new short[FAR_VOXEL_COUNT];
    private final float[] cpuPaletteLut = new float[PALETTE_LUT_FLOATS];
    private final Map<BlockState, Integer> paletteMap = new HashMap<>(256);
    private final List<BlockStateModelPart> palettePartsScratch = new ArrayList<>(4);
    private final RandomSource paletteRandom = RandomSource.create(42L);
    private int nextPaletteId = 1;
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
    private final int[] subBitsScratch = new int[2];
    private ClientLevel lastLevel = null;
    private int originX = Integer.MIN_VALUE;
    private int originY = Integer.MIN_VALUE;
    private int originZ = Integer.MIN_VALUE;
    private int farOriginX = Integer.MIN_VALUE;
    private int farOriginY = Integer.MIN_VALUE;
    private int farOriginZ = Integer.MIN_VALUE;
    private int farRefreshCursor = 0;
    private boolean maskDirty = false;
    private boolean emissiveDirty = false;
    private boolean subVoxelDirty = false;
    private boolean farVoxelDirty = false;
    private boolean voxelPaletteDirty = false;
    private boolean farPaletteDirty = false;
    private boolean paletteLutDirty = false;
    private boolean hasStaleWrapFlags = false;
    private boolean needsShClear = true;
    private int heldVoxelX = Integer.MIN_VALUE;
    private int heldVoxelY = Integer.MIN_VALUE;
    private int heldVoxelZ = Integer.MIN_VALUE;
    private float[] heldVoxelSpec = null;
    private float curPlIntensity = 1.0f;
    private int curPlColorMode = 0;
    private float[] curPlTint = new float[] { 1.0f, 1.0f, 1.0f };

    // Vulkan resources
    private VkDevice vkDevice;
    private long vmaAllocator;
    private long descLayout = VK12.VK_NULL_HANDLE;
    private long pipelineLayout = VK12.VK_NULL_HANDLE;
    private long computePipeline = VK12.VK_NULL_HANDLE;
    private long descriptorPool = VK12.VK_NULL_HANDLE;
    private final long[] descSets = new long[RING_SIZE * MAX_PROPAGATION_STEPS]; // up to 4 steps/frame * 4-frame ring
    private int ringIndex = 0;

    private long nearestRepeatSampler = VK12.VK_NULL_HANDLE;
    private long linearRepeatSampler = VK12.VK_NULL_HANDLE;

    // 3D Voxel Mask + Emissive + 4x4x4 Sub-Voxel + 2m Far Clipmap + Palette-Indexed Block Atlas LUT
    private long maskImage = VK12.VK_NULL_HANDLE, maskAlloc = 0L, maskView = VK12.VK_NULL_HANDLE;
    private long emImage = VK12.VK_NULL_HANDLE, emAlloc = 0L, emView = VK12.VK_NULL_HANDLE;
    private long subVoxelImage = VK12.VK_NULL_HANDLE, subVoxelAlloc = 0L, subVoxelView = VK12.VK_NULL_HANDLE;
    private long farVoxelImage = VK12.VK_NULL_HANDLE, farVoxelAlloc = 0L, farVoxelView = VK12.VK_NULL_HANDLE;
    private long paletteLutImage = VK12.VK_NULL_HANDLE, paletteLutAlloc = 0L, paletteLutView = VK12.VK_NULL_HANDLE;
    private long voxelPaletteImage = VK12.VK_NULL_HANDLE, voxelPaletteAlloc = 0L, voxelPaletteView = VK12.VK_NULL_HANDLE;
    private long farPaletteImage = VK12.VK_NULL_HANDLE, farPaletteAlloc = 0L, farPaletteView = VK12.VK_NULL_HANDLE;
    private final long[] maskStagingBufs = new long[RING_SIZE];
    private final long[] maskStagingAllocs = new long[RING_SIZE];
    private final long[] emStagingBufs = new long[RING_SIZE];
    private final long[] emStagingAllocs = new long[RING_SIZE];
    private final long[] subVoxelStagingBufs = new long[RING_SIZE];
    private final long[] subVoxelStagingAllocs = new long[RING_SIZE];
    private final long[] farVoxelStagingBufs = new long[RING_SIZE];
    private final long[] farVoxelStagingAllocs = new long[RING_SIZE];
    private final long[] paletteLutStagingBufs = new long[RING_SIZE];
    private final long[] paletteLutStagingAllocs = new long[RING_SIZE];
    private final long[] voxelPaletteStagingBufs = new long[RING_SIZE];
    private final long[] voxelPaletteStagingAllocs = new long[RING_SIZE];
    private final long[] farPaletteStagingBufs = new long[RING_SIZE];
    private final long[] farPaletteStagingAllocs = new long[RING_SIZE];

    // Ping-pong 3D L1 SH volumes: [0]=A, [1]=B
    private final long[] shRImg = new long[2], shRAlloc = new long[2], shRView = new long[2];
    private final long[] shGImg = new long[2], shGAlloc = new long[2], shGView = new long[2];
    private final long[] shBImg = new long[2], shBAlloc = new long[2], shBView = new long[2];
    private int activeShIndex = 0; // index holding the latest propagated SH result

    private boolean imagesTransitioned = false;
    private boolean initialized = false;
    private boolean unavailable = false;

    public boolean isReady() {
        return initialized && !unavailable && shRView[activeShIndex] != VK12.VK_NULL_HANDLE
                && subVoxelView != VK12.VK_NULL_HANDLE && farVoxelView != VK12.VK_NULL_HANDLE
                && paletteLutView != VK12.VK_NULL_HANDLE && voxelPaletteView != VK12.VK_NULL_HANDLE
                && farPaletteView != VK12.VK_NULL_HANDLE;
    }

    public long shRView() { return shRView[activeShIndex]; }
    public long shGView() { return shGView[activeShIndex]; }
    public long shBView() { return shBView[activeShIndex]; }
    public long voxelMaskView() { return maskView; }
    public long subVoxelView() { return subVoxelView; }
    public long farVoxelView() { return farVoxelView; }
    public long blockPaletteLutView() { return paletteLutView; }
    public long voxelPaletteView() { return voxelPaletteView; }
    public long farPaletteView() { return farPaletteView; }
    public long emissiveView() { return emView; }
    public long linearRepeatSampler() { return linearRepeatSampler; }
    public long nearestRepeatSampler() { return nearestRepeatSampler; }

    /**
     * Incrementally updates the 64x64x64 toroidal voxel mask & emissive volume around the player.
     * Only newly entered slices + a 7x7x7 instant local cube are scanned each frame.
     */
    public void updateVoxels(CameraRenderState cameraState) {
        updateVoxels(cameraState, null);
    }

    public void updateVoxels(CameraRenderState cameraState, RaindioConfig config) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || cameraState == null || cameraState.pos == null) return;

        float targetIntensity = (config != null) ? config.pointLightIntensity : 1.0f;
        int targetColorMode = (config != null) ? config.pointLightColorMode : 0;
        boolean lightSettingChanged = (Math.abs(targetIntensity - curPlIntensity) > 1e-3f) || (targetColorMode != curPlColorMode);
        this.curPlIntensity = targetIntensity;
        this.curPlColorMode = targetColorMode;
        this.curPlTint = (config != null) ? config.pointLightTintRgb() : new float[] { 1.0f, 1.0f, 1.0f };

        // Only clear previous frame's bit-7 stale-wrap flags if a wrap actually occurred last frame
        if (hasStaleWrapFlags) {
            for (int i = 0; i < MASK_BYTES; i++) {
                byte oldVal = cpuMask[i];
                byte cleared = (byte) (oldVal & 0x7F);
                if (oldVal != cleared) {
                    cpuMask[i] = cleared;
                    maskDirty = true;
                }
            }
            hasStaleWrapFlags = false;
        }

        // Resolve player's held light once so writeVoxel merges it inline (prevents false dirty flips)
        int px = (int) Math.floor(cameraState.pos.x);
        int py = (int) Math.floor(cameraState.pos.y);
        int pz = (int) Math.floor(cameraState.pos.z);
        this.heldVoxelX = px;
        this.heldVoxelY = py;
        this.heldVoxelZ = pz;
        LocalPlayer player = mc.player;
        float[] hSpec = (player != null) ? classifyHeldEmissive(player.getMainHandItem()) : null;
        if (hSpec == null && player != null) {
            hSpec = classifyHeldEmissive(player.getOffhandItem());
        }
        this.heldVoxelSpec = hSpec;

        int newOriginX = px - (GRID_SIZE / 2);
        int newOriginY = py - (GRID_SIZE / 2);
        int newOriginZ = pz - (GRID_SIZE / 2);

        boolean fullRebuild = (level != lastLevel)
                || lightSettingChanged
                || (originX == Integer.MIN_VALUE)
                || (Math.abs(newOriginX - originX) >= 16)
                || (Math.abs(newOriginY - originY) >= 16)
                || (Math.abs(newOriginZ - originZ) >= 16);

        if (fullRebuild) {
            if (level != lastLevel) {
                paletteMap.clear();
                Arrays.fill(cpuPaletteLut, 0.0f);
                nextPaletteId = 1;
                paletteLutDirty = true;
            }
            this.lastLevel = level;
            this.originX = newOriginX;
            this.originY = newOriginY;
            this.originZ = newOriginZ;
            scanBox(level, originX, originY, originZ, originX + GRID_SIZE, originY + GRID_SIZE, originZ + GRID_SIZE, true);
            this.hasStaleWrapFlags = true;
            this.maskDirty = true;
            this.emissiveDirty = true;
            this.subVoxelDirty = true;
            this.voxelPaletteDirty = true;
            this.needsShClear = true;
            updateFarVoxels(level, px, py, pz, true);
            return;
        }

        // Incremental toroidal slab updates when player crosses integer block boundaries
        int dx = newOriginX - originX;
        int dy = newOriginY - originY;
        int dz = newOriginZ - originZ;

        if (dx != 0) {
            int x0 = dx > 0 ? (originX + GRID_SIZE) : newOriginX;
            int x1 = dx > 0 ? (newOriginX + GRID_SIZE) : originX;
            scanBox(level, x0, newOriginY, newOriginZ, x1, newOriginY + GRID_SIZE, newOriginZ + GRID_SIZE, true);
            this.hasStaleWrapFlags = true;
        }
        if (dy != 0) {
            int y0 = dy > 0 ? (originY + GRID_SIZE) : newOriginY;
            int y1 = dy > 0 ? (newOriginY + GRID_SIZE) : originY;
            scanBox(level, newOriginX, y0, newOriginZ, newOriginX + GRID_SIZE, y1, newOriginZ + GRID_SIZE, true);
            this.hasStaleWrapFlags = true;
        }
        if (dz != 0) {
            int z0 = dz > 0 ? (originZ + GRID_SIZE) : newOriginZ;
            int z1 = dz > 0 ? (newOriginZ + GRID_SIZE) : originZ;
            scanBox(level, newOriginX, newOriginY, z0, newOriginX + GRID_SIZE, newOriginY + GRID_SIZE, z1, true);
            this.hasStaleWrapFlags = true;
        }

        this.originX = newOriginX;
        this.originY = newOriginY;
        this.originZ = newOriginZ;

        // Refresh a tiny 7x7x7 cube around the player every frame (343 blocks) so placing/breaking
        // walls, stained glass, torches, or sea lanterns reflects in LPV immediately —
        // only uploading 3D textures to VRAM if at least one byte actually changed!
        scanBox(level, px - 3, py - 3, pz - 3, px + 4, py + 4, pz + 4, false);
        updateFarVoxels(level, px, py, pz, false);
    }

    /**
     * Maintains LOD 1 (128x64x128 @ 2m/voxel = 256x128x256m / +-128m horizontal) toroidal clipmap.
     * 2x finer geometry than 4m cells; only scans newly entered 2m slabs + 3 rolling chunk columns/frame.
     */
    private void updateFarVoxels(ClientLevel level, int px, int py, int pz, boolean forceFull) {
        int newFarX = (px >> 1) - (FAR_XZ / 2);
        int newFarY = (py >> 1) - (FAR_Y / 2);
        int newFarZ = (pz >> 1) - (FAR_XZ / 2);
        boolean full = forceFull || (farOriginX == Integer.MIN_VALUE)
                || Math.abs(newFarX - farOriginX) >= 16
                || Math.abs(newFarY - farOriginY) >= 12
                || Math.abs(newFarZ - farOriginZ) >= 16;
        if (full) {
            this.farOriginX = newFarX;
            this.farOriginY = newFarY;
            this.farOriginZ = newFarZ;
            scanFarBox(level, farOriginX, farOriginY, farOriginZ, farOriginX + FAR_XZ, farOriginY + FAR_Y, farOriginZ + FAR_XZ);
            this.farVoxelDirty = true;
            return;
        }
        int dx = newFarX - farOriginX;
        int dy = newFarY - farOriginY;
        int dz = newFarZ - farOriginZ;
        if (dx != 0) {
            int x0 = dx > 0 ? (farOriginX + FAR_XZ) : newFarX;
            int x1 = dx > 0 ? (newFarX + FAR_XZ) : farOriginX;
            scanFarBox(level, x0, newFarY, newFarZ, x1, newFarY + FAR_Y, newFarZ + FAR_XZ);
        }
        if (dy != 0) {
            int y0 = dy > 0 ? (farOriginY + FAR_Y) : newFarY;
            int y1 = dy > 0 ? (newFarY + FAR_Y) : farOriginY;
            scanFarBox(level, newFarX, y0, newFarZ, newFarX + FAR_XZ, y1, newFarZ + FAR_XZ);
        }
        if (dz != 0) {
            int z0 = dz > 0 ? (farOriginZ + FAR_XZ) : newFarZ;
            int z1 = dz > 0 ? (newFarZ + FAR_XZ) : farOriginZ;
            scanFarBox(level, newFarX, newFarY, z0, newFarX + FAR_XZ, newFarY + FAR_Y, z1);
        }
        this.farOriginX = newFarX;
        this.farOriginY = newFarY;
        this.farOriginZ = newFarZ;

        // Rolling background refresh (3 chunk columns/frame across the 16x16 far chunk grid) so newly
        // streamed-in distant chunks are always populated in uFarVoxelTex with zero chunk-boundary cutoffs.
        for (int k = 0; k < 3; k++) {
            int cIdx = (farRefreshCursor++) & 255;
            int lx = (cIdx & 15) << 3;
            int lz = ((cIdx >> 4) & 15) << 3;
            scanFarBox(level, farOriginX + lx, farOriginY, farOriginZ + lz,
                    farOriginX + lx + 8, farOriginY + FAR_Y, farOriginZ + lz + 8);
        }
    }

    private void scanFarBox(ClientLevel level, int minMX, int minMY, int minMZ, int maxMX, int maxMY, int maxMZ) {
        int worldMinY = level.getMinY();
        int worldMaxY = worldMinY + level.getHeight();
        int minCX = (minMX << 1) >> 4;
        int maxCX = ((maxMX - 1) << 1) >> 4;
        int minCZ = (minMZ << 1) >> 4;
        int maxCZ = ((maxMZ - 1) << 1) >> 4;
        int minSY = Math.max(worldMinY, minMY << 1) >> 4;
        int maxSY = (Math.min(worldMaxY, maxMY << 1) - 1) >> 4;

        for (int cx = minCX; cx <= maxCX; cx++) {
            int mxStart = Math.max(minMX, cx << 3);
            int mxEnd = Math.min(maxMX, (cx << 3) + 8);
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                int mzStart = Math.max(minMZ, cz << 3);
                int mzEnd = Math.min(maxMZ, (cz << 3) + 8);
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                LevelChunkSection[] sections = (chunk != null) ? chunk.getSections() : null;

                if ((minMY << 1) < worldMinY) {
                    clearFarAirBox(mxStart, minMY, mzStart, mxEnd, Math.min(maxMY, worldMinY >> 1), mzEnd);
                }
                if ((maxMY << 1) > worldMaxY) {
                    clearFarAirBox(mxStart, Math.max(minMY, worldMaxY >> 1), mzStart, mxEnd, maxMY, mzEnd);
                }

                for (int sy = minSY; sy <= maxSY; sy++) {
                    int myStart = Math.max(minMY, sy << 3);
                    int myEnd = Math.min(maxMY, (sy << 3) + 8);
                    LevelChunkSection sec = null;
                    if (sections != null) {
                        int sIdx = level.getSectionIndexFromSectionY(sy);
                        if (sIdx >= 0 && sIdx < sections.length) sec = sections[sIdx];
                    }
                    if (sec == null || sec.hasOnlyAir()) {
                        clearFarAirBox(mxStart, myStart, mzStart, mxEnd, myEnd, mzEnd);
                        continue;
                    }

                    for (int my = myStart; my < myEnd; my++) {
                        int ty = my & FAR_Y_MASK;
                        int by = (my & 7) << 1;
                        for (int mz = mzStart; mz < mzEnd; mz++) {
                            int tz = mz & FAR_XZ_MASK;
                            int bz = (mz & 7) << 1;
                            int rowOff = (tz * FAR_Y + ty) * FAR_XZ;
                            for (int mx = mxStart; mx < mxEnd; mx++) {
                                int bx = (mx & 7) << 1;
                                int idx = rowOff + (mx & FAR_XZ_MASK);
                                sampleFarMacroCell(sec, bx, by, bz, idx);
                            }
                        }
                    }
                }
            }
        }
    }

    private void clearFarAirBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int my = y0; my < y1; my++) {
            int ty = my & FAR_Y_MASK;
            for (int mz = z0; mz < z1; mz++) {
                int tz = mz & FAR_XZ_MASK;
                int rowOff = (tz * FAR_Y + ty) * FAR_XZ;
                for (int mx = x0; mx < x1; mx++) {
                    int idx = rowOff + (mx & FAR_XZ_MASK);
                    int o = idx * 4;
                    if (cpuFarVoxel[o] != 0 || cpuFarVoxel[o + 1] != 0 || cpuFarVoxel[o + 2] != 0 || cpuFarVoxel[o + 3] != 0) {
                        cpuFarVoxel[o] = 0; cpuFarVoxel[o + 1] = 0; cpuFarVoxel[o + 2] = 0; cpuFarVoxel[o + 3] = 0;
                        farVoxelDirty = true;
                    }
                    if (cpuFarPalette[idx] != 0) {
                        cpuFarPalette[idx] = 0;
                        farPaletteDirty = true;
                    }
                }
            }
        }
    }

    private static final int[] FAR_TAP_X = { 0, 1, 1, 0 };
    private static final int[] FAR_TAP_Y = { 1, 0, 1, 0 };
    private static final int[] FAR_TAP_Z = { 0, 1, 0, 1 };

    private void sampleFarMacroCell(LevelChunkSection sec, int bx, int by, int bz, int idx) {
        byte outR = 0, outG = 0, outB = 0, outA = 0;
        short outPal = 0;
        for (int t = 0; t < 4; t++) {
            BlockState st = sec.getBlockState(bx + FAR_TAP_X[t], by + FAR_TAP_Y[t], bz + FAR_TAP_Z[t]);
            if (st.isAir() || !st.getFluidState().isEmpty()) continue;
            Block b = st.getBlock();
            int em = st.getLightEmission();
            if (em >= 6) {
                float[] spec = classifyEmissive(b, em);
                outR = toUByte(spec[0] * curPlTint[0]);
                outG = toUByte(spec[1] * curPlTint[1]);
                outB = toUByte(spec[2] * curPlTint[2]);
                outA = (byte) 250;
                break;
            }
            if ((st.canOcclude() && st.isSolidRender()) || (b instanceof LeavesBlock)) {
                int col = b.defaultMapColor().col;
                float r = ((col >> 16) & 0xFF) / 255.0f;
                float g = ((col >> 8) & 0xFF) / 255.0f;
                float bl = (col & 0xFF) / 255.0f;
                float lum = 0.2126f * r + 0.7152f * g + 0.0722f * bl;
                float sat = (b instanceof LeavesBlock) ? 1.25f : 1.45f;
                r = Math.max(0.0f, Math.min(1.0f, lum + (r - lum) * sat));
                g = Math.max(0.0f, Math.min(1.0f, lum + (g - lum) * sat));
                bl = Math.max(0.0f, Math.min(1.0f, lum + (bl - lum) * sat));
                outR = toUByte((float) Math.pow(r, 1.5));
                outG = toUByte((float) Math.pow(g, 1.5));
                outB = toUByte((float) Math.pow(bl, 1.5));
                outA = (byte) ((b instanceof LeavesBlock) ? 160 : 128);
                outPal = resolveBlockPaletteId(st);
                break;
            }
        }
        int o = idx * 4;
        if (cpuFarVoxel[o] != outR || cpuFarVoxel[o + 1] != outG || cpuFarVoxel[o + 2] != outB || cpuFarVoxel[o + 3] != outA) {
            cpuFarVoxel[o] = outR; cpuFarVoxel[o + 1] = outG; cpuFarVoxel[o + 2] = outB; cpuFarVoxel[o + 3] = outA;
            farVoxelDirty = true;
        }
        if (cpuFarPalette[idx] != outPal) {
            cpuFarPalette[idx] = outPal;
            farPaletteDirty = true;
        }
    }

    /**
     * Resolves a unique BlockState into a compact 1..1023 palette ID and records its live
     * Minecraft TextureAtlasSprite UVs (top & side faces) plus BlockColors tint into cpuPaletteLut.
     */
    private short resolveBlockPaletteId(BlockState state) {
        Integer cached = paletteMap.get(state);
        if (cached != null) return cached.shortValue();
        if (nextPaletteId >= PALETTE_MAX) return 0;

        int id = nextPaletteId++;
        paletteMap.put(state, id);

        Minecraft mc = Minecraft.getInstance();
        TextureAtlasSprite topSprite = null;
        TextureAtlasSprite sideSprite = null;
        boolean topTinted = false;
        boolean sideTinted = false;
        int topTintIdx = -1;
        int sideTintIdx = -1;

        try {
            BlockStateModelSet modelSet = mc.getModelManager().getBlockStateModelSet();
            BlockStateModel model = modelSet.get(state);
            if (model != null) {
                palettePartsScratch.clear();
                model.collectParts(paletteRandom, palettePartsScratch);
                for (int i = 0; i < palettePartsScratch.size(); i++) {
                    BlockStateModelPart part = palettePartsScratch.get(i);
                    if (topSprite == null) {
                        List<BakedQuad> upQuads = part.getQuads(Direction.UP);
                        if (upQuads != null && !upQuads.isEmpty()) {
                            BakedQuad.MaterialInfo mi = upQuads.get(0).materialInfo();
                            topSprite = mi.sprite();
                            topTinted = mi.isTinted();
                            topTintIdx = mi.tintIndex();
                        }
                    }
                    if (sideSprite == null) {
                        List<BakedQuad> sideQuads = part.getQuads(Direction.NORTH);
                        if (sideQuads != null && !sideQuads.isEmpty()) {
                            BakedQuad.MaterialInfo mi = sideQuads.get(0).materialInfo();
                            sideSprite = mi.sprite();
                            sideTinted = mi.isTinted();
                            sideTintIdx = mi.tintIndex();
                        }
                    }
                    if (topSprite != null && sideSprite != null) break;
                }
                if (topSprite == null || sideSprite == null) {
                    Material.Baked pm = model.particleMaterial();
                    TextureAtlasSprite fallback = (pm != null) ? pm.sprite() : null;
                    if (topSprite == null) topSprite = fallback;
                    if (sideSprite == null) sideSprite = fallback;
                }
            }
        } catch (Throwable ignored) {
        }

        if (topSprite != null) {
            int o0 = id * 4;
            cpuPaletteLut[o0]     = topSprite.getU0();
            cpuPaletteLut[o0 + 1] = topSprite.getV0();
            cpuPaletteLut[o0 + 2] = topSprite.getU1() - topSprite.getU0();
            cpuPaletteLut[o0 + 3] = topSprite.getV1() - topSprite.getV0();
        }
        if (sideSprite != null) {
            int o1 = (PALETTE_MAX + id) * 4;
            cpuPaletteLut[o1]     = sideSprite.getU0();
            cpuPaletteLut[o1 + 1] = sideSprite.getV0();
            cpuPaletteLut[o1 + 2] = sideSprite.getU1() - sideSprite.getU0();
            cpuPaletteLut[o1 + 3] = sideSprite.getV1() - sideSprite.getV0();
        }

        float tr = 1.0f, tg = 1.0f, tb = 1.0f;
        if (topTinted || sideTinted) {
            int tintIdx = topTinted ? Math.max(0, topTintIdx) : Math.max(0, sideTintIdx);
            try {
                BlockColors bc = mc.getBlockColors();
                BlockTintSource src = (bc != null) ? bc.getTintSource(state, tintIdx) : null;
                int argb = (src != null) ? src.color(state) : -1;
                if (argb != -1 && (argb & 0xFFFFFF) != 0) {
                    tr = ((argb >> 16) & 0xFF) / 255.0f;
                    tg = ((argb >> 8) & 0xFF) / 255.0f;
                    tb = (argb & 0xFF) / 255.0f;
                }
            } catch (Throwable ignored) {
            }
        }
        int flags = (topTinted ? 1 : 0) | (sideTinted ? 2 : 0);
        int o2 = (PALETTE_MAX * 2 + id) * 4;
        cpuPaletteLut[o2]     = tr;
        cpuPaletteLut[o2 + 1] = tg;
        cpuPaletteLut[o2 + 2] = tb;
        cpuPaletteLut[o2 + 3] = (float) flags;

        paletteLutDirty = true;
        return (short) id;
    }

    private static float[] classifyHeldEmissive(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Item item = stack.getItem();
        if (item == Items.TORCH || item == Items.CAMPFIRE) return new float[] { 1.00f, 0.46f, 0.10f, 2.6f };
        if (item == Items.SOUL_TORCH || item == Items.SOUL_LANTERN || item == Items.SOUL_CAMPFIRE) {
            return new float[] { 0.10f, 0.78f, 1.00f, 2.5f };
        }
        if (item == Items.LANTERN || item == Items.JACK_O_LANTERN) return new float[] { 1.00f, 0.54f, 0.14f, 3.0f };
        if (item == Items.GLOWSTONE || item == Items.SHROOMLIGHT || item == Items.OCHRE_FROGLIGHT) {
            return new float[] { 1.00f, 0.70f, 0.24f, 3.2f };
        }
        if (item == Items.SEA_LANTERN) return new float[] { 0.36f, 0.94f, 1.00f, 3.2f };
        if (item == Items.VERDANT_FROGLIGHT) return new float[] { 0.26f, 1.00f, 0.46f, 3.1f };
        if (item == Items.PEARLESCENT_FROGLIGHT) return new float[] { 0.94f, 0.46f, 1.00f, 3.1f };
        if (item == Items.END_ROD || item == Items.BEACON) return new float[] { 0.94f, 0.88f, 1.00f, 2.9f };
        if (item == Items.LAVA_BUCKET || item == Items.BLAZE_ROD) return new float[] { 1.00f, 0.26f, 0.04f, 2.7f };
        if (item == Items.REDSTONE_TORCH) return new float[] { 1.00f, 0.12f, 0.08f, 1.5f };
        return null;
    }

    private void scanBox(ClientLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean markStaleWrap) {
        int worldMinY = level.getMinY();
        int worldMaxY = worldMinY + level.getHeight();
        int minCX = minX >> 4;
        int maxCX = (maxX - 1) >> 4;
        int minCZ = minZ >> 4;
        int maxCZ = (maxZ - 1) >> 4;

        for (int cx = minCX; cx <= maxCX; cx++) {
            int xStart = Math.max(minX, cx << 4);
            int xEnd = Math.min(maxX, (cx << 4) + 16);
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                int zStart = Math.max(minZ, cz << 4);
                int zEnd = Math.min(maxZ, (cz << 4) + 16);
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                LevelChunkSection[] sections = chunk != null ? chunk.getSections() : null;

                int minSY = Math.max(worldMinY, minY) >> 4;
                int maxSY = (Math.min(worldMaxY, maxY) - 1) >> 4;

                // Clear out-of-world-height slices to air
                if (minY < worldMinY) {
                    clearAirBox(xStart, minY, zStart, xEnd, Math.min(maxY, worldMinY), zEnd, markStaleWrap);
                }
                if (maxY > worldMaxY) {
                    clearAirBox(xStart, Math.max(minY, worldMaxY), zStart, xEnd, maxY, zEnd, markStaleWrap);
                }

                for (int sy = minSY; sy <= maxSY; sy++) {
                    int yStart = Math.max(minY, sy << 4);
                    int yEnd = Math.min(maxY, (sy << 4) + 16);
                    LevelChunkSection sec = null;
                    if (sections != null) {
                        int sIdx = level.getSectionIndexFromSectionY(sy);
                        if (sIdx >= 0 && sIdx < sections.length) {
                            sec = sections[sIdx];
                        }
                    }
                    if (sec == null || sec.hasOnlyAir()) {
                        clearAirBox(xStart, yStart, zStart, xEnd, yEnd, zEnd, markStaleWrap);
                        continue;
                    }

                    for (int wy = yStart; wy < yEnd; wy++) {
                        int ty = wy & GRID_MASK;
                        int ly = wy & 15;
                        for (int wz = zStart; wz < zEnd; wz++) {
                            int tz = wz & GRID_MASK;
                            int lz = wz & 15;
                            int rowOffset = (tz * GRID_SIZE + ty) * GRID_SIZE;
                            for (int wx = xStart; wx < xEnd; wx++) {
                                int tx = wx & GRID_MASK;
                                int idx = rowOffset + tx;
                                BlockState state = sec.getBlockState(wx & 15, ly, lz);
                                writeVoxel(level, wx, wy, wz, idx, state, markStaleWrap);
                            }
                        }
                    }
                }
            }
        }
    }

    private void clearAirBox(int xStart, int yStart, int zStart, int xEnd, int yEnd, int zEnd, boolean markStaleWrap) {
        byte airMask = markStaleWrap ? (byte) 0x80 : 0;
        for (int wy = yStart; wy < yEnd; wy++) {
            int ty = wy & GRID_MASK;
            for (int wz = zStart; wz < zEnd; wz++) {
                int tz = wz & GRID_MASK;
                int rowOffset = (tz * GRID_SIZE + ty) * GRID_SIZE;
                for (int wx = xStart; wx < xEnd; wx++) {
                    int idx = rowOffset + (wx & GRID_MASK);
                    if (cpuMask[idx] != airMask) {
                        cpuMask[idx] = airMask;
                        maskDirty = true;
                    }
                    byte emR = 0, emG = 0, emB = 0, emA = 0;
                    if (heldVoxelSpec != null && wx == heldVoxelX && wy == heldVoxelY && wz == heldVoxelZ) {
                        emR = toUByte(heldVoxelSpec[0] * curPlTint[0]);
                        emG = toUByte(heldVoxelSpec[1] * curPlTint[1]);
                        emB = toUByte(heldVoxelSpec[2] * curPlTint[2]);
                        emA = toUByte(heldVoxelSpec[3] * curPlIntensity * 0.25f);
                    }
                    int eOff = idx * 4;
                    if (cpuEmissive[eOff] != emR || cpuEmissive[eOff + 1] != emG
                            || cpuEmissive[eOff + 2] != emB || cpuEmissive[eOff + 3] != emA) {
                        cpuEmissive[eOff] = emR;
                        cpuEmissive[eOff + 1] = emG;
                        cpuEmissive[eOff + 2] = emB;
                        cpuEmissive[eOff + 3] = emA;
                        emissiveDirty = true;
                    }
                    int sOff = idx * 2;
                    if (cpuSubVoxel[sOff] != 0 || cpuSubVoxel[sOff + 1] != 0) {
                        cpuSubVoxel[sOff] = 0;
                        cpuSubVoxel[sOff + 1] = 0;
                        subVoxelDirty = true;
                    }
                    if (cpuVoxelPalette[idx] != 0) {
                        cpuVoxelPalette[idx] = 0;
                        voxelPaletteDirty = true;
                    }
                }
            }
        }
    }

    private void writeVoxel(ClientLevel level, int wx, int wy, int wz, int idx, BlockState state, boolean markStaleWrap) {
        Block b = state.getBlock();
        int emission = state.getLightEmission();
        int sOff = idx * 2;
        int newSubLo = 0;
        int newSubHi = 0;

        // 1. Voxel occlusion / stained-glass / 4x4x4 sub-voxel mask:
        //    0 = air, 1 = full opaque solid, 2..17 = stained glass, 18 = 4x4x4 sub-voxel partial blocker (stairs/fences/slabs/doors/chests/beds/etc.)
        byte maskVal;
        if (b instanceof StainedGlassBlock sgb) {
            DyeColor color = sgb.getColor();
            maskVal = (byte) (2 + color.ordinal());
        } else if (b instanceof StainedGlassPaneBlock sgpb) {
            DyeColor color = sgpb.getColor();
            maskVal = (byte) (2 + color.ordinal());
        } else if (emission > 0) {
            // Any emissive block (including solid glowstone, sea lantern, redstone lamp, froglight, magma)
            // must never self-occlude as mask == 1 so LPV injection and direct point lights pass cleanly.
            maskVal = 0;
        } else if (state.canOcclude() && state.isSolidRender()) {
            maskVal = 1;
        } else if (b instanceof LeavesBlock) {
            // maskVal = 19: foliage canopy (transparent to LPV/point-light GI, opaque to 3D RT reflections!)
            maskVal = 19;
        } else if (!state.isAir() && state.getFluidState().isEmpty()
                && !(b instanceof HalfTransparentBlock)) {
            scratchPos.set(wx, wy, wz);
            VoxelShape shape = state.getCollisionShape(level, scratchPos);
            if (!shape.isEmpty()) {
                subBitsScratch[0] = 0;
                subBitsScratch[1] = 0;
                shape.forAllBoxes((minX, minY, minZ, maxX, maxY, maxZ) -> {
                    double cx0 = Math.max(0.0, Math.min(1.0, minX));
                    double cy0 = Math.max(0.0, Math.min(1.0, minY));
                    double cz0 = Math.max(0.0, Math.min(1.0, minZ));
                    double cx1 = Math.max(0.0, Math.min(1.0, maxX));
                    double cy1 = Math.max(0.0, Math.min(1.0, maxY));
                    double cz1 = Math.max(0.0, Math.min(1.0, maxZ));
                    if (cx1 <= cx0 || cy1 <= cy0 || cz1 <= cz0) return;

                    int xMask = axisMask4(cx0, cx1);
                    int yMask = axisMask4(cy0, cy1);
                    int zMask = axisMask4(cz0, cz1);
                    int lo = subBitsScratch[0];
                    int hi = subBitsScratch[1];
                    for (int sy = 0; sy < 4; sy++) {
                        if ((yMask & (1 << sy)) == 0) continue;
                        for (int sz = 0; sz < 4; sz++) {
                            if ((zMask & (1 << sz)) == 0) continue;
                            int rowBits = xMask << ((sz << 2) | ((sy & 1) << 4));
                            if (sy < 2) { lo |= rowBits; } else { hi |= rowBits; }
                        }
                    }
                    subBitsScratch[0] = lo;
                    subBitsScratch[1] = hi;
                });
                int lo = subBitsScratch[0];
                int hi = subBitsScratch[1];
                // Never promote non-solid-render blocks (spawners, barriers, etc.) into maskVal=1 LPV walls
                if ((lo != 0 || hi != 0) && !(lo == -1 && hi == -1)) {
                    maskVal = 18;
                    newSubLo = lo;
                    newSubHi = hi;
                } else {
                    maskVal = 0;
                }
            } else {
                maskVal = 0;
            }
        } else {
            maskVal = 0;
        }
        byte finalMask = markStaleWrap ? (byte) (maskVal | 0x80) : maskVal;
        if (cpuMask[idx] != finalMask) {
            cpuMask[idx] = finalMask;
            maskDirty = true;
        }
        if (cpuSubVoxel[sOff] != newSubLo || cpuSubVoxel[sOff + 1] != newSubHi) {
            cpuSubVoxel[sOff] = newSubLo;
            cpuSubVoxel[sOff + 1] = newSubHi;
            subVoxelDirty = true;
        }

        // 2. Emissive injection (a > 0) OR solid wall linear albedo for LPV color bleeding (mask == 1, a == 0)
        byte emR = 0, emG = 0, emB = 0, emA = 0;
        if (emission >= 3) {
            float[] spec = classifyEmissive(b, emission);
            emR = toUByte(spec[0] * curPlTint[0]);
            emG = toUByte(spec[1] * curPlTint[1]);
            emB = toUByte(spec[2] * curPlTint[2]);
            emA = toUByte(spec[3] * curPlIntensity * 0.25f); // intensity / 4.0
        } else if (maskVal == 1 || maskVal == 18 || maskVal == 19) {
            int col = b.defaultMapColor().col;
            float r = ((col >> 16) & 0xFF) / 255.0f;
            float g = ((col >> 8) & 0xFF) / 255.0f;
            float bl = (col & 0xFF) / 255.0f;
            float lum = 0.2126f * r + 0.7152f * g + 0.0722f * bl;
            float sat = (maskVal == 19) ? 1.25f : 1.52f;
            r = Math.max(0.0f, Math.min(1.0f, lum + (r - lum) * sat));
            g = Math.max(0.0f, Math.min(1.0f, lum + (g - lum) * sat));
            bl = Math.max(0.0f, Math.min(1.0f, lum + (bl - lum) * sat));
            emR = toUByte((float) Math.pow(r, 1.5));
            emG = toUByte((float) Math.pow(g, 1.5));
            emB = toUByte((float) Math.pow(bl, 1.5));
            emA = 0; // Strictly 0: distinguishes surface albedo from emissive blocks
        }
        // Merge player's held light inline on the player's air cell to avoid redundant dirty flips
        if (heldVoxelSpec != null && maskVal != 1 && wx == heldVoxelX && wy == heldVoxelY && wz == heldVoxelZ) {
            int curA = emA & 0xFF;
            int newA = toUByte(heldVoxelSpec[3] * curPlIntensity * 0.25f) & 0xFF;
            if (newA > curA) {
                emR = toUByte(heldVoxelSpec[0] * curPlTint[0]);
                emG = toUByte(heldVoxelSpec[1] * curPlTint[1]);
                emB = toUByte(heldVoxelSpec[2] * curPlTint[2]);
                emA = (byte) newA;
            }
        }
        int eOff = idx * 4;
        if (cpuEmissive[eOff] != emR || cpuEmissive[eOff + 1] != emG
                || cpuEmissive[eOff + 2] != emB || cpuEmissive[eOff + 3] != emA) {
            cpuEmissive[eOff] = emR;
            cpuEmissive[eOff + 1] = emG;
            cpuEmissive[eOff + 2] = emB;
            cpuEmissive[eOff + 3] = emA;
            emissiveDirty = true;
        }
        short palId = (maskVal == 1 || maskVal == 18 || maskVal == 19) ? resolveBlockPaletteId(state) : 0;
        if (cpuVoxelPalette[idx] != palId) {
            cpuVoxelPalette[idx] = palId;
            voxelPaletteDirty = true;
        }
    }

    /** Maps a [0, 1] box span along one axis into a 4-bit occupancy mask over the 4 quarter-block sub-voxels. */
    private static int axisMask4(double minV, double maxV) {
        int m = 0;
        for (int i = 0; i < 4; i++) {
            double c0 = i * 0.25;
            double c1 = c0 + 0.25;
            double overlap = Math.min(maxV, c1) - Math.max(minV, c0);
            if (overlap > 0.03) {
                m |= (1 << i);
            }
        }
        if (m == 0) {
            int mid = Math.max(0, Math.min(3, (int) Math.floor((minV + maxV) * 2.0)));
            m = 1 << mid;
        }
        return m;
    }

    private static byte toUByte(float v) {
        int iv = Math.round(Math.max(0.0f, Math.min(1.0f, v)) * 255.0f);
        return (byte) iv;
    }

    // Returns { r, g, b, intensity (0..4) }
    private static float[] classifyEmissive(Block b, int emission) {
        if (b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH
                || b == Blocks.SOUL_LANTERN || b == Blocks.SOUL_CAMPFIRE || b == Blocks.SOUL_FIRE) {
            return new float[] { 0.10f, 0.78f, 1.00f, 2.4f };
        }
        if (b == Blocks.TORCH || b == Blocks.WALL_TORCH || b == Blocks.CAMPFIRE || b == Blocks.FIRE) {
            return new float[] { 1.00f, 0.46f, 0.10f, 2.5f };
        }
        if (b == Blocks.LANTERN || b == Blocks.JACK_O_LANTERN) {
            return new float[] { 1.00f, 0.54f, 0.14f, 2.8f };
        }
        if (b == Blocks.GLOWSTONE || b == Blocks.SHROOMLIGHT) {
            return new float[] { 1.00f, 0.70f, 0.24f, 3.2f };
        }
        if (b == Blocks.SEA_LANTERN || b == Blocks.CONDUIT) {
            return new float[] { 0.36f, 0.94f, 1.00f, 3.2f };
        }
        if (b == Blocks.REDSTONE_LAMP) {
            return new float[] { 1.00f, 0.52f, 0.14f, 3.0f };
        }
        if (b == Blocks.OCHRE_FROGLIGHT) return new float[] { 1.00f, 0.78f, 0.28f, 3.1f };
        if (b == Blocks.VERDANT_FROGLIGHT) return new float[] { 0.26f, 1.00f, 0.46f, 3.1f };
        if (b == Blocks.PEARLESCENT_FROGLIGHT) return new float[] { 0.94f, 0.46f, 1.00f, 3.1f };
        if (b == Blocks.END_ROD || b == Blocks.BEACON) return new float[] { 0.94f, 0.88f, 1.00f, 2.9f };
        if (b == Blocks.LAVA || b == Blocks.LAVA_CAULDRON || b == Blocks.MAGMA_BLOCK) {
            return new float[] { 1.00f, 0.24f, 0.03f, 3.0f };
        }
        if (b == Blocks.NETHER_PORTAL || b == Blocks.CRYING_OBSIDIAN || b == Blocks.AMETHYST_CLUSTER) {
            return new float[] { 0.68f, 0.16f, 1.00f, 2.2f };
        }
        if (b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_WALL_TORCH) {
            return new float[] { 1.00f, 0.12f, 0.08f, 1.4f };
        }
        float norm = emission / 15.0f;
        return new float[] { 1.00f, 0.62f, 0.24f, 0.8f + norm * 1.8f };
    }

    public boolean dispatch(CommandEncoder encoder, RaindioAttachments att, RaindioUniformManager uniforms) {
        return dispatch(encoder, att, uniforms, 2);
    }

    /**
     * Uploads updated toroidal voxel mask & emissive volumes and runs 1..4 rounds of 6-neighbor
     * unified L1 SH-LPV propagation (point lights + sun bounce via shadowtex0/shadowcolor0).
     */
    public boolean dispatch(CommandEncoder encoder, RaindioAttachments att, RaindioUniformManager uniforms, int stepsPerFrame) {
        if (unavailable) return false;

        VulkanCommandEncoder vkEncoder = unwrapVulkanEncoder(encoder);
        if (vkEncoder == null) {
            unavailable = true;
            return false;
        }

        VulkanCommandEncoderAccessor accessor = (VulkanCommandEncoderAccessor) vkEncoder;
        VulkanDevice dev = accessor.raindio$getDevice();
        if (dev == null) return false;

        if (!initialized) {
            try {
                initVulkan(dev);
            } catch (Throwable t) {
                unavailable = true;
                RaindioClientMod.LOGGER.error("[Raindio] Failed to initialize Phase 2 LPV 3D Compute pipeline.", t);
                RaindioKeybinds.sendChatAndOverlay(Minecraft.getInstance(), "[Raindio LPV Error] " + t.getMessage());
                close();
                return false;
            }
        }

        VkCommandBuffer cmd = accessor.raindio$commandBuffer();
        if (cmd == null) return false;

        long frameUbo = unwrapBuffer(uniforms.frameBuffer());
        long s0View = unwrapImageView(att.shadowtex0View());
        long sc0View = unwrapImageView(att.shadowcolor0View());
        long nearestClamp = unwrapSampler(att.nearestClampSampler());
        long linearClamp = unwrapSampler(att.linearClampSampler());
        if (frameUbo == 0L || s0View == 0L || sc0View == 0L) return false;

        int slot = ringIndex;
        ringIndex = (ringIndex + 1) % RING_SIZE;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 0. Transition 3D images from UNDEFINED -> GENERAL once AND explicitly zero-clear VRAM
            //    (critical on Windows NVIDIA where newly allocated VRAM is uninitialized and may contain NaN/Inf)
            if (!imagesTransitioned) {
                clearAndInit3DColor(cmd, stack, maskImage, true);
                clearAndInit3DColor(cmd, stack, emImage, false);
                clearAndInit3DColor(cmd, stack, subVoxelImage, true);
                clearAndInit3DColor(cmd, stack, farVoxelImage, false);
                clearAndInit3DColor(cmd, stack, paletteLutImage, false);
                clearAndInit3DColor(cmd, stack, voxelPaletteImage, true);
                clearAndInit3DColor(cmd, stack, farPaletteImage, true);
                for (int i = 0; i < 2; i++) {
                    clearAndInit3DColor(cmd, stack, shRImg[i], false);
                    clearAndInit3DColor(cmd, stack, shGImg[i], false);
                    clearAndInit3DColor(cmd, stack, shBImg[i], false);
                }
                imagesTransitioned = true;
                needsShClear = false;
            } else if (needsShClear) {
                // Clear active SH volume on dimension switch / teleport so stale or NaN values never linger
                clear3DGeneral(cmd, stack, shRImg[activeShIndex]);
                clear3DGeneral(cmd, stack, shGImg[activeShIndex]);
                clear3DGeneral(cmd, stack, shBImg[activeShIndex]);
                needsShClear = false;
            }

            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            // 1. Upload only the textures that actually changed this frame
            if (maskDirty) {
                upload3D(cmd, stack, maskStagingBufs[slot], maskStagingAllocs[slot], cpuMask, maskImage);
                maskDirty = false;
            }
            if (emissiveDirty) {
                upload3D(cmd, stack, emStagingBufs[slot], emStagingAllocs[slot], cpuEmissive, emImage);
                emissiveDirty = false;
            }
            if (subVoxelDirty) {
                upload3DInts(cmd, stack, subVoxelStagingBufs[slot], subVoxelStagingAllocs[slot], cpuSubVoxel, subVoxelImage);
                subVoxelDirty = false;
            }
            if (farVoxelDirty) {
                upload3D(cmd, stack, farVoxelStagingBufs[slot], farVoxelStagingAllocs[slot], cpuFarVoxel, farVoxelImage, FAR_XZ, FAR_Y, FAR_XZ);
                farVoxelDirty = false;
            }
            if (paletteLutDirty) {
                upload2DFloats(cmd, stack, paletteLutStagingBufs[slot], paletteLutStagingAllocs[slot], cpuPaletteLut, paletteLutImage, PALETTE_MAX, 3);
                paletteLutDirty = false;
            }
            if (voxelPaletteDirty) {
                upload3DShorts(cmd, stack, voxelPaletteStagingBufs[slot], voxelPaletteStagingAllocs[slot], cpuVoxelPalette, voxelPaletteImage, GRID_SIZE, GRID_SIZE, GRID_SIZE);
                voxelPaletteDirty = false;
            }
            if (farPaletteDirty) {
                upload3DShorts(cmd, stack, farPaletteStagingBufs[slot], farPaletteStagingAllocs[slot], cpuFarPalette, farPaletteImage, FAR_XZ, FAR_Y, FAR_XZ);
                farPaletteDirty = false;
            }

            // 2. Run 1..4 rounds of 6-neighbor unified L1 SH-LPV propagation (ping-pong A <-> B)
            int steps = Math.max(1, Math.min(MAX_PROPAGATION_STEPS, stepsPerFrame));
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline);
            int groups = GRID_SIZE / 4; // 16x16x16 workgroups
            IntBuffer pushBuf = stack.mallocInt(4);
            int safeOriginX = (originX == Integer.MIN_VALUE) ? 0 : originX;
            int safeOriginY = (originY == Integer.MIN_VALUE) ? 0 : originY;
            int safeOriginZ = (originZ == Integer.MIN_VALUE) ? 0 : originZ;

            for (int step = 0; step < steps; step++) {
                int srcIdx = activeShIndex;
                int dstIdx = 1 - activeShIndex;
                long descSet = descSets[slot * MAX_PROPAGATION_STEPS + step];

                updateDescriptorSet(stack, descSet, frameUbo, srcIdx, dstIdx, s0View, nearestClamp, sc0View, linearClamp);
                pushBuf.put(0, safeOriginX);
                pushBuf.put(1, safeOriginY);
                pushBuf.put(2, safeOriginZ);
                pushBuf.put(3, step);
                VK12.vkCmdPushConstants(cmd, pipelineLayout, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushBuf);
                VK12.vkCmdBindDescriptorSets(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                        pipelineLayout, 0, stack.longs(descSet), null);
                VK12.vkCmdDispatch(cmd, groups, groups, groups);

                // Barrier between propagation iterations (and before point_lights.comp reads final SH)
                transition3D(cmd, stack, shRImg[dstIdx], VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                        VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                transition3D(cmd, stack, shGImg[dstIdx], VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                        VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                transition3D(cmd, stack, shBImg[dstIdx], VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                        VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

                activeShIndex = dstIdx;
            }
            needsShClear = false;
        }

        return true;
    }

    private void upload3D(
            VkCommandBuffer cmd, MemoryStack stack,
            long stagingBuf, long stagingAlloc, byte[] srcBytes, long dstImage
    ) {
        upload3D(cmd, stack, stagingBuf, stagingAlloc, srcBytes, dstImage, GRID_SIZE, GRID_SIZE, GRID_SIZE);
    }

    private void upload3D(
            VkCommandBuffer cmd, MemoryStack stack,
            long stagingBuf, long stagingAlloc, byte[] srcBytes, long dstImage,
            int extW, int extH, int extD
    ) {
        PointerBuffer pMapped = stack.mallocPointer(1);
        checkVk(Vma.vmaMapMemory(vmaAllocator, stagingAlloc, pMapped), "vmaMapMemory(LPV3D)");
        ByteBuffer dst = MemoryUtil.memByteBuffer(pMapped.get(0), srcBytes.length);
        dst.put(srcBytes);
        Vma.vmaFlushAllocation(vmaAllocator, stagingAlloc, 0, srcBytes.length);
        Vma.vmaUnmapMemory(vmaAllocator, stagingAlloc);

        transition3D(cmd, stack, dstImage, VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK12.VK_ACCESS_SHADER_READ_BIT, VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
        region.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.imageOffset().set(0, 0, 0);
        region.imageExtent().set(extW, extH, extD);

        VK12.vkCmdCopyBufferToImage(cmd, stagingBuf, dstImage, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

        transition3D(cmd, stack, dstImage, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    private void upload3DInts(
            VkCommandBuffer cmd, MemoryStack stack,
            long stagingBuf, long stagingAlloc, int[] srcInts, long dstImage
    ) {
        int byteLen = srcInts.length * 4;
        PointerBuffer pMapped = stack.mallocPointer(1);
        checkVk(Vma.vmaMapMemory(vmaAllocator, stagingAlloc, pMapped), "vmaMapMemory(LPVSubVoxel3D)");
        IntBuffer dst = MemoryUtil.memIntBuffer(pMapped.get(0), srcInts.length);
        dst.put(srcInts);
        Vma.vmaFlushAllocation(vmaAllocator, stagingAlloc, 0, byteLen);
        Vma.vmaUnmapMemory(vmaAllocator, stagingAlloc);

        transition3D(cmd, stack, dstImage, VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK12.VK_ACCESS_SHADER_READ_BIT, VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
        region.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.imageOffset().set(0, 0, 0);
        region.imageExtent().set(GRID_SIZE, GRID_SIZE, GRID_SIZE);

        VK12.vkCmdCopyBufferToImage(cmd, stagingBuf, dstImage, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

        transition3D(cmd, stack, dstImage, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    private void upload3DShorts(
            VkCommandBuffer cmd, MemoryStack stack,
            long stagingBuf, long stagingAlloc, short[] srcShorts, long dstImage,
            int extW, int extH, int extD
    ) {
        int byteLen = srcShorts.length * 2;
        PointerBuffer pMapped = stack.mallocPointer(1);
        checkVk(Vma.vmaMapMemory(vmaAllocator, stagingAlloc, pMapped), "vmaMapMemory(LPVPalette3D)");
        ShortBuffer dst = MemoryUtil.memShortBuffer(pMapped.get(0), srcShorts.length);
        dst.put(srcShorts);
        Vma.vmaFlushAllocation(vmaAllocator, stagingAlloc, 0, byteLen);
        Vma.vmaUnmapMemory(vmaAllocator, stagingAlloc);

        transition3D(cmd, stack, dstImage, VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK12.VK_ACCESS_SHADER_READ_BIT, VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
        region.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.imageOffset().set(0, 0, 0);
        region.imageExtent().set(extW, extH, extD);

        VK12.vkCmdCopyBufferToImage(cmd, stagingBuf, dstImage, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

        transition3D(cmd, stack, dstImage, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    private void upload2DFloats(
            VkCommandBuffer cmd, MemoryStack stack,
            long stagingBuf, long stagingAlloc, float[] srcFloats, long dstImage,
            int extW, int extH
    ) {
        int byteLen = srcFloats.length * 4;
        PointerBuffer pMapped = stack.mallocPointer(1);
        checkVk(Vma.vmaMapMemory(vmaAllocator, stagingAlloc, pMapped), "vmaMapMemory(PaletteLut2D)");
        FloatBuffer dst = MemoryUtil.memFloatBuffer(pMapped.get(0), srcFloats.length);
        dst.put(srcFloats);
        Vma.vmaFlushAllocation(vmaAllocator, stagingAlloc, 0, byteLen);
        Vma.vmaUnmapMemory(vmaAllocator, stagingAlloc);

        transition3D(cmd, stack, dstImage, VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK12.VK_ACCESS_SHADER_READ_BIT, VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);

        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
        region.imageSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.imageOffset().set(0, 0, 0);
        region.imageExtent().set(extW, extH, 1);

        VK12.vkCmdCopyBufferToImage(cmd, stagingBuf, dstImage, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

        transition3D(cmd, stack, dstImage, VK12.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    private void initVulkan(VulkanDevice dev) throws IOException {
        this.vkDevice = dev.vkDevice();
        this.vmaAllocator = dev.vma();

        byte[] spvBytes = readResource(LPV_SPV);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pHandle = stack.mallocLong(1);

            // Samplers (NEAREST+REPEAT for texelFetch/mask, LINEAR+REPEAT for silky toroidal SH sampling)
            VkSamplerCreateInfo sampInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(VK12.VK_FILTER_NEAREST)
                    .minFilter(VK12.VK_FILTER_NEAREST)
                    .mipmapMode(VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .maxLod(0.0f);
            checkVk(VK12.vkCreateSampler(vkDevice, sampInfo, null, pHandle), "vkCreateSampler(LPV nearest)");
            this.nearestRepeatSampler = pHandle.get(0);

            sampInfo.magFilter(VK12.VK_FILTER_LINEAR).minFilter(VK12.VK_FILTER_LINEAR);
            checkVk(VK12.vkCreateSampler(vkDevice, sampInfo, null, pHandle), "vkCreateSampler(LPV linear)");
            this.linearRepeatSampler = pHandle.get(0);

            // Descriptor layout: 0=FrameUniforms UBO, 1..5=3D samplers, 6..8=3D storage images, 9..10=shadowtex0/shadowcolor0
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(11, stack);
            bind(bindings.get(0), 0, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            for (int i = 1; i <= 5; i++) {
                bind(bindings.get(i), i, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            }
            for (int i = 6; i <= 8; i++) {
                bind(bindings.get(i), i, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            }
            bind(bindings.get(9), 9, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            bind(bindings.get(10), 10, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pBindings(bindings);
            checkVk(VK12.vkCreateDescriptorSetLayout(vkDevice, layoutInfo, null, pHandle), "vkCreateDescriptorSetLayout(LPV)");
            this.descLayout = pHandle.get(0);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(16);
            VkPipelineLayoutCreateInfo pLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(descLayout))
                    .pPushConstantRanges(pushRange);
            checkVk(VK12.vkCreatePipelineLayout(vkDevice, pLayoutInfo, null, pHandle), "vkCreatePipelineLayout(LPV)");
            this.pipelineLayout = pHandle.get(0);

            this.computePipeline = createComputePipeline(stack, spvBytes, pipelineLayout);

            int totalSets = RING_SIZE * MAX_PROPAGATION_STEPS;
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(totalSets);
            poolSizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(totalSets * 7);
            poolSizes.get(2).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(totalSets * 3);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .maxSets(totalSets)
                    .pPoolSizes(poolSizes);
            checkVk(VK12.vkCreateDescriptorPool(vkDevice, poolInfo, null, pHandle), "vkCreateDescriptorPool(LPV)");
            this.descriptorPool = pHandle.get(0);

            LongBuffer layouts = stack.mallocLong(totalSets);
            for (int i = 0; i < totalSets; i++) layouts.put(i, descLayout);
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default()
                    .descriptorPool(descriptorPool)
                    .pSetLayouts(layouts);
            LongBuffer setsOut = stack.mallocLong(totalSets);
            checkVk(VK12.vkAllocateDescriptorSets(vkDevice, allocInfo, setsOut), "vkAllocateDescriptorSets(LPV)");
            for (int i = 0; i < totalSets; i++) descSets[i] = setsOut.get(i);

            // Allocate 64x64x64 3D textures
            long[] m = create3DImage(stack, VK12.VK_FORMAT_R8_UINT,
                    VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT);
            this.maskImage = m[0]; this.maskAlloc = m[1]; this.maskView = m[2];

            long[] e = create3DImage(stack, VK12.VK_FORMAT_R8G8B8A8_UNORM,
                    VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT);
            this.emImage = e[0]; this.emAlloc = e[1]; this.emView = e[2];

            long[] sv = create3DImage(stack, VK12.VK_FORMAT_R32G32_UINT,
                    VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT);
            this.subVoxelImage = sv[0]; this.subVoxelAlloc = sv[1]; this.subVoxelView = sv[2];

            long[] fv = create3DImage(stack, VK12.VK_FORMAT_R8G8B8A8_UNORM,
                    VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT, FAR_XZ, FAR_Y, FAR_XZ);
            this.farVoxelImage = fv[0]; this.farVoxelAlloc = fv[1]; this.farVoxelView = fv[2];

            long[] plut = create2DImage(stack, VK12.VK_FORMAT_R32G32B32A32_SFLOAT,
                    VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT, PALETTE_MAX, 3);
            this.paletteLutImage = plut[0]; this.paletteLutAlloc = plut[1]; this.paletteLutView = plut[2];

            long[] vp = create3DImage(stack, VK12.VK_FORMAT_R16_UINT,
                    VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT, GRID_SIZE, GRID_SIZE, GRID_SIZE);
            this.voxelPaletteImage = vp[0]; this.voxelPaletteAlloc = vp[1]; this.voxelPaletteView = vp[2];

            long[] fp = create3DImage(stack, VK12.VK_FORMAT_R16_UINT,
                    VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT, FAR_XZ, FAR_Y, FAR_XZ);
            this.farPaletteImage = fp[0]; this.farPaletteAlloc = fp[1]; this.farPaletteView = fp[2];

            int shUsage = VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            for (int i = 0; i < 2; i++) {
                long[] r = create3DImage(stack, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, shUsage);
                shRImg[i] = r[0]; shRAlloc[i] = r[1]; shRView[i] = r[2];

                long[] g = create3DImage(stack, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, shUsage);
                shGImg[i] = g[0]; shGAlloc[i] = g[1]; shGView[i] = g[2];

                long[] b = create3DImage(stack, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, shUsage);
                shBImg[i] = b[0]; shBAlloc[i] = b[1]; shBView[i] = b[2];
            }

            // Allocate 4-frame staging buffers for voxel grids & palette-indexed block atlas LUT
            for (int i = 0; i < RING_SIZE; i++) {
                long[] mb = createStagingBuffer(stack, MASK_BYTES);
                maskStagingBufs[i] = mb[0]; maskStagingAllocs[i] = mb[1];

                long[] eb = createStagingBuffer(stack, EMISSIVE_BYTES);
                emStagingBufs[i] = eb[0]; emStagingAllocs[i] = eb[1];

                long[] svb = createStagingBuffer(stack, SUBVOXEL_BYTES);
                subVoxelStagingBufs[i] = svb[0]; subVoxelStagingAllocs[i] = svb[1];

                long[] fvb = createStagingBuffer(stack, FAR_VOXEL_BYTES);
                farVoxelStagingBufs[i] = fvb[0]; farVoxelStagingAllocs[i] = fvb[1];

                long[] plb = createStagingBuffer(stack, PALETTE_LUT_BYTES);
                paletteLutStagingBufs[i] = plb[0]; paletteLutStagingAllocs[i] = plb[1];

                long[] vpb = createStagingBuffer(stack, VOXEL_PALETTE_BYTES);
                voxelPaletteStagingBufs[i] = vpb[0]; voxelPaletteStagingAllocs[i] = vpb[1];

                long[] fpb = createStagingBuffer(stack, FAR_PALETTE_BYTES);
                farPaletteStagingBufs[i] = fpb[0]; farPaletteStagingAllocs[i] = fpb[1];
            }
        }

        this.initialized = true;
        RaindioClientMod.LOGGER.info("[Raindio] Initialized Phase 2 64x64x64 Toroidal Voxel Mask + L1 SH-LPV 3D Compute pipeline.");
    }

    private long[] create3DImage(MemoryStack stack, int format, int usage) {
        return create3DImage(stack, format, usage, GRID_SIZE, GRID_SIZE, GRID_SIZE);
    }

    private long[] create3DImage(MemoryStack stack, int format, int usage, int extW, int extH, int extD) {
        VkImageCreateInfo imgInfo = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .imageType(VK12.VK_IMAGE_TYPE_3D)
                .format(format)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
        imgInfo.extent().set(extW, extH, extD);

        VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);

        LongBuffer pImg = stack.mallocLong(1);
        PointerBuffer pAlloc = stack.mallocPointer(1);
        checkVk(Vma.vmaCreateImage(vmaAllocator, imgInfo, allocInfo, pImg, pAlloc, null), "vmaCreateImage(LPV3D)");
        long image = pImg.get(0);
        long alloc = pAlloc.get(0);

        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(image)
                .viewType(VK12.VK_IMAGE_VIEW_TYPE_3D)
                .format(format);
        viewInfo.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

        LongBuffer pView = stack.mallocLong(1);
        checkVk(VK12.vkCreateImageView(vkDevice, viewInfo, null, pView), "vkCreateImageView(LPV3D)");
        return new long[] { image, alloc, pView.get(0) };
    }

    private long[] create2DImage(MemoryStack stack, int format, int usage, int extW, int extH) {
        VkImageCreateInfo imgInfo = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .imageType(VK12.VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(1)
                .arrayLayers(1)
                .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
        imgInfo.extent().set(extW, extH, 1);

        VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);

        LongBuffer pImg = stack.mallocLong(1);
        PointerBuffer pAlloc = stack.mallocPointer(1);
        checkVk(Vma.vmaCreateImage(vmaAllocator, imgInfo, allocInfo, pImg, pAlloc, null), "vmaCreateImage(PaletteLut2D)");
        long image = pImg.get(0);
        long alloc = pAlloc.get(0);

        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(image)
                .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
        viewInfo.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

        LongBuffer pView = stack.mallocLong(1);
        checkVk(VK12.vkCreateImageView(vkDevice, viewInfo, null, pView), "vkCreateImageView(PaletteLut2D)");
        return new long[] { image, alloc, pView.get(0) };
    }

    private long[] createStagingBuffer(MemoryStack stack, int sizeBytes) {
        VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
                .sType$Default()
                .size(sizeBytes)
                .usage(VK12.VK_BUFFER_USAGE_TRANSFER_SRC_BIT)
                .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
        VmaAllocationCreateInfo vmaAlloc = VmaAllocationCreateInfo.calloc(stack)
                .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);

        LongBuffer pBuf = stack.mallocLong(1);
        PointerBuffer pAlloc = stack.mallocPointer(1);
        checkVk(Vma.vmaCreateBuffer(vmaAllocator, bufInfo, vmaAlloc, pBuf, pAlloc, null), "vmaCreateBuffer(LPV staging)");
        return new long[] { pBuf.get(0), pAlloc.get(0) };
    }

    private void updateDescriptorSet(
            MemoryStack stack, long set, long frameUbo, int srcIdx, int dstIdx,
            long s0View, long nearestClamp, long sc0View, long linearClamp
    ) {
        VkDescriptorBufferInfo.Buffer uboInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(frameUbo).offset(0).range(RaindioUniformManager.FRAME_UBO_SIZE);

        VkDescriptorImageInfo.Buffer infos = VkDescriptorImageInfo.calloc(10, stack);
        infos.get(0).sampler(nearestRepeatSampler).imageView(maskView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        infos.get(1).sampler(nearestRepeatSampler).imageView(emView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        infos.get(2).sampler(nearestRepeatSampler).imageView(shRView[srcIdx]).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        infos.get(3).sampler(nearestRepeatSampler).imageView(shGView[srcIdx]).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        infos.get(4).sampler(nearestRepeatSampler).imageView(shBView[srcIdx]).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        infos.get(5).sampler(VK12.VK_NULL_HANDLE).imageView(shRView[dstIdx]).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        infos.get(6).sampler(VK12.VK_NULL_HANDLE).imageView(shGView[dstIdx]).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        infos.get(7).sampler(VK12.VK_NULL_HANDLE).imageView(shBView[dstIdx]).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        infos.get(8).sampler(nearestClamp).imageView(s0View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        infos.get(9).sampler(linearClamp).imageView(sc0View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(11, stack);
        writes.get(0).sType$Default().dstSet(set).dstBinding(0)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(uboInfo);
        for (int i = 0; i < 5; i++) {
            writes.get(i + 1).sType$Default().dstSet(set).dstBinding(i + 1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(infos.address(i), 1));
        }
        for (int i = 5; i < 8; i++) {
            writes.get(i + 1).sType$Default().dstSet(set).dstBinding(i + 1)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(infos.address(i), 1));
        }
        writes.get(9).sType$Default().dstSet(set).dstBinding(9)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.create(infos.address(8), 1));
        writes.get(10).sType$Default().dstSet(set).dstBinding(10)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.create(infos.address(9), 1));
        VK12.vkUpdateDescriptorSets(vkDevice, writes, null);
    }

    private static long unwrapBuffer(GpuBuffer buf) { return (buf instanceof VulkanGpuBuffer vb) ? vb.vkBuffer() : 0L; }
    private static long unwrapImageView(GpuTextureView view) { return (view instanceof VulkanGpuTextureView vv) ? vv.vkImageView() : 0L; }
    private static long unwrapSampler(GpuSampler samp) { return (samp instanceof VulkanGpuSampler vs) ? vs.vkSampler() : 0L; }

    private long createComputePipeline(MemoryStack stack, byte[] spvBytes, long pLayout) {
        ByteBuffer codeBuf = MemoryUtil.memAlloc(spvBytes.length);
        long shaderModule = VK12.VK_NULL_HANDLE;
        try {
            codeBuf.put(spvBytes).flip();
            VkShaderModuleCreateInfo modInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(codeBuf);
            LongBuffer pMod = stack.mallocLong(1);
            checkVk(VK12.vkCreateShaderModule(vkDevice, modInfo, null, pMod), "vkCreateShaderModule(LPV)");
            shaderModule = pMod.get(0);

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pipeInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipeInfo.get(0).sType$Default().stage(stageInfo).layout(pLayout);

            LongBuffer pPipe = stack.mallocLong(1);
            checkVk(VK12.vkCreateComputePipelines(vkDevice, VK12.VK_NULL_HANDLE, pipeInfo, null, pPipe), "vkCreateComputePipelines(LPV)");
            return pPipe.get(0);
        } finally {
            if (shaderModule != VK12.VK_NULL_HANDLE) VK12.vkDestroyShaderModule(vkDevice, shaderModule, null);
            MemoryUtil.memFree(codeBuf);
        }
    }

    private static void clearAndInit3DColor(VkCommandBuffer cmd, MemoryStack stack, long image, boolean isUint) {
        transition3D(cmd, stack, image,
                VK12.VK_IMAGE_LAYOUT_UNDEFINED, VK12.VK_IMAGE_LAYOUT_GENERAL,
                0, VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkClearColorValue clearVal = VkClearColorValue.calloc(stack);
        if (isUint) {
            clearVal.uint32(0, 0).uint32(1, 0).uint32(2, 0).uint32(3, 0);
        } else {
            clearVal.float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 0.0f);
        }
        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
        VK12.vkCmdClearColorImage(cmd, image, VK12.VK_IMAGE_LAYOUT_GENERAL, clearVal, range);
        transition3D(cmd, stack, image,
                VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    private static void clear3DGeneral(VkCommandBuffer cmd, MemoryStack stack, long image) {
        transition3D(cmd, stack, image,
                VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkClearColorValue clearVal = VkClearColorValue.calloc(stack);
        clearVal.float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 0.0f);
        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
        VK12.vkCmdClearColorImage(cmd, image, VK12.VK_IMAGE_LAYOUT_GENERAL, clearVal, range);
        transition3D(cmd, stack, image,
                VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    private static void transition3D(
            VkCommandBuffer cmd, MemoryStack stack, long image,
            int oldLayout, int newLayout,
            int srcAccess, int dstAccess,
            int srcStage, int dstStage
    ) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess);
        barrier.get(0).subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
        VK12.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
    }

    private static void bind(VkDescriptorSetLayoutBinding b, int binding, int type) {
        b.binding(binding).descriptorType(type).descriptorCount(1).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT);
    }

    private static VulkanCommandEncoder unwrapVulkanEncoder(CommandEncoder encoder) {
        if (encoder instanceof VulkanCommandEncoder vk) return vk;
        if (encoder instanceof FrontendCommandEncoder fe && fe.backend() instanceof VulkanCommandEncoder vk) return vk;
        return null;
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = RaindioClientMod.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing Compute SPIR-V resource: " + path);
            return in.readAllBytes();
        }
    }

    private static void checkVk(int result, String op) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException("Vulkan call failed: " + op + " (VkResult=" + result + ")");
        }
    }

    @Override
    public void close() {
        lastLevel = null;
        originX = originY = originZ = Integer.MIN_VALUE;
        farOriginX = farOriginY = farOriginZ = Integer.MIN_VALUE;
        if (vkDevice != null) {
            VK12.vkDeviceWaitIdle(vkDevice);
            destroyImage(maskImage, maskAlloc, maskView); maskImage = maskView = VK12.VK_NULL_HANDLE; maskAlloc = 0L;
            destroyImage(emImage, emAlloc, emView); emImage = emView = VK12.VK_NULL_HANDLE; emAlloc = 0L;
            destroyImage(subVoxelImage, subVoxelAlloc, subVoxelView); subVoxelImage = subVoxelView = VK12.VK_NULL_HANDLE; subVoxelAlloc = 0L;
            destroyImage(farVoxelImage, farVoxelAlloc, farVoxelView); farVoxelImage = farVoxelView = VK12.VK_NULL_HANDLE; farVoxelAlloc = 0L;
            destroyImage(paletteLutImage, paletteLutAlloc, paletteLutView); paletteLutImage = paletteLutView = VK12.VK_NULL_HANDLE; paletteLutAlloc = 0L;
            destroyImage(voxelPaletteImage, voxelPaletteAlloc, voxelPaletteView); voxelPaletteImage = voxelPaletteView = VK12.VK_NULL_HANDLE; voxelPaletteAlloc = 0L;
            destroyImage(farPaletteImage, farPaletteAlloc, farPaletteView); farPaletteImage = farPaletteView = VK12.VK_NULL_HANDLE; farPaletteAlloc = 0L;
            for (int i = 0; i < 2; i++) {
                destroyImage(shRImg[i], shRAlloc[i], shRView[i]); shRImg[i] = shRView[i] = VK12.VK_NULL_HANDLE; shRAlloc[i] = 0L;
                destroyImage(shGImg[i], shGAlloc[i], shGView[i]); shGImg[i] = shGView[i] = VK12.VK_NULL_HANDLE; shGAlloc[i] = 0L;
                destroyImage(shBImg[i], shBAlloc[i], shBView[i]); shBImg[i] = shBView[i] = VK12.VK_NULL_HANDLE; shBAlloc[i] = 0L;
            }
            for (int i = 0; i < RING_SIZE; i++) {
                if (maskStagingBufs[i] != VK12.VK_NULL_HANDLE) {
                    Vma.vmaDestroyBuffer(vmaAllocator, maskStagingBufs[i], maskStagingAllocs[i]);
                    maskStagingBufs[i] = VK12.VK_NULL_HANDLE; maskStagingAllocs[i] = 0L;
                }
                if (emStagingBufs[i] != VK12.VK_NULL_HANDLE) {
                    Vma.vmaDestroyBuffer(vmaAllocator, emStagingBufs[i], emStagingAllocs[i]);
                    emStagingBufs[i] = VK12.VK_NULL_HANDLE; emStagingAllocs[i] = 0L;
                }
                if (subVoxelStagingBufs[i] != VK12.VK_NULL_HANDLE) {
                    Vma.vmaDestroyBuffer(vmaAllocator, subVoxelStagingBufs[i], subVoxelStagingAllocs[i]);
                    subVoxelStagingBufs[i] = VK12.VK_NULL_HANDLE; subVoxelStagingAllocs[i] = 0L;
                }
                if (farVoxelStagingBufs[i] != VK12.VK_NULL_HANDLE) {
                    Vma.vmaDestroyBuffer(vmaAllocator, farVoxelStagingBufs[i], farVoxelStagingAllocs[i]);
                    farVoxelStagingBufs[i] = VK12.VK_NULL_HANDLE; farVoxelStagingAllocs[i] = 0L;
                }
                if (paletteLutStagingBufs[i] != VK12.VK_NULL_HANDLE) {
                    Vma.vmaDestroyBuffer(vmaAllocator, paletteLutStagingBufs[i], paletteLutStagingAllocs[i]);
                    paletteLutStagingBufs[i] = VK12.VK_NULL_HANDLE; paletteLutStagingAllocs[i] = 0L;
                }
                if (voxelPaletteStagingBufs[i] != VK12.VK_NULL_HANDLE) {
                    Vma.vmaDestroyBuffer(vmaAllocator, voxelPaletteStagingBufs[i], voxelPaletteStagingAllocs[i]);
                    voxelPaletteStagingBufs[i] = VK12.VK_NULL_HANDLE; voxelPaletteStagingAllocs[i] = 0L;
                }
                if (farPaletteStagingBufs[i] != VK12.VK_NULL_HANDLE) {
                    Vma.vmaDestroyBuffer(vmaAllocator, farPaletteStagingBufs[i], farPaletteStagingAllocs[i]);
                    farPaletteStagingBufs[i] = VK12.VK_NULL_HANDLE; farPaletteStagingAllocs[i] = 0L;
                }
            }
            if (nearestRepeatSampler != VK12.VK_NULL_HANDLE) { VK12.vkDestroySampler(vkDevice, nearestRepeatSampler, null); nearestRepeatSampler = VK12.VK_NULL_HANDLE; }
            if (linearRepeatSampler != VK12.VK_NULL_HANDLE) { VK12.vkDestroySampler(vkDevice, linearRepeatSampler, null); linearRepeatSampler = VK12.VK_NULL_HANDLE; }
            if (computePipeline != VK12.VK_NULL_HANDLE) { VK12.vkDestroyPipeline(vkDevice, computePipeline, null); computePipeline = VK12.VK_NULL_HANDLE; }
            if (pipelineLayout != VK12.VK_NULL_HANDLE) { VK12.vkDestroyPipelineLayout(vkDevice, pipelineLayout, null); pipelineLayout = VK12.VK_NULL_HANDLE; }
            if (descLayout != VK12.VK_NULL_HANDLE) { VK12.vkDestroyDescriptorSetLayout(vkDevice, descLayout, null); descLayout = VK12.VK_NULL_HANDLE; }
            if (descriptorPool != VK12.VK_NULL_HANDLE) { VK12.vkDestroyDescriptorPool(vkDevice, descriptorPool, null); descriptorPool = VK12.VK_NULL_HANDLE; }
        }
        imagesTransitioned = false;
        initialized = false;
    }

    private void destroyImage(long image, long alloc, long view) {
        if (view != VK12.VK_NULL_HANDLE) VK12.vkDestroyImageView(vkDevice, view, null);
        if (image != VK12.VK_NULL_HANDLE) Vma.vmaDestroyImage(vmaAllocator, image, alloc);
    }
}
