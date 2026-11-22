package me.napim.gomod.mixins;

import com.moonsworth.webosr.javascript.CallbackJS;
import me.napim.gomod.features.GomodLunarPlusLocker;
import me.napim.gomod.features.GomodLogoColorSync;
import me.napim.gomod.features.GoparkyTauntSettings;
import me.napim.gomod.features.LunarUiInputState;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bridges the patched Locker UI and records only its open/close lifecycle. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.OOIRIHRRCHRIHRHHCOOCICRIRCHIIO."
                + "COCIICOHOOCCHCHRIRHHCHORROOCOR.RCRHRICCICICHOOCCCIRRCIRCICIOC."
                + "OHRIIRCCRIROIORIIHHOCRRIICIROI",
        remap = false
)
public abstract class LunarUiCallbacksMixin {
    @CallbackJS("gomodIsLunarFullAccessEnabled")
    public boolean gomod$isLunarFullAccessEnabled() {
        return GomodLogoColorSync.isFullAccessColorAvailable();
    }

    @CallbackJS("gomodSetLockerTextFocus")
    public void gomod$setLockerTextFocus(boolean focused) {
        LunarUiInputState.setLockerTextInputFocused(focused);
    }

    @Inject(
            method = "HHRIIRORRCOOORIIRHIHICRCIOCHRR(Ljava/lang/String;)V",
            at = @At("HEAD"), require = 0, remap = false
    )
    private void gomod$lunarScreenOpened(String route, CallbackInfo ci) {
        LunarUiInputState.setOverlayOpen(true);
        GomodLunarPlusLocker.onLockerUiOpened();
    }

    @Inject(method = "COCROOIORRHCRICCORHCORIIOHIROO(Ljava/lang/String;)V", at = @At("HEAD"), require = 0, remap = false)
    private void gomod$lunarRouteOpened(String overlay, CallbackInfo ci) {
        LunarUiInputState.setOverlayOpen(true);
        GomodLunarPlusLocker.onLockerUiOpened();
    }

    @Inject(method = "IIIOHCHRICHHCHCCRRRHICICHIHHHO()V", at = @At("HEAD"), require = 0, remap = false)
    private void gomod$lunarOverlayClosed(CallbackInfo ci) {
        LunarUiInputState.setOverlayOpen(false);
    }

    @CallbackJS("gomodSetLunarLogoColor")
    public void gomod$setLunarLogoColor(String value) {
        GomodLogoColorSync.setLogoColorFromUi(value);
    }

    @CallbackJS("gomodQueryLunarLogoColor")
    public void gomod$queryLunarLogoColor() {
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
