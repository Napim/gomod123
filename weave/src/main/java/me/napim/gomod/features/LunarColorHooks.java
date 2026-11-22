package me.napim.gomod.features;

import java.util.UUID;
import net.minecraft.client.Minecraft;

/** Small callbacks shared by structurally discovered native Locker/profile hooks. */
public final class LunarColorHooks {
    private LunarColorHooks() { }
    public static void captureLocker(Object model, String publisher) {
        GomodLunarPlusLocker.captureNativePublisher(model, publisher);
    }
    public static boolean selectPlus(Integer color) {
        if (color == null || !GomodLunarPlusLocker.isFullAccessEnabled()) return false;
        if (color.intValue() == 0) {
            GomodLunarPlusLocker.selectNoPlus();
            return true;
        }
        GomodLogoColorSync.rememberLocalLunarPlusColor(color.intValue());
        return false;
    }
    public static void selectedPlus(Integer color) {
        LunarPublicLoginGuard.nativePlusSelected(color);
        if (color != null && color.intValue() != 0 && GomodLunarPlusLocker.isFullAccessEnabled())
            GomodLunarPlusLocker.selectLunarColor(color.intValue());
    }
    public static void profileLogin(Object manager, Object response) {
        GomodLogoColorSync.onPlayerLogoProfileManagerAvailable(manager);
        LunarFullDisguise.onLocalCosmeticsLoginResponse(response);
        LunarTabLogoCompatibility.clearProfileLogoWidths();
    }
    public static void profilePush(Object manager, UUID playerId, Object push) {
        LunarFullDisguise.onCosmeticsPush(playerId, push);
        GomodLogoColorSync.preserveSelectedLocalBadgeAfterProfileUpdate(manager, playerId);
        Minecraft mc = Minecraft.getMinecraft();
        boolean local = playerId != null && mc != null && mc.getSession() != null
                && mc.getSession().getProfile() != null && playerId.equals(mc.getSession().getProfile().getId());
        LunarNametagDebugCapture.recordNativeProfileUpdate(local, push,
                GomodLogoColorSync.describeNativeProfileColors(manager, playerId));
        GomodLogoColorSync.onPlayerLogoProfileManagerAvailable(manager, local);
        LunarTabLogoCompatibility.invalidateProfileLogoWidth(playerId);
        if (local) GomodLogoColorSync.requestPeerAppearanceRefresh();
        LunarConnectionDiagnostics.record("lunar_profile_update.received", "target=" + (local ? "local" : playerId == null ? "unknown" : "peer"));
        Boolean hidePlus = playerId == null ? null : GomodPeerLunarPlus.advertisedHidePlus(playerId);
        if (playerId != null && GomodPeerLunarPlus.hasPeerState(playerId))
            GomodLogoColorSync.refreshRemoteLunarPlusVisibility(playerId.toString().replace("-", ""),
                    hidePlus != null && hidePlus.booleanValue());
        LunarNametagDebugCapture.recordProfileUpdate(GomodLogoColorSync.getNativeTabLogoWidth(playerId));
    }
}
