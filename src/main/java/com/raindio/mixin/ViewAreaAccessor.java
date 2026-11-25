package com.raindio.mixin;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes ViewArea.sections so Raindio's shadow pass can collect all nearby loaded
 * RenderSections around the player without being clipped by the main camera's frustum
 * or cave occlusion culling.
 */
@Mixin(ViewArea.class)
public interface ViewAreaAccessor {
    @Accessor("sections")
    RotatingSectionStorage<SectionRenderDispatcher.RenderSection> raindio$getSections();
}
