package me.napim.gomod.features;

import com.moonsworth.webosr.javascript.CallbackJS;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.features.GoparkyTauntSettings;

/** Stable callbacks used by Gomod's Locker UI patch, registered on Lunar's WebOSR function buses. */
public final class GomodLunarUiCallbacks {
    private GomodLunarUiCallbacks() {
    }

    @CallbackJS("gomodGetFitColors")
    public static String getFitColors() { return LunarFitColors.uiSnapshot(); }

    @CallbackJS("gomodSaveFitColors")
    public static boolean saveFitColors(String request) { return LunarFitColors.saveFromUi(request); }

    @CallbackJS("gomodShareLunarFit")
    public static String shareLunarFit(String request) { return LunarFitSharing.send(request); }

    @CallbackJS("gomodRetryReceivedFits")
    public static void retryReceivedFits() { LunarFitSharing.nativeReady(); }
    @CallbackJS("gomodDecideLunarFit")
    public static boolean decideLunarFit(String request) { return LunarFitSharing.decide(request); }
    @CallbackJS("gomodFitNotifications")
    public static String fitNotifications(){return LunarFitNotifications.poll();}
    @CallbackJS("gomodDismissFitNotification")
    public static void dismissFitNotification(String request){LunarFitNotifications.dismiss(request);}
    @CallbackJS("gomodGiftDecision")
    public static String giftDecision(String request){return LunarFitNotifications.decide(request);}
    @CallbackJS("gomodGiftNotificationBounds")
    public static void giftNotificationBounds(String request){LunarFitNotifications.bounds(request);}
    @CallbackJS("gomodViewReceivedFits")
    public static void viewReceivedFits(String request) { LunarFitSharing.viewed(request); }

    @CallbackJS("gomodIsLunarFullAccessEnabled")
    public static boolean isLunarFullAccessEnabled() {
        return GomodLogoColorSync.isFullAccessColorAvailable();
    }

    @CallbackJS("gomodIsLunarOnlineConnected")
    public static boolean isLunarOnlineConnected() { return LunarOnlineStatus.isConnected(); }

    @CallbackJS("gomodGetCosmeticOwnershipBadges")
    public static String getCosmeticOwnershipBadges() {
        return LunarSlenderMeOwnership.cosmeticOwnershipBadgeDataJson();
    }

    @CallbackJS("gomodGetOfficialLoadout")
    public static String getOfficialLoadout() {
        return LunarPublicLoginGuard.officialLoadoutJson();
    }

    @CallbackJS("gomodRefreshOfficialLoadout")
    public static boolean refreshOfficialLoadout() {
        return LunarSpectatorCosmetics.requestOfficialSelfProfile();
    }

    @CallbackJS("gomodGetCustomBadgeImages")
    public static String getCustomBadgeImages() {
        return LunarCustomBadgeCatalog.imageDataJson();
    }

    @CallbackJS("gomodSetLockerTextFocus")
    public static void setLockerTextFocus(boolean focused) {
        LunarUiInputState.setLockerTextInputFocused(focused);
    }

    @CallbackJS("gomodSetLunarLogoColor")
    public static void setLunarLogoColor(String value) {
        LunarNametagDebugCapture.recordColorFlow("logo_color_js_callback", "logo", -1, "set_callback");
        GomodLogoColorSync.setLogoColorFromUi(value);
    }

    @CallbackJS("gomodQueryLunarLogoColor")
    public static void queryLunarLogoColor() {
        LunarNametagDebugCapture.recordColorFlow("logo_color_js_callback", "logo", -1, "query_callback");
        GomodLogoColorSync.queryLogoColor();
    }

