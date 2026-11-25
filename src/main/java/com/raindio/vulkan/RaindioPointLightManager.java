package com.raindio.vulkan;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.raindio.RaindioClientMod;
import com.raindio.client.RaindioKeybinds;
import com.raindio.config.RaindioConfig;
import com.raindio.mixin.VulkanCommandEncoderAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
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
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Native Vulkan ReSTIR DI + ReSTIR GI Compute Manager:
 * - Incrementally scans & caches emissive blocks per 16x16x16 SectionPos within 80 blocks (512 lights max).
 * - Pass 1 (restir_di_gi_temporal.comp):
 *     * 16x16 Tile Culling + Streamed RIS + O(1) single-ray Voxel DDA visibility + Temporal Reuse (DI)
 *     * 1-bounce screen-space + LPV/Sky GI sample + Temporal Reservoir Reuse (GI, 64m+ range)
 * - Pass 2 (restir_di_gi_spatial_resolve.comp):
 *     * 5-tap Spatial Reservoir Reuse (DI + GI) + Neighbor visibility check + Final PBR/GI Resolve -> pointLightTex
 */
public final class RaindioPointLightManager implements AutoCloseable {
    private static final String TEMPORAL_SPV = "/assets/raindio/shaders/spirv/compute/restir_di_gi_temporal.comp.spv";
    private static final String SPATIAL_SPV  = "/assets/raindio/shaders/spirv/compute/restir_di_gi_spatial_resolve.comp.spv";

    private static final int RING_SIZE = 4;
    private static final int MAX_LIGHTS = 512;
    private static final int MAX_ENTITIES = 64;
    private static final int ENTITY_SECTION_OFFSET = 16 + MAX_LIGHTS * 32; // 16,400
    private static final int SSBO_BYTES = ENTITY_SECTION_OFFSET + 16 + MAX_ENTITIES * 32; // 18,464 bytes
    private static final int SCAN_SECTION_RADIUS = 3; // +-3 sections = 48 blocks (faster RT culling)
    private static final double MAX_LIGHT_DIST = 48.0;
    private static final double MAX_LIGHT_DIST_SQ = MAX_LIGHT_DIST * MAX_LIGHT_DIST;
    private static final double MAX_ENTITY_SHADOW_DIST_SQ = 32.0 * 32.0;
    private static final int MAX_SECTIONS_NEW_PER_FRAME = 10;
    private static final int MAX_SECTIONS_REFRESH_PER_FRAME = 3;

    private record CachedLight(
            double x, double y, double z,
            float radius,
            float r, float g, float b, float intensity
    ) {}

    private static final class ActiveLight {
        float relX, relY, relZ, radius;
        float r, g, b, intensity;
        double distSq;
    }

    private static final class ActiveEntityBox {
        float minX, minY, minZ;
        float maxX, maxY, maxZ;
        double distSq;
    }

    private final Long2ObjectOpenHashMap<List<CachedLight>> sectionCache = new Long2ObjectOpenHashMap<>();
    private final LongArrayList cachedSectionKeys = new LongArrayList();
    private final List<ActiveLight> candidatePool = new ArrayList<>(1024);
    private final List<ActiveLight> activeLights = new ArrayList<>(1024);
    private final List<ActiveEntityBox> entityBoxPool = new ArrayList<>(128);
    private final List<ActiveEntityBox> activeEntityBoxes = new ArrayList<>(128);
    private final ByteBuffer ssboStaging = ByteBuffer.allocateDirect(SSBO_BYTES).order(ByteOrder.nativeOrder());

    private ClientLevel lastLevel = null;
    private int refreshCursor = 0;
    private int activeLightCount = 0;

    // Vulkan native compute resources (2-pass ReSTIR DI + GI)
    private VkDevice vkDevice;
    private long vmaAllocator;
    private long temporalDescLayout = VK12.VK_NULL_HANDLE;
    private long temporalPipeLayout = VK12.VK_NULL_HANDLE;
    private long temporalPipeline   = VK12.VK_NULL_HANDLE;
    private long spatialDescLayout  = VK12.VK_NULL_HANDLE;
    private long spatialPipeLayout  = VK12.VK_NULL_HANDLE;
    private long spatialPipeline    = VK12.VK_NULL_HANDLE;
    private long descriptorPool     = VK12.VK_NULL_HANDLE;

    private final long[] temporalDescSets = new long[RING_SIZE];
    private final long[] spatialDescSets  = new long[RING_SIZE];
    private final long[] ssboBuffers      = new long[RING_SIZE];
    private final long[] ssboAllocs       = new long[RING_SIZE];
    private int ringIndex = 0;

    // Persistent Ping-Pong ReSTIR Reservoir Textures (internal resolution) + final outImage
    private int outWidth = -1;
    private int outHeight = -1;
    private long outImage = VK12.VK_NULL_HANDLE, outAlloc = 0L, outView = VK12.VK_NULL_HANDLE;
    private final long[] diResImg = new long[2], diResAlloc = new long[2], diResView = new long[2];
    private final long[] giPosImg = new long[2], giPosAlloc = new long[2], giPosView = new long[2];
    private final long[] giRadImg = new long[2], giRadAlloc = new long[2], giRadView = new long[2];
    private final long[] geoImg   = new long[2], geoAlloc   = new long[2], geoView   = new long[2];

    private int reservoirIdx = 0;
    private boolean imagesTransitioned = false;
    private boolean historyValid = false;

    private boolean initialized = false;
    private boolean unavailable = false;

    public boolean isAvailable() {
        return !unavailable;
    }

    public int activeLightCount() {
        return activeLightCount;
    }

