package me.napim.gomod.mixins;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.RenderProfiler;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to show invisible players when VisibleSpecs decides they should be visible.
 * Replay detection is folded into VisibleSpecs, so spectator opacity/nametag settings
 * are the single source of truth.
 */
@Mixin(Entity.class)
public class EntityPlayerInvisibilityMixin {

    @Inject(method = "isInvisible", at = @At("HEAD"), cancellable = true)
    private void gomod$showInvisibleInReplay(CallbackInfoReturnable<Boolean> cir) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        if (!VisibleSpecs.isRuntimeActiveForRender()) return;
        try {
            // Only apply to players
            Entity entity = (Entity) (Object) this;
            if (!(entity instanceof EntityPlayer)) {
                return;
            }
            
            EntityPlayer player = (EntityPlayer) entity;
            
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null) {
                return;
            }

            if (VisibleSpecs.shouldShowPlayerRemote(player)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable e) {
            // Silently fail to avoid breaking the game
        }
    }
    
    @Inject(method = "isInvisibleToPlayer", at = @At("HEAD"), cancellable = true)
    private void gomod$showInvisibleToPlayerInReplay(EntityPlayer viewer, CallbackInfoReturnable<Boolean> cir) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        if (!VisibleSpecs.isRuntimeActiveForRender()) return;
        try {
            // Only apply to players
            Entity entity = (Entity) (Object) this;
            if (!(entity instanceof EntityPlayer)) {
                return;
            }
            
            EntityPlayer invisPlayer = (EntityPlayer) entity;
            
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null) {
                return;
            }

            if (VisibleSpecs.shouldShowPlayerRemote(invisPlayer)) {
                cir.setReturnValue(false);
            }
        } catch (Throwable e) {
            // Silently fail to avoid breaking the game
        }
    }
}
