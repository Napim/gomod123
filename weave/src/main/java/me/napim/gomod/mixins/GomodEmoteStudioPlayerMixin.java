package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodCustomEmotes;
import me.napim.gomod.features.RenderProfiler;
import net.minecraft.client.model.*;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ModelPlayer.class)
public abstract class GomodEmoteStudioPlayerMixin {
    @Inject(method="setRotationAngles",at=@At("HEAD"))
    private void gomod$restore(float a,float b,float c,float d,float e,float f,Entity entity,CallbackInfo ci){GomodCustomEmotes.restore((ModelBiped)(Object)this);}
    @Inject(method="setRotationAngles",at=@At("RETURN"))
    private void gomod$pose(float a,float b,float c,float d,float e,float f,Entity entity,CallbackInfo ci){if(RenderProfiler.shouldBypassPlayerHooks())return;GomodCustomEmotes.pose((ModelBiped)(Object)this,entity);}
}
