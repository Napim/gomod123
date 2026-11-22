package me.napim.gomod.mixins;

import me.napim.gomod.features.SpectatorKillerNametagOverlay;
import me.napim.gomod.features.LunarNametagLogoOverlay;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.ReplayPlayerHealthTagFix;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.gui.MountPreviewHelper;
import me.napim.gomod.mixins.RenderAccessor;
import me.napim.gomod.render.NametagLayerBridge;
import me.napim.gomod.features.NametagRenderDebugCapture;
import me.napim.gomod.util.OutlineRenderingHelper;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Exposes the foot {@code y} of the main IGN line to {@link NametagLayerBridge}. With {@code ONLY_IGN}, the IGN is kept
 * on the hearts-slot {@code y} at close range instead of vanilla's stepped-up row above invisible hearts.
 */
@Mixin(RenderPlayer.class)
public abstract class RenderPlayerNametagLayerMixin {

    @Unique
    private double gomod$lastMainNameFootYForBridge;

    /**
     * /glowplayer2 (chameleon) uses {@code setRenderOutlines(true)} with {@link OutlineRenderingHelper#isInOutlineMode}
     * false — only the suppress flag blocks {@code renderName}. Vanilla name + tab health still go through
     * {@code renderOffsetLivingLabel}, which would otherwise feed the outline shader and glow the nametag.
     */
    @Inject(
            method = "renderOffsetLivingLabel(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDLjava/lang/String;FD)V",
            at = @At("HEAD")
    )
    private void gomod$beginMainNameFootYCapture(AbstractClientPlayer player, double x, double y, double z,
            String str, float labelScale, double distanceSq, CallbackInfo ci) {
        NametagRenderDebugCapture.recordOffsetLabel("player-layer.enter", player, str, x, y, z,
                labelScale, distanceSq);
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        gomod$lastMainNameFootYForBridge = y;
    }

    @Inject(
            method = "renderOffsetLivingLabel(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDLjava/lang/String;FD)V",
            at = @At("TAIL"),
            require = 0
    )
    private void gomod$recordPlayerOffsetLabelReturn(AbstractClientPlayer player, double x, double y, double z,
            String str, float labelScale, double distanceSq, CallbackInfo ci) {
        NametagRenderDebugCapture.recordOffsetLabel("player-layer.return", player, str, x, y, z,
                labelScale, distanceSq);
    }

