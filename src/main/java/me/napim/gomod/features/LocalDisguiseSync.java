/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.ScaledResolution
 *  net.minecraft.entity.player.InventoryPlayer
 *  net.minecraft.inventory.ContainerChest
 *  net.minecraft.inventory.IInventory
 *  net.minecraft.inventory.Slot
 *  net.minecraft.scoreboard.ScoreObjective
 *  net.weavemc.loader.api.event.ChatReceivedEvent
 *  net.weavemc.loader.api.event.PlayerListEvent$Add
 *  net.weavemc.loader.api.event.PlayerListEvent$Remove
 *  net.weavemc.loader.api.event.RenderGameOverlayEvent$Post
 *  net.weavemc.loader.api.event.RenderGameOverlayEvent$Pre
 *  net.weavemc.loader.api.event.SubscribeEvent
 *  net.weavemc.loader.api.event.TickEvent
 */
package me.napim.gomod.features;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.command.DisguiseCommand;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.DisguiseBlitzKitSelectorOverride;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.MixinStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.weavemc.loader.api.event.ChatReceivedEvent;
import net.weavemc.loader.api.event.PlayerListEvent;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

public class LocalDisguiseSync {
    private long lastSyncMs = 0L;
    private long lastSkullScanMs = 0L;
    private boolean applied = false;
    private int lastWorldIdentity = 0;
    private int lastOpenContainerIdentity = 0;
    private boolean pendingReconnectRestore = false;
    private boolean xpOverrideActive = false;
    private int xpLevel = 0;
    private float xpProgress = 0.0f;
    private int xpTotal = 0;

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!ClientTickHub.isFeatureEnabled("LocalDisguiseSync")) return;
        long _profT = FeatureProfiler.begin("LocalDisguiseSync");
        try {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        if (mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) {
            DisguiseBlitzKitSelectorOverride.onTick();
            this.lastWorldIdentity = 0;
            this.resetSkullDisguiseScanState();
            if (this.applied && !LocalDisguise.isEnabled()) {
                LocalDisguise.clearLiveOverrides();
                this.applied = false;
            }
            return;
        }
        int currentWorldIdentity = System.identityHashCode(mc.theWorld);
        if (currentWorldIdentity != this.lastWorldIdentity) {
            if (this.lastWorldIdentity != 0 && LocalDisguise.isEnabled()) {
                LocalDisguise.resetMatchKillCounterProgress();
            }
            this.lastWorldIdentity = currentWorldIdentity;
            this.pendingReconnectRestore = GomodInternalFeatures.isEnabled()
                    && ConfigManager.isDisguiseRestoreOnReconnect()
                    && !"OFF".equals(ConfigManager.getDisguiseReconnectKind())
                    && !LocalDisguise.isEnabled()
                    && !DisguiseCommand.isLoading();
            if (LocalDisguise.isEnabled()) {
                LocalDisguise.resetLiveOverrideReflection();
                this.lastSyncMs = 0L;
                this.resetSkullDisguiseScanState();
                LocalDisguise.applyLiveOverrides();
                this.applied = true;
            }
        }
        if (LocalDisguise.isEnabled()) {
            this.pendingReconnectRestore = false;
        } else if (this.pendingReconnectRestore && !DisguiseCommand.isLoading()) {
            this.pendingReconnectRestore = false;
            DisguiseCommand.restorePersistedDisguiseAsync();
        }
        if (!LocalDisguise.isEnabled()) {
            DisguiseBlitzKitSelectorOverride.onTick();
            this.resetSkullDisguiseScanState();
            if (this.applied) {
                LocalDisguise.clearLiveOverrides();
                this.applied = false;
            }
            return;
        }
        DisguiseBlitzKitSelectorOverride.onTick();
        long now = System.currentTimeMillis();
        if (now - this.lastSyncMs < 500L) {
            return;
        }
        this.lastSyncMs = now;
        LocalDisguise.applyLiveOverrides();
        this.applied = true;
        this.applySkullDisguiseIfNeeded(mc, now);
        } finally { FeatureProfiler.end("LocalDisguiseSync", _profT); }
    }

    private boolean shouldRestorePersistedDisguise() {
        return GomodInternalFeatures.isEnabled()
                && ConfigManager.isDisguiseRestoreOnReconnect()
                && !"OFF".equals(ConfigManager.getDisguiseReconnectKind());
    }

    @SubscribeEvent
    public void onChatReceived(ChatReceivedEvent event) {
        if (event == null || event.getMessage() == null) {
            return;
        }
        LocalDisguise.handleIncomingChat(event.getMessage().getUnformattedText());
        RemoteDisguiseManager.handleIncomingChat(event.getMessage().getUnformattedText());

        // ChatReceivedEvent can be delivered by the Forge packet bridge on Netty's thread. Keep this
        // handler observation-only: display rewriting belongs to GuiNewChatMixin, after the packet has
        // reached the client thread. Mutating/rebuilding the component here can block packet processing
        // behind LocalDisguise's render-state monitor and makes the original kill line disappear briefly.
    }

    @SubscribeEvent
    public void onRenderOverlayPre(RenderGameOverlayEvent.Pre event) {
        if (MixinStatus.guiIngameActive || this.xpOverrideActive) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        boolean localDisguiseXp = LocalDisguise.shouldOverrideHypixelLobbyXpBar();
        LocalDisguise.RemoteDisguiseProfile remoteTarget = localDisguiseXp
                ? null : (RemoteDisguiseManager.shouldOverrideLocalPlayerHypixelLobbyXpBar()
                ? RemoteDisguiseManager.getLocalPlayerNetworkXpTarget() : null);
        if (!localDisguiseXp && remoteTarget == null) {
            return;
        }
        this.xpOverrideActive = true;
        this.xpLevel = mc.thePlayer.experienceLevel;
        this.xpProgress = mc.thePlayer.experience;
        this.xpTotal = mc.thePlayer.experienceTotal;
        if (localDisguiseXp) {
            mc.thePlayer.experienceLevel = LocalDisguise.getDisguisedNetworkLevel();
            mc.thePlayer.experience = LocalDisguise.getDisguisedNetworkProgress();
            mc.thePlayer.experienceTotal = LocalDisguise.getDisguisedNetworkExperienceTotal();
            LocalDisguise.noteHypixelLobbyXpBarApplied();
        } else {
            mc.thePlayer.experienceLevel = remoteTarget.networkLevel;
            mc.thePlayer.experience = remoteTarget.networkProgress;
            mc.thePlayer.experienceTotal = remoteTarget.networkExperienceTotal;
        }
    }

    @SubscribeEvent
    public void onRenderOverlayPost(RenderGameOverlayEvent.Post event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (this.xpOverrideActive) {
            if (mc != null && mc.thePlayer != null) {
                mc.thePlayer.experienceLevel = this.xpLevel;
                mc.thePlayer.experience = this.xpProgress;
                mc.thePlayer.experienceTotal = this.xpTotal;
            }
            this.xpOverrideActive = false;
        }
        // Keep this Post hook restore-only. Disguise sidebar rewrites now mutate the real scoreboard rows directly.
    }

    @SubscribeEvent
    public void onPlayerListAdd(PlayerListEvent.Add event) {
        if (!LocalDisguise.isEnabled()) {
            return;
        }
        LocalDisguise.resetLiveOverrideReflection();
        LocalDisguise.applyLiveOverrides();
        this.applied = true;
        this.lastSyncMs = 0L;
        this.resetSkullDisguiseScanState();
        // Lunar rebuilds its TAB rows during teleports. Recompose the cache on the next render while the
        // render-time local-row guards prevent the raw profile IGN from flashing first.
        KillCounter.markTabCompositionDirty();
    }

    @SubscribeEvent
    public void onPlayerListRemove(PlayerListEvent.Remove event) {
        if (!LocalDisguise.isEnabled()) {
            return;
        }
        LocalDisguise.resetLiveOverrideReflection();
        this.lastSyncMs = 0L;
        this.resetSkullDisguiseScanState();
        KillCounter.markTabCompositionDirty();
    }

    private void applySkullDisguiseIfNeeded(Minecraft mc, long now) {
        if (mc == null || mc.thePlayer == null || !LocalDisguise.shouldApplyAuxiliaryOverrides()) {
            return;
        }
        int currentContainerIdentity = mc.thePlayer.openContainer == null
                ? 0
                : System.identityHashCode(mc.thePlayer.openContainer);
        boolean containerChanged = currentContainerIdentity != this.lastOpenContainerIdentity;
        if (!containerChanged && now - this.lastSkullScanMs < 1000L) {
            return;
        }
        this.lastOpenContainerIdentity = currentContainerIdentity;
        this.lastSkullScanMs = now;
        this.applySkullDisguise(mc);
    }

    private void applySkullDisguise(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || !LocalDisguise.shouldApplyAuxiliaryOverrides()) {
            return;
        }
        try {
            if (mc.thePlayer.openContainer != null && mc.thePlayer.openContainer.inventorySlots != null) {
                for (Object slotObj : mc.thePlayer.openContainer.inventorySlots) {
                    if (!(slotObj instanceof Slot)) continue;
                    LocalDisguise.applySkullDisguise(((Slot)slotObj).getStack());
                }
                return;
            }
            InventoryPlayer inv = mc.thePlayer.inventory;
            if (inv != null) {
                for (int i = 0; i < inv.getSizeInventory(); ++i) {
                    LocalDisguise.applySkullDisguise(inv.getStackInSlot(i));
                }
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    private void resetSkullDisguiseScanState() {
        this.lastSkullScanMs = 0L;
        this.lastOpenContainerIdentity = 0;
    }
}
