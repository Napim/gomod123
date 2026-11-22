package me.napim.gomod.mixins;

import com.moonsworth.webosr.javascript.CallbackJS;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.features.GomodLunarPlusLocker;
import me.napim.gomod.features.GomodLogoColorSync;
import me.napim.gomod.features.GoparkyTauntSettings;
import me.napim.gomod.features.LunarNametagDebugCapture;
import me.napim.gomod.features.LunarUiInputState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bridges Gomod's Locker callbacks into the current Lunar WebOSR callback owner. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.COOHHRRCORRHIRIHRORIHRHICCIOIH."
                + "IOCIHHIRIROCOIROOCORCCIOOIIIRR.IOHOHIHHHHORRCCRHCRCHCRIOIHRHH."
                + "CIIHHRHHHIOIIHHRROOHRHICIHOOOC",
        remap = false
)
public abstract class LunarUiCallbacksCurrentMixin {
    @CallbackJS("gomodIsLunarFullAccessEnabled")
    public boolean gomod$isLunarFullAccessEnabled() {
        return GomodLogoColorSync.isFullAccessColorAvailable();
    }

    @CallbackJS("gomodSetLockerTextFocus")
    public void gomod$setLockerTextFocus(boolean focused) {
        LunarUiInputState.setLockerTextInputFocused(focused);
    }

    @Inject(
            method = "CCOIIRRHOIROCCIHIIOCHOOOHIIORO(Ljava/lang/String;)V",
            at = @At("HEAD"), require = 0, remap = false
    )
    private void gomod$lunarRouteOpened(String route, CallbackInfo ci) {
        LunarUiInputState.setOverlayOpen(true);
        LunarNametagDebugCapture.recordLockerNavigation(route);
        GomodLunarPlusLocker.onLockerUiOpened();
    }

    @Inject(
            method = "ORHCOORORHHCHOHOCIICIIROIICIOH(Ljava/lang/String;)V",
            at = @At("HEAD"), require = 0, remap = false
    )
    private void gomod$lunarOverlayOpened(String overlay, CallbackInfo ci) {
        LunarUiInputState.setOverlayOpen(true);
        LunarNametagDebugCapture.recordLockerNavigation(overlay);
        GomodLunarPlusLocker.onLockerUiOpened();
    }

    @Inject(
            method = "HIORRIROIOHOIHRHCHCOOIRHRROOHH(Ljava/lang/String;)V",
            at = @At("HEAD"), require = 0, remap = false
    )
    private void gomod$lunarOverlayRouteChanged(String overlay, CallbackInfo ci) {
        LunarNametagDebugCapture.recordLockerNavigation(overlay);
        // The focused Locker React input can survive a WebOSR route replacement without onBlur.
        // Clear only its stale focus; its next genuine onFocus event re-enables typing suppression.
        LunarUiInputState.resetLockerTextInputFocusForMenuNavigation();
    }

    @Inject(
            method = "CHCRIIICCHICRCIRRICOOROHRHCRIH(Ljava/lang/String;)V",
            at = @At("HEAD"), require = 0, remap = false
    )
    private void gomod$lunarRouteDispatched(String route, CallbackInfo ci) {
        LunarNametagDebugCapture.recordLockerNavigation(route);
        // This current Lunar method dispatches the selected WebOSR route after navigation.
        LunarUiInputState.resetLockerTextInputFocusForMenuNavigation();
    }

    @Inject(
            method = "CCHRHCORHICOICRHOHOIIRCIHRROIH()V",
            at = @At("HEAD"), require = 0, remap = false
    )
    private void gomod$lunarOverlayClosed(CallbackInfo ci) {
        LunarUiInputState.setOverlayOpen(false);
    }

    @CallbackJS("gomodSetLunarLogoColor")
    public void gomod$setLunarLogoColor(String value) {
        LunarNametagDebugCapture.recordColorFlow("logo_color_js_callback", "logo", -1, "set_callback");
        GomodLogoColorSync.setLogoColorFromUi(value);
    }

    @CallbackJS("gomodQueryLunarLogoColor")
    public void gomod$queryLunarLogoColor() {
        LunarNametagDebugCapture.recordColorFlow("logo_color_js_callback", "logo", -1, "query_callback");
        GomodLogoColorSync.queryLogoColor();
    }

    @CallbackJS("gomodGetLunarLogoColor")
    public String gomod$getLunarLogoColor() {
        int color = GomodLogoColorSync.getCurrentRgb();
        return color == GomodLogoColorSync.DEFAULT_SENTINEL
                ? "DEFAULT"
                : String.format("#%06X", color & 0xFFFFFF);
    }

    @CallbackJS("gomodGetLunarLogoColorStatus")
    public String gomod$getLunarLogoColorStatus() {
        return GomodLogoColorSync.getStatus();
    }

    @CallbackJS("gomodGetGoparkyTauntSettings")
    public String gomod$getGoparkyTauntSettings() {
        return GoparkyTauntSettings.toJson(GoparkyTauntSettings.get());
    }

    @CallbackJS("gomodSaveGoparkyTauntSettings")
    public boolean gomod$saveGoparkyTauntSettings(String encoded) {
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
    public String gomod$getCosmeticAppearance(String id) { return me.napim.gomod.features.GomodCosmeticAppearance.open(id); }

    @CallbackJS("gomodPreviewCosmeticAppearance")
    public boolean gomod$previewCosmeticAppearance(String json) { return me.napim.gomod.features.GomodCosmeticAppearance.preview(json); }

    @CallbackJS("gomodApplyCosmeticAppearance")
    public String gomod$applyCosmeticAppearance(String json) { return me.napim.gomod.features.GomodCosmeticAppearance.apply(json); }

    @CallbackJS("gomodCloseCosmeticAppearance")
    public void gomod$closeCosmeticAppearance(String json) { me.napim.gomod.features.GomodCosmeticAppearance.close(json); }
}
