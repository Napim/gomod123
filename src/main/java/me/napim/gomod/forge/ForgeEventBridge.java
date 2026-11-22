package me.napim.gomod.forge;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.Packet;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import me.napim.gomod.features.BlocksFinder;
import me.napim.gomod.features.ChestCheckpointLabels;
import me.napim.gomod.features.BlitzChestDumpLoggedOverlay;
import me.napim.gomod.features.CageChestOverlay;
import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.PerspectiveDistance;
import me.napim.gomod.features.PeeEffectManager;
import me.napim.gomod.features.ClientTickHub;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.features.LocalMobDisguise;
import me.napim.gomod.features.MountHeadDim;
import me.napim.gomod.features.MountXpOverlay;
import me.napim.gomod.features.MobTargetDisplay;
import me.napim.gomod.features.PlayersPlayMode;
import me.napim.gomod.features.RoleplayMode;
import me.napim.gomod.features.GomodBlockOverlay;
import me.napim.gomod.features.GomodBlockOverlayPlayerOutline;
import me.napim.gomod.features.SpawnedMobTracker;
import me.napim.gomod.features.SpectatorTracker;
import me.napim.gomod.features.ScoreboardTeamDisguiseDebugCapture;
import me.napim.gomod.render.HorseFoodHudRenderer;
import me.napim.gomod.util.GlowTrailRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import me.napim.gomod.util.HudGlState;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import net.weavemc.loader.api.event.ChatReceivedEvent;
import net.weavemc.loader.api.event.EventBus;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.PlayerListEvent;
import net.weavemc.loader.api.event.RenderWorldEvent;

