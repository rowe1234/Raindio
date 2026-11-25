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
import com.raindio.mixin.VulkanCommandEncoderAccessor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK12;
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
 * Native Vulkan 3D World-Space Ray-Traced Reflections Compute Manager.
 * Replaces screen-space SSR with 64^3 3D Voxel DDA + 4x4x4 Sub-Voxel + Shadow-Map + LPV + Sky RT reflections,
 * writing directly into att.colortex9().rgb while preserving colortex9.a (GTAO from composite2).
 */
public final class RaindioRtReflectionManager implements AutoCloseable {
    private static final String REFL_SPV = "/assets/raindio/shaders/spirv/compute/rt_reflections.comp.spv";
    private static final int RING_SIZE = 4;

    private VkDevice vkDevice;
    private long vmaAllocator;
    private long descLayout = VK12.VK_NULL_HANDLE;
    private long pipeLayout = VK12.VK_NULL_HANDLE;
    private long pipeline   = VK12.VK_NULL_HANDLE;
    private long descriptorPool = VK12.VK_NULL_HANDLE;
    private final long[] descSets = new long[RING_SIZE];
    private int ringIndex = 0;

    private int outWidth = -1;
    private int outHeight = -1;
    private long outImage = VK12.VK_NULL_HANDLE;
    private long outAlloc = 0L;
    private long outView  = VK12.VK_NULL_HANDLE;
    private boolean imageTransitioned = false;

    private boolean initialized = false;
    private boolean unavailable = false;

    public boolean isAvailable() {
        return !unavailable;
    }

