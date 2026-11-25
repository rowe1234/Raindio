package com.raindio.vulkan;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.raindio.RaindioClientMod;
import com.raindio.mixin.VulkanCommandEncoderAccessor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
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
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

/**
 * Native Vulkan Compute GPU Luminance Histogram Auto-Exposure (Apple M1 MoltenVK compatible):
 * - Pass 1 (histogram_build.comp, 16x16 workgroups): 2x2-strided center-weighted 256-bin log2 histogram
 * - Pass 2 (histogram_adapt.comp, 256x1 workgroup): trimmed weighted mean (15%..90%) + asymmetric
 *   exponential eye adaptation (2.6/s dark->bright, 1.3/s bright->dark) -> persistent 1x1 RGBA16F texture
 * - Copies 1x1 adapted exposure into RaindioAttachments.autoExposureTex for composite5.frag.
 */
public final class RaindioAutoExposure implements AutoCloseable {
    private static final String BUILD_SPV = "/assets/raindio/shaders/spirv/compute/histogram_build.comp.spv";
    private static final String ADAPT_SPV = "/assets/raindio/shaders/spirv/compute/histogram_adapt.comp.spv";
    private static final int RING_SIZE = 4;
    private static final int HIST_BYTES = 256 * 4 + 16; // 256 uint bins + 4 float persistent exposure state
    private static final int ADAPT_PUSH_BYTES = 16; // float dt, int resetFlag, float keyValue, float pad
    private static final float KEY_VALUE = 0.185f;

    private VkDevice vkDevice;
    private long vmaAllocator;

    private long buildDescLayout = VK12.VK_NULL_HANDLE;
    private long buildPipelineLayout = VK12.VK_NULL_HANDLE;
    private long buildPipeline = VK12.VK_NULL_HANDLE;

    private long adaptDescLayout = VK12.VK_NULL_HANDLE;
    private long adaptPipelineLayout = VK12.VK_NULL_HANDLE;
    private long adaptPipeline = VK12.VK_NULL_HANDLE;

    private long descriptorPool = VK12.VK_NULL_HANDLE;
    private final long[] buildDescSets = new long[RING_SIZE];
    private final long[] adaptDescSets = new long[RING_SIZE];
    private int ringIndex = 0;

    private long histBuffer = VK12.VK_NULL_HANDLE;
    private long histAlloc = 0L;

    private long adaptImage = VK12.VK_NULL_HANDLE;
    private long adaptAlloc = 0L;
    private long adaptView = VK12.VK_NULL_HANDLE;

    private boolean resourcesTransitioned = false;
    private boolean initialized = false;
    private boolean unavailable = false;

    private ClientLevel lastLevel = null;
    private long lastFrameNanos = 0L;

    public boolean isAvailable() {
        return !unavailable;
    }

    public boolean dispatch(CommandEncoder encoder, RaindioAttachments att) {
        return dispatch(encoder, att, att.colortex0ScratchView(), att.width(), att.height());
    }

    public boolean dispatch(CommandEncoder encoder, RaindioAttachments att, GpuTextureView hdrSourceView) {
        return dispatch(encoder, att, hdrSourceView, att.width(), att.height());
    }

