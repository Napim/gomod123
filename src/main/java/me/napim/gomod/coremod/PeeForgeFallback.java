package me.napim.gomod.coremod;

import me.napim.gomod.features.PeeEffectManager;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;

/** Targeted Forge renderer fallbacks for profiles where another mod wins the MixinTweaker race. */
public final class PeeForgeFallback {
    private static boolean poseBridgeSeen;
    private static boolean stainBridgeSeen;

    private PeeForgeFallback() {
    }

    public static void applyPlayerPose(Object model, float ageInTicks, Object entity) {
        if (model instanceof ModelBiped && entity instanceof Entity) {
            if (!poseBridgeSeen) {
                poseBridgeSeen = true;
                System.out.println("[gomod] Forge /pee pose fallback invoked");
            }
            PeeEffectManager.applyPlayerModelPoseIfActive((ModelBiped) model, ageInTicks, (Entity) entity);
        }
    }

    public static void renderPlayerStains(Object renderer, Object entity, float scale) {
        if (!(renderer instanceof RendererLivingEntity) || !(entity instanceof EntityPlayer)) {
            return;
        }
        if (!stainBridgeSeen) {
            stainBridgeSeen = true;
            System.out.println("[gomod] Forge /pee player-stain fallback invoked");
        }
        ModelBase model = ((RendererLivingEntity) renderer).getMainModel();
        if (model instanceof ModelBiped) {
            PeeEffectManager.renderPlayerSkinStains((EntityPlayer) entity, (ModelBiped) model, scale);
        }
    }
}
