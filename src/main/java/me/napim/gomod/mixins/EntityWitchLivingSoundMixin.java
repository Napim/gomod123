package me.napim.gomod.mixins;

import me.napim.gomod.features.WitchAutoSound;
import net.minecraft.entity.monster.EntityWitch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla plays {@code EntityLivingBase#playLivingSound} from the entity's living tick when
 * {@code livingSoundTime} hits 0 (see MCP / EntityLivingBase). On many MP clients that path is
 * inaudible; we re-roll ambient on the same cadence as the witch keeps updating.
 */
@Mixin(EntityWitch.class)
public abstract class EntityWitchLivingSoundMixin {

    @Inject(method = "onLivingUpdate", at = @At("RETURN"), require = 0)
    private void gomod$witchClientAmbient(CallbackInfo ci) {
        try {
            WitchAutoSound.onClientWitchLivingTick((EntityWitch) (Object) this);
        } catch (Throwable ignored) {
        }
    }
}