    /**
     * Builds the 256-bin luminance histogram from the post-HDR-TAA/TAAU (or composite3) HDR view,
     * adapts the persistent 1x1 exposure image, and copies it into att.autoExposureTex().
     */
    public boolean dispatch(CommandEncoder encoder, RaindioAttachments att, GpuTextureView hdrSourceView, int srcWidth, int srcHeight) {
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
                RaindioClientMod.LOGGER.error("[Raindio] Failed to initialize GPU Histogram Auto-Exposure — falling back to eyeBrightnessSmooth.", t);
                close();
                return false;
            }
        }

        VkCommandBuffer cmd = accessor.raindio$commandBuffer();
        if (cmd == null) return false;

        GpuTextureView srcView = (hdrSourceView != null) ? hdrSourceView : att.colortex0ScratchView();
        long hdrView = unwrapImageView(srcView);
        long nearestSamp = unwrapSampler(att.nearestClampSampler());
        long dstTex = unwrapImage(att.autoExposureTex());
        if (hdrView == 0L || nearestSamp == 0L || dstTex == 0L) return false;

        long nowNanos = System.nanoTime();
        float dt = (lastFrameNanos == 0L) ? 0.016f : (float) ((nowNanos - lastFrameNanos) * 1.0e-9);
        dt = Math.max(0.001f, Math.min(0.1f, dt));
        lastFrameNanos = nowNanos;

        ClientLevel curLevel = Minecraft.getInstance().level;
        boolean needsReset = !resourcesTransitioned || (curLevel != lastLevel);
        lastLevel = curLevel;

        int slot = ringIndex;
        ringIndex = (ringIndex + 1) % RING_SIZE;
        long buildSet = buildDescSets[slot];
        long adaptSet = adaptDescSets[slot];

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 0. One-time clear of histBuffer and layout transition of 1x1 adaptImage
            if (!resourcesTransitioned) {
                VK12.vkCmdFillBuffer(cmd, histBuffer, 0, HIST_BYTES, 0);
                bufferBarrier(cmd, stack, histBuffer,
                        VK12.VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                        VK12.VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

                transitionImage(cmd, stack, adaptImage,
                        VK12.VK_IMAGE_LAYOUT_UNDEFINED, VK12.VK_IMAGE_LAYOUT_GENERAL,
                        0, VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                        VK12.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                resourcesTransitioned = true;
            }

            // Ensure composite3 HDR output in colortex0Scratch is visible to compute shader reads
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            // 1. Pass 1: histogram_build.comp (2x2 stride over internal resolution)
            updateBuildDescriptorSet(stack, buildSet, hdrView, nearestSamp);
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, buildPipeline);
            VK12.vkCmdBindDescriptorSets(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    buildPipelineLayout, 0, stack.longs(buildSet), null);

            int safeW = Math.max(2, srcWidth);
            int safeH = Math.max(2, srcHeight);
            int sampleW = (safeW + 1) / 2;
            int sampleH = (safeH + 1) / 2;
            VK12.vkCmdDispatch(cmd, (sampleW + 15) / 16, (sampleH + 15) / 16, 1);

            // Barrier: histogram_build atomicAdd writes -> histogram_adapt reads & clear
            bufferBarrier(cmd, stack, histBuffer,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            // 2. Pass 2: histogram_adapt.comp (1 workgroup of 256 threads)
            updateAdaptDescriptorSet(stack, adaptSet);
            VK12.vkCmdBindPipeline(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE, adaptPipeline);
            VK12.vkCmdBindDescriptorSets(cmd, VK12.VK_PIPELINE_BIND_POINT_COMPUTE,
                    adaptPipelineLayout, 0, stack.longs(adaptSet), null);

            ByteBuffer pushBuf = stack.malloc(ADAPT_PUSH_BYTES);
            pushBuf.putFloat(0, dt);
            pushBuf.putInt(4, needsReset ? 1 : 0);
            pushBuf.putFloat(8, KEY_VALUE);
            pushBuf.putFloat(12, 0.0f);
            VK12.vkCmdPushConstants(cmd, adaptPipelineLayout, VK12.VK_SHADER_STAGE_COMPUTE_BIT, 0, pushBuf);
            VK12.vkCmdDispatch(cmd, 1, 1, 1);

            // Ensure SSBO bin zeroing & persistent adaptedExp writes are visible to next frame's passes
            bufferBarrier(cmd, stack, histBuffer,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_ACCESS_SHADER_READ_BIT | VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            // 3. Copy 1x1 adaptImage -> RenderPearl att.autoExposureTex (GENERAL)
            transitionImage(cmd, stack, adaptImage,
                    VK12.VK_IMAGE_LAYOUT_GENERAL, VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK12.VK_ACCESS_SHADER_WRITE_BIT, VK12.VK_ACCESS_TRANSFER_READ_BIT,
                    VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK12.VK_PIPELINE_STAGE_TRANSFER_BIT);

            VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
            region.srcSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.srcOffset().set(0, 0, 0);
            region.dstSubresource().aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.dstOffset().set(0, 0, 0);
            region.extent().set(1, 1, 1);

            VK12.vkCmdCopyImage(
                    cmd,
                    adaptImage, VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dstTex, VK12.VK_IMAGE_LAYOUT_GENERAL,
                    region
            );

            transitionImage(cmd, stack, adaptImage,
                    VK12.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK12.VK_IMAGE_LAYOUT_GENERAL,
                    VK12.VK_ACCESS_TRANSFER_READ_BIT, VK12.VK_ACCESS_SHADER_WRITE_BIT,
                    VK12.VK_PIPELINE_STAGE_TRANSFER_BIT, VK12.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

            // Ensure att.autoExposureTex is visible to composite5 fragment sampling
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }

        return true;
    }

    private void initVulkan(VulkanDevice dev) throws IOException {
        this.vkDevice = dev.vkDevice();
        this.vmaAllocator = dev.vma();

        byte[] buildBytes = readResource(BUILD_SPV);
        byte[] adaptBytes = readResource(ADAPT_SPV);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pHandle = stack.mallocLong(1);

            // Build layout: 0=uHdrScene (sampler2D), 1=HistogramBuffer (SSBO)
            VkDescriptorSetLayoutBinding.Buffer buildBindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bind(buildBindings.get(0), 0, VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            bind(buildBindings.get(1), 1, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            VkDescriptorSetLayoutCreateInfo buildLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(buildBindings);
            checkVk(VK12.vkCreateDescriptorSetLayout(vkDevice, buildLayoutInfo, null, pHandle), "vkCreateDescriptorSetLayout(histBuild)");
            this.buildDescLayout = pHandle.get(0);

            // Adapt layout: 0=HistogramBuffer (SSBO), 1=uAdaptImg (STORAGE_IMAGE)
            VkDescriptorSetLayoutBinding.Buffer adaptBindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            bind(adaptBindings.get(0), 0, VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            bind(adaptBindings.get(1), 1, VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE);
            VkDescriptorSetLayoutCreateInfo adaptLayoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(adaptBindings);
            checkVk(VK12.vkCreateDescriptorSetLayout(vkDevice, adaptLayoutInfo, null, pHandle), "vkCreateDescriptorSetLayout(histAdapt)");
            this.adaptDescLayout = pHandle.get(0);

            VkPipelineLayoutCreateInfo buildPLayout = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(buildDescLayout));
            checkVk(VK12.vkCreatePipelineLayout(vkDevice, buildPLayout, null, pHandle), "vkCreatePipelineLayout(histBuild)");
            this.buildPipelineLayout = pHandle.get(0);

            VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
            pushRange.get(0).stageFlags(VK12.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(ADAPT_PUSH_BYTES);
            VkPipelineLayoutCreateInfo adaptPLayout = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(adaptDescLayout)).pPushConstantRanges(pushRange);
            checkVk(VK12.vkCreatePipelineLayout(vkDevice, adaptPLayout, null, pHandle), "vkCreatePipelineLayout(histAdapt)");
            this.adaptPipelineLayout = pHandle.get(0);

            this.buildPipeline = createComputePipeline(stack, buildBytes, buildPipelineLayout);
            this.adaptPipeline = createComputePipeline(stack, adaptBytes, adaptPipelineLayout);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(3, stack);
            poolSizes.get(0).type(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(RING_SIZE);
            poolSizes.get(1).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(RING_SIZE * 2);
            poolSizes.get(2).type(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(RING_SIZE);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().maxSets(RING_SIZE * 2).pPoolSizes(poolSizes);
            checkVk(VK12.vkCreateDescriptorPool(vkDevice, poolInfo, null, pHandle), "vkCreateDescriptorPool(autoExp)");
            this.descriptorPool = pHandle.get(0);

            LongBuffer layouts = stack.mallocLong(RING_SIZE);
            LongBuffer setsOut = stack.mallocLong(RING_SIZE);
            for (int i = 0; i < RING_SIZE; i++) layouts.put(i, buildDescLayout);
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descriptorPool).pSetLayouts(layouts);
            checkVk(VK12.vkAllocateDescriptorSets(vkDevice, allocInfo, setsOut), "vkAllocateDescriptorSets(histBuild)");
            for (int i = 0; i < RING_SIZE; i++) buildDescSets[i] = setsOut.get(i);

            for (int i = 0; i < RING_SIZE; i++) layouts.put(i, adaptDescLayout);
            allocInfo.pSetLayouts(layouts);
            checkVk(VK12.vkAllocateDescriptorSets(vkDevice, allocInfo, setsOut), "vkAllocateDescriptorSets(histAdapt)");
            for (int i = 0; i < RING_SIZE; i++) adaptDescSets[i] = setsOut.get(i);

            // Allocate 256-uint device-local histogram SSBO
            VkBufferCreateInfo bufInfo = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(HIST_BYTES)
                    .usage(VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo bufAlloc = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            PointerBuffer pAlloc = stack.mallocPointer(1);
            checkVk(Vma.vmaCreateBuffer(vmaAllocator, bufInfo, bufAlloc, pHandle, pAlloc, null), "vmaCreateBuffer(histSSBO)");
            this.histBuffer = pHandle.get(0);
            this.histAlloc = pAlloc.get(0);

            // Allocate persistent 1x1 RGBA16F adaptation image
            VkImageCreateInfo imgInfo = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK12.VK_IMAGE_TYPE_2D)
                    .format(VK12.VK_FORMAT_R16G16B16A16_SFLOAT)
                    .mipLevels(1).arrayLayers(1)
                    .samples(VK12.VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK12.VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK12.VK_IMAGE_USAGE_STORAGE_BIT | VK12.VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK12.VK_IMAGE_LAYOUT_UNDEFINED);
            imgInfo.extent().set(1, 1, 1);

            VmaAllocationCreateInfo imgAlloc = VmaAllocationCreateInfo.calloc(stack)
                    .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
            checkVk(Vma.vmaCreateImage(vmaAllocator, imgInfo, imgAlloc, pHandle, pAlloc, null), "vmaCreateImage(adapt1x1)");
            this.adaptImage = pHandle.get(0);
            this.adaptAlloc = pAlloc.get(0);

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(adaptImage)
                    .viewType(VK12.VK_IMAGE_VIEW_TYPE_2D)
                    .format(VK12.VK_FORMAT_R16G16B16A16_SFLOAT);
            viewInfo.subresourceRange()
                    .aspectMask(VK12.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1)
                    .baseArrayLayer(0).layerCount(1);
            checkVk(VK12.vkCreateImageView(vkDevice, viewInfo, null, pHandle), "vkCreateImageView(adapt1x1)");
            this.adaptView = pHandle.get(0);
        }

        this.initialized = true;
        RaindioClientMod.LOGGER.info("[Raindio] Initialized GPU Luminance Histogram Auto-Exposure (256-bin Log2 + 1x1 RGBA16F adaptation).");
    }

    private void updateBuildDescriptorSet(MemoryStack stack, long set, long hdrView, long nearestSamp) {
        VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(nearestSamp).imageView(hdrView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);
        VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(histBuffer).offset(0).range(HIST_BYTES);

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
        writes.get(0).sType$Default().dstSet(set).dstBinding(0)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(imgInfo);
        writes.get(1).sType$Default().dstSet(set).dstBinding(1)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bufInfo);
        VK12.vkUpdateDescriptorSets(vkDevice, writes, null);
    }

    private void updateAdaptDescriptorSet(MemoryStack stack, long set) {
        VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(histBuffer).offset(0).range(HIST_BYTES);
        VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(VK12.VK_NULL_HANDLE).imageView(adaptView).imageLayout(VK12.VK_IMAGE_LAYOUT_GENERAL);

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
        writes.get(0).sType$Default().dstSet(set).dstBinding(0)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(bufInfo);
        writes.get(1).sType$Default().dstSet(set).dstBinding(1)
                .descriptorType(VK12.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(imgInfo);
        VK12.vkUpdateDescriptorSets(vkDevice, writes, null);
    }

    private long createComputePipeline(MemoryStack stack, byte[] spvBytes, long pipelineLayout) {
        ByteBuffer codeBuf = MemoryUtil.memAlloc(spvBytes.length);
        long shaderModule = VK12.VK_NULL_HANDLE;
        try {
            codeBuf.put(spvBytes).flip();
            VkShaderModuleCreateInfo modInfo = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(codeBuf);
            LongBuffer pMod = stack.mallocLong(1);
            checkVk(VK12.vkCreateShaderModule(vkDevice, modInfo, null, pMod), "vkCreateShaderModule(autoExp)");
            shaderModule = pMod.get(0);

            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK12.VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer pipeInfo = VkComputePipelineCreateInfo.calloc(1, stack);
            pipeInfo.get(0).sType$Default().stage(stageInfo).layout(pipelineLayout);

            LongBuffer pPipe = stack.mallocLong(1);
            checkVk(VK12.vkCreateComputePipelines(vkDevice, VK12.VK_NULL_HANDLE, pipeInfo, null, pPipe), "vkCreateComputePipelines(autoExp)");
            return pPipe.get(0);
        } finally {
            if (shaderModule != VK12.VK_NULL_HANDLE) VK12.vkDestroyShaderModule(vkDevice, shaderModule, null);
            MemoryUtil.memFree(codeBuf);
        }
    }

    private static void bufferBarrier(
            VkCommandBuffer cmd, MemoryStack stack, long buffer,
            int srcAccess, int dstAccess, int srcStage, int dstStage
    ) {
        VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(1, stack);
        barrier.get(0)
                .sType$Default()
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .buffer(buffer).offset(0).size(HIST_BYTES);
        VK12.vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, barrier, null);
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
                .oldLayout(oldLayout).newLayout(newLayout)
                .srcQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK12.VK_QUEUE_FAMILY_IGNORED)
                .image(image)
                .srcAccessMask(srcAccess).dstAccessMask(dstAccess);
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
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException("Vulkan call failed: " + op + " (VkResult=" + result + ")");
        }
    }

    @Override
    public void close() {
        lastLevel = null;
        lastFrameNanos = 0L;
        if (vkDevice != null) {
            VK12.vkDeviceWaitIdle(vkDevice);
            if (adaptView != VK12.VK_NULL_HANDLE) { VK12.vkDestroyImageView(vkDevice, adaptView, null); adaptView = VK12.VK_NULL_HANDLE; }
            if (adaptImage != VK12.VK_NULL_HANDLE) { Vma.vmaDestroyImage(vmaAllocator, adaptImage, adaptAlloc); adaptImage = VK12.VK_NULL_HANDLE; adaptAlloc = 0L; }
            if (histBuffer != VK12.VK_NULL_HANDLE) { Vma.vmaDestroyBuffer(vmaAllocator, histBuffer, histAlloc); histBuffer = VK12.VK_NULL_HANDLE; histAlloc = 0L; }
            if (buildPipeline != VK12.VK_NULL_HANDLE) { VK12.vkDestroyPipeline(vkDevice, buildPipeline, null); buildPipeline = VK12.VK_NULL_HANDLE; }
            if (adaptPipeline != VK12.VK_NULL_HANDLE) { VK12.vkDestroyPipeline(vkDevice, adaptPipeline, null); adaptPipeline = VK12.VK_NULL_HANDLE; }
            if (buildPipelineLayout != VK12.VK_NULL_HANDLE) { VK12.vkDestroyPipelineLayout(vkDevice, buildPipelineLayout, null); buildPipelineLayout = VK12.VK_NULL_HANDLE; }
            if (adaptPipelineLayout != VK12.VK_NULL_HANDLE) { VK12.vkDestroyPipelineLayout(vkDevice, adaptPipelineLayout, null); adaptPipelineLayout = VK12.VK_NULL_HANDLE; }
            if (buildDescLayout != VK12.VK_NULL_HANDLE) { VK12.vkDestroyDescriptorSetLayout(vkDevice, buildDescLayout, null); buildDescLayout = VK12.VK_NULL_HANDLE; }
            if (adaptDescLayout != VK12.VK_NULL_HANDLE) { VK12.vkDestroyDescriptorSetLayout(vkDevice, adaptDescLayout, null); adaptDescLayout = VK12.VK_NULL_HANDLE; }
            if (descriptorPool != VK12.VK_NULL_HANDLE) { VK12.vkDestroyDescriptorPool(vkDevice, descriptorPool, null); descriptorPool = VK12.VK_NULL_HANDLE; }
        }
        resourcesTransitioned = false;
        initialized = false;
    }
}
