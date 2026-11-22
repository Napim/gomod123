package me.napim.gomod.mixins;

import com.mojang.authlib.GameProfile;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.features.Denicker;
import me.napim.gomod.features.DenickerRealSkinCache;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.GomodPeerStandaloneCape;
import me.napim.gomod.features.GomodPeerDisguise;
import me.napim.gomod.features.GomodPeerDisguiseDisplay;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.LocalDisguiseDebugCapture;
import me.napim.gomod.features.PlayerStandaloneCape;
import me.napim.gomod.features.RemoteDisguiseManager;
import me.napim.gomod.features.TabDuplicateNickResolver;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetworkPlayerInfo.class)
public abstract class NetworkPlayerInfoMixin implements KillCounter.TabRowKeyAccess {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void gomod$restoreCachedPeerOnJoin(CallbackInfo ci) {
        if (GomodFixes.isWeaveRuntime()) RemoteDisguiseManager.applyToJoinedInfo((NetworkPlayerInfo) (Object) this);
    }

    @Shadow @Final private GameProfile gameProfile;
    @Shadow private IChatComponent displayName;
    @Unique private String gomod$tabRowKey;
    @Unique private GameProfile gomod$lunarDisguiseProfile;
    @Unique private String gomod$lunarDisguiseProfileName;

    @Override
    public String gomod$getTabRowKey() {
        String key = this.gomod$tabRowKey;
        if (key == null) {
            key = KillCounter.buildTabRowKey((NetworkPlayerInfo) (Object) this, this.gameProfile);
            this.gomod$tabRowKey = key;
        }
        return key;
    }