    /**
     * Evaluates directional + ambient RT point-light illumination at the player's first-person hand
     * from the current frame's active point lights (out12: dirXYZ, dirStrength, dirRGB, pad, ambRGB, pad).
     */
    public void computeHandPointLight(float[] out12) {
        for (int i = 0; i < 12; i++) out12[i] = 0.0f;
        int count = Math.min(this.activeLightCount, MAX_LIGHTS);
        if (count <= 0) return;

        float bestScore = 0.0f;
        float domDx = 0.0f, domDy = 1.0f, domDz = 0.0f;
        float domR = 0.0f, domG = 0.0f, domB = 0.0f;
        float ambR = 0.0f, ambG = 0.0f, ambB = 0.0f;

        for (int i = 0; i < count; i++) {
            int off = 16 + i * 32;
            float lx = ssboStaging.getFloat(off);
            float ly = ssboStaging.getFloat(off + 4) + 0.15f; // relative to hand (~0.15m below eye)
            float lz = ssboStaging.getFloat(off + 8);
            float radius = Math.max(ssboStaging.getFloat(off + 12), 0.5f);
            float distSq = lx * lx + ly * ly + lz * lz;
            if (distSq >= radius * radius) continue;

            float dist = (float) Math.sqrt(Math.max(distSq, 0.04f));
            float u = dist / radius;
            float u2 = u * u;
            float win = Math.max(0.0f, 1.0f - u2 * u2);
            win *= win;
            float atten = win / (1.0f + distSq * 0.85f);

            float r = ssboStaging.getFloat(off + 16);
            float g = ssboStaging.getFloat(off + 20);
            float b = ssboStaging.getFloat(off + 24);
            float intensity = ssboStaging.getFloat(off + 28);
            float contrib = intensity * atten;

            ambR += r * contrib * 0.35f;
            ambG += g * contrib * 0.35f;
            ambB += b * contrib * 0.35f;

            float score = (0.2126f * r + 0.7152f * g + 0.0722f * b) * contrib;
            if (score > bestScore) {
                bestScore = score;
                domDx = lx / dist;
                domDy = ly / dist;
                domDz = lz / dist;
                domR = r * contrib;
                domG = g * contrib;
                domB = b * contrib;
            }
        }

        out12[0] = domDx;
        out12[1] = domDy;
        out12[2] = domDz;
        out12[3] = bestScore > 1e-4f ? 1.0f : 0.0f;
        out12[4] = Math.min(domR, 4.0f);
        out12[5] = Math.min(domG, 4.0f);
        out12[6] = Math.min(domB, 4.0f);
        out12[7] = 0.0f;
        out12[8] = Math.min(ambR, 3.0f);
        out12[9] = Math.min(ambG, 3.0f);
        out12[10] = Math.min(ambB, 3.0f);
        out12[11] = 0.0f;
    }

    public void updateLights(CameraRenderState cameraState) {
        updateLights(cameraState, null);
    }

    public void updateLights(CameraRenderState cameraState, RaindioConfig config) {
        this.activeLightCount = 0;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || cameraState == null || cameraState.pos == null) {
            packEmptySsbo();
            return;
        }
        if (level != lastLevel) {
            sectionCache.clear();
            cachedSectionKeys.clear();
            refreshCursor = 0;
            lastLevel = level;
            historyValid = false;
        }

        double camX = cameraState.pos.x;
        double camY = cameraState.pos.y;
        double camZ = cameraState.pos.z;

        int centerSX = SectionPos.blockToSectionCoord((int) Math.floor(camX));
        int centerSY = SectionPos.blockToSectionCoord((int) Math.floor(camY));
        int centerSZ = SectionPos.blockToSectionCoord((int) Math.floor(camZ));

        int minSY = level.getMinSectionY();
        int maxSY = level.getMaxSectionY();

        if (centerSY >= minSY && centerSY <= maxSY) {
            scanAndCacheSection(level, centerSX, centerSY, centerSZ);
        }

        int newlyScanned = 0;
        for (int sx = centerSX - SCAN_SECTION_RADIUS; sx <= centerSX + SCAN_SECTION_RADIUS && newlyScanned < MAX_SECTIONS_NEW_PER_FRAME; sx++) {
            for (int sz = centerSZ - SCAN_SECTION_RADIUS; sz <= centerSZ + SCAN_SECTION_RADIUS && newlyScanned < MAX_SECTIONS_NEW_PER_FRAME; sz++) {
                int yStart = Math.max(minSY, centerSY - SCAN_SECTION_RADIUS);
                int yEnd = Math.min(maxSY, centerSY + SCAN_SECTION_RADIUS);
                for (int sy = yStart; sy <= yEnd && newlyScanned < MAX_SECTIONS_NEW_PER_FRAME; sy++) {
                    long key = SectionPos.asLong(sx, sy, sz);
                    if (!sectionCache.containsKey(key)) {
                        scanAndCacheSection(level, sx, sy, sz);
                        newlyScanned++;
                    }
                }
            }
        }

        if (!sectionCache.isEmpty()) {
            cachedSectionKeys.clear();
            var it = sectionCache.long2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                Long2ObjectMap.Entry<List<CachedLight>> e = it.next();
                long key = e.getLongKey();
                int sx = SectionPos.x(key);
                int sy = SectionPos.y(key);
                int sz = SectionPos.z(key);
                if (Math.abs(sx - centerSX) > SCAN_SECTION_RADIUS + 1
                        || Math.abs(sy - centerSY) > SCAN_SECTION_RADIUS + 1
                        || Math.abs(sz - centerSZ) > SCAN_SECTION_RADIUS + 1) {
                    it.remove();
                } else {
                    cachedSectionKeys.add(key);
                }
            }

            int totalCached = cachedSectionKeys.size();
            if (totalCached > 0) {
                for (int i = 0; i < MAX_SECTIONS_REFRESH_PER_FRAME; i++) {
                    refreshCursor = (refreshCursor + 1) % totalCached;
                    long key = cachedSectionKeys.getLong(refreshCursor);
                    scanAndCacheSection(level, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
                }
            }
        }

        activeLights.clear();
        int poolIdx = 0;
        for (List<CachedLight> list : sectionCache.values()) {
            if (list == null || list.isEmpty()) continue;
            for (int i = 0; i < list.size(); i++) {
                CachedLight cl = list.get(i);
                double dx = cl.x - camX;
                double dy = cl.y - camY;
                double dz = cl.z - camZ;
                double dSq = dx * dx + dy * dy + dz * dz;
                if (dSq > MAX_LIGHT_DIST_SQ) continue;

                ActiveLight al = borrowActiveLight(poolIdx++);
                al.relX = (float) dx;
                al.relY = (float) dy;
                al.relZ = (float) dz;
                al.radius = cl.radius;
                al.r = cl.r;
                al.g = cl.g;
                al.b = cl.b;
                al.intensity = cl.intensity;
                al.distSq = dSq;
                activeLights.add(al);
            }
        }

        if (activeLights.size() > MAX_LIGHTS - 1) {
            activeLights.sort(Comparator.comparingDouble(a -> a.distSq));
        }

        ssboStaging.clear();
        int count = 0;
        ssboStaging.putInt(0, 0);
        ssboStaging.putInt(4, 0);
        ssboStaging.putInt(8, 0);
        ssboStaging.putInt(12, 0);

        float intensityScale = (config != null) ? config.pointLightIntensity : 1.0f;
        float[] tint = (config != null) ? config.pointLightTintRgb() : new float[] { 1.0f, 1.0f, 1.0f };

        LocalPlayer player = mc.player;
        if (player != null) {
            count = appendHeldLight(player, cameraState, count, intensityScale, tint);
        }

        int worldLimit = Math.min(activeLights.size(), MAX_LIGHTS - count);
        for (int i = 0; i < worldLimit; i++) {
            ActiveLight al = activeLights.get(i);
            float tr = Math.min(1.5f, al.r * tint[0]);
            float tg = Math.min(1.5f, al.g * tint[1]);
            float tb = Math.min(1.5f, al.b * tint[2]);
            writeLightSlot(count++, al.relX, al.relY, al.relZ, al.radius, tr, tg, tb, al.intensity * intensityScale);
        }