public class ForgeEventBridge {
    private static final String HANDLER_NAME = "gomod_packet_handler";

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTransparentMountLivingPre(RenderLivingEvent.Pre<? extends EntityLivingBase> event) {
        try {
            if (isGomodTransparentMountEvent(event.entity)) {
                MountHeadDim.enforceTransparentMountRenderState();
            }
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onTransparentMountLivingPost(RenderLivingEvent.Post<? extends EntityLivingBase> event) {
        try {
            if (isGomodTransparentMountEvent(event.entity)) {
                MountHeadDim.enforceTransparentMountRenderState();
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isGomodTransparentMountEvent(Entity entity) {
        return MountHeadDim.isLocalPlayerRiddenMount(entity) && MountHeadDim.isRenderingMountDim();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        try { ScoreboardTeamDisguiseDebugCapture.recordTick(Minecraft.getMinecraft(), "forge-client-tick"); }
        catch (Throwable failure) { ScoreboardTeamDisguiseDebugCapture.recordHookFailure("forge-client-tick", failure); }
        me.napim.gomod.features.BarrierVisibilityModelFix.clientTickEnd();
        me.napim.gomod.features.BarrierVisibilityRangeTicker.clientTickEnd();
        me.napim.gomod.features.GomodKeybinds.onClientTick();
        EventBus.post(new net.weavemc.loader.api.event.TickEvent());
        try {
            ClientTickHub.runClientFeatureTicks();
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onRenderOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        MountXpOverlay.beginHudFrame();
        HorseFoodHudRenderer.beginFrame();
        me.napim.gomod.features.HorseDonkeyTracker.beginFoodNametagFrame();
        try {
            MobTargetDisplay.renderOverlay();
        } catch (Throwable ignored) {
        }
        EventBus.post(new net.weavemc.loader.api.event.RenderGameOverlayEvent.Pre(event.partialTicks));
    }

    @SubscribeEvent
    public void onRenderOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) {
            return;
        }
        HorseFoodHudRenderer.renderOverlay();
        try {
            MountXpOverlay.renderFallbackAfterOverlay();
        } catch (Throwable ignored) {
        }
        try {
            BlocksFinder.renderHud(event.partialTicks);
        } catch (Throwable ignored) {
        }
        EventBus.post(new net.weavemc.loader.api.event.RenderGameOverlayEvent.Post(event.partialTicks));
        EventBus.post(new net.weavemc.loader.api.event.RenderGameOverlayEvent(event.partialTicks));
        // Render gomod menu close animation overlay if active
        try {
            me.napim.gomod.gui.GomodMenuCloseAnim.renderIfActive(event.partialTicks);
        } catch (Throwable ignored) {
        }
        // Weave listeners often touch lighting/blend/masks; reset before other Forge Post handlers
        // (e.g. external HUD mods) run in the same tick.
        HudGlState.restoreForOverlayText();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDrawBlockHighlightGomodOverlay(DrawBlockHighlightEvent event) {
        try {
            GomodBlockOverlay.onDrawBlockHighlight(event);
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onRenderWorldLastGomodBlockOverlay(RenderWorldLastEvent event) {
        if (PlayersPlayMode.getCurrentRenderTarget() != null) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.gameSettings != null && mc.gameSettings.thirdPersonView != 0) {
                return;
            }
            GomodBlockOverlay.render(event.partialTicks);
            GomodBlockOverlayPlayerOutline.render(event.partialTicks);
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

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onRenderWorld(RenderWorldLastEvent event) {
        // Forge calls dispatchRenderLast once per renderWorldPass (inside every renderWorld).
        // PlayersPlay issues 3–4 extra full renderWorld calls with a scissored quarter viewport;
        // running our world-last overlays here rebinding FBO/tracers/glow resets GL and draws at
        // wrong resolution — visibly hits the first embedded pane (top-left) hardest.
        if (PlayersPlayMode.getCurrentRenderTarget() != null) {
            return;
        }
        long __rpRw = RenderProfiler.begin("Forge.RenderWorldLast.total");
        try {
            long tEv = RenderProfiler.begin("Forge.RenderWorldLast.WeaveEventBus");
            try {
                EventBus.post(new RenderWorldEvent(event.partialTicks));
            } finally {
                RenderProfiler.end("Forge.RenderWorldLast.WeaveEventBus", tEv);
            }
            // World-last drawing assumes the main full-frame camera; roleplay swaps renderViewEntity.
            if (RoleplayMode.isEnabled()) {
                return;
            }
            try {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc == null || mc.theWorld == null) {
                    return;
                }
                if (mc.getFramebuffer() != null) {
                    mc.getFramebuffer().bindFramebuffer(false);
                }
                PeeEffectManager.renderSurfaceStains(event.partialTicks);
                long t1 = RenderProfiler.begin("Forge.RenderWorldLast.GlowTrailRenderer");
                try {
                    GlowTrailRenderer.cleanupAndRender(event.partialTicks);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.GlowTrailRenderer", t1);
                }
                long t2 = RenderProfiler.begin("Forge.RenderWorldLast.PlayerTracers");
                try {
                    me.napim.gomod.features.PlayerTracers.render(event.partialTicks);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.PlayerTracers", t2);
                }
                long t2dp = RenderProfiler.begin("Forge.RenderWorldLast.DuelPredictor");
                try {
                    me.napim.gomod.features.DuelPredictor.render(event.partialTicks);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.DuelPredictor", t2dp);
                }
                long t3 = RenderProfiler.begin("Forge.RenderWorldLast.CageChestOverlay");
                try {
                    CageChestOverlay.render(event.partialTicks);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.CageChestOverlay", t3);
                }
                long t3c = RenderProfiler.begin("Forge.RenderWorldLast.CageMiddleLootNametags");
                try {
                    me.napim.gomod.features.CageMiddleLootNametags.render(event.partialTicks);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.CageMiddleLootNametags", t3c);
                }
                long t3b = RenderProfiler.begin("Forge.RenderWorldLast.BlitzChestDumpLoggedOverlay");
                try {
                    BlitzChestDumpLoggedOverlay.render(event.partialTicks);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.BlitzChestDumpLoggedOverlay", t3b);
                }
                long t4 = RenderProfiler.begin("Forge.RenderWorldLast.ChestCheckpointLabels");
                try {
                    ChestCheckpointLabels.render(event.partialTicks);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.ChestCheckpointLabels", t4);
                }
                long t5 = RenderProfiler.begin("Forge.RenderWorldLast.HorseDonkeyTracker.worldNametags");
                try {
                    me.napim.gomod.features.HorseDonkeyTracker.renderWorldFoodNametags(event.partialTicks);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.HorseDonkeyTracker.worldNametags", t5);
                }
                final boolean forceNametagsThroughChests =
                        me.napim.gomod.features.VisibleSpecs.shouldAllowNametagThroughBlocksNow();
                if (forceNametagsThroughChests) {
                    me.napim.gomod.features.MobNametags.beginWorldMobNametagFrame();
                    me.napim.gomod.features.MobHealthDisplay.beginWorldHealthNametagFrame();
                }
                long t5a = RenderProfiler.begin("Forge.RenderWorldLast.MobNametags.worldNametags");
                try {
                    me.napim.gomod.features.MobNametags.renderWorldMobNametags(event.partialTicks,
                            forceNametagsThroughChests);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.MobNametags.worldNametags", t5a);
                }
                long t5b = RenderProfiler.begin("Forge.RenderWorldLast.MobHealthDisplay.worldNametags");
                try {
                    me.napim.gomod.features.MobHealthDisplay.renderWorldHealthNametags(event.partialTicks,
                            forceNametagsThroughChests);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.MobHealthDisplay.worldNametags", t5b);
                }
                long t6 = RenderProfiler.begin("Forge.RenderWorldLast.LocalMobDisguise");
                try {
                    LocalMobDisguise.renderAfterWorld(mc, event.partialTicks);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.LocalMobDisguise", t6);
                }
                long t7a = RenderProfiler.begin("Forge.RenderWorldLast.BlocksFinder.worldOpacityRestore");
                try {
                    BlocksFinder.renderTrackedWorldRestore(event.partialTicks);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.BlocksFinder.worldOpacityRestore", t7a);
                }
                long t7 = RenderProfiler.begin("Forge.RenderWorldLast.BlocksFinder");
                try {
                    BlocksFinder.render(event.partialTicks);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.BlocksFinder", t7);
                }
                long t7b = RenderProfiler.begin("Forge.RenderWorldLast.PlacementPreviews");
                try {
                    me.napim.gomod.features.TntPlacementPreview.render(event.partialTicks);
                } finally {
                    RenderProfiler.end("Forge.RenderWorldLast.PlacementPreviews", t7b);
                }
            } catch (Throwable ignored) {
            }
        } finally {
            RenderProfiler.end("Forge.RenderWorldLast.total", __rpRw);
        }
    }

    /**
     * Mirrors {@link me.napim.gomod.mixins.MinecraftClickPulseMixin} when that inject does not apply (Lunar / mapping issues).
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onCeEditMouseFallback(MouseEvent event) {
        if (event.isCanceled()) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
                return;
            }
            if (!ChestRouteManager.isEditMode()) {
                return;
            }
            if (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) {
                return;
            }
            int b = event.button;
            if (b != 0 && b != 1) {
                return;
            }
            if (!event.buttonstate) {
                return;
            }
            if (b == 0) {
                if (ChestRouteManager.tryLeftClickCheckpointInteraction(mc)) {
                    event.setCanceled(true);
                }
            } else {
                ChestRouteManager.signalVanillaRightClick();
                if (ChestRouteManager.tryLongRayCheckpointOnRightClick(mc)) {
                    event.setCanceled(true);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBlocksFinderWorldOpacityScroll(MouseEvent event) {
        if (event.isCanceled() || event.dwheel == 0) {
            return;
        }
        try {
            if (me.napim.gomod.features.BlocksFinder.handleWorldOpacityScroll(event.dwheel)) {
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onAdvancedRenderDistanceScroll(MouseEvent event) {
        if (event.isCanceled() || event.dwheel == 0) {
            return;
        }
        try {
            if (me.napim.gomod.features.AdvancedRenderDistance.handleScroll(event.dwheel)) {
                event.setCanceled(true);
            } else if (me.napim.gomod.features.AdvancedRenderDistance.wouldHandleScroll()) {
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPerspectiveScroll(MouseEvent event) {
        if (event.isCanceled() || event.dwheel == 0) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.gameSettings == null) return;
            if (mc.gameSettings.thirdPersonView == 0) return;
            if (!PerspectiveDistance.isEnabled()) return;
            if (!PerspectiveDistance.isAdvancedPerspectiveAllowed(mc)) return;
            if (mc.currentScreen != null) return;

            int zoomKey = PerspectiveDistance.getZoomKeyCode();
            boolean zoomKeyHeld = zoomKey > 0 && org.lwjgl.input.Keyboard.isKeyDown(zoomKey);
            boolean requireHold = PerspectiveDistance.isRequireHold();

            if (!requireHold || zoomKeyHeld) {
                int dWheel = event.dwheel;
                int notches = (int) Math.signum(dWheel) * Math.max(1, Math.abs(dWheel) / 120);
                float step = PerspectiveDistance.getScrollStep();
                float current = PerspectiveDistance.getDistance();
                float updated = current - (notches * step);
                PerspectiveDistance.setDistance(updated);
                event.setCanceled(true);
            } else if (PerspectiveDistance.isLockScroll()) {
                event.setCanceled(true);
            }
        } catch (Throwable ignored) {}
    }

    @SubscribeEvent
    public void onMouse(MouseEvent event) {
        net.weavemc.loader.api.event.MouseEvent we =
                new net.weavemc.loader.api.event.MouseEvent(event.button, event.buttonstate, event.x, event.y);
        EventBus.post(we);
        if (we.isCancelled()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onClientConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        attachHandler(event.manager);
        try { me.napim.gomod.features.AutoQueue.onConnectionTransition(); } catch (Throwable ignored) {}
        try { me.napim.gomod.features.PlayerQueuer.onConnectionTransition(); } catch (Throwable ignored) {}
        try { me.napim.gomod.features.KillCounter.onWorldTransition(); } catch (Throwable ignored) {}
        try {
            me.napim.gomod.features.GomodSocialStore.resetLocalOnlineSession();
            me.napim.gomod.features.GomodSocialStore.ensureLocalOnlineSession();
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public void onClientDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        detachHandler(event.manager);
        try { me.napim.gomod.features.AutoQueue.onConnectionTransition(); } catch (Throwable ignored) {}
        try { me.napim.gomod.features.PlayerQueuer.onConnectionTransition(); } catch (Throwable ignored) {}
        try { me.napim.gomod.features.KillCounter.onWorldTransition(); } catch (Throwable ignored) {}
        try { me.napim.gomod.features.EntityNametagLimiter.clear(); } catch (Throwable ignored) {}
        try { me.napim.gomod.render.DeferredNametagRenderer.clear(); } catch (Throwable ignored) {}
        try { me.napim.gomod.features.SpawnedMobTracker.onWorldChange(); } catch (Throwable ignored) {}
        try { me.napim.gomod.features.GomodSocialStore.resetLocalOnlineSession(); } catch (Throwable ignored) {}
    }

    private void attachHandler(NetworkManager manager) {
        if (manager == null || manager.channel() == null) {
            return;
        }
        if (manager.channel().pipeline().get(HANDLER_NAME) != null) {
            return;
        }
        manager.channel().pipeline().addBefore("packet_handler", HANDLER_NAME, new PacketInterceptor());
    }

    private void detachHandler(NetworkManager manager) {
        if (manager == null || manager.channel() == null) {
            return;
        }
        if (manager.channel().pipeline().get(HANDLER_NAME) != null) {
            manager.channel().pipeline().remove(HANDLER_NAME);
        }
    }

    private static class PacketInterceptor extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof Packet)) {
                super.channelRead(ctx, msg);
                return;
            }

            Packet<?> packet = (Packet<?>) msg;
            try { if (me.napim.gomod.features.BlitzMatchIdentity.onIncomingPacket(packet)) return; } catch (Throwable ignored) {}
            try { me.napim.gomod.features.ForceGameChat.onIncomingPacket(packet); } catch (Throwable ignored) {}
            try { me.napim.gomod.features.BlitzRouletteDebug.recordInboundPacket(packet); } catch (Throwable ignored) {}
            try { ScoreboardTeamDisguiseDebugCapture.recordInboundTeamsPacket(packet); }
            catch (Throwable failure) { ScoreboardTeamDisguiseDebugCapture.recordHookFailure("forge-inbound-packet", failure); }
            PacketEvent.Receive packetEvent = new PacketEvent.Receive(packet);
            EventBus.post(packetEvent);
            if (packetEvent.isCancelled()) {
                return;
            }

            // Feed packets to the spectator tracker (spawn/move/destroy player entities)
            try { SpectatorTracker.onPacketReceived(packet); } catch (Throwable ignored) {}
            try { me.napim.gomod.features.NickRenamedItemScanner.onPacketReceived(packet); } catch (Throwable ignored) {}

            if (packet instanceof S02PacketChat) {
                S02PacketChat chat = (S02PacketChat) packet;
                ChatReceivedEvent chatEvent = new ChatReceivedEvent(chat.getChatComponent());
                EventBus.post(chatEvent);
                if (chatEvent.isCancelled()) {
                    return;
                }
            }

            if (packet instanceof S38PacketPlayerListItem) {
                S38PacketPlayerListItem listItem = (S38PacketPlayerListItem) packet;
                S38PacketPlayerListItem.Action action = listItem.getAction();
                if (action == S38PacketPlayerListItem.Action.ADD_PLAYER) {
                    EventBus.post(new PlayerListEvent.Add());
                } else if (action == S38PacketPlayerListItem.Action.REMOVE_PLAYER) {
                    EventBus.post(new PlayerListEvent.Remove());
                }
            }

            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (!(msg instanceof Packet)) {
                super.write(ctx, msg, promise);
                return;
            }

            Packet<?> packet = (Packet<?>) msg;
            PacketEvent.Send sendEvent = new PacketEvent.Send(packet);
            EventBus.post(sendEvent);
            if (sendEvent.isCancelled()) {
                return;
            }

            try {
                SpawnedMobTracker.onOutgoingClientPacket(packet);
            } catch (Throwable ignored) {
            }

            super.write(ctx, msg, promise);
        }
    }
}
