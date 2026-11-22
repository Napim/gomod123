package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodLogoColorSync;
import me.napim.gomod.features.GomodPeerLunarPlus;
import me.napim.gomod.features.LunarFullDisguise;
import me.napim.gomod.features.LunarNametagDebugCapture;
import me.napim.gomod.features.LunarTabLogoCompatibility;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/** Captures Lunar's live player-logo profile manager after it handles a cosmetics update. */
// Superseded by LunarColorCompatibilityTransform; intentionally omitted from gomod.weave.mixins.json.
@Deprecated
@Pseudo
@Mixin(
        targets = {"com.moonsworth.lunar.client.CRICRCRHRRICCICRHICIIIOHRIRHRH."
                + "HHICICROOCIIROOHOORRCIHOICHOIH."
                + "OIRIRHRCCIHOHRROCOHICIRIHIIHCH",
                "com.moonsworth.lunar.client.IHCHHIIIHROCIROHROCHRCIROHOICH."
                        + "IHIRRHORRRRHICCOHOHORCHIHHHIOO.OOIOHHHCIICRRRCICOIIIRRRICOCIC"},
        remap = false
)
public abstract class LunarPlayerLogoProfileMixin {

    @Inject(
            method = {"IOCIHHIRIROCOIROOCORCCIOOIIIRR(Lcom/lunarclient/websocket/cosmetic/v2/LoginResponse;)V",
                    "CICOHIIRRHHIIHRROHOICOCRHHHHOO(Lcom/lunarclient/websocket/cosmetic/v2/LoginResponse;)V"},
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void gomod$playerLogoManagerAvailable(@Coerce Object response, CallbackInfo ci) {
        GomodLogoColorSync.onPlayerLogoProfileManagerAvailable(this);
        LunarFullDisguise.onLocalCosmeticsLoginResponse(response);
        LunarTabLogoCompatibility.clearProfileLogoWidths();
    }

    @Inject(
            method = {"IOHOHIHHHHORRCCRHCRCHCRIOIHRHH(Ljava/util/UUID;"
                    + "Lcom/lunarclient/websocket/cosmetic/v2/PlayerCosmeticsPushV2;I)V",
                    "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC(Ljava/util/UUID;"
                            + "Lcom/lunarclient/websocket/cosmetic/v2/PlayerCosmeticsPushV2;I)V"},
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void gomod$playerLogoProfileUpdated(UUID playerId, @Coerce Object push,
                                                int badgeId, CallbackInfo ci) {
        LunarFullDisguise.onCosmeticsPush(playerId, push);
        String target = "unknown";
        Minecraft mc = Minecraft.getMinecraft();
        if (playerId != null && mc != null && mc.getSession() != null
                && mc.getSession().getProfile() != null
                && playerId.equals(mc.getSession().getProfile().getId())) {
            target = "local";
        } else if (playerId != null) {
            target = "peer";
        }
        LunarNametagDebugCapture.recordNativeProfileUpdate("local".equals(target), push,
                GomodLogoColorSync.describeNativeProfileColors(this, playerId));
        GomodLogoColorSync.onPlayerLogoProfileManagerAvailable(this, "local".equals(target));
        LunarTabLogoCompatibility.invalidateProfileLogoWidth(playerId);
        if (playerId != null && mc != null && mc.getSession() != null
                && mc.getSession().getProfile() != null
                && playerId.equals(mc.getSession().getProfile().getId())) {
            target = "local";
            GomodLogoColorSync.requestPeerAppearanceRefresh();
        } else if (playerId != null) {
            target = "peer";
        }
        me.napim.gomod.features.LunarConnectionDiagnostics.record(
                "lunar_profile_update.received", "target=" + target);
        Boolean hidePlus = playerId == null ? null : GomodPeerLunarPlus.advertisedHidePlus(playerId);
        if (playerId != null && GomodPeerLunarPlus.hasPeerState(playerId)) {
            GomodLogoColorSync.refreshRemoteLunarPlusVisibility(
                    playerId.toString().replace("-", ""),
                    hidePlus != null && hidePlus.booleanValue());
        }
        LunarNametagDebugCapture.recordProfileUpdate(
                GomodLogoColorSync.getNativeTabLogoWidth(playerId));
    }
}
