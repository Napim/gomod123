package me.napim.gomod.mixins;

import me.napim.gomod.features.BlitzPregamePlayerToggle;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes non-visual traces emitted by players hidden with the Blitz pregame
 * control. These hooks run on the entity itself, so they also cover replay
 * playback without guessing which coordinate-based sound belongs to a player.
 */
@Mixin(Entity.class)
public class BlitzPregameHiddenPlayerEffectsMixin {

    @Inject(method = {"playStepSound", "func_180429_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$muteHiddenPlayerSteps(BlockPos pos, Block blockIn, CallbackInfo ci) {
        if (BlitzPregamePlayerToggle.shouldHide((Entity) (Object) this)) {
            ci.cancel();
        }
    }

    @Inject(method = {"spawnRunningParticles", "func_174830_Y"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$removeHiddenPlayerRunningParticles(CallbackInfo ci) {
        if (BlitzPregamePlayerToggle.shouldHide((Entity) (Object) this)) {
            ci.cancel();
        }
    }
}
