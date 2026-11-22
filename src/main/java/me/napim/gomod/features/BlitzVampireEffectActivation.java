package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

/**
 * Vampire Blitz star: chat fires when the star is used, but the 30s effect only starts after the player
 * right-clicks the Hypixel redstone activator (no chat). We must not start the HUD when that redstone is
 * dropped (Q) or thrown from the hand.
 */
public final class BlitzVampireEffectActivation {

    /** After local player gets Vampire from chat, redstone use within this window can start the HUD. */
    private static final long PENDING_CHAT_WINDOW_MS = 120_000L;
    /** Ticks to wait for server sync after {@code processRightClick} returned true but stack unchanged. */
    private static final int DEFERRED_VERIFY_TICKS = 8;
    /** After dropping held redstone, ignore brief right-click tails that might race the inventory update. */
    private static final int DROP_CANCEL_TICKS = 6;

    private static long pendingChatSinceMs;
    private static boolean snapshotWasRedstone;
    private static int snapshotRedstoneSize = -1;

    private static int deferredTicks;
    private static int deferredOriginalSize = -1;

    private static int dropCancelTicks;

    private BlitzVampireEffectActivation() {
    }

    public static void reset() {
        pendingChatSinceMs = 0L;
        snapshotWasRedstone = false;
        snapshotRedstoneSize = -1;
        deferredTicks = 0;
        deferredOriginalSize = -1;
        dropCancelTicks = 0;
    }

    /**
     * Local player used Vampire Blitz star (chat). Arms redstone right-click detection; does not start the HUD timer.
     */
    public static void markLocalPlayerReceivedVampireStarChat() {
        pendingChatSinceMs = System.currentTimeMillis();
    }

    public static void onLocalPlayerDroppedHeldRedstone() {
        deferredTicks = 0;
        deferredOriginalSize = -1;
        dropCancelTicks = DROP_CANCEL_TICKS;
        pendingChatSinceMs = 0L;
    }

    public static void clientTick(Minecraft mc) {
        if (dropCancelTicks > 0) {
            dropCancelTicks--;
        }
        if (deferredTicks <= 0) {
            return;
        }
        if (mc == null || mc.thePlayer == null) {
            clearDeferred();
            return;
        }
        deferredTicks--;
        if (tryActivateFromSnapshot(mc.thePlayer, deferredOriginalSize)) {
            clearDeferred();
        } else if (deferredTicks <= 0) {
            clearDeferred();
        }
    }

    private static void clearDeferred() {
        deferredTicks = 0;
        deferredOriginalSize = -1;
    }

    /** Call at HEAD of {@code processRightClick} / {@code processRightClickBlock} / {@code sendUseItem}. */
    public static void onProcessUseHead(ItemStack held) {
        snapshotWasRedstone = held != null && held.getItem() == Items.redstone;
        snapshotRedstoneSize = snapshotWasRedstone ? held.stackSize : -1;
    }

    /** Call at TAIL after vanilla right-click / use-item handling. */
    public static void onProcessUseTail(EntityPlayer player, boolean success) {
        if (!snapshotWasRedstone) {
            return;
        }
        snapshotWasRedstone = false;
        if (!(player instanceof EntityPlayerSP)) {
            return;
        }
        EntityPlayerSP sp = (EntityPlayerSP) player;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer != sp) {
            return;
        }
        if (!success) {
            return;
        }
        if (!GameContext.isOnHypixel(mc)) {
            return;
        }
        if (!GameContext.isBlitzGameActive(mc) && !GameContext.isBlitzCagePhaseSidebar(mc)) {
            return;
        }
        if (dropCancelTicks > 0) {
            return;
        }
        if (!isArmedFromVampireChat()) {
            return;
        }
        if (tryActivateFromSnapshot(sp, snapshotRedstoneSize)) {
            return;
        }
        if (snapshotRedstoneSize > 0) {
            deferredTicks = DEFERRED_VERIFY_TICKS;
            deferredOriginalSize = snapshotRedstoneSize;
        }
    }

    private static boolean isArmedFromVampireChat() {
        if (pendingChatSinceMs <= 0L) {
            return false;
        }
        return System.currentTimeMillis() - pendingChatSinceMs <= PENDING_CHAT_WINDOW_MS;
    }

    private static boolean tryActivateFromSnapshot(EntityPlayerSP sp, int prevSize) {
        if (prevSize < 1) {
            return false;
        }
        if (!isArmedFromVampireChat()) {
            return false;
        }
        ItemStack cur = sp.getHeldItem();
        if (!looksLikeVampireRedstoneConsumed(cur, prevSize)) {
            return false;
        }
        pendingChatSinceMs = 0L;
        BlitzStarEffectsHudTracker.onVampireActivatedFromRedstoneUse();
        return true;
    }

    /**
     * True when the main-hand stack is no longer the same redstone stack as before the click.
     * Treats full removal or swap away from redstone as consume; ignores single-piece placement
     * (still redstone, count dropped by 1) unless the player only held one unit.
     */
    private static boolean looksLikeVampireRedstoneConsumed(ItemStack cur, int prevSize) {
        if (cur == null || cur.getItem() != Items.redstone) {
            return true;
        }
        if (cur.stackSize >= prevSize) {
            return false;
        }
        return prevSize == 1;
    }
}
