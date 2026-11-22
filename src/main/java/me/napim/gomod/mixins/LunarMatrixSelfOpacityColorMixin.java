package me.napim.gomod.mixins;

import me.napim.gomod.features.EntityOpacityGlintBlendTracker;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.RenderProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Lunar's current cosmetic/cloak renderer can write vertex colours through its own matrix/buffer
 * abstraction instead of Minecraft's {@code WorldRenderer} or {@code GlStateManager.color}.
 * Scale the alpha argument there too, but only during gomod's local-player self-opacity render.
 */
@Pseudo
@Mixin(
        targets = {
                "com.moonsworth.lunar.COICHHIHCCCOOROCCHHICHRIRCCHRR.ORCRCHHRCHRIIRHIRCIHRHOHRCHROC.COCHICRCCIRHIHRRCHCIOOIOOOHHIH.RHOOHIOCHCHORHHIOHHCHCCICHCCOI",
                "com.moonsworth.lunar.COICHHIHCCCOOROCCHHICHRIRCCHRR.ORCRCHHRCHRIIRHIRCIHRHOHRCHROC.COCHICRCCIRHIHRRCHCIOOIOOOHHIH.ICOCCRRCCCICCIHRCOOOOHCOHHIHCO",
                "com.moonsworth.lunar.COICHHIHCCCOOROCCHHICHRIRCCHRR.ORCRCHHRCHRIIRHIRCIHRHOHRCHROC.COCHICRCCIRHIHRRCHCIOOIOOOHHIH.OHRHOCIRHOOOCOCHIIOOHIHRCICIHH"
        },
        remap = false
)
public abstract class LunarMatrixSelfOpacityColorMixin {
    @ModifyVariable(
            method = {
                    "ORCRCHHRCHRIIRHIRCIHRHOHRCHROC(FFFF)V",
                    "ROROCHCIRCCHHCRCCOOOIHIRHOOCOI(FFFF)V",
                    "RHOOROCCICRCRICICHICIIHRCOIRHC(FFFF)V",
                    "COICHHIHCCCOOROCCHHICHRIRCCHRR(FFFF)V",
                    "COCHICRCCIRHIHRRCHCIOOIOOOHHIH(FFFF)V"
            },
            at = @At("HEAD"),
            ordinal = 3,
            argsOnly = true,
            require = 0,
            remap = false
    )
    private float gomod$scaleLunarMatrixAlpha(float alpha) {
        if (!EntityOpacityGlintBlendTracker.hasAnyOpacityGlInterceptionState()) return alpha;
        if (RenderProfiler.shouldBypassPlayerHooks()) return alpha;
        if (SelfOpacity.shouldScaleVertexAlphaOnRenderThread()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            return alpha * SelfOpacity.getRenderAlpha();
        }
        if (VisibleSpecs.shouldScaleVertexAlphaOnRenderThread()
                && !EntityOpacityGlintBlendTracker.isEnchantGlintBlendActiveForAlphaSkip()) {
            return alpha * VisibleSpecs.getRenderOpacity();
        }
        return alpha;
    }
}