        ssboStaging.putInt(0, count);
        this.activeLightCount = count;

        packEntityBoxes(level, cameraState, camX, camY, camZ);
    }

    private void packEntityBoxes(ClientLevel level, CameraRenderState cameraState, double camX, double camY, double camZ) {
        activeEntityBoxes.clear();
        if (this.activeLightCount <= 0) {
            ssboStaging.putInt(ENTITY_SECTION_OFFSET, 0);
            ssboStaging.putInt(ENTITY_SECTION_OFFSET + 4, 0);
            ssboStaging.putInt(ENTITY_SECTION_OFFSET + 8, 0);
            ssboStaging.putInt(ENTITY_SECTION_OFFSET + 12, 0);
            return;
        }
        float partialTicks = cameraState.cameraEntityPartialTicks;
        int poolIdx = 0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity == null || entity.isInvisible() || entity.isSpectator()) continue;
            AABB bb = entity.getBoundingBox();
            double sx = bb.getXsize();
            double sy = bb.getYsize();
            double sz = bb.getZsize();
            if (sy < 0.30 || (sx < 0.25 && sz < 0.25)) continue;

            Vec3 interp = entity.getPosition(partialTicks);
            Vec3 rawPos = entity.position();
            double ox = (interp.x - rawPos.x) - camX;
            double oy = (interp.y - rawPos.y) - camY;
            double oz = (interp.z - rawPos.z) - camZ;

            double cx = (bb.minX + bb.maxX) * 0.5 + ox;
            double cy = (bb.minY + bb.maxY) * 0.5 + oy;
            double cz = (bb.minZ + bb.maxZ) * 0.5 + oz;
            double dSq = cx * cx + cy * cy + cz * cz;
            if (dSq > MAX_ENTITY_SHADOW_DIST_SQ) continue;

            double entPad = 0.5 * Math.sqrt(sx * sx + sy * sy + sz * sz);
            boolean nearAnyLight = false;
            for (int li = 0; li < this.activeLightCount; li++) {
                int lOff = 16 + li * 32;
                double lx = ssboStaging.getFloat(lOff);
                double ly = ssboStaging.getFloat(lOff + 4);
                double lz = ssboStaging.getFloat(lOff + 8);
                double lr = ssboStaging.getFloat(lOff + 12) + entPad;
                double ddx = cx - lx;
                double ddy = cy - ly;
                double ddz = cz - lz;
                if (ddx * ddx + ddy * ddy + ddz * ddz <= lr * lr) {
                    nearAnyLight = true;
                    break;
                }
            }
            if (!nearAnyLight) continue;

            double insetX = sx * 0.08;
            double insetZ = sz * 0.08;
            ActiveEntityBox box = borrowEntityBox(poolIdx++);
            box.minX = (float) (bb.minX + ox + insetX);
            box.minY = (float) (bb.minY + oy);
            box.minZ = (float) (bb.minZ + oz + insetZ);
            box.maxX = (float) (bb.maxX + ox - insetX);
            box.maxY = (float) (bb.maxY + oy);
            box.maxZ = (float) (bb.maxZ + oz - insetZ);
            box.distSq = dSq;
            activeEntityBoxes.add(box);
        }

        if (activeEntityBoxes.size() > MAX_ENTITIES) {
            activeEntityBoxes.sort(Comparator.comparingDouble(b -> b.distSq));
        }
        int entCount = Math.min(activeEntityBoxes.size(), MAX_ENTITIES);
        ssboStaging.putInt(ENTITY_SECTION_OFFSET, entCount);
        ssboStaging.putInt(ENTITY_SECTION_OFFSET + 4, 0);
        ssboStaging.putInt(ENTITY_SECTION_OFFSET + 8, 0);
        ssboStaging.putInt(ENTITY_SECTION_OFFSET + 12, 0);
        for (int i = 0; i < entCount; i++) {
            ActiveEntityBox b = activeEntityBoxes.get(i);
            int off = ENTITY_SECTION_OFFSET + 16 + i * 32;
            ssboStaging.putFloat(off, b.minX);
            ssboStaging.putFloat(off + 4, b.minY);
            ssboStaging.putFloat(off + 8, b.minZ);
            ssboStaging.putFloat(off + 12, 0.0f);
            ssboStaging.putFloat(off + 16, b.maxX);
            ssboStaging.putFloat(off + 20, b.maxY);
            ssboStaging.putFloat(off + 24, b.maxZ);
            ssboStaging.putFloat(off + 28, 0.0f);
        }
    }


    private ActiveEntityBox borrowEntityBox(int idx) {
        while (entityBoxPool.size() <= idx) entityBoxPool.add(new ActiveEntityBox());
        return entityBoxPool.get(idx);
    }

    private int appendHeldLight(LocalPlayer player, CameraRenderState cameraState, int count, float intensityScale, float[] tint) {
        float[] mainSpec = classifyHeldItem(player.getMainHandItem());
        float[] offSpec = classifyHeldItem(player.getOffhandItem());
        float[] chosen = mainSpec != null ? mainSpec : offSpec;
        if (chosen == null || count >= MAX_LIGHTS) return count;

        boolean isOffhandOnly = (mainSpec == null);
        Vec3 look = player.getLookAngle();
        Vec3 right = new Vec3(-look.z, 0.0, look.x);
        double rLen = right.length();
        if (rLen > 1e-4) right = right.scale(1.0 / rLen);
        double sideSign = isOffhandOnly ? -0.35 : 0.35;

        float relX = (float) (look.x * 0.45 + right.x * sideSign);
        float relY = (float) (look.y * 0.30 - 0.18);
        float relZ = (float) (look.z * 0.45 + right.z * sideSign);
        float tr = Math.min(1.5f, chosen[1] * tint[0]);
        float tg = Math.min(1.5f, chosen[2] * tint[1]);
        float tb = Math.min(1.5f, chosen[3] * tint[2]);
        writeLightSlot(count, relX, relY, relZ, chosen[0], tr, tg, tb, chosen[4] * intensityScale);
        return count + 1;
    }

    private void scanAndCacheSection(ClientLevel level, int sx, int sy, int sz) {
        long key = SectionPos.asLong(sx, sy, sz);
        LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
        if (chunk == null) return;

        int sectionIndex = level.getSectionIndexFromSectionY(sy);
        LevelChunkSection[] sections = chunk.getSections();
        if (sectionIndex < 0 || sectionIndex >= sections.length) return;

        LevelChunkSection section = sections[sectionIndex];
        if (section == null || section.hasOnlyAir()) {
            sectionCache.put(key, List.of());
            return;
        }

        int baseX = sx << 4;
        int baseY = sy << 4;
        int baseZ = sz << 4;
        ArrayList<CachedLight> found = null;

        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    BlockState state = section.getBlockState(lx, ly, lz);
                    int emission = state.getLightEmission();
                    if (emission < 4) continue;

                    float[] spec = classifyBlockState(state, emission);
                    if (spec == null) continue;

                    Block b = state.getBlock();
                    double yOffset = (b == Blocks.TORCH || b == Blocks.SOUL_TORCH || b == Blocks.REDSTONE_TORCH)
                            ? 0.72 : 0.55;

                    if (found == null) found = new ArrayList<>(8);
                    found.add(new CachedLight(
                            baseX + lx + 0.5, baseY + ly + yOffset, baseZ + lz + 0.5,
                            spec[0], spec[1], spec[2], spec[3], spec[4]
                    ));
                }
            }
        }
        sectionCache.put(key, found != null ? found : List.of());
    }


    private static float[] classifyBlockState(BlockState state, int emission) {
        Block b = state.getBlock();
        if (b == Blocks.SOUL_TORCH || b == Blocks.SOUL_WALL_TORCH
                || b == Blocks.SOUL_LANTERN || b == Blocks.SOUL_CAMPFIRE || b == Blocks.SOUL_FIRE) {
            return new float[] { 8.0f, 0.10f, 0.78f, 1.00f, 0.82f };
        }
        if (b == Blocks.TORCH || b == Blocks.WALL_TORCH || b == Blocks.CAMPFIRE || b == Blocks.FIRE) {
            return new float[] { 8.5f, 1.00f, 0.46f, 0.10f, 0.85f };
        }
        if (b == Blocks.LANTERN || b == Blocks.JACK_O_LANTERN) {
            return new float[] { 9.5f, 1.00f, 0.54f, 0.14f, 0.98f };
        }
        if (b == Blocks.GLOWSTONE || b == Blocks.SHROOMLIGHT) {
            return new float[] { 10.5f, 1.00f, 0.70f, 0.24f, 1.08f };
        }
        if (b == Blocks.SEA_LANTERN || b == Blocks.CONDUIT) {
            return new float[] { 10.5f, 0.36f, 0.94f, 1.00f, 1.05f };
        }
        if (b == Blocks.REDSTONE_LAMP) {
            return new float[] { 10.0f, 1.00f, 0.52f, 0.14f, 1.02f };
        }
        if (b == Blocks.OCHRE_FROGLIGHT) return new float[] { 10.5f, 1.00f, 0.78f, 0.28f, 1.02f };
        if (b == Blocks.VERDANT_FROGLIGHT) return new float[] { 10.5f, 0.26f, 1.00f, 0.46f, 1.02f };
        if (b == Blocks.PEARLESCENT_FROGLIGHT) return new float[] { 10.5f, 0.94f, 0.46f, 1.00f, 1.02f };
        if (b == Blocks.END_ROD || b == Blocks.BEACON) return new float[] { 9.5f, 0.94f, 0.88f, 1.00f, 0.96f };
        if (b == Blocks.LAVA || b == Blocks.LAVA_CAULDRON || b == Blocks.MAGMA_BLOCK) {
            return new float[] { 8.5f, 1.00f, 0.24f, 0.03f, 0.90f };
        }
        if (b == Blocks.NETHER_PORTAL || b == Blocks.CRYING_OBSIDIAN || b == Blocks.AMETHYST_CLUSTER) {
            return new float[] { 7.5f, 0.68f, 0.16f, 1.00f, 0.74f };
        }
        if (b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_WALL_TORCH) {
            return new float[] { 5.5f, 1.00f, 0.12f, 0.08f, 0.55f };
        }
        float norm = emission / 15.0f;
        return new float[] { 4.5f + norm * 5.0f, 1.00f, 0.62f, 0.24f, 0.35f + norm * 0.55f };
    }

    private static float[] classifyHeldItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Item item = stack.getItem();
        if (item == Items.TORCH || item == Items.CAMPFIRE) return new float[] { 8.0f, 1.00f, 0.46f, 0.10f, 0.78f };
        if (item == Items.SOUL_TORCH || item == Items.SOUL_LANTERN || item == Items.SOUL_CAMPFIRE) {
            return new float[] { 7.5f, 0.10f, 0.78f, 1.00f, 0.75f };
        }
        if (item == Items.LANTERN || item == Items.JACK_O_LANTERN) return new float[] { 9.0f, 1.00f, 0.54f, 0.14f, 0.88f };
        if (item == Items.GLOWSTONE || item == Items.SHROOMLIGHT || item == Items.OCHRE_FROGLIGHT) {
            return new float[] { 9.5f, 1.00f, 0.70f, 0.24f, 0.92f };
        }
        if (item == Items.SEA_LANTERN) return new float[] { 9.5f, 0.36f, 0.94f, 1.00f, 0.92f };
        if (item == Items.VERDANT_FROGLIGHT) return new float[] { 9.5f, 0.26f, 1.00f, 0.46f, 0.90f };
        if (item == Items.PEARLESCENT_FROGLIGHT) return new float[] { 9.5f, 0.94f, 0.46f, 1.00f, 0.90f };
        if (item == Items.END_ROD || item == Items.BEACON) return new float[] { 9.0f, 0.94f, 0.88f, 1.00f, 0.85f };
        if (item == Items.LAVA_BUCKET || item == Items.BLAZE_ROD) return new float[] { 7.5f, 1.00f, 0.26f, 0.04f, 0.75f };
        if (item == Items.REDSTONE_TORCH) return new float[] { 5.0f, 1.00f, 0.12f, 0.08f, 0.50f };
        return null;
    }

    private ActiveLight borrowActiveLight(int idx) {
        while (candidatePool.size() <= idx) candidatePool.add(new ActiveLight());
        return candidatePool.get(idx);
    }

    private void writeLightSlot(
            int idx, float x, float y, float z, float radius,
            float r, float g, float b, float intensity
    ) {
        int off = 16 + idx * 32;
        ssboStaging.putFloat(off, x);
        ssboStaging.putFloat(off + 4, y);
        ssboStaging.putFloat(off + 8, z);
        ssboStaging.putFloat(off + 12, radius);
        ssboStaging.putFloat(off + 16, r);
        ssboStaging.putFloat(off + 20, g);
        ssboStaging.putFloat(off + 24, b);
        ssboStaging.putFloat(off + 28, intensity);
    }

    private void packEmptySsbo() {
        ssboStaging.putInt(0, 0);
        ssboStaging.putInt(4, 0);
        ssboStaging.putInt(8, 0);
        ssboStaging.putInt(12, 0);
        ssboStaging.putInt(ENTITY_SECTION_OFFSET, 0);
        ssboStaging.putInt(ENTITY_SECTION_OFFSET + 4, 0);
        ssboStaging.putInt(ENTITY_SECTION_OFFSET + 8, 0);
        ssboStaging.putInt(ENTITY_SECTION_OFFSET + 12, 0);
    }

    public boolean dispatch(
            CommandEncoder encoder,
            RaindioAttachments att,
            RaindioUniformManager uniforms,
            RaindioLpvManager lpv,
            int debugView
    ) {
        return dispatch(encoder, att, uniforms, lpv, debugView, 2, true);
    }

    public boolean dispatch(
            CommandEncoder encoder,
            RaindioAttachments att,
            RaindioUniformManager uniforms,
            RaindioLpvManager lpv,
            int debugView,
            int ddaTaps
    ) {
        return dispatch(encoder, att, uniforms, lpv, debugView, ddaTaps, true);
    }

    /**
     * Dispatches 2-pass Native Vulkan ReSTIR DI + ReSTIR GI:
     *   Pass 1: restir_di_gi_temporal.comp (16x16 tile culling + optional Top-3 or full many-light + GI + Temporal Reuse)
     *   Pass 2: restir_di_gi_spatial_resolve.comp (Spatial Reuse + Final PBR/GI Resolve -> att.pointLightTex)
     */
    public boolean dispatch(
            CommandEncoder encoder,
            RaindioAttachments att,
            RaindioUniformManager uniforms,
            RaindioLpvManager lpv,
            int debugView,
            int ddaTaps,
            boolean topKEnabled
    ) {
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
                RaindioClientMod.LOGGER.error("[Raindio] Failed to initialize Vulkan ReSTIR DI+GI Compute pipeline.", t);
                RaindioKeybinds.sendChatAndOverlay(Minecraft.getInstance(), "[Raindio ReSTIR Error] " + t.getMessage());
                close();
                return false;
            }
        }

        ensureOutputImages(att.width(), att.height());
        if (outView == VK12.VK_NULL_HANDLE) return false;

        VkCommandBuffer cmd = accessor.raindio$commandBuffer();
        if (cmd == null) return false;

        long frameUbo = unwrapBuffer(uniforms.frameBuffer());
        long c0View = unwrapImageView(att.colortex0View());
        long c1View = unwrapImageView(att.colortex1View());
        long c2View = unwrapImageView(att.colortex2View());
        long c3View = unwrapImageView(att.colortex3View());
        long c6View = unwrapImageView(att.colortex6View());
        long d0View = unwrapImageView(att.depthtex0View());
        long dstImage = unwrapImage(att.pointLightTex());
        long nearestSamp = unwrapSampler(att.nearestClampSampler());
        long linearSamp = unwrapSampler(att.linearClampSampler());

        if (frameUbo == 0L || c0View == 0L || c1View == 0L || c2View == 0L || c3View == 0L
                || c6View == 0L || d0View == 0L || dstImage == 0L) {
            return false;
        }

        boolean lpvReady = (lpv != null && lpv.isReady());
        if (!lpvReady) return false;

        ssboStaging.putInt(4, 1);
        ssboStaging.putInt(8, debugView == 9 ? 1 : 0);
        ssboStaging.putInt(12, historyValid ? 1 : 0);
        ssboStaging.putInt(ENTITY_SECTION_OFFSET + 4, topKEnabled ? 1 : 0);
        ssboStaging.putInt(ENTITY_SECTION_OFFSET + 8, ddaTaps);

        int slot = ringIndex;
        ringIndex = (ringIndex + 1) % RING_SIZE;

        int prevIdx = reservoirIdx;
        int curIdx = 1 - reservoirIdx;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pMapped = stack.mallocPointer(1);
            checkVk(Vma.vmaMapMemory(vmaAllocator, ssboAllocs[slot], pMapped), "vmaMapMemory(restirSSBO)");
            MemoryUtil.memCopy(MemoryUtil.memAddress(ssboStaging), pMapped.get(0), SSBO_BYTES);
            Vma.vmaFlushAllocation(vmaAllocator, ssboAllocs[slot], 0, VK12.VK_WHOLE_SIZE);
            Vma.vmaUnmapMemory(vmaAllocator, ssboAllocs[slot]);

            if (!imagesTransitioned) {
                clearAndInitImage(cmd, stack, outImage);
                for (int i = 0; i < 2; i++) {
                    clearAndInitImage(cmd, stack, diResImg[i]);
                    clearAndInitImage(cmd, stack, giPosImg[i]);
                    clearAndInitImage(cmd, stack, giRadImg[i]);
                    clearAndInitImage(cmd, stack, geoImg[i]);
                }
                imagesTransitioned = true;
            }

            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            // ==================== Pass 1: ReSTIR DI + GI Initial Sampling & Temporal Reuse ====================
            updateTemporalDescriptorSet(
                    stack, temporalDescSets[slot], frameUbo, ssboBuffers[slot],
                    c0View, c1View, c2View, c6View, d0View,
                    diResView[prevIdx], giPosView[prevIdx], giRadView[prevIdx], geoView[prevIdx],
                    nearestSamp, linearSamp,
                    lpv.shRView(), lpv.shGView(), lpv.shBView(), lpv.linearRepeatSampler(),
                    lpv.voxelMaskView(), lpv.subVoxelView(), lpv.nearestRepeatSampler(),
                    diResView[curIdx], giPosView[curIdx], giRadView[curIdx], geoView[curIdx]
            );

            int groupsX = (outWidth + 15) / 16;
            int groupsY = (outHeight + 15) / 16;

            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, temporalPipeline);
            VK12.vkCmdBindDescriptorSets(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    temporalPipeLayout, 0, stack.longs(temporalDescSets[slot]), null);
            VK12.vkCmdDispatch(cmd, groupsX, groupsY, 1);

            // Barrier: Pass 1 writes curIdx reservoirs -> Pass 2 reads curIdx reservoirs
            barrierComputeWriteToRead(cmd, stack, diResImg[curIdx]);
            barrierComputeWriteToRead(cmd, stack, giPosImg[curIdx]);
            barrierComputeWriteToRead(cmd, stack, giRadImg[curIdx]);
            barrierComputeWriteToRead(cmd, stack, geoImg[curIdx]);

            // ==================== Pass 2: ReSTIR DI + GI Spatial Reuse & Final Resolve ====================
            updateSpatialDescriptorSet(
                    stack, spatialDescSets[slot], frameUbo, ssboBuffers[slot],
                    c1View, c2View, c3View, d0View,
                    diResView[curIdx], giPosView[curIdx], giRadView[curIdx], geoView[curIdx],
                    nearestSamp,
                    lpv.shRView(), lpv.shGView(), lpv.shBView(), lpv.linearRepeatSampler(),
                    lpv.voxelMaskView(), lpv.subVoxelView(), lpv.nearestRepeatSampler(),
                    outView
            );

            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, spatialPipeline);
            VK12.vkCmdBindDescriptorSets(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    spatialPipeLayout, 0, stack.longs(spatialDescSets[slot]), null);
            VK12.vkCmdDispatch(cmd, groupsX, groupsY, 1);

            // Copy resolved outImage -> att.pointLightTex()
            transitionImage(cmd, stack, outImage,
                    VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_TRANSFER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);

            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.srcOffset().set(0, 0, 0);
            region.dstSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstOffset().set(0, 0, 0);
            region.extent().set(outWidth, outHeight, 1);

            VK12.vkCmdCopyImage(
                    cmd,
                    outImage, VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dstImage, VK12.VK_IMAGE_LAYOUT_GENERAL,
                    region
            );

            transitionImage(cmd, stack, outImage,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT, VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            transitionImage(cmd, stack, dstImage,
                    VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }

        reservoirIdx = curIdx;
        historyValid = true;
        return true;
    }

    private void initVulkan(VulkanDevice dev) throws IOException {
        this.vkDevice = dev.vkDevice();
        this.vmaAllocator = dev.vma();

        byte[] temporalSpv = readResource(TEMPORAL_SPV);
        byte[] spatialSpv  = readResource(SPATIAL_SPV);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pHandle = stack.mallocLong(1);

            // Temporal layout: 0=UBO, 1=SSBO, 2..15=14 samplers, 16..19=4 storage images
            VkDescriptorSetLayoutBinding.Buffer tBindings = VkDescriptorSetLayoutBinding.calloc(20, stack);
            bind(tBindings.get(0), 0, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            bind(tBindings.get(1), 1, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            for (int i = 2; i <= 15; i++) bind(tBindings.get(i), i, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            for (int i = 16; i <= 19; i++) bind(tBindings.get(i), i, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);

            VkDescriptorSetLayoutCreateInfo tLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(tBindings);
            checkVk(VK12.vkCreateDescriptorSetLayout(vkDevice, tLayoutInfo, null, pHandle), "vkCreateDescriptorSetLayout(restirTemporal)");
            this.temporalDescLayout = pHandle.get(0);

            VkPipelineLayoutCreateInfo tPLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(temporalDescLayout));
            checkVk(VK12.vkCreatePipelineLayout(vkDevice, tPLayoutInfo, null, pHandle), "vkCreatePipelineLayout(restirTemporal)");
            this.temporalPipeLayout = pHandle.get(0);
            this.temporalPipeline = createComputePipeline(stack, temporalSpv, temporalPipeLayout);

            // Spatial layout: 0=UBO, 1=SSBO, 2..14=13 samplers, 15=1 storage image
            VkDescriptorSetLayoutBinding.Buffer sBindings = VkDescriptorSetLayoutBinding.calloc(16, stack);
            bind(sBindings.get(0), 0, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            bind(sBindings.get(1), 1, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            for (int i = 2; i <= 14; i++) bind(sBindings.get(i), i, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            bind(sBindings.get(15), 15, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);

            VkDescriptorSetLayoutCreateInfo sLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(sBindings);
            checkVk(VK12.vkCreateDescriptorSetLayout(vkDevice, sLayoutInfo, null, pHandle), "vkCreateDescriptorSetLayout(restirSpatial)");
            this.spatialDescLayout = pHandle.get(0);

            VkPipelineLayoutCreateInfo sPLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(spatialDescLayout));
            checkVk(VK12.vkCreatePipelineLayout(vkDevice, sPLayoutInfo, null, pHandle), "vkCreatePipelineLayout(restirSpatial)");
            this.spatialPipeLayout = pHandle.get(0);
            this.spatialPipeline = createComputePipeline(stack, spatialSpv, spatialPipeLayout);

            // Descriptor pool for both passes across RING_SIZE frames
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(4, stack);
            poolSizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(RING_SIZE * 2);
            poolSizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(RING_SIZE * 2);
            poolSizes.get(2).type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(RING_SIZE * (14 + 13));
            poolSizes.get(3).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(RING_SIZE * (4 + 1));

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(RING_SIZE * 2).pPoolSizes(poolSizes);
            checkVk(VK12.vkCreateDescriptorPool(vkDevice, poolInfo, null, pHandle), "vkCreateDescriptorPool(restir)");
            this.descriptorPool = pHandle.get(0);

            allocateSets(stack, temporalDescLayout, temporalDescSets);
            allocateSets(stack, spatialDescLayout, spatialDescSets);

            for (int i = 0; i < RING_SIZE; i++) {
                VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .size(SSBO_BYTES)
                        .usage(VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
                        .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
                VmaAllocationCreateInfo vmaAlloc = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO)
                        .flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);

                LongBuffer pBuf = stack.mallocLong(1);
                PointerBuffer pAlloc = stack.mallocPointer(1);
                checkVk(Vma.vmaCreateBuffer(vmaAllocator, bufInfo, vmaAlloc, pBuf, pAlloc, null), "vmaCreateBuffer(restirSSBO)");
                ssboBuffers[i] = pBuf.get(0);
                ssboAllocs[i] = pAlloc.get(0);
            }
        }

        this.initialized = true;
        RaindioClientMod.LOGGER.info("[Raindio] Initialized Native Vulkan ReSTIR DI + ReSTIR GI Compute pipeline (512 lights, 80m scan, O(1) visibility + spatiotemporal GI).");
    }

    private void allocateSets(MemoryStack stack, long layout, long[] outSets) {
        LongBuffer layouts = stack.mallocLong(RING_SIZE);
        for (int i = 0; i < RING_SIZE; i++) layouts.put(i, layout);
        VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                .sType$Default().descriptorPool(descriptorPool).pSetLayouts(layouts);
        LongBuffer setsOut = stack.mallocLong(RING_SIZE);
        checkVk(VK12.vkAllocateDescriptorSets(vkDevice, allocInfo, setsOut), "vkAllocateDescriptorSets(restir)");
        for (int i = 0; i < RING_SIZE; i++) outSets[i] = setsOut.get(i);
    }

    private long createComputePipeline(MemoryStack stack, byte[] spvBytes, long pLayout) {
        ByteBuffer codeBuf = MemoryUtil.memAlloc(spvBytes.length);
        long shaderModule = VK12.VK_NULL_HANDLE;
        try {
            codeBuf.put(spvBytes).flip();
            VkShaderModuleCreateInfo modInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(codeBuf);
            LongBuffer pMod = stack.mallocLong(1);
            checkVk(VK12.vkCreateShaderModule(vkDevice, modInfo, null, pMod), "vkCreateShaderModule(restir)");
            shaderModule = pMod.get(0);

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pipeInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipeInfo.get(0).sType$Default().stage(stageInfo).layout(pLayout);

            LongBuffer pPipe = stack.mallocLong(1);
            checkVk(VK12.vkCreateComputePipelines(vkDevice, VK12.VK_NULL_HANDLE, pipeInfo, null, pPipe), "vkCreateComputePipelines(restir)");
            return pPipe.get(0);
        } finally {
            if (shaderModule != VK12.VK_NULL_HANDLE) VK12.vkDestroyShaderModule(vkDevice, shaderModule, null);
            MemoryUtil.memFree(codeBuf);
        }
    }

    private void ensureOutputImages(int w, int h) {
        int safeW = Math.max(2, w);
        int safeH = Math.max(2, h);
        if (safeW == outWidth && safeH == outHeight && outImage != VK12.VK_NULL_HANDLE) {
            return;
        }
        destroyOutputImages();

        this.outWidth = safeW;
        this.outHeight = safeH;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long[] out = create2DImage(stack, safeW, safeH, VK12.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            this.outImage = out[0]; this.outAlloc = out[1]; this.outView = out[2];

            int resUsage = VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_SAMPLED_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            for (int i = 0; i < 2; i++) {
                long[] d = create2DImage(stack, safeW, safeH, VK12.VK_FORMAT_R32G32B32A32_SFLOAT, resUsage);
                diResImg[i] = d[0]; diResAlloc[i] = d[1]; diResView[i] = d[2];

                long[] gp = create2DImage(stack, safeW, safeH, VK12.VK_FORMAT_R32G32B32A32_SFLOAT, resUsage);
                giPosImg[i] = gp[0]; giPosAlloc[i] = gp[1]; giPosView[i] = gp[2];

                long[] gr = create2DImage(stack, safeW, safeH, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, resUsage);
                giRadImg[i] = gr[0]; giRadAlloc[i] = gr[1]; giRadView[i] = gr[2];

                long[] g = create2DImage(stack, safeW, safeH, VK12.VK_FORMAT_R16G16B16A16_SFLOAT, resUsage);
                geoImg[i] = g[0]; geoAlloc[i] = g[1]; geoView[i] = g[2];
            }
        }
        this.imagesTransitioned = false;
        this.historyValid = false;
    }

    private long[] create2DImage(MemoryStack stack, int w, int h, int format, int usage) {
        VkImageCreateInfo imgInfo = VkImageCreateInfo.calloc(stack)
                .sType$Default()
                .imageType(VK12.VK_IMAGE_TYPE_2D)
                .format(format)
                .mipLevels(1).arrayLayers(1)
                .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                .usage(usage)
                .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
        imgInfo.extent().set(w, h, 1);

        VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);

        LongBuffer pImg = stack.mallocLong(1);
        PointerBuffer pAlloc = stack.mallocPointer(1);
        checkVk(Vma.vmaCreateImage(vmaAllocator, imgInfo, allocInfo, pImg, pAlloc, null), "vmaCreateImage(restir)");
        long img = pImg.get(0);
        long alloc = pAlloc.get(0);

        VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(img)
                .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
        viewInfo.subresourceRange()
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);

        LongBuffer pView = stack.mallocLong(1);
        checkVk(VK12.vkCreateImageView(vkDevice, viewInfo, null, pView), "vkCreateImageView(restir)");
        return new long[] { img, alloc, pView.get(0) };
    }

    private void updateTemporalDescriptorSet(
            MemoryStack stack, long set, long frameUbo, long ssboBuf,
            long c0View, long c1View, long c2View, long c6View, long d0View,
            long prevDiView, long prevGiPosView, long prevGiRadView, long prevGeoView,
            long nearestSamp, long linearSamp,
            long lpvShRView, long lpvShGView, long lpvShBView, long lpvLinearSamp,
            long voxelMaskView, long subVoxelView, long voxelNearestSamp,
            long curDiView, long curGiPosView, long curGiRadView, long curGeoView
    ) {
        VkDescriptorBufferInfo.Buffer uboInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(frameUbo).offset(0).range(RaindioUniformManager.FRAME_UBO_SIZE);
        VkDescriptorBufferInfo.Buffer ssboInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(ssboBuf).offset(0).range(SSBO_BYTES);

        VkDescriptorImageInfo.Buffer imgs = VkDescriptorImageInfo.calloc(18, stack);
        imgs.get(0).sampler(nearestSamp).imageView(c0View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(1).sampler(nearestSamp).imageView(c1View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(2).sampler(nearestSamp).imageView(c2View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(3).sampler(linearSamp).imageView(c6View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(4).sampler(nearestSamp).imageView(d0View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(5).sampler(nearestSamp).imageView(prevDiView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(6).sampler(nearestSamp).imageView(prevGiPosView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(7).sampler(nearestSamp).imageView(prevGiRadView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(8).sampler(nearestSamp).imageView(prevGeoView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(9).sampler(lpvLinearSamp).imageView(lpvShRView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(10).sampler(lpvLinearSamp).imageView(lpvShGView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(11).sampler(lpvLinearSamp).imageView(lpvShBView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(12).sampler(voxelNearestSamp).imageView(voxelMaskView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(13).sampler(voxelNearestSamp).imageView(subVoxelView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(14).sampler(VK12.VK_NULL_HANDLE).imageView(curDiView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(15).sampler(VK12.VK_NULL_HANDLE).imageView(curGiPosView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(16).sampler(VK12.VK_NULL_HANDLE).imageView(curGiRadView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(17).sampler(VK12.VK_NULL_HANDLE).imageView(curGeoView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(20, stack);
        writes.get(0).sType$Default().dstSet(set).dstBinding(0)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(uboInfo);
        writes.get(1).sType$Default().dstSet(set).dstBinding(1)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(ssboInfo);
        for (int i = 0; i < 14; i++) {
            writes.get(2 + i).sType$Default().dstSet(set).dstBinding(2 + i)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(imgs.address(i), 1));
        }
        for (int i = 0; i < 4; i++) {
            writes.get(16 + i).sType$Default().dstSet(set).dstBinding(16 + i)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(imgs.address(14 + i), 1));
        }
        VK12.vkUpdateDescriptorSets(vkDevice, writes, null);
    }

    private void updateSpatialDescriptorSet(
            MemoryStack stack, long set, long frameUbo, long ssboBuf,
            long c1View, long c2View, long c3View, long d0View,
            long inDiView, long inGiPosView, long inGiRadView, long inGeoView,
            long nearestSamp,
            long lpvShRView, long lpvShGView, long lpvShBView, long lpvLinearSamp,
            long voxelMaskView, long subVoxelView, long voxelNearestSamp,
            long outStorageView
    ) {
        VkDescriptorBufferInfo.Buffer uboInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(frameUbo).offset(0).range(RaindioUniformManager.FRAME_UBO_SIZE);
        VkDescriptorBufferInfo.Buffer ssboInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(ssboBuf).offset(0).range(SSBO_BYTES);

        VkDescriptorImageInfo.Buffer imgs = VkDescriptorImageInfo.calloc(14, stack);
        imgs.get(0).sampler(nearestSamp).imageView(c1View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(1).sampler(nearestSamp).imageView(c2View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(2).sampler(nearestSamp).imageView(c3View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(3).sampler(nearestSamp).imageView(d0View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(4).sampler(nearestSamp).imageView(inDiView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(5).sampler(nearestSamp).imageView(inGiPosView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(6).sampler(nearestSamp).imageView(inGiRadView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(7).sampler(nearestSamp).imageView(inGeoView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(8).sampler(lpvLinearSamp).imageView(lpvShRView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(9).sampler(lpvLinearSamp).imageView(lpvShGView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(10).sampler(lpvLinearSamp).imageView(lpvShBView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(11).sampler(voxelNearestSamp).imageView(voxelMaskView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(12).sampler(voxelNearestSamp).imageView(subVoxelView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(13).sampler(VK12.VK_NULL_HANDLE).imageView(outStorageView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(16, stack);
        writes.get(0).sType$Default().dstSet(set).dstBinding(0)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(uboInfo);
        writes.get(1).sType$Default().dstSet(set).dstBinding(1)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(ssboInfo);
        for (int i = 0; i < 13; i++) {
            writes.get(2 + i).sType$Default().dstSet(set).dstBinding(2 + i)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(imgs.address(i), 1));
        }
        writes.get(15).sType$Default().dstSet(set).dstBinding(15)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.create(imgs.address(13), 1));
        VK12.vkUpdateDescriptorSets(vkDevice, writes, null);
    }

    private static void clearAndInitImage(VkCommandBuffer cmd, MemoryStack stack, long image) {
        transitionImage(cmd, stack, image,
                VK12.VK_IMAGE_LAYOUT_UNDEFINED, VK12.VK_IMAGE_LAYOUT_GENERAL,
                0, VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkClearColorValue clearVal = VkClearColorValue.calloc(stack);
        clearVal.float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 0.0f);
        VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
                .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
        VK12.vkCmdClearColorImage(cmd, image, VK12.VK_IMAGE_LAYOUT_GENERAL, clearVal, range);
        transitionImage(cmd, stack, image,
                VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_ACCESS_TRANSFER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    private static void barrierComputeWriteToRead(VkCommandBuffer cmd, MemoryStack stack, long image) {
        transitionImage(cmd, stack, image,
                VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_SHADER_READ_BIT,
                VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    private static void transitionImage(
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

    private static long unwrapBuffer(GpuBuffer buf) { return (buf instanceof VulkanGpuBuffer vb) ? vb.vkBuffer() : 0L; }
    private static long unwrapImageView(GpuTextureView view) { return (view instanceof VulkanGpuTextureView vv) ? vv.vkImageView() : 0L; }
    private static long unwrapImage(GpuTexture tex) { return (tex instanceof VulkanGpuTexture vt) ? vt.vkImage() : 0L; }
    private static long unwrapSampler(GpuSampler samp) { return (samp instanceof VulkanGpuSampler vs) ? vs.vkSampler() : 0L; }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = RaindioClientMod.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing Compute SPIR-V resource: " + path);
            return in.readAllBytes();
        }
    }

    private static void checkVk(int result, String op) {
        if (result != VK12.VK_SUCCESS) throw new IllegalStateException("Vulkan call failed: " + op + " (VkResult=" + result + ")");
    }

    private void destroy2DImage(long img, long alloc, long view) {
        if (view != VK12.VK_NULL_HANDLE) VK12.vkDestroyImageView(vkDevice, view, null);
        if (img != VK12.VK_NULL_HANDLE) Vma.vmaDestroyImage(vmaAllocator, img, alloc);
    }

    private void destroyOutputImages() {
        if (vkDevice == null) return;
        if (outImage != VK12.VK_NULL_HANDLE) VK12.vkDeviceWaitIdle(vkDevice);
        destroy2DImage(outImage, outAlloc, outView);
        outImage = outView = VK12.VK_NULL_HANDLE; outAlloc = 0L;
        for (int i = 0; i < 2; i++) {
            destroy2DImage(diResImg[i], diResAlloc[i], diResView[i]); diResImg[i] = diResView[i] = VK12.VK_NULL_HANDLE; diResAlloc[i] = 0L;
            destroy2DImage(giPosImg[i], giPosAlloc[i], giPosView[i]); giPosImg[i] = giPosView[i] = VK12.VK_NULL_HANDLE; giPosAlloc[i] = 0L;
            destroy2DImage(giRadImg[i], giRadAlloc[i], giRadView[i]); giRadImg[i] = giRadView[i] = VK12.VK_NULL_HANDLE; giRadAlloc[i] = 0L;
            destroy2DImage(geoImg[i],   geoAlloc[i],   geoView[i]);   geoImg[i]   = geoView[i]   = VK12.VK_NULL_HANDLE; geoAlloc[i]   = 0L;
        }
        imagesTransitioned = false;
        historyValid = false;
    }

    @Override
    public void close() {
        sectionCache.clear();
        cachedSectionKeys.clear();
        activeLights.clear();
        candidatePool.clear();
        lastLevel = null;

        if (vkDevice != null) {
            VK12.vkDeviceWaitIdle(vkDevice);
            destroyOutputImages();
            for (int i = 0; i < RING_SIZE; i++) {
                if (ssboBuffers[i] != VK12.VK_NULL_HANDLE) {
                    Vma.vmaDestroyBuffer(vmaAllocator, ssboBuffers[i], ssboAllocs[i]);
                    ssboBuffers[i] = VK12.VK_NULL_HANDLE;
                    ssboAllocs[i] = 0L;
                }
            }
            if (temporalPipeline   != VK12.VK_NULL_HANDLE) { VK12.vkDestroyPipeline(vkDevice, temporalPipeline, null); temporalPipeline = VK12.VK_NULL_HANDLE; }
            if (temporalPipeLayout != VK12.VK_NULL_HANDLE) { VK12.vkDestroyPipelineLayout(vkDevice, temporalPipeLayout, null); temporalPipeLayout = VK12.VK_NULL_HANDLE; }
            if (temporalDescLayout != VK12.VK_NULL_HANDLE) { VK12.vkDestroyDescriptorSetLayout(vkDevice, temporalDescLayout, null); temporalDescLayout = VK12.VK_NULL_HANDLE; }
            if (spatialPipeline    != VK12.VK_NULL_HANDLE) { VK12.vkDestroyPipeline(vkDevice, spatialPipeline, null); spatialPipeline = VK12.VK_NULL_HANDLE; }
            if (spatialPipeLayout  != VK12.VK_NULL_HANDLE) { VK12.vkDestroyPipelineLayout(vkDevice, spatialPipeLayout, null); spatialPipeLayout = VK12.VK_NULL_HANDLE; }
            if (spatialDescLayout  != VK12.VK_NULL_HANDLE) { VK12.vkDestroyDescriptorSetLayout(vkDevice, spatialDescLayout, null); spatialDescLayout = VK12.VK_NULL_HANDLE; }
            if (descriptorPool     != VK12.VK_NULL_HANDLE) { VK12.vkDestroyDescriptorPool(vkDevice, descriptorPool, null); descriptorPool = VK12.VK_NULL_HANDLE; }
        }
        initialized = false;
        outWidth = -1;
        outHeight = -1;
    }
}
