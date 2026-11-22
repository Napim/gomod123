package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodLunarPlusLocker;
import me.napim.gomod.features.LunarUiInputState;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Republishes Locker state when the current Lunar WebOSR screen becomes visible. */
@Mixin(Minecraft.class)
public abstract class LunarLockerOverlayRefreshMixin {
    private boolean gomod$lunarOverlayWasOpen;

    @Inject(method = {"runTick", "func_71407_l"}, at = @At("HEAD"), require = 0)
    private void gomod$refreshLockerWhenLunarOverlayOpens(CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        boolean overlayOpen = LunarUiInputState.isOverlayContext(mc);
        if (overlayOpen && !gomod$lunarOverlayWasOpen) {
            GomodLunarPlusLocker.onLockerUiOpened();
        }
        gomod$lunarOverlayWasOpen = overlayOpen;
    }
}
