package me.napim.gomod.mixins;

import me.napim.gomod.features.MobHealthDisplay;
import me.napim.gomod.features.MobNametags;
import me.napim.gomod.features.EntityNametagLimiter;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.LeaderboardHologramDebugCapture;
import me.napim.gomod.features.NametagRenderDebugCapture;
import me.napim.gomod.render.DeferredNametagRenderer;
import me.napim.gomod.features.RemoteDisguiseManager;
import me.napim.gomod.gui.MountPreviewHelper;
import me.napim.gomod.render.NametagRenderState;
import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces mob nametag text during vanilla {@code renderLivingLabel} (entity-pass timing). No deferred/custom
 * billboard — fixes labels drifting down within a few blocks.
 */
@Mixin(Render.class)
public class RenderLivingLabelMixin {
    @Inject(method = {"renderLivingLabel", "func_147906_a"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$markMobNametagRendered(Entity entity, String str, double x, double y, double z,
            int maxDistance, CallbackInfo ci) {
        NametagRenderDebugCapture.recordLivingLabel("enter", entity, str, x, y, z, maxDistance);
        LeaderboardHologramDebugCapture.recordRenderLabelEntry(entity, str);
        if (MountPreviewHelper.shouldSuppressGuiPreviewNametag(entity)) {
            NametagRenderDebugCapture.recordLabelCancelled(entity, str, "mount-preview");
            ci.cancel();
            return;
        }
        if (OutlineRenderingHelper.isInOutlineMode()
                || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
            NametagRenderDebugCapture.recordLabelCancelled(entity, str, "outline-framebuffer");
            ci.cancel();
            return;
        }
        if (EntityNametagLimiter.shouldSuppress(entity)) {
            NametagRenderDebugCapture.recordLabelCancelled(entity, str, "entity-limiter");
            ci.cancel();
            return;
        }
        NametagRenderState.begin("native-render-living-label");
        if (entity instanceof EntityLivingBase && !(entity instanceof EntityPlayer)
                && MobNametags.shouldRenderMobNametag((EntityLivingBase) entity)) {
            MobNametags.markWorldMobNametagRendered((EntityLivingBase) entity);
        }

    }

    @Inject(method = {"renderLivingLabel", "func_147906_a"}, at = @At("TAIL"), require = 0)
    private void gomod$recordLivingLabelReturn(Entity entity, String str, double x, double y, double z,
            int maxDistance, CallbackInfo ci) {
        NametagRenderDebugCapture.recordLivingLabel("return", entity, str, x, y, z, maxDistance);
        NametagRenderState.end();
    }

    @ModifyVariable(
            method = {"renderLivingLabel", "func_147906_a"},
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 0
    )
    private String gomod$mobNametagLabelOverride(String label, Entity entity) {
        if (DeferredNametagRenderer.isRenderingQueuedNativeMobLabel()) {
            return label;
        }
        if (!(entity instanceof EntityLivingBase)) {
            return label;
        }
        if (entity instanceof EntityPlayer) {
            return label;
        }
        EntityLivingBase living = (EntityLivingBase) entity;
        String resolved;
        if (MobHealthDisplay.isRenderingHealthNametagRow()) {
            resolved = label;
        } else if (MobHealthDisplay.isRenderingStackedMobNameRow()) {
            resolved = MobNametags.resolveLivingLabel(living, label);
        } else if (MobHealthDisplay.shouldRenderHealthNametagFor(living) && MobHealthDisplay.isCombinedNametagMode()) {
            resolved = MobHealthDisplay.formatLivingLabel(living);
        } else {
            resolved = MobNametags.resolveLivingLabel(living, label);
        }
        // Server holograms are armor-stand labels. Rewrite the label value here, once per distinct value,
        // instead of making every FontRenderer call in the game inspect disguise state.
        if (entity instanceof EntityArmorStand) {
            String beforeDisguise = resolved;
            if (LocalDisguise.fastRenderTextRewriteFlag) {
                resolved = LocalDisguise.rewriteRenderedTextFast(resolved);
            }
            if (RemoteDisguiseManager.hasEntries()) {
                resolved = RemoteDisguiseManager.rewriteLeaderboardHologramText(resolved);
            }
            LeaderboardHologramDebugCapture.recordArmorStandLabel(entity, beforeDisguise, resolved,
                    LocalDisguise.fastRenderTextRewriteFlag || RemoteDisguiseManager.hasEntries());
        }
        return resolved;
    }

    @ModifyVariable(
            method = {"renderLivingLabel", "func_147906_a"},
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 1,
            require = 0
    )
    private double gomod$raiseWitchLabels(double y, Entity entity) {
        if (!MobHealthDisplay.isApplyingLabelBaseYOffset()) {
            return y + MobHealthDisplay.mobLabelBaseYOffset(entity);
        }
        return y;
    }
}
