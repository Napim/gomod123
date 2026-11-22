package me.napim.gomod.mixins;

import me.napim.gomod.features.Denicker;
import me.napim.gomod.features.DenickerRealSkinCache;
import me.napim.gomod.features.GomodPeerStandaloneCape;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.LocalDisguiseDebugCapture;
import me.napim.gomod.features.ManualGomodCapeRenderer;
import me.napim.gomod.features.PlayerStandaloneCape;
import me.napim.gomod.features.PlayersPlayMode;
import me.napim.gomod.features.RoleplayMode;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.features.TabDuplicateNickResolver;
import me.napim.gomod.GomodInternalFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * When roleplay mode is active and the first-person arm is being rendered,
 * intercept the local player's skin lookups so the arm shows the target
 * player's skin texture and arm model (slim / default) instead of our own.
 *
 * The flag {@link RoleplayMode#isRenderingFirstPersonArm()} is only set to
 * {@code true} while {@code ItemRenderer.renderItemInFirstPerson} is executing,
 * so normal third-person rendering of the local player still uses our real skin.
 */
@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerMixin {

    /**
     * Return the roleplay target's skin texture when the first-person arm is
     * being drawn and {@code this} is the local player.
     */
    @Inject(method = "getLocationSkin", at = @At("HEAD"), cancellable = true)
    private void gomod$roleplayOverrideSkin(CallbackInfoReturnable<ResourceLocation> cir) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        long profile = RenderProfiler.begin("GomodPlayerHooks.skinLookup");
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;

            if ((Object) this != mc.thePlayer) {
                AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
                if (player.getGameProfile() != null
                        && LocalDisguise.isHypixelReplayContextCached(mc)) {
                    if (LocalDisguise.shouldApplySkinOverride()
                            && LocalDisguise.isReplayLocalDisguiseSource(player.getGameProfile().getName())
                            && LocalDisguise.getSkinLocation() != null) {
                        gomod$recordReplaySkinSelection(player, "local", LocalDisguise.getSkinType(), "texture=true");
                        cir.setReturnValue(LocalDisguise.getSkinLocation());
                        return;
                    }
                    LocalDisguise.RemoteDisguiseProfile disguise =
                            me.napim.gomod.features.RemoteDisguiseManager
                                    .getDisguisedTargetProfile(player.getGameProfile());
                    if (disguise != null && disguise.skinLocation != null) {
                        gomod$recordReplaySkinSelection(player, disguise.name, disguise.skinType, "texture=true");
                        cir.setReturnValue(disguise.skinLocation);
                        return;
                    }
                }
                if (Denicker.isEnabled() && Denicker.isRevealRealSkinEnabled()) {
                    if (player.getGameProfile() != null) {
                        String realName = TabDuplicateNickResolver.resolveRealNameForDenicker(
                                player.getGameProfile().getId(), player.getGameProfile().getName());
                        if (realName != null) {
                            cir.setReturnValue(DenickerRealSkinCache.resolveSkinForRealName(realName));
                            return;
                        }
                    }
                }
                return;
            }

            if (RoleplayMode.isRenderingFirstPersonArm() || PlayersPlayMode.isRenderingFirstPersonArm()) {
                EntityPlayer target = RoleplayMode.isRenderingFirstPersonArm()
                        ? RoleplayMode.getTargetPlayer()
                        : PlayersPlayMode.getCurrentRenderTarget();
                if (target instanceof AbstractClientPlayer) {
                    cir.setReturnValue(((AbstractClientPlayer) target).getLocationSkin());
                }
                return;
            }

            if (LocalDisguise.shouldApplySkinOverride() && LocalDisguise.getSkinLocation() != null) {
                cir.setReturnValue(LocalDisguise.getSkinLocation());
            }
        } catch (Throwable ignored) {}
        finally { RenderProfiler.end("GomodPlayerHooks.skinLookup", profile); }
    }

    /**
     * Return the roleplay target's skin type ("slim" or "default") so the
     * correct arm model (thin vs normal) is used during first-person rendering.
     */
    @Inject(method = "getSkinType", at = @At("HEAD"), cancellable = true)
    private void gomod$roleplayOverrideSkinType(CallbackInfoReturnable<String> cir) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        long profile = RenderProfiler.begin("GomodPlayerHooks.skinTypeLookup");
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;

            if ((Object) this != mc.thePlayer) {
                AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
                if (player.getGameProfile() != null
                        && LocalDisguise.isHypixelReplayContextCached(mc)) {
                    if (LocalDisguise.shouldApplySkinOverride()
                            && LocalDisguise.isReplayLocalDisguiseSource(player.getGameProfile().getName())) {
                        gomod$recordReplaySkinSelection(player, "local", LocalDisguise.getSkinType(), "texture=local");
                        cir.setReturnValue(LocalDisguise.getSkinType());
                        return;
                    }
                    LocalDisguise.RemoteDisguiseProfile disguise =
                            me.napim.gomod.features.RemoteDisguiseManager
                                    .getDisguisedTargetProfile(player.getGameProfile());
                    if (disguise != null && disguise.skinType != null && !disguise.skinType.isEmpty()) {
                        gomod$recordReplaySkinSelection(player, disguise.name, disguise.skinType, "texture=profile");
                        cir.setReturnValue(disguise.skinType);
                        return;
                    }
                }
                if (Denicker.isEnabled() && Denicker.isRevealRealSkinEnabled()) {
                    if (player.getGameProfile() != null) {
                        String realName = TabDuplicateNickResolver.resolveRealNameForDenicker(
                                player.getGameProfile().getId(), player.getGameProfile().getName());
                        if (realName != null) {
                            cir.setReturnValue(DenickerRealSkinCache.resolveSkinTypeForRealName(realName));
                            return;
                        }
                    }
                }
                return;
            }

            if (RoleplayMode.isRenderingFirstPersonArm() || PlayersPlayMode.isRenderingFirstPersonArm()) {
                EntityPlayer target = RoleplayMode.isRenderingFirstPersonArm()
                        ? RoleplayMode.getTargetPlayer()
                        : PlayersPlayMode.getCurrentRenderTarget();
                if (target instanceof AbstractClientPlayer) {
                    cir.setReturnValue(((AbstractClientPlayer) target).getSkinType());
                }
                return;
            }

            if (LocalDisguise.shouldApplySkinOverride()) {
                cir.setReturnValue(LocalDisguise.getSkinType());
            }
        } catch (Throwable ignored) {}
        finally { RenderProfiler.end("GomodPlayerHooks.skinTypeLookup", profile); }
    }

    private static void gomod$recordReplaySkinSelection(AbstractClientPlayer player,
            String target, String skinType, String texture) {
        if (!LocalDisguiseDebugCapture.isActive() || player == null || player.getGameProfile() == null) return;
        // Skin texture and skin-model lookups alternate every render pass. Keep separate heartbeat keys so
        // their changing detail strings don't flood the bounded disguise trace before a GUI can be inspected.
        LocalDisguiseDebugCapture.record("replay-skin-model",
                player.getGameProfile().getName() + "/" + texture,
                "target=" + target + " skinType=" + skinType);
    }

    @Inject(method = "getLocationCape", at = @At("HEAD"), cancellable = true)
    private void gomod$localDisguiseOverrideCape(CallbackInfoReturnable<ResourceLocation> cir) {
        if (RenderProfiler.shouldBypassPlayerHooks()) return;
        long profile = RenderProfiler.begin("GomodPlayerHooks.capeLookup");
        try {
            Minecraft mc = Minecraft.getMinecraft();
            ResourceLocation nativeLayerCape = ManualGomodCapeRenderer.getNativeLayerCape();
            if (nativeLayerCape != null && mc != null && (Object) this == mc.thePlayer) {
                cir.setReturnValue(nativeLayerCape);
                return;
            }
            AbstractClientPlayer disguisedPlayer = (AbstractClientPlayer) (Object) this;
            if (GomodPeerStandaloneCape.isManualDisguiseProfile(disguisedPlayer.getGameProfile())) {
                cir.setReturnValue(GomodPeerStandaloneCape.capeForProfile(disguisedPlayer.getGameProfile()));
                return;
            }
            if (mc == null || (Object) this != mc.thePlayer) {
                if (mc != null && (Object) this instanceof AbstractClientPlayer) {
                    AbstractClientPlayer player = (AbstractClientPlayer) (Object) this;
                    if (player.getGameProfile() != null && !LocalDisguise.matchesLocalProfile(player.getGameProfile())) {
                        if (GomodPeerStandaloneCape.shouldSuppressVanillaCapeForProfile(player.getGameProfile())) {
                            cir.setReturnValue(null);
                            return;
                        }
                        ResourceLocation peerCape = GomodPeerStandaloneCape.capeForProfile(player.getGameProfile());
                        if (peerCape != null) {
                            cir.setReturnValue(peerCape);
                        }
                    }
                }
                return;
            }
            if (PlayerStandaloneCape.shouldUseGomodCapeSource()
                    && GomodInternalFeatures.isEnabled() && LocalDisguise.shouldApplyCapeOverride()) {
                if (LocalDisguise.isNoCapeSelected()) {
                    cir.setReturnValue(null);
                    return;
                }
                ResourceLocation cape = LocalDisguise.getCapeLocation();
                if (cape != null) {
                    cir.setReturnValue(cape);
                }
            } else if (PlayerStandaloneCape.shouldApplyLocalCapeOverride()) {
                if (PlayerStandaloneCape.isNoCapeSelected()) {
                    cir.setReturnValue(null);
                    return;
                }
                ResourceLocation cape = PlayerStandaloneCape.getCapeLocation();
                if (cape != null) {
                    cir.setReturnValue(cape);
                }
            }
        } catch (Throwable ignored) {}
        finally { RenderProfiler.end("GomodPlayerHooks.capeLookup", profile); }
    }
}
