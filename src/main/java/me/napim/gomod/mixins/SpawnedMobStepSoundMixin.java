package me.napim.gomod.mixins;

import me.napim.gomod.features.SpawnedMobStepSoundMute;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels client-triggered {@code Entity#playStepSound} for local-player-spawned Ocelots and
 * Witches in Blitz, and tracks active {@code playStepSound} calls for {@link SoundManagerMixin}.
 */
@Mixin(Entity.class)
public class SpawnedMobStepSoundMixin {

    @Inject(method = {"playStepSound", "func_180429_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$onPlayStepSoundHead(BlockPos pos, Block blockIn, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (SpawnedMobStepSoundMute.shouldMuteEntityStepSound(self)) {
            ci.cancel();
            return;
        }
        SpawnedMobStepSoundMute.enterPlayStepSound(self);
    }

    @Inject(method = {"playStepSound", "func_180429_a"}, at = @At("RETURN"), require = 0)
    private void gomod$onPlayStepSoundReturn(BlockPos pos, Block blockIn, CallbackInfo ci) {
        SpawnedMobStepSoundMute.exitPlayStepSound((Entity) (Object) this);
    }
}
