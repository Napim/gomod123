package me.napim.gomod.weave;

import me.napim.gomod.GomodRuntimeBootstrap;
import me.napim.gomod.Main;
import me.napim.gomod.features.*;
import me.napim.gomod.render.GomodEntityOutlineFallback;
import me.napim.gomod.render.HorseFoodHudRenderer;
import me.napim.gomod.render.DeferredNametagRenderer;
import me.napim.gomod.render.LunarGlowArrowMarkers;
import me.napim.gomod.util.GlowTrailRenderer;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.util.IChatComponent;
import net.weavemc.api.event.*;
import net.weavemc.loader.api.event.ChatReceivedEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Translates Weave 1.3.4 lifecycle events into gomod's shared event surface and
 * runs the same feature hubs used by the Forge bridge.
 */
public final class WeaveEventBridge {
    private static volatile boolean gomodInitialized;
    /** Prevent the replacement chat line from re-entering the Weave receive hook recursively. */
    private static final ThreadLocal<Boolean> printingRewrittenChat = new ThreadLocal<>();

    @SubscribeEvent
    public void onStartGame(StartGameEvent.Post event) {
        // Retry once Lunar has finished initializing its UI cache. The patcher is
        // idempotent and keeps a byte-for-byte backup before changing a bundle.
        LunarLockerUiBundlePatcher.patchSupportedBundles();
        initializeGomodAfterLunarStartup();
        net.weavemc.loader.api.event.EventBus.post(
                new net.weavemc.loader.api.event.StartGameEvent.Post());
        try {
            BarrierVisibilityModelFix.clientTickEnd();
            WaterClarityAtlasHook.syncFromConfig();
            WaterClarity.reloadChunkMeshesForSurfaceClarity();
        } catch (Throwable ignored) {
        }
    }

