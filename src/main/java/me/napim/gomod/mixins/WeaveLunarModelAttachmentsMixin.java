package me.napim.gomod.mixins;

import net.minecraft.client.model.ModelRenderer;
import org.spongepowered.asm.mixin.Mixin;

/** Runs the signature-checked postApply patch against Lunar's already-merged attachment draw. */
@Mixin(ModelRenderer.class)
public abstract class WeaveLunarModelAttachmentsMixin {
}
