package me.napim.gomod.mixins;

import me.napim.gomod.features.PlayersPlayMode;
import me.napim.gomod.features.RoleplayMode;
import me.napim.gomod.features.GomodMenuSettings;
import me.napim.gomod.features.LunarUiInputState;
import me.napim.gomod.gui.GomodSocialGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovementInput;
import net.minecraft.util.MovementInputFromOptions;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Synchronizes allowed menu movement and suppresses it for roleplay or Lunar text input.
 */
@Mixin(MovementInputFromOptions.class)
public abstract class MovementInputFromOptionsMixin extends MovementInput {

    /**
     * Re-sync movement at the point vanilla samples it for eligible Gomod and Minecraft menus.
     */
    @Inject(method = "updatePlayerMoveState", at = @At("HEAD"), require = 0)
    private void gomod$syncMenuMovementAtInputSample(CallbackInfo ci) {
        me.napim.gomod.features.LunarMenuDebugCapture.sampleMovement(this, false);
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.gameSettings == null || mc.thePlayer == null || mc.theWorld == null
                    || mc.currentScreen instanceof GuiChat) {
                return;
            }

            boolean lunarOverlay = LunarUiInputState.isOverlayContext(mc);
            if (mc.currentScreen == null && !lunarOverlay) {
                return;
            }

            // A focused Lunar/Locker search field owns the keyboard. Clear movement bindings
            // before vanilla samples them so menu key forwarding or stale key states cannot
            // leak through while the user is typing.
            if (lunarOverlay && LunarUiInputState.isTextInputFocused()) {
                releaseMovementKeys(mc);
                return;
            }

            // A WebOSR overlay can own input while Minecraft has no GuiScreen.
            String screenClass = mc.currentScreen == null ? "" : mc.currentScreen.getClass().getName();
            boolean gomodScreen = screenClass.startsWith("me.napim.gomod.gui.");
            boolean minecraftMenu = GomodMenuSettings.isSafeMinecraftMenuScreen(mc.currentScreen);
            boolean compactSocialDock = mc.currentScreen instanceof GomodSocialGUI
                    && ((GomodSocialGUI) mc.currentScreen).shouldAllowCompactDockMovement();
            boolean menuMovementAllowed = compactSocialDock
                    || (gomodScreen && GomodMenuSettings.isMoveWhileMenuEnabled()
                        && !GomodMenuSettings.shouldSuppressMoveWhileMenuAndDragLook(mc))
                    || (minecraftMenu && GomodMenuSettings.isMinecraftMenuControlsEnabled())
                    || (lunarOverlay && GomodMenuSettings.isLunarMenuControlsEnabled());
            if (lunarOverlay && (!menuMovementAllowed
                    || GomodMenuSettings.shouldSuppressMoveWhileMenuAndDragLook(mc))) {
                releaseMovementKeys(mc);
                return;
            }
            if (!menuMovementAllowed) {
                return;
            }

            KeyBinding[] bindings = {
                    mc.gameSettings.keyBindForward,
                    mc.gameSettings.keyBindBack,
                    mc.gameSettings.keyBindLeft,
                    mc.gameSettings.keyBindRight,
                    mc.gameSettings.keyBindJump,
                    mc.gameSettings.keyBindSneak
            };
            for (KeyBinding binding : bindings) {
                if (binding == null || binding.getKeyCode() == 0) {
                    continue;
                }
                int code = binding.getKeyCode();
                boolean down = code < 0
                        ? Mouse.isButtonDown(code + 100)
                        : Keyboard.isKeyDown(code);
                KeyBinding.setKeyBindState(code, down);
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "updatePlayerMoveState", at = @At("RETURN"))
    private void gomod$blockMovementForInputCapture(CallbackInfo ci) {
        me.napim.gomod.features.LunarMenuDebugCapture.sampleMovement(this, true);
        try {
            boolean blockRoleplayMovement = RoleplayMode.isEnabled() || PlayersPlayMode.isEnabled();
            Minecraft mc = Minecraft.getMinecraft();
            boolean blockLunarMenuMovement = LunarUiInputState.isOverlayContext(mc)
                    && (!GomodMenuSettings.isLunarMenuControlsEnabled()
                        || LunarUiInputState.isTextInputFocused());
            if (!blockRoleplayMovement && !blockLunarMenuMovement) return;
            if (blockLunarMenuMovement) {
                releaseMovementKeys(mc);
            }
            // Enforce the input capture after vanilla samples keys, even if another handler re-syncs them.
            this.moveForward  = 0.0f;
            this.moveStrafe   = 0.0f;
            this.jump         = false;
            this.sneak        = false;
        } catch (Throwable t) { System.err.println("[gomod] Error in MovementInputFromOptionsMixin: " + t.getMessage()); }
    }

    private static void releaseMovementKeys(Minecraft mc) {
        KeyBinding[] bindings = {
                mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack,
                mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindJump, mc.gameSettings.keyBindSneak
        };
        for (KeyBinding binding : bindings) {
            if (binding != null) {
                KeyBinding.setKeyBindState(binding.getKeyCode(), false);
            }
        }
    }
}