    @CallbackJS("gomodGetLunarLogoColor")
    public static String getLunarLogoColor() {
        int color = GomodLogoColorSync.getCurrentRgb();
        return color == GomodLogoColorSync.DEFAULT_SENTINEL
                ? "DEFAULT"
                : String.format(java.util.Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    @CallbackJS("gomodGetLunarLogoColorStatus")
    public static String getLunarLogoColorStatus() {
        return GomodLogoColorSync.getStatus();
    }

    @CallbackJS("gomodGetGoparkyTauntSettings")
    public static String getGoparkyTauntSettings() {
        return GoparkyTauntSettings.toJson(GoparkyTauntSettings.get());
    }

    @CallbackJS("gomodSaveGoparkyTauntSettings")
    public static boolean saveGoparkyTauntSettings(String encoded) {
        try {
            GoparkyTauntSettings.saveFromJson(encoded);
            return true;
        } catch (Throwable error) {
            System.err.println("[gomod] Goparky Locker settings were rejected: "
                    + error.getClass().getSimpleName());
            return false;
        }
    }

    @CallbackJS("gomodGetCosmeticAppearance")
    public static String gomod$getCosmeticAppearance(String id) { return me.napim.gomod.features.GomodCosmeticAppearance.open(id); }

    @CallbackJS("gomodPreviewCosmeticAppearance")
    public static boolean gomod$previewCosmeticAppearance(String json) { return me.napim.gomod.features.GomodCosmeticAppearance.preview(json); }

    @CallbackJS("gomodApplyCosmeticAppearance")
    public static String gomod$applyCosmeticAppearance(String json) { return me.napim.gomod.features.GomodCosmeticAppearance.apply(json); }

    @CallbackJS("gomodCloseCosmeticAppearance")
    public static void gomod$closeCosmeticAppearance(String json) { me.napim.gomod.features.GomodCosmeticAppearance.close(json); }
    @CallbackJS("gomodStudioOpen")
    public static String studioOpen(String json){return GomodStudioSettings.open(json);}
    @CallbackJS("gomodStudioPreview")
    public static String studioPreview(String json){return GomodStudioSettings.preview(json);}
    @CallbackJS("gomodStudioSave")
    public static String studioSave(String json){return GomodStudioSettings.save(json);}
    @CallbackJS("gomodStudioPoll")
    public static String studioPoll(String json){return GomodStudioSettings.poll(json);}
    @CallbackJS("gomodStudioClose")
    public static void studioClose(String json){GomodStudioSettings.close(json);}
    @CallbackJS("gomodStudioLibrary")
    public static String studioLibrary(){return GomodStudioSettings.library();}
    @CallbackJS("gomodStudioBadgeStyles")
    public static String studioBadgeStyles(){return GomodStudioSettings.badgeStyles();}
    @CallbackJS("gomodStudioEditedItems")
    public static String studioEditedItems(){
        com.google.gson.JsonObject result=new com.google.gson.JsonObject();
        com.google.gson.JsonArray cosmetics=new com.google.gson.JsonArray(),badges=new com.google.gson.JsonArray(),emotes=new com.google.gson.JsonArray();
        if(GomodLogoColorSync.isFullAccessColorAvailable()){
            for(Long id:GomodCosmeticAppearance.editedCosmeticIds())cosmetics.add(id);
            for(Integer id:GomodStudioSettings.editedBadgeIds())badges.add(id);
            for(Integer id:GomodStudioSettings.editedEmoteIds())emotes.add(id);
        }
        result.add("cosmetics",cosmetics);result.add("badges",badges);result.add("emotes",emotes);return result.toString();
    }
    @CallbackJS("gomodResetEditedItem")
    public static boolean resetEditedItem(String kind,String id){
        try{
            if("cosmetic".equals(kind))return GomodCosmeticAppearance.resetEdited(Long.parseLong(id));
            if("badge".equals(kind)||"speed".equals(kind))return GomodStudioSettings.resetEdited(kind,id);
        }catch(Throwable error){System.err.println("[gomod] Could not reset edited "+kind+": "+error.getClass().getSimpleName());}
        return false;
    }
    @CallbackJS("gomodStudioPlay")
    public static String studioPlay(String json){return GomodCustomEmotes.play(json);}
    @CallbackJS("gomodStudioStop")
    public static void studioStop(){GomodCustomEmotes.stop();}
    @CallbackJS("gomodEmoteShareSend")
    public static String emoteShareSend(String json){return GomodEmoteSharing.send(json);}
    @CallbackJS("gomodEmoteShareState")
    public static String emoteShareState(){return GomodEmoteSharing.snapshot();}
    @CallbackJS("gomodEmoteShareDecide")
    public static String emoteShareDecide(String json){return GomodEmoteSharing.decide(json);}
    @CallbackJS("gomodEmoteShareOpen")
    public static String emoteShareOpen(String json){return GomodEmoteSharing.openPreview(json);}
    @CallbackJS("gomodEmoteShareApply")
    public static String emoteShareApply(String json){return GomodEmoteSharing.apply(json);}
    @CallbackJS("gomodGetCosmeticSizeSupport")
    public static String cosmeticSizeSupport(String id){GomodStudioSettings.requireConnected();return GomodCosmeticAppearance.sizeSupport(Long.parseLong(id));}
    @CallbackJS("gomodGetMainMenuQueueState")
    public static String getMainMenuQueueState(){MainMenuBlitzQueue.ensureFreshCounts();return MainMenuBlitzQueue.getUiSnapshotJson();}
    @CallbackJS("gomodQuickQueueBlitz")
    public static boolean quickQueueBlitz(String mode){return MainMenuBlitzQueue.triggerQuickQueueByName(mode);}
}