    /**
     * Lunar's newer TAB renderer can read the profile name directly instead of asking vanilla for displayName. That
     * bypasses every row-text hook and is why the real IGN can flash for one render while a teleport rebuilds TAB.
     * Return a display-only profile copy only while the TAB overlay is rendering; the NetworkPlayerInfo's real profile
     * remains intact for networking, kill tracking, and all non-render code. Keep this check O(1): Lunar asks for the
     * profile repeatedly while TAB is open, so inspecting the call stack here makes the overlay visibly lag.
     */
    @Inject(method = "getGameProfile", at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$maskLocalProfileNameForLunarTab(CallbackInfoReturnable<GameProfile> cir) {
        if (!GomodFixes.isWeaveRuntime()
                || !KillCounter.isTabOverlayRenderActive()) {
            return;
        }
        String targetName = KillCounter.getLunarTabProfileAlias(this.gameProfile);
        if (LocalDisguiseDebugCapture.isActive() && this.gameProfile != null
                && LocalDisguise.matchesLocalProfileFast(this.gameProfile)) {
            LocalDisguiseDebugCapture.record("tab-profile", "local-player",
                    "overlay=true sourceProfile=" + this.gameProfile.getName() + " target=" + targetName
                            + " localOverride=" + LocalDisguise.shouldApplyTabDisplayOverride()
                            + " returnedProfile=" + (targetName == null ? this.gameProfile.getName() : targetName));
        }
        if (targetName == null || targetName.trim().isEmpty()
                || this.gameProfile == null || this.gameProfile.getId() == null) {
            return;
        }
        targetName = targetName.trim();
        if (targetName.equalsIgnoreCase(this.gameProfile.getName())) {
            return;
        }
        if (this.gomod$lunarDisguiseProfile == null
                || !targetName.equals(this.gomod$lunarDisguiseProfileName)) {
            this.gomod$lunarDisguiseProfile = new GameProfile(this.gameProfile.getId(), targetName);
            this.gomod$lunarDisguiseProfile.getProperties().putAll(this.gameProfile.getProperties());
            this.gomod$lunarDisguiseProfileName = targetName;
        }
        cir.setReturnValue(this.gomod$lunarDisguiseProfile);
    }

    @Inject(method = "hasLocationSkin", at = @At("HEAD"), cancellable = true)
    private void gomod$localDisguiseHasLocationSkin(CallbackInfoReturnable<Boolean> cir) {
        if (LocalDisguise.isHypixelReplayContextCached(net.minecraft.client.Minecraft.getMinecraft())) {
            if (LocalDisguise.shouldApplySkinOverride()
                    && LocalDisguise.isReplayLocalDisguiseSource(this.gameProfile == null
                            ? null : this.gameProfile.getName())) {
                cir.setReturnValue(LocalDisguise.getSkinLocation() != null);
                return;
            }
            LocalDisguise.RemoteDisguiseProfile replayTarget = RemoteDisguiseManager
                    .getDisguisedTargetProfile(this.gameProfile);
            if (replayTarget != null) {
                cir.setReturnValue(replayTarget.skinLocation != null);
                return;
            }
        }
        if (LocalDisguise.shouldApplySkinOverride() && LocalDisguise.matchesLocalProfile(this.gameProfile)) {
            cir.setReturnValue(LocalDisguise.getSkinLocation() != null);
            return;
        }
        if (gomod$denickerRevealRealSkin(this.gameProfile) != null) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "getSkinType", at = @At("HEAD"), cancellable = true)
    private void gomod$localDisguiseGetSkinType(CallbackInfoReturnable<String> cir) {
        if (LocalDisguise.isHypixelReplayContextCached(net.minecraft.client.Minecraft.getMinecraft())) {
            if (LocalDisguise.shouldApplySkinOverride()
                    && LocalDisguise.isReplayLocalDisguiseSource(this.gameProfile == null
                            ? null : this.gameProfile.getName())) {
                String type = LocalDisguise.getSkinType();
                if (LocalDisguiseDebugCapture.isActive()) {
                    LocalDisguiseDebugCapture.record("replay-skin-model", this.gameProfile.getName(),
                            "source=network-player-info target=" + LocalDisguise.fastTargetNameText
                                    + " skinType=" + type + " texture=local");
                }
                cir.setReturnValue(type);
                return;
            }
            LocalDisguise.RemoteDisguiseProfile replayTarget = RemoteDisguiseManager
                    .getDisguisedTargetProfile(this.gameProfile);
            if (replayTarget != null && replayTarget.skinType != null && !replayTarget.skinType.isEmpty()) {
                if (LocalDisguiseDebugCapture.isActive()) {
                    LocalDisguiseDebugCapture.record("replay-skin-model", this.gameProfile.getName(),
                            "source=network-player-info target=" + replayTarget.name
                                    + " skinType=" + replayTarget.skinType + " texture=profile");
                }
                cir.setReturnValue(replayTarget.skinType);
                return;
            }
        }
        if (LocalDisguise.shouldApplySkinOverride() && LocalDisguise.matchesLocalProfile(this.gameProfile)) {
            cir.setReturnValue(LocalDisguise.getSkinType());
            return;
        }
        String realName = gomod$denickerRevealRealSkin(this.gameProfile);
        if (realName != null) {
            cir.setReturnValue(DenickerRealSkinCache.resolveSkinTypeForRealName(realName));
        }
    }

    @Inject(method = "getLocationSkin", at = @At("HEAD"), cancellable = true)
    private void gomod$localDisguiseGetLocationSkin(CallbackInfoReturnable<ResourceLocation> cir) {
        if (LocalDisguise.isHypixelReplayContextCached(net.minecraft.client.Minecraft.getMinecraft())) {
            if (LocalDisguise.shouldApplySkinOverride()
                    && LocalDisguise.isReplayLocalDisguiseSource(this.gameProfile == null
                            ? null : this.gameProfile.getName())
                    && LocalDisguise.getSkinLocation() != null) {
                if (LocalDisguiseDebugCapture.isActive()) {
                    LocalDisguiseDebugCapture.record("replay-skin-model", this.gameProfile.getName(),
                            "source=network-player-info target=" + LocalDisguise.fastTargetNameText
                                    + " skinType=" + LocalDisguise.getSkinType() + " texture=local");
                }
                cir.setReturnValue(LocalDisguise.getSkinLocation());
                return;
            }
            LocalDisguise.RemoteDisguiseProfile replayTarget = RemoteDisguiseManager
                    .getDisguisedTargetProfile(this.gameProfile);
            if (replayTarget != null && replayTarget.skinLocation != null) {
                if (LocalDisguiseDebugCapture.isActive()) {
                    LocalDisguiseDebugCapture.record("replay-skin-model", this.gameProfile.getName(),
                            "source=network-player-info target=" + replayTarget.name
                                    + " skinType=" + replayTarget.skinType + " texture=profile");
                }
                cir.setReturnValue(replayTarget.skinLocation);
                return;
            }
        }
        if (LocalDisguise.shouldApplySkinOverride() && LocalDisguise.matchesLocalProfile(this.gameProfile)) {
            cir.setReturnValue(LocalDisguise.getSkinLocation());
            return;
        }
        String realName = gomod$denickerRevealRealSkin(this.gameProfile);
        if (realName != null) {
            cir.setReturnValue(DenickerRealSkinCache.resolveSkinForRealName(realName));
        }
    }

    private static String gomod$denickerRevealRealSkin(GameProfile profile) {
        if (!Denicker.isEnabled() || !Denicker.isRevealRealSkinEnabled() || profile == null || profile.getId() == null) {
            return null;
        }
        return TabDuplicateNickResolver.resolveRealNameForDenicker(profile.getId(), profile.getName());
    }

    @Inject(method = "getLocationCape", at = @At("HEAD"), cancellable = true)
    private void gomod$localDisguiseGetLocationCape(CallbackInfoReturnable<ResourceLocation> cir) {
        // A local /disguise owns the complete target-cape decision. This must not depend on the separate
        // standalone-cape preference, otherwise Lunar can keep using the target's Mojang cape instead.
        if (GomodInternalFeatures.isEnabled() && LocalDisguise.isEnabled()
                && LocalDisguise.shouldApplyCapeOverride()
                && LocalDisguise.matchesLocalProfile(this.gameProfile)) {
            boolean noCape = LocalDisguise.isNoCapeSelected();
            ResourceLocation selected = noCape ? null : LocalDisguise.getCapeLocation();
            if (LocalDisguiseDebugCapture.isActive()) {
                LocalDisguiseDebugCapture.record("cape-render", "local-player",
                        "source=local-disguise target=" + LocalDisguise.getTargetName()
                                + " noCape=" + noCape + " resolved=" + selected);
            }
            cir.setReturnValue(selected);
            return;
        }
        if (GomodPeerStandaloneCape.isManualDisguiseProfile(this.gameProfile)) {
            cir.setReturnValue(GomodPeerStandaloneCape.capeForProfile(this.gameProfile));
            return;
        }
        if (LocalDisguise.matchesLocalProfile(this.gameProfile)
                && PlayerStandaloneCape.shouldUseGomodCapeSource()
                && (LocalDisguise.shouldApplyCapeOverride() || PlayerStandaloneCape.isActive())) {
            if (GomodInternalFeatures.isEnabled() && LocalDisguise.isEnabled()
                    && LocalDisguise.shouldApplyCapeOverride()) {
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
            return;
        }
        if (GomodPeerStandaloneCape.shouldSuppressVanillaCapeForProfile(this.gameProfile)) {
            cir.setReturnValue(null);
            return;
        }
        ResourceLocation peerCape = GomodPeerStandaloneCape.capeForProfile(this.gameProfile);
        if (peerCape != null) {
            cir.setReturnValue(peerCape);
        }
    }

    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void gomod$localDisguiseGetDisplayName(CallbackInfoReturnable<IChatComponent> cir) {
        if (!GomodFixes.isWeaveRuntime()
                && me.napim.gomod.util.GameContext.isHypixelReplayWatching(
                        net.minecraft.client.Minecraft.getMinecraft())) {
            String source = this.displayName == null
                    ? net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(
                            ((NetworkPlayerInfo) (Object) this).getPlayerTeam(), this.gameProfile.getName())
                    : this.displayName.getFormattedText();
            cir.setReturnValue(new ChatComponentText(LocalDisguise.rewriteReplayTabRowForRender(
                    source, (NetworkPlayerInfo) (Object) this)));
            return;
        }
        if (!GomodFixes.isWeaveRuntime() && LocalDisguise.shouldApplyTabDisplayOverride()
                && LocalDisguise.matchesLocalProfile(this.gameProfile)) {
            cir.setReturnValue(LocalDisguise.buildTabDisplayComponent(this.displayName, this.gameProfile.getName()));
            return;
        }

        // The native Weave build intentionally keeps composed TAB rows out of displayName so incoming Lunar/Hypixel
        // updates remain observable through the raw accessor. Lunar's player-list renderer can bypass
        // GuiPlayerTabOverlay, though, and ask NetworkPlayerInfo for the display component directly. Expose the
        // render-only cache here so denicker suffixes (and the other shared TAB decorations) reach that path too.
        if (GomodFixes.isWeaveRuntime()) {
            String rendered = KillCounter.getRenderedTabLineForInfo((NetworkPlayerInfo) (Object) this);
            if (me.napim.gomod.util.GameContext.isHypixelReplayWatching(
                    net.minecraft.client.Minecraft.getMinecraft())) {
                String source = rendered;
                if (source == null || source.isEmpty()) {
                    source = this.displayName == null
                            ? net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(
                                    ((NetworkPlayerInfo) (Object) this).getPlayerTeam(), this.gameProfile.getName())
                            : this.displayName.getFormattedText();
                }
                cir.setReturnValue(new ChatComponentText(
                        LocalDisguise.rewriteReplayTabRowForRender(source, (NetworkPlayerInfo) (Object) this)));
                return;
            }
            // Lunar can ask this method while a teleport is rebuilding the player-list rows, before the next
            // composed TAB pass has run. Repair the local row at this render boundary so the real profile name
            // cannot flash through during that short cache window.
            if (LocalDisguise.shouldApplyTabDisplayOverride()
                    && LocalDisguise.matchesLocalProfileFast(this.gameProfile)) {
                String source = rendered;
                if (source == null || source.isEmpty()) {
                    source = this.displayName == null
                            ? this.gameProfile.getName()
                            : this.displayName.getFormattedText();
                }
                String disguised = LocalDisguise.buildTabDisplayName(source, this.gameProfile.getName());
                if (disguised != null && !disguised.isEmpty()) {
                    cir.setReturnValue(new ChatComponentText(disguised));
                    return;
                }
            }
            if (rendered != null && !rendered.isEmpty()) {
                cir.setReturnValue(new ChatComponentText(GomodPeerDisguiseDisplay.rewriteTabName(this.gameProfile, rendered)));
                return;
            }
        }
        if (RemoteDisguiseManager.getManualDisguiseTarget(this.gameProfile) != null
                || GomodPeerDisguise.getPeerName(this.gameProfile) != null) {
            String source = this.displayName == null
                    ? net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(
                            ((NetworkPlayerInfo) (Object) this).getPlayerTeam(), this.gameProfile.getName())
                    : this.displayName.getFormattedText();
            cir.setReturnValue(new ChatComponentText(GomodPeerDisguiseDisplay.rewriteTabName(this.gameProfile, source)));
        }
    }
}
