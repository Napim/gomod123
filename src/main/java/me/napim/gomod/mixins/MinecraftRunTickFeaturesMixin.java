package me.napim.gomod.mixins;

import me.napim.gomod.GomodRuntimeBootstrap;
import me.napim.gomod.features.BestArmorContainerOverlayDriver;
import me.napim.gomod.features.ChestDumpLogger;
import me.napim.gomod.features.ClientTickHub;
import me.napim.gomod.features.BlockVerticalLoadClip;
import me.napim.gomod.features.GomodMenuSettings;
import me.napim.gomod.features.ScreenshotChatPrompt;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.TabPerformanceDebug;
import me.napim.gomod.features.WaterClarity;
import me.napim.gomod.gui.GomodSocialGUI;
import me.napim.gomod.gui.GomodGuiScaleOverride;
import me.napim.gomod.util.GomodClasspathTextures;
import me.napim.gomod.util.GomodResourcePackInjector;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lunar/Weave may not deliver {@code TickEvent}; vanilla {@code runTick} always runs in-game.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftRunTickFeaturesMixin {

    private boolean gomod$perspectiveWasDown;
    @Unique private long gomod$tabDisplayUpdateStart;

    /** Lunar can refresh a cached HUD less often than it renders frames; count the outer game loop. */
    @Inject(method = {"runGameLoop", "func_71411_J"}, at = @At("HEAD"), require = 0)
    private void gomod$tabPerformanceFrame(CallbackInfo ci) {
        TabPerformanceDebug.onFrame((Minecraft) (Object) this);
    }

    @Inject(method = {"updateDisplay", "func_175601_h"}, at = @At("HEAD"), require = 0)
    private void gomod$tabPerformanceDisplayStart(CallbackInfo ci) {
        gomod$tabDisplayUpdateStart = TabPerformanceDebug.begin();
    }

    @Inject(method = {"updateDisplay", "func_175601_h"}, at = @At("RETURN"), require = 0)
    private void gomod$tabPerformanceDisplayEnd(CallbackInfo ci) {
        TabPerformanceDebug.end(TabPerformanceDebug.Metric.DISPLAY_UPDATE, gomod$tabDisplayUpdateStart);
        gomod$tabDisplayUpdateStart = 0L;
    }

    /** A null world is Minecraft's disconnect-to-menu path; discard only the old camera anchor. */
    @Inject(method = {"loadWorld", "func_71403_a"}, at = @At("HEAD"), require = 0)
    private void gomod$resetSelfieWhenWorldUnloads(WorldClient world, CallbackInfo ci) {
        if (world == null) {
            me.napim.gomod.features.Selfie.resetCameraForReconnect();
        }
    }

    /** Screenshot keybind: rising edge arms a delayed share-row fallback when {@code ScreenShotHelper} hooks miss. */
    private static boolean gomod$screenshotKeyWasDown = false;

    @Inject(method = {"runTick", "func_71407_l"}, at = @At("HEAD"), require = 0)
    private void gomod$advancedRenderDistanceTick(CallbackInfo ci) {
        try {
            me.napim.gomod.features.LunarMenuDebugCapture.sampleTick(false);
        } catch (Throwable ignored) {
        }
        try {
            me.napim.gomod.features.AdvancedRenderDistance.onClientTick();
        } catch (Throwable ignored) {
        }
        try {
            me.napim.gomod.features.ChatMovement.onRunTickHead();
        } catch (Throwable ignored) {
        }
        try {
            me.napim.gomod.features.MainMenuBlitzQueue.onClientTick((Minecraft) (Object) this);
        } catch (Throwable ignored) {
        }
        try {
            me.napim.gomod.features.HypixelLanguagePrompt.onClientTick((Minecraft) (Object) this);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = {"displayGuiScreen", "func_147108_a"}, at = @At("HEAD"), require = 0)
    private void gomod$chestDumpOnDisplayGuiScreen(GuiScreen screen, CallbackInfo ci) {
        try {
            me.napim.gomod.features.InventoryQolChestDebug.onDisplayGuiScreen((Minecraft) (Object) this, screen);
        } catch (Throwable ignored) {
        }
        try {
            ChestDumpLogger.onDisplayGuiScreen(screen);
        } catch (Throwable ignored) {
        }
        try {
            GomodGuiScaleOverride.beforeDisplayGuiScreen((Minecraft) (Object) this, screen);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = {"runTick", "func_71407_l"}, at = @At("HEAD"), require = 0)
    private void gomod$waterClarityChunkWorkerCache(CallbackInfo ci) {
        try {
            WaterClarity.refreshChunkWorkerSafeState();
            BlockVerticalLoadClip.refreshChunkWorkerSafeState();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Enable WASD movement while any gomod GUI is open, when "Move while in menu" is toggled on.
     * When a GUI is open, runTick sends keyboard events to the screen instead of
     * KeyBinding.setKeyBindState, so movement keys keep their stale pressed state.
     * We manually sync the six movement key-bind states from LWJGL hardware state.
     * Do not call {@code movementInput.updatePlayerMoveState()} here: {@link net.minecraft.client.entity.EntityPlayerSP}
     * relies on reading {@code movementInput.jump} before that call to detect a rising edge for creative
     * double-tap flight ({@code allowFlying && !flag && movementInput.jump}). Updating movement input early would
     * leave {@code flag} true every tick while space is held and flight would never toggle.
     */
    @Inject(method = {"runTick", "func_71407_l"}, at = @At("HEAD"), require = 0)
    private void gomod$moveWhileInMenu(CallbackInfo ci) {
        try {
            Minecraft mc = (Minecraft) (Object) this;
            me.napim.gomod.features.LunarMenuKeybindForwarder.poll(mc);
            // Lunar's WebOSR open/close callbacks are authoritative; different Locker panels can
            // leave different Minecraft screens active, so do not clear overlay state based on
            // the current screen class.
            boolean lunarOverlay = me.napim.gomod.features.LunarUiInputState.isOverlayContext(mc);
            gomod$pollMenuPerspective(mc, lunarOverlay);
            if ((mc.currentScreen == null && !lunarOverlay) || mc.thePlayer == null || mc.theWorld == null) return;
            if (mc.currentScreen instanceof net.minecraft.client.gui.GuiChat) return;
            boolean compactSocialDock = mc.currentScreen instanceof GomodSocialGUI
                    && ((GomodSocialGUI) mc.currentScreen).shouldAllowCompactDockMovement();
            String screenClass = mc.currentScreen == null ? "" : mc.currentScreen.getClass().getName();
            boolean gomodScreen = screenClass.startsWith("me.napim.gomod.gui.");
            boolean minecraftMenu = GomodMenuSettings.isSafeMinecraftMenuScreen(mc.currentScreen);
            boolean movementAllowed = (gomodScreen && GomodMenuSettings.isMoveWhileMenuEnabled())
                    || (minecraftMenu && GomodMenuSettings.isMinecraftMenuControlsEnabled())
                    || (lunarOverlay && GomodMenuSettings.isLunarMenuControlsEnabled());
            if (!compactSocialDock && !movementAllowed) {
                // Release menu movement keys when movement is not enabled for this screen.
                if (mc.currentScreen instanceof net.minecraft.client.gui.inventory.GuiContainer
                        || minecraftMenu || lunarOverlay) {
                    gomod$releaseGomodMenuMovementKeys(mc);
                }
                return;
            }
            if (GomodMenuSettings.shouldSuppressMoveWhileMenuAndDragLook(mc)) {
                gomod$releaseGomodMenuMovementKeys(mc);
                return;
            }
            // Sync movement and player-list key-bind pressed states from LWJGL hardware;
            // when a screen is open, runTick does not call KeyBinding.setKeyBindState.
            net.minecraft.client.settings.KeyBinding[] moveKeys = {
                mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack,
                mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindJump, mc.gameSettings.keyBindSneak
            };
            for (net.minecraft.client.settings.KeyBinding kb : moveKeys) {
                net.minecraft.client.settings.KeyBinding.setKeyBindState(
                    kb.getKeyCode(), org.lwjgl.input.Keyboard.isKeyDown(kb.getKeyCode()));
            }
        } catch (Throwable ignored) {
        }
    }

    private void gomod$pollMenuPerspective(Minecraft mc, boolean lunarOverlay) {
        if (mc == null || mc.gameSettings == null || mc.thePlayer == null || mc.theWorld == null
                || mc.currentScreen == null) {
            gomod$perspectiveWasDown = false;
            return;
        }

        String screenClass = mc.currentScreen.getClass().getName();
        boolean gomodMenu = !lunarOverlay
                && screenClass.startsWith("me.napim.gomod.gui.")
                && GomodMenuSettings.isMoveWhileMenuEnabled()
                && !GomodMenuSettings.shouldSuppressMoveWhileMenuAndDragLook(mc);
        boolean minecraftMenu = !lunarOverlay
                && GomodMenuSettings.isSafeMinecraftMenuScreen(mc.currentScreen)
                && GomodMenuSettings.isMinecraftMenuControlsEnabled();
        boolean lunarMenu = lunarOverlay
                && (screenClass.startsWith("com.moonsworth.lunar.")
                    || screenClass.startsWith("com.moonsworth.webosr."))
                && GomodMenuSettings.isLunarMenuControlsEnabled()
                && !GomodMenuSettings.shouldSuppressMoveWhileMenuAndDragLook(mc);
        if (!gomodMenu && !minecraftMenu && !lunarMenu) {
            gomod$perspectiveWasDown = false;
            return;
        }

        int keyCode = mc.gameSettings.keyBindTogglePerspective.getKeyCode();
        boolean down = keyCode > 0 && org.lwjgl.input.Keyboard.isKeyDown(keyCode);
        if (down && !gomod$perspectiveWasDown) {
            mc.gameSettings.thirdPersonView = (mc.gameSettings.thirdPersonView + 1) % 3;
        }
        gomod$perspectiveWasDown = down;
    }

    /** Keep eligible vanilla menus from freezing an integrated-server world while MC-menu movement is enabled. */
    @Redirect(
        method = {"runGameLoop", "func_71411_J"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiScreen;doesGuiPauseGame()Z"),
        require = 0
    )
    private boolean gomod$mcMovementMenuDoesNotPause(GuiScreen screen) {
        Minecraft mc = (Minecraft) (Object) this;
        if (mc.theWorld != null
                && GomodMenuSettings.isMinecraftMenuControlsEnabled()
                && GomodMenuSettings.isSafeMinecraftMenuScreen(screen)) {
            return false;
        }
        return screen.doesGuiPauseGame();
    }

    private static void gomod$releaseGomodMenuMovementKeys(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        net.minecraft.client.settings.KeyBinding[] moveKeys = {
                mc.gameSettings.keyBindForward, mc.gameSettings.keyBindBack,
                mc.gameSettings.keyBindLeft, mc.gameSettings.keyBindRight,
                mc.gameSettings.keyBindJump, mc.gameSettings.keyBindSneak
        };
        for (net.minecraft.client.settings.KeyBinding kb : moveKeys) {
            net.minecraft.client.settings.KeyBinding.setKeyBindState(kb.getKeyCode(), false);
        }
    }

    @Inject(method = {"runTick", "func_71407_l"}, at = @At("RETURN"), require = 0)
    private void gomod$afterRunTickFeatures(CallbackInfo ci) {
        try {
            GomodRuntimeBootstrap.ensureStarted();
            GomodRuntimeBootstrap.ensureStartGamePosted();
            GomodResourcePackInjector.tickDeferredRefresh((Minecraft) (Object) this);
        } catch (Throwable ignored) {
        }
        try {
            GomodClasspathTextures.tickIncrementalHudPreload((Minecraft) (Object) this);
        } catch (Throwable ignored) {
        }
        try {
            ClientTickHub.runClientFeatureTicks();
        } catch (Throwable ignored) {
        }
        try {
            // Forge's client-tick event is absent on a few Weave/Lunar launch paths.
            me.napim.gomod.features.ChatMovement.onClientTickEnd();
        } catch (Throwable ignored) {
        }
        try {
            // Guaranteed fallback for config-backed keybinds when Forge/Weave tick events are absent.
            // Hardware edge detection keeps duplicate platform + runTick calls from toggling twice.
            me.napim.gomod.features.GomodKeybinds.pollGuaranteedRuntimeKeybinds();
        } catch (Throwable ignored) {
        }
        try {
            // ClientTickHub intentionally skips menus; Social still polls and completes deferred GUI opens there.
            me.napim.gomod.Main.getGlobalChatClient().pollGuaranteedRuntimeTick();
            me.napim.gomod.gui.GomodGuiOpenHelper.clientTick();
            me.napim.gomod.features.GomodSocialMainMenuNotifications.tickIfMenu(
                    (Minecraft) (Object) this);
        } catch (Throwable ignored) {
        }
        try {
            me.napim.gomod.features.BlitzPregamePlayerToggle.onClientTick((Minecraft) (Object) this);
        } catch (Throwable ignored) {
        }
        try {
            // Once per runTick (Forge/Weave may also call ClientTickHub — do not tie this only to the hub).
            ScreenshotChatPrompt.tickScreenshotShareFallback();
        } catch (Throwable ignored) {
        }
        try {
            gomod$pollScreenshotKeyArmFallback((Minecraft) (Object) this);
        } catch (Throwable ignored) {
        }
        try {
            SelfOpacity.tickSelfOpacityDebugChatDrain();
        } catch (Throwable ignored) {
        }
        try {
            me.napim.gomod.features.AdvancedRenderDistance.pollScrollAtEndOfRunTick();
        } catch (Throwable ignored) {
        }
        try {
            me.napim.gomod.features.LunarMenuDebugCapture.sampleTick(true);
        } catch (Throwable ignored) {
        }
    }

    private static void gomod$pollScreenshotKeyArmFallback(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.gameSettings == null) {
            return;
        }
        net.minecraft.client.settings.KeyBinding shot = mc.gameSettings.keyBindScreenshot;
        boolean down = shot != null && shot.isKeyDown();
        if (down && !gomod$screenshotKeyWasDown && !ScreenshotChatPrompt.isScreenshotShareFallbackPending()) {
            ScreenshotChatPrompt.armScreenshotShareFallback(55);
        }
        gomod$screenshotKeyWasDown = down;
    }

    /**
     * Best-armor mode 5: paint after the full GUI frame so highlights sit on top and no {@code GuiContainer} mixin is required.
     */
    @Inject(
        method = { "runTick", "func_71407_l" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiScreen;drawScreen(IIF)V",
            shift = At.Shift.AFTER
        ),
        require = 0
    )
    private void gomod$bestArmorAfterGuiScreenDraw(CallbackInfo ci) {
        try {
            BestArmorContainerOverlayDriver.renderAfterCurrentScreenDraw();
        } catch (Throwable ignored) {
        }
    }
}