    @Inject(
            method = "renderOffsetLivingLabel(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDLjava/lang/String;FD)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void gomod$skipPlayerOffsetLabelInOutlineFbo(AbstractClientPlayer player, double x, double y, double z,
            String str, float labelScale, double distanceSq, CallbackInfo ci) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        if (MountPreviewHelper.shouldSuppressGuiPreviewNametag(player)) {
            NametagRenderDebugCapture.recordLabelCancelled(player, str, "mount-preview");
            ci.cancel();
            return;
        }
        if (VisibleSpecs.shouldSuppressPlayerNametagForBlindness(player, x, y, z)) {
            NametagRenderDebugCapture.recordLabelCancelled(player, str, "blindness-occlusion");
            ci.cancel();
            return;
        }
        if (OutlineRenderingHelper.isInOutlineMode()
                || OutlineRenderingHelper.shouldSuppressNametagInOutlineFramebuffer()) {
            NametagRenderDebugCapture.recordLabelCancelled(player, str, "outline-framebuffer");
            ci.cancel();
            return;
        }
        if (VisibleSpecs.shouldSuppressNametag(player)
                && !SpectatorKillerNametagOverlay.shouldUseMainIgnSlotForKiller(player)) {
            NametagRenderDebugCapture.recordLabelCancelled(player, str, "visible-specs-policy");
            ci.cancel();
            return;
        }
        // Force-replay-health mode owns the whole stack. Replay spectator settings use the normal
        // RenderPlayer path, deferred below, so ONLY_IGN keeps the vanilla IGN placement.
        if (ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack(player)) {
            NametagRenderDebugCapture.recordLabelCancelled(player, str, "replay-stack-substitution");
            ReplayPlayerHealthTagFix.renderForcedStackFromPlayerLayer(
                    (RendererLivingEntity) (Object) this, player, x, y, z);
            ci.cancel();
            return;
        }
        if (VisibleSpecs.isDeferredOpacityFlushActive()) {
            if (VisibleSpecs.isDeferredOpacityPrepassActive()) {
                NametagRenderDebugCapture.recordLabelCancelled(player, str, "opacity-prepass");
                ci.cancel();
                return;
            }
            if (VisibleSpecs.shouldDeferNametagForOpacity(player)) {
                NametagRenderDebugCapture.recordLabelCancelled(player, str, "deferred-for-opacity");
                VisibleSpecs.scheduleDeferredPlayerOffsetNametag((RenderPlayer) (Object) this,
                        player, x, y, z, str, labelScale, distanceSq);
                ci.cancel();
            }
        }
    }

    @Redirect(
            method = "renderOffsetLivingLabel(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDLjava/lang/String;FD)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/RenderPlayer;renderLivingLabel(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI)V"
            ),
            require = 0
    )
    private void gomod$skipVisibleSpecsHealthNametag(RenderPlayer renderer, Entity entity, String str,
            double x, double y, double z, int maxDistance) {
        if (!RenderProfiler.shouldBypassPlayerHooks() && VisibleSpecs.shouldSuppressHealthNametag(entity)) {
            NametagRenderDebugCapture.recordLabelCancelled(entity, str, "health-row-policy");
            return;
        }
        ((RenderAccessor) renderer).gomod$invokeRenderLivingLabel(entity, str, x, y, z, maxDistance);
    }

    @Redirect(
            method = "renderOffsetLivingLabel(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDLjava/lang/String;FD)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/Render;renderLivingLabel(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI)V"
            ),
            require = 0
    )
    private void gomod$skipVisibleSpecsHealthNametagRenderOwner(Render renderer, Entity entity, String str,
            double x, double y, double z, int maxDistance) {
        if (!RenderProfiler.shouldBypassPlayerHooks() && VisibleSpecs.shouldSuppressHealthNametag(entity)) {
            NametagRenderDebugCapture.recordLabelCancelled(entity, str, "health-row-policy");
            return;
        }
        ((RenderAccessor) renderer).gomod$invokeRenderLivingLabel(entity, str, x, y, z, maxDistance);
    }

    @ModifyArgs(
            method = "renderOffsetLivingLabel(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDLjava/lang/String;FD)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/RendererLivingEntity;renderOffsetLivingLabel(Lnet/minecraft/entity/Entity;DDDLjava/lang/String;FD)V"
            )
    )
    private void gomod$adjustMainNameFootYBeforeSuper(Args args) {
        Entity entity = (Entity) args.get(0);
        double footYAfterVanillaTabHealthStep = (double) args.get(2);
        if (RenderProfiler.shouldBypassPlayerHooks()) {
            gomod$lastMainNameFootYForBridge = footYAfterVanillaTabHealthStep;
            return;
        }
        float labelScale = (float) args.get(5);
        double distanceSq = (double) args.get(6);
        gomod$lastMainNameFootYForBridge = VisibleSpecs.adjustMainNameFootYForOnlyIgnMode(
                (Render) (Object) this, entity, footYAfterVanillaTabHealthStep, labelScale, distanceSq);
        args.set(2, gomod$lastMainNameFootYForBridge);
    }

    @Inject(
            method = "renderOffsetLivingLabel(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDLjava/lang/String;FD)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/RendererLivingEntity;renderOffsetLivingLabel(Lnet/minecraft/entity/Entity;DDDLjava/lang/String;FD)V",
                    shift = At.Shift.AFTER
            )
    )
    private void gomod$fireNametagBridgeAfterMainName(AbstractClientPlayer player, double x, double y, double z,
            String str, float labelScale, double distanceSq, CallbackInfo ci) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        LunarNametagLogoOverlay.render(player, (Render) (Object) this, x,
                gomod$lastMainNameFootYForBridge, z, str);
        NametagLayerBridge.fireAfterVanillaPlayerNameplate(player, (Render) (Object) this,
                x, gomod$lastMainNameFootYForBridge, z, labelScale, distanceSq, str);
    }
}