    private static void initializeGomodAfterLunarStartup() {
        if (gomodInitialized) {
            return;
        }
        synchronized (WeaveEventBridge.class) {
            if (gomodInitialized) {
                return;
            }
            System.out.println("[gomod] Lunar startup complete; initializing gomod features and commands");
            new Main().preInit();
            GomodRuntimeBootstrap.completeWeaveBootstrap();
            DeferredNametagRenderer.useWeaveWorldEventFlush();
            gomodInitialized = true;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.Post event) {
        TabPerformanceDebug.onTick();
        try { ScoreboardTeamDisguiseDebugCapture.recordTick(Minecraft.getMinecraft(), "weave-client-tick"); }
        catch (Throwable failure) { ScoreboardTeamDisguiseDebugCapture.recordHookFailure("weave-client-tick", failure); }
        try { BarrierVisibilityModelFix.clientTickEnd(); } catch (Throwable ignored) {}
        try { BarrierVisibilityRangeTicker.clientTickEnd(); } catch (Throwable ignored) {}
        try { BarrierVisibilityWeaveFallbackRenderer.clientTickEnd(); } catch (Throwable ignored) {}
        try { GomodKeybinds.onClientTick(); } catch (Throwable ignored) {}
        net.weavemc.loader.api.event.EventBus.post(new net.weavemc.loader.api.event.TickEvent());
        try { ClientTickHub.runClientFeatureTicks(); } catch (Throwable ignored) {}
    }

    @SubscribeEvent
    public void onChatReceived(ChatEvent.Received event) {
        if (Boolean.TRUE.equals(printingRewrittenChat.get())) {
            // This is the one intentional second pass used to insert a rewritten component into Lunar's chat GUI.
            // Let GuiNewChat handle it normally; do not cancel/rewrite it again.
            return;
        }
        IChatComponent original = event.getMessage();
        ChatReceivedEvent bridged = new ChatReceivedEvent(original);
        net.weavemc.loader.api.event.EventBus.post(bridged);

        IChatComponent rewritten = bridged.getMessage();
        if (!bridged.isCancelled() && rewritten != null) {
            // Lunar/Weave does not apply GuiNewChatMixin, so perform the display rewrite here on the
            // client event thread. Keep this out of the packet bridge; that path runs on Netty.
            try {
                IChatComponent disguised = LocalDisguise.rewriteIncomingChatComponentForDisplay(rewritten);
                if (disguised != rewritten) {
                    rewritten = disguised;
                }
            } catch (Throwable ignored) {
            }
        }
        if (bridged.isCancelled() || rewritten != original) {
            event.setCancelled(true);
        }
        if (!bridged.isCancelled() && rewritten != null && rewritten != original) {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null) {
                    printingRewrittenChat.set(Boolean.TRUE);
                    try {
                        mc.ingameGUI.getChatGUI().printChatMessage(rewritten);
                    } finally {
                        printingRewrittenChat.remove();
                    }
                }
            } catch (Throwable ignored) {
                printingRewrittenChat.remove();
            }
        }
    }

    @SubscribeEvent
    public void onPacketReceive(PacketEvent.Receive event) {
        try { if (BlitzMatchIdentity.onIncomingPacket(event.getPacket())) { event.setCancelled(true); return; } } catch (Throwable ignored) {}
        try { me.napim.gomod.features.ForceGameChat.onIncomingPacket(event.getPacket()); } catch (Throwable ignored) {}
        try { BlitzRouletteDebug.recordInboundPacket(event.getPacket()); } catch (Throwable ignored) {}
        try { ScoreboardTeamDisguiseDebugCapture.recordInboundTeamsPacket(event.getPacket()); }
        catch (Throwable failure) { ScoreboardTeamDisguiseDebugCapture.recordHookFailure("weave-inbound-packet", failure); }
        net.weavemc.loader.api.event.PacketEvent.Receive bridged =
                new net.weavemc.loader.api.event.PacketEvent.Receive(event.getPacket());
        net.weavemc.loader.api.event.EventBus.post(bridged);
        if (bridged.isCancelled()) {
            event.setCancelled(true);
            return;
        }
        try { SpectatorTracker.onPacketReceived(event.getPacket()); } catch (Throwable ignored) {}
        try { me.napim.gomod.features.NickRenamedItemScanner.onPacketReceived(event.getPacket()); } catch (Throwable ignored) {}
    }

    @SubscribeEvent
    public void onPacketSend(PacketEvent.Send event) {
        // Another native mod may already have consumed a local command. Do not dispatch it again.
        if (event.isCancelled()) return;
        net.weavemc.loader.api.event.PacketEvent.Send bridged =
                new net.weavemc.loader.api.event.PacketEvent.Send(event.getPacket());
        net.weavemc.loader.api.event.EventBus.post(bridged);
        if (bridged.isCancelled()) {
            event.setCancelled(true);
            return;
        }
        try { SpawnedMobTracker.onOutgoingClientPacket(event.getPacket()); } catch (Throwable ignored) {}
    }

    @SubscribeEvent
    public void onPlayerAdded(PlayerListEvent.Add event) {
        net.weavemc.loader.api.event.EventBus.post(
                new net.weavemc.loader.api.event.PlayerListEvent.Add());
    }

    @SubscribeEvent
    public void onPlayerRemoved(PlayerListEvent.Remove event) {
        net.weavemc.loader.api.event.EventBus.post(
                new net.weavemc.loader.api.event.PlayerListEvent.Remove());
    }

    @SubscribeEvent
    public void onConnected(ClientConnectedToServerEvent event) {
        try {
            GomodSocialStore.resetLocalOnlineSession();
            GomodSocialStore.ensureLocalOnlineSession();
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        EntityNametagLimiter.clear();
        try { SpawnedMobTracker.onWorldChange(); } catch (Throwable ignored) {}
        try { GomodSocialStore.resetLocalOnlineSession(); } catch (Throwable ignored) {}
    }

    @SubscribeEvent
    public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        TabPerformanceDebug.onHudRefresh();
        me.napim.gomod.features.InventoryQolHudUnderlay.beginHudFrame();
        float partialTicks = event.getPartialTicks();
        MountXpOverlay.beginHudFrame();
        HorseFoodHudRenderer.beginFrame();
        HorseDonkeyTracker.beginFoodNametagFrame();
        try {
            MobTargetDisplay.renderOverlay();
        } catch (Throwable ignored) {
        }
        net.weavemc.loader.api.event.EventBus.post(
                new net.weavemc.loader.api.event.RenderGameOverlayEvent.Pre(partialTicks));
    }

    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        float partialTicks = event.getPartialTicks();
        try { HorseFoodHudRenderer.renderOverlay(); } catch (Throwable ignored) {}
        try { MountXpOverlay.renderFallbackAfterOverlay(); } catch (Throwable ignored) {}
        try { BlocksFinder.renderHud(partialTicks); } catch (Throwable ignored) {}

        net.weavemc.loader.api.event.EventBus.post(
                new net.weavemc.loader.api.event.RenderGameOverlayEvent.Post(partialTicks));
        net.weavemc.loader.api.event.EventBus.post(
                new net.weavemc.loader.api.event.RenderGameOverlayEvent(partialTicks));
        try { me.napim.gomod.gui.GomodMenuCloseAnim.renderIfActive(partialTicks); } catch (Throwable ignored) {}
        HudGlState.restoreForOverlayText();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldEvent event) {
        // Weave posts this after world/tile rendering and immediately before the hand pass.
        // It is also the safe outer-world location for Lunar's deferred spectator/self-opacity draws; the
        // Forge build uses EntityRendererSelfOpacityTranslucentMixin / EntityRendererMountFlushMixin for this stage.
        final float partialTicks = event.getPartialTicks();
        boolean barrierRenderedBeforeSelfOpacity = false;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getRenderManager() != null) {
                if (MountHeadDim.hasDeferredMountRender()) {
                    MountHeadDim.flushDeferredMountRender(mc.getRenderManager());
                }
                if (LightningRenderFix.hasDeferred()) {
                    LightningRenderFix.flushDeferred(mc.getRenderManager());
                }
                if (VisibleSpecs.needsRenderWorldFallbackFlush()) {
                    VisibleSpecs.flushDeferred(mc.getRenderManager());
                }
                if (SelfOpacity.hasDeferredRender()
                        && SelfOpacity.shouldFlushSelfOpacityAfterTranslucentWorldPassPostBlockLayer()) {
                    if (PlayersPlayMode.getCurrentRenderTarget() == null) {
                        barrierRenderedBeforeSelfOpacity = BarrierVisibilityWeaveFallbackRenderer.render(partialTicks);
                    }
                    SelfOpacity.flushDeferred(mc.getRenderManager(),
                            SelfOpacity.DeferredFlushSite.TRANSLUCENT_WORLD_PASS_POST_BLOCK_LAYER);
                }
            }
        } catch (Throwable t) {
            System.err.println("[gomod] Weave opacity flush: " + t.getMessage());
        }
        if (PlayersPlayMode.getCurrentRenderTarget() != null) {
            return;
        }
        final boolean skipLateBarrierFallback = barrierRenderedBeforeSelfOpacity;
        long total = RenderProfiler.begin("Weave.RenderWorld.total");
        try {
            GomodBlockOverlay.beginWorldEntitiesPass();
            HudGlState.restoreBeforeWorldText();
            net.weavemc.loader.api.event.EventBus.post(
                    new net.weavemc.loader.api.event.RenderWorldEvent(partialTicks));
            renderBlockOverlay(partialTicks);
            runProfiled("Weave.RenderWorld.PeeSurfaceStains", () -> PeeEffectManager.renderSurfaceStains(partialTicks));

            if (RoleplayMode.isEnabled()) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                return;
            }
            if (mc.getFramebuffer() != null) {
                mc.getFramebuffer().bindFramebuffer(false);
            }
            HudGlState.restoreBeforeWorldText();
            runProfiled("Weave.RenderWorld.EntityOutlineFallback",
                    () -> GomodEntityOutlineFallback.renderFromWeaveWorldEvent(mc, partialTicks));
            runProfiled("Weave.RenderWorld.DeferredNametags",
                    () -> DeferredNametagRenderer.flushFromWeaveWorldEvent());
            runProfiled("Weave.RenderWorld.GlowArrows", () -> LunarGlowArrowMarkers.render(partialTicks));
            runProfiled("Weave.RenderWorld.GlowTrail", () -> GlowTrailRenderer.cleanupAndRender(partialTicks));
            runProfiled("Weave.RenderWorld.PlayerTracers", () -> PlayerTracers.render(partialTicks));
            runProfiled("Weave.RenderWorld.DuelPredictor", () -> DuelPredictor.render(partialTicks));
            runProfiled("Weave.RenderWorld.CageChest", () -> CageChestOverlay.render(partialTicks));
            runProfiled("Weave.RenderWorld.CageLootNames", () -> CageMiddleLootNametags.render(partialTicks));
            runProfiled("Weave.RenderWorld.ChestDump", () -> BlitzChestDumpLoggedOverlay.render(partialTicks));
            runProfiled("Weave.RenderWorld.Checkpoints", () -> ChestCheckpointLabels.render(partialTicks));
            HudGlState.restoreBeforeWorldText();
            runProfiled("Weave.RenderWorld.HorseFood", () -> HorseDonkeyTracker.renderWorldFoodNametags(partialTicks));
            runProfiled("Weave.RenderWorld.MobNames",
                    () -> renderMobNamesAfterWorld(partialTicks));
            runProfiled("Weave.RenderWorld.MobHealth",
                    () -> renderMobHealthAfterWorld(partialTicks));
            runProfiled("Weave.RenderWorld.MobDisguise", () -> LocalMobDisguise.renderAfterWorld(mc, partialTicks));
            runProfiled("Weave.RenderWorld.OpacityRestore", () -> BlocksFinder.renderTrackedWorldRestore(partialTicks));
            runProfiled("Weave.RenderWorld.BlocksFinder", () -> BlocksFinder.render(partialTicks));
            if (!skipLateBarrierFallback) {
                runProfiled("Weave.RenderWorld.BarrierFallback", () -> BarrierVisibilityWeaveFallbackRenderer.render(partialTicks));
            }
            runProfiled("Weave.RenderWorld.TntPreview", () -> TntPlacementPreview.render(partialTicks));
        } catch (Throwable ignored) {
        } finally {
            // The barrier fallback returns early when no barrier is in view, while other
            // overlays can leave different GL state on that path. Normalize before the
            // first-person hand pass every time so the arm does not change with visibility.
            try { me.napim.gomod.features.BarrierVisibilityDebugCapture.sample("world.before-hand-reset",
                    "barrierRenderedBeforeSelfOpacity=" + barrierRenderedBeforeSelfOpacity
                            + " skipLateFallback=" + skipLateBarrierFallback); } catch (Throwable ignored) {}
            try { HudGlState.restoreForFirstPersonHand(); } catch (Throwable ignored) {}
            try { me.napim.gomod.features.BarrierVisibilityDebugCapture.sample("world.after-hand-reset",
                    "barrierRenderedBeforeSelfOpacity=" + barrierRenderedBeforeSelfOpacity
                            + " skipLateFallback=" + skipLateBarrierFallback); } catch (Throwable ignored) {}
            RenderProfiler.end("Weave.RenderWorld.total", total);
        }
    }

    private static void renderMobNamesAfterWorld(float partialTicks) {
        if (VisibleSpecs.shouldAllowNametagThroughBlocksNow()) {
            MobNametags.renderMissingWorldMobNametagsThroughChests(partialTicks);
        } else {
            MobNametags.renderWorldMobNametags(partialTicks);
        }
    }

    private static void renderMobHealthAfterWorld(float partialTicks) {
        if (VisibleSpecs.shouldAllowNametagThroughBlocksNow()) {
            MobHealthDisplay.renderMissingWorldHealthNametagsThroughChests(partialTicks);
        } else {
            MobHealthDisplay.renderWorldHealthNametags(partialTicks);
        }
    }

    private static void renderBlockOverlay(float partialTicks) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.gameSettings == null) {
                return;
            }
            GomodBlockOverlay.render(partialTicks);
            GomodBlockOverlayPlayerOutline.render(partialTicks);
        } catch (Throwable ignored) {
        } finally {
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && mc.gameSettings != null && mc.gameSettings.thirdPersonView == 0
                        && GomodBlockOverlay.drewThisFrame()) {
                    GomodBlockOverlay.prepareFirstPersonHandGl();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        if (event.isCancelled()) {
            return;
        }

        if(me.napim.gomod.features.LunarFitNotifications.handleMouseEvent(event.getX(),event.getY(),event.getButton(),event.getButtonState())){
            event.setCancelled(true);return;
        }

        net.weavemc.loader.api.event.MouseEvent bridged =
                new net.weavemc.loader.api.event.MouseEvent(
                        event.getButton(), event.getButtonState(), event.getX(), event.getY());
        net.weavemc.loader.api.event.EventBus.post(bridged);
        if (bridged.isCancelled()) {
            event.setCancelled(true);
            return;
        }

        int wheel = event.getDWheel();
        try {
            if (wheel != 0 && BlocksFinder.handleWorldOpacityScroll(wheel)) {
                event.setCancelled(true);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            if (wheel != 0 && (AdvancedRenderDistance.handleScroll(wheel)
                    || AdvancedRenderDistance.wouldHandleScroll())) {
                event.setCancelled(true);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            if (wheel != 0 && handlePerspectiveScroll(wheel)) {
                event.setCancelled(true);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            handleFakeBreakRestoreClick(event);
        } catch (Throwable ignored) {}
        try {
            handleChestRouteClick(event);
        } catch (Throwable ignored) {}
    }

    private static void handleFakeBreakRestoreClick(MouseEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.currentScreen != null) {
            return;
        }
        if (event.getButton() == 1 && event.getButtonState() && FakeBreak.tryRestoreTargetedBrokenBlock(mc)) {
            event.setCancelled(true);
        }
    }

    private static boolean handlePerspectiveScroll(int wheel) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null || mc.gameSettings.thirdPersonView == 0
                || !PerspectiveDistance.isEnabled() || !PerspectiveDistance.isAdvancedPerspectiveAllowed(mc)
                || mc.currentScreen != null) {
            return false;
        }
        int zoomKey = PerspectiveDistance.getZoomKeyCode();
        boolean held = zoomKey > 0 && org.lwjgl.input.Keyboard.isKeyDown(zoomKey);
        if (!PerspectiveDistance.isRequireHold() || held) {
            int notches = (int) Math.signum(wheel) * Math.max(1, Math.abs(wheel) / 120);
            PerspectiveDistance.setDistance(PerspectiveDistance.getDistance()
                    - notches * PerspectiveDistance.getScrollStep());
            return true;
        }
        return PerspectiveDistance.isLockScroll();
    }

    private static void handleChestRouteClick(MouseEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        int button = event.getButton();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || !ChestRouteManager.isEditMode()
                || (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame())
                || (button != 0 && button != 1) || !event.getButtonState()) {
            return;
        }
        if (button == 0) {
            if (ChestRouteManager.tryLeftClickCheckpointInteraction(mc)) event.setCancelled(true);
        } else {
            ChestRouteManager.signalVanillaRightClick();
            if (ChestRouteManager.tryLongRayCheckpointOnRightClick(mc)) event.setCancelled(true);
        }
    }

    private static void runProfiled(String name, @NotNull Runnable action) {
        long start = RenderProfiler.begin(name);
        try {
            action.run();
        } catch (Throwable ignored) {
        } finally {
            RenderProfiler.end(name, start);
        }
    }
}
