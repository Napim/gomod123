package me.napim.gomod.mixins;

import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Rewrites the local player's name at Lunar's shared renderName source before its custom label renderer draws it. */
@Mixin(RendererLivingEntity.class)
public abstract class WeaveLocalDisguiseNametagMixin {

    @Redirect(
            method = {"renderName", "func_177067_a"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/entity/EntityLivingBase;getDisplayName()Lnet/minecraft/util/IChatComponent;"),
            require = 0
    )
    private IChatComponent gomod$rewriteLocalPlayerDisplayName(EntityLivingBase entity) {
        IChatComponent original = entity.getDisplayName();
        Minecraft mc = Minecraft.getMinecraft();
        boolean replay = mc != null && GameContext.isHypixelReplayWatching(mc);
        if (!(entity instanceof EntityPlayer) || mc == null || mc.thePlayer != entity
                || (!LocalDisguise.isEnabled() && !replay) || original == null) {
            return original;
        }

        String formatted = original.getFormattedText();
        String rewritten = replay
                ? LocalDisguise.rewriteReplayNametagForRender(formatted, mc.thePlayer.getName(), true)
                : LocalDisguise.buildNametagDisplayName(formatted, mc.thePlayer.getName());
        if (rewritten == null) return original;
        LunarConnectionDiagnostics.recordOnce("disguise.local_nametag_display", "local-player",
                "redirectReached=true localPlayer=true disguise=" + LocalDisguise.isEnabled()
                        + " replay=" + replay + " changed="
                        + (rewritten != null && !rewritten.equals(formatted)));
        if (rewritten == null || rewritten.equals(formatted)) {
            return original;
        }
        return new ChatComponentText(rewritten);
    }
}