    public boolean dispatch(
            CommandEncoder encoder,
            RaindioAttachments att,
            RaindioUniformManager uniforms,
            RaindioNoiseTexture noise,
            RaindioLpvManager lpv
    ) {
        if (unavailable || att == null || uniforms == null || noise == null || lpv == null || !lpv.isReady()) {
            return false;
        }

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
                RaindioClientMod.LOGGER.error("[Raindio] Failed to initialize Vulkan RT Reflections pipeline.", t);
                RaindioKeybinds.sendChatAndOverlay(Minecraft.getInstance(), "[Raindio RT Refl Error] " + t.getMessage());
                close();
                return false;
            }
        }

        GpuTexture c9Tex = att.colortex9();
        if (c9Tex == null) return false;
        int w = c9Tex.getWidth(0);
        int h = c9Tex.getHeight(0);
        ensureOutputImage(w, h);
        if (outView == VK12.VK_NULL_HANDLE) return false;

        VkCommandBuffer cmd = accessor.raindio$commandBuffer();
        if (cmd == null) return false;

        long frameUbo = unwrapBuffer(uniforms.frameBuffer());
        long c0View = unwrapImageView(att.colortex0View());
        long c1View = unwrapImageView(att.colortex1View());
        long c3View = unwrapImageView(att.colortex3View());
        long c6View = unwrapImageView(att.colortex6View());
        long c9View = unwrapImageView(att.colortex9View());
        long d0View = unwrapImageView(att.depthtex0View());
        long s0View = unwrapImageView(att.shadowtex0View());
        long noiseView = unwrapImageView(noise.view());
        long farVoxelView = lpv.farVoxelView();
        GpuTextureView blockAtlasGpuView = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        long blockAtlasView = unwrapImageView(blockAtlasGpuView);
        long paletteLutView = lpv.blockPaletteLutView();
        long voxelPaletteView = lpv.voxelPaletteView();
        long farPaletteView = lpv.farPaletteView();
        long dstImage = unwrapImage(c9Tex);
        long nearestSamp = unwrapSampler(att.nearestClampSampler());
        long linearSamp = unwrapSampler(att.linearClampSampler());
        long linearRepeatSamp = unwrapSampler(att.linearRepeatSampler());

        if (frameUbo == 0L || c0View == 0L || c1View == 0L || c3View == 0L || c6View == 0L
                || c9View == 0L || d0View == 0L || s0View == 0L || noiseView == 0L
                || farVoxelView == 0L || blockAtlasView == 0L || paletteLutView == 0L
                || voxelPaletteView == 0L || farPaletteView == 0L || dstImage == 0L) {
            return false;
        }

        int slot = ringIndex;
        ringIndex = (ringIndex + 1) % RING_SIZE;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!imageTransitioned) {
                clearAndInitImage(cmd, stack, outImage);
                imageTransitioned = true;
            }

            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            updateDescriptorSet(
                    stack, descSets[slot], frameUbo,
                    c1View, c3View, c6View, c9View, d0View, s0View, farVoxelView,
                    nearestSamp, linearSamp, linearRepeatSamp,
                    lpv.voxelMaskView(), lpv.subVoxelView(), lpv.emissiveView(), lpv.nearestRepeatSampler(),
                    lpv.shRView(), lpv.shGView(), lpv.shBView(), lpv.linearRepeatSampler(),
                    c0View, noiseView,
                    blockAtlasView, paletteLutView, voxelPaletteView, farPaletteView,
                    outView
            );

            int groupsX = (outWidth + 15) / 16;
            int groupsY = (outHeight + 15) / 16;

            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK12.vkCmdBindDescriptorSets(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    pipeLayout, 0, stack.longs(descSets[slot]), null);
            VK12.vkCmdDispatch(cmd, groupsX, groupsY, 1);

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
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);

            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }

        return true;
    }

    private void initVulkan(VulkanDevice dev) throws IOException {
        this.vkDevice = dev.vkDevice();
        this.vmaAllocator = dev.vma();

        byte[] spvBytes = readResource(REFL_SPV);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pHandle = stack.mallocLong(1);

            // 0=UBO, 1..19=19 samplers, 20=1 storage image
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(21, stack);
            bind(bindings.get(0), 0, VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            for (int i = 1; i <= 19; i++) {
                bind(bindings.get(i), i, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            }
            bind(bindings.get(20), 20, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            checkVk(VK12.vkCreateDescriptorSetLayout(vkDevice, layoutInfo, null, pHandle), "vkCreateDescriptorSetLayout(rtRefl)");
            this.descLayout = pHandle.get(0);

            VkPipelineLayoutCreateInfo pLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(descLayout));
            checkVk(VK12.vkCreatePipelineLayout(vkDevice, pLayoutInfo, null, pHandle), "vkCreatePipelineLayout(rtRefl)");
            this.pipeLayout = pHandle.get(0);

            this.pipeline = createComputePipeline(stack, spvBytes, pipeLayout);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(RING_SIZE);
            poolSizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(RING_SIZE * 19);
            poolSizes.get(2).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(RING_SIZE);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(RING_SIZE).pPoolSizes(poolSizes);
            checkVk(VK12.vkCreateDescriptorPool(vkDevice, poolInfo, null, pHandle), "vkCreateDescriptorPool(rtRefl)");
            this.descriptorPool = pHandle.get(0);

            LongBuffer layouts = stack.mallocLong(RING_SIZE);
            for (int i = 0; i < RING_SIZE; i++) layouts.put(i, descLayout);
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descriptorPool).pSetLayouts(layouts);
            LongBuffer setsOut = stack.mallocLong(RING_SIZE);
            checkVk(VK12.vkAllocateDescriptorSets(vkDevice, allocInfo, setsOut), "vkAllocateDescriptorSets(rtRefl)");
            for (int i = 0; i < RING_SIZE; i++) descSets[i] = setsOut.get(i);
        }

        this.initialized = true;
        RaindioClientMod.LOGGER.info("[Raindio] Initialized Native Vulkan 3D World-Space RT Reflections pipeline.");
    }

    private void ensureOutputImage(int w, int h) {
        int safeW = Math.max(2, w);
        int safeH = Math.max(2, h);
        if (safeW == outWidth && safeH == outHeight && outImage != VK12.VK_NULL_HANDLE) {
            return;
        }
        destroyOutputImage();
        this.outWidth = safeW;
        this.outHeight = safeH;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo imgInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK12.VK_IMAGE_TYPE_2D)
                    .format(VK12.VK_FORMAT_R16G16B16A16_SFLOAT)
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            imgInfo.extent().set(safeW, safeH, 1);

            VmaAllocationCreateInfo allocInfo = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);

            LongBuffer pImg = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            checkVk(Vma.vmaCreateImage(vmaAllocator, imgInfo, allocInfo, pImg, pAlloc, null), "vmaCreateImage(rtRefl)");
            this.outImage = pImg.get(0);
            this.outAlloc = pAlloc.get(0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(outImage)
                    .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK12.VK_FORMAT_R16G16B16A16_SFLOAT);
            viewInfo.subresourceRange()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);

            LongBuffer pView = stack.mallocLong(1);
            checkVk(VK12.vkCreateImageView(vkDevice, viewInfo, null, pView), "vkCreateImageView(rtRefl)");
            this.outView = pView.get(0);
        }
        this.imageTransitioned = false;
    }

    private void updateDescriptorSet(
            MemoryStack stack, long set, long frameUbo,
            long c1View, long c3View, long c6View, long c9View, long d0View, long s0View, long farVoxelView,
            long nearestSamp, long linearSamp, long linearRepeatSamp,
            long voxelMaskView, long subVoxelView, long emissiveView, long voxelNearestSamp,
            long shRView, long shGView, long shBView, long lpvLinearSamp,
            long c0View, long noiseView,
            long blockAtlasView, long paletteLutView, long voxelPaletteView, long farPaletteView,
            long outStorageView
    ) {
        VkDescriptorBufferInfo.Buffer uboInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(frameUbo).offset(0).range(RaindioUniformManager.FRAME_UBO_SIZE);

        VkDescriptorImageInfo.Buffer imgs = VkDescriptorImageInfo.calloc(20, stack);
        imgs.get(0).sampler(nearestSamp).imageView(c1View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(1).sampler(linearSamp).imageView(c3View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(2).sampler(linearSamp).imageView(c6View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(3).sampler(nearestSamp).imageView(c9View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(4).sampler(nearestSamp).imageView(d0View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(5).sampler(nearestSamp).imageView(s0View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(6).sampler(voxelNearestSamp).imageView(farVoxelView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(7).sampler(voxelNearestSamp).imageView(voxelMaskView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(8).sampler(voxelNearestSamp).imageView(subVoxelView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(9).sampler(voxelNearestSamp).imageView(emissiveView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(10).sampler(lpvLinearSamp).imageView(shRView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(11).sampler(lpvLinearSamp).imageView(shGView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(12).sampler(lpvLinearSamp).imageView(shBView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(13).sampler(nearestSamp).imageView(c0View).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(14).sampler(linearRepeatSamp).imageView(noiseView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(15).sampler(nearestSamp).imageView(blockAtlasView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(16).sampler(nearestSamp).imageView(paletteLutView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(17).sampler(voxelNearestSamp).imageView(voxelPaletteView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(18).sampler(voxelNearestSamp).imageView(farPaletteView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        imgs.get(19).sampler(VK12.VK_NULL_HANDLE).imageView(outStorageView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(21, stack);
        writes.get(0).sType$Default().dstSet(set).dstBinding(0)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).pBufferInfo(uboInfo);
        for (int i = 0; i < 19; i++) {
            writes.get(1 + i).sType$Default().dstSet(set).dstBinding(1 + i)
                    .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(imgs.address(i), 1));
        }
        writes.get(20).sType$Default().dstSet(set).dstBinding(20)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1)
                .pImageInfo(VkDescriptorImageInfo.create(imgs.address(19), 1));
        VK12.vkUpdateDescriptorSets(vkDevice, writes, null);
    }

    private long createComputePipeline(MemoryStack stack, byte[] spvBytes, long pLayout) {
        ByteBuffer codeBuf = MemoryUtil.memAlloc(spvBytes.length);
        long shaderModule = VK12.VK_NULL_HANDLE;
        try {
            codeBuf.put(spvBytes).flip();
            VkShaderModuleCreateInfo modInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(codeBuf);
            LongBuffer pMod = stack.mallocLong(1);
            checkVk(VK12.vkCreateShaderModule(vkDevice, modInfo, null, pMod), "vkCreateShaderModule(rtRefl)");
            shaderModule = pMod.get(0);

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default()
                    .stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer pipeInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipeInfo.get(0).sType$Default().stage(stageInfo).layout(pLayout);

            LongBuffer pPipe = stack.mallocLong(1);
            checkVk(VK12.vkCreateComputePipelines(vkDevice, VK12.VK_NULL_HANDLE, pipeInfo, null, pPipe), "vkCreateComputePipelines(rtRefl)");
            return pPipe.get(0);
        } finally {
            if (shaderModule != VK12.VK_NULL_HANDLE) VK12.vkDestroyShaderModule(vkDevice, shaderModule, null);
            MemoryUtil.memFree(codeBuf);
        }
    }

    private static void clearAndInitImage(VkCommandBuffer cmd, MemoryStack stack, long image) {
        transitionImage(cmd, stack, image,
                VK12.VK_IMAGE_LAYOUT_UNDEFINED, VK12.VK_IMAGE_LAYOUT_GENERAL,
                0, VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkClearColorValue clearVal = VkClearColorValue.calloc(stack);
        clearVal.float32(0, 0.0f).float32(1, 0.0f).float32(2, 0.0f).float32(3, 1.0f);
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

    private void destroyOutputImage() {
        if (vkDevice == null) return;
        if (outImage != VK12.VK_NULL_HANDLE) VK12.vkDeviceWaitIdle(vkDevice);
        if (outView != VK12.VK_NULL_HANDLE) VK12.vkDestroyImageView(vkDevice, outView, null);
        if (outImage != VK12.VK_NULL_HANDLE) Vma.vmaDestroyImage(vmaAllocator, outImage, outAlloc);
        outImage = outView = VK12.VK_NULL_HANDLE;
        outAlloc = 0L;
        imageTransitioned = false;
    }

    @Override
    public void close() {
        if (vkDevice != null) {
            VK12.vkDeviceWaitIdle(vkDevice);
            destroyOutputImage();
            if (pipeline != VK12.VK_NULL_HANDLE) { VK12.vkDestroyPipeline(vkDevice, pipeline, null); pipeline = VK12.VK_NULL_HANDLE; }
            if (pipeLayout != VK12.VK_NULL_HANDLE) { VK12.vkDestroyPipelineLayout(vkDevice, pipeLayout, null); pipeLayout = VK12.VK_NULL_HANDLE; }
            if (descLayout != VK12.VK_NULL_HANDLE) { VK12.vkDestroyDescriptorSetLayout(vkDevice, descLayout, null); descLayout = VK12.VK_NULL_HANDLE; }
            if (descriptorPool != VK12.VK_NULL_HANDLE) { VK12.vkDestroyDescriptorPool(vkDevice, descriptorPool, null); descriptorPool = VK12.VK_NULL_HANDLE; }
        }
        initialized = false;
        outWidth = -1;
        outHeight = -1;
    }
}
