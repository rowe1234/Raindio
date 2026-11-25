package com.raindio.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes RenderPearl's underlying VulkanDevice and active VkCommandBuffer
 * so Raindio can record native Vulkan Compute Shader dispatches (vkCmdDispatch)
 * directly inside the frame's command buffer with full synchronization.
 */
@Mixin(value = VulkanCommandEncoder.class, remap = false)
public interface VulkanCommandEncoderAccessor {
    @Accessor("device")
    VulkanDevice raindio$getDevice();

    @Invoker("commandBuffer")
    VkCommandBuffer raindio$commandBuffer();
}
