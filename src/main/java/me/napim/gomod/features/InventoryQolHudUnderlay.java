package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

/** Shared before-icon pass for hotbar and InventoryHUD; the caller owns slot transforms. */
public final class InventoryQolHudUnderlay {
    public static final String SOURCE_HOTBAR = "hotbar";
    public static final String SOURCE_HOTBAR_FALLBACK = "hotbar_fallback";
    public static final String SOURCE_INVENTORY_HUD = "inventory_hud";

    /** Reused on the client render thread; one scratch intent per player-inventory slot avoids per-frame garbage. */
    private static final BestArmorDisplay.BestArmorUnderlayIntent[] HUD_INTENTS =
            new BestArmorDisplay.BestArmorUnderlayIntent[36];

    private static int renderedHotbarSlots;
    private static long hudFrameSerial;
    private static boolean nativeHotbarObserved;

    private InventoryQolHudUnderlay() {}

    public static void beginHudFrame() {
        renderedHotbarSlots = 0;
        hudFrameSerial++;
    }

    public static long getHudFrameSerial() {
        return hudFrameSerial;
    }

    public static boolean wasHotbarSlotRendered(int slot) {
        return slot >= 0 && slot < 9 && (renderedHotbarSlots & (1 << slot)) != 0;
    }

    private static void markHotbarSlotRendered(int slot) {
        if (slot >= 0 && slot < 9) renderedHotbarSlots |= 1 << slot;
    }

    public static boolean shouldRenderHotbarFallback() {
        // Lunar can reuse or redraw a native HUD layer independently of Weave's overlay events.
        // Seeing no native item draw in this event therefore does not mean the hook is absent.
        return !nativeHotbarObserved;
    }

    private static boolean beginSlotDraw(String source, int slot) {
        if (SOURCE_HOTBAR.equals(source)) {
            if (slot < 0 || slot >= 9) return false;
            nativeHotbarObserved = true;
            markHotbarSlotRendered(slot);
            // Each real icon draw needs its underlay, including redraws of a freshly cleared
            // Lunar layer. A previous fallback or native draw must never suppress this pass.
            return true;
        }
        if (SOURCE_HOTBAR_FALLBACK.equals(source)) {
            if (slot < 0 || slot >= 9 || !shouldRenderHotbarFallback() || wasHotbarSlotRendered(slot)) {
                return false;
            }
            markHotbarSlotRendered(slot);
        }
        return true;
    }

    public static void renderSlot(int inventorySlot, int x, int y, EntityPlayer player) {
        renderSlot(inventorySlot, x, y, player, "unknown");
    }

    /** Native Lunar hook called inside GuiIngame's real hotbar render pass when its mixin is skipped. */
    public static void renderNativeHotbarSlot(int inventorySlot, int x, int y) {
        if (inventorySlot < 0 || inventorySlot > 8) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            renderSlot(inventorySlot, x, y, mc.thePlayer, SOURCE_HOTBAR);
        }
    }

    public static void renderSlot(int inventorySlot, int x, int y, EntityPlayer player, String source) {
        if (player == null || player.inventory == null || inventorySlot < 0 || inventorySlot > 35) {
            return;
        }
        if (!beginSlotDraw(source, inventorySlot)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        // Compile-time gate: public builds keep the hot HUD path free of debug locks and GL queries.
        boolean capture = false;
        if (!GomodReleaseBuild.PUBLIC_RELEASE && InventoryQolDebugCapture.isCaptureArmed()) {
            capture = InventoryQolDebugCapture.shouldSample(source, inventorySlot, mc);
        }
        String glBefore = capture ? InventoryQolDebugCapture.glState() : null;
        String pixelsBefore = capture ? InventoryQolDebugCapture.framebufferPixels(x, y, mc) : null;
        boolean bestArmor = BestArmorDisplay.isOverlayActiveInContext(mc);
        boolean useless = UselessItemOverlay.isOverlayActiveInContext(mc);
        if (!bestArmor && !useless) {
            if (capture) {
                InventoryQolDebugCapture.recordSlot(source, inventorySlot, x, y, mc, player,
                        player.inventory.getStackInSlot(inventorySlot), BestArmorDisplay.isEnabled(), false,
                        false, -1, false, UselessItemOverlay.isEnabled(), false, 0,
                        glBefore, null, InventoryQolDebugCapture.glState(), pixelsBefore,
                        InventoryQolDebugCapture.framebufferPixels(x, y, mc), "both_features_inactive_in_context");
            }
            return;
        }
        ItemStack stack = player.inventory.getStackInSlot(inventorySlot);
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) {
            if (capture) {
                InventoryQolDebugCapture.recordSlot(source, inventorySlot, x, y, mc, player,
                        stack, BestArmorDisplay.isEnabled(), bestArmor, false, -1, false,
                        UselessItemOverlay.isEnabled(), useless, 0,
                        glBefore, null, InventoryQolDebugCapture.glState(), pixelsBefore,
                        InventoryQolDebugCapture.framebufferPixels(x, y, mc), "empty_slot");
            }
            return;
        }
        BestArmorDisplay.BestArmorUnderlayIntent intent = null;
        if (bestArmor) {
            intent = HUD_INTENTS[inventorySlot];
            if (intent == null) {
                intent = new BestArmorDisplay.BestArmorUnderlayIntent();
                HUD_INTENTS[inventorySlot] = intent;
            }
            BestArmorDisplay.computeBestArmorUnderlayIntentCached(stack, player.inventory, null, intent);
            if (!intent.draw && inventorySlot >= 9
                    && BestArmorDisplay.isSlotBetterArmor(player.inventory, inventorySlot)) {
                intent.draw = true;
                intent.tieType = -1;
                intent.split = false;
            }
        }
        boolean bestArmorDraw = intent != null && intent.draw;
        if (!useless && !bestArmorDraw) {
            if (capture) {
                InventoryQolDebugCapture.recordSlot(source, inventorySlot, x, y, mc, player,
                        stack, BestArmorDisplay.isEnabled(), bestArmor, false,
                        intent == null ? -1 : intent.tieType, intent != null && intent.split,
                        UselessItemOverlay.isEnabled(), useless, 0,
                        glBefore, null, InventoryQolDebugCapture.glState(), pixelsBefore,
                        InventoryQolDebugCapture.framebufferPixels(x, y, mc), "no_overlay_decision_for_slot");
            }
            return;
        }
        // Color AND coverage must reach Lunar's layer. Never mask alpha writes on HUD underlays.
        HudGlState.restoreForInventoryUnderlay();
        String glDraw = capture ? InventoryQolDebugCapture.glState() : null;
        int uselessLevel = 0;
        try {
            if (bestArmorDraw) {
                BestArmorDisplay.drawBestArmorUnderlayAbsolute(x, y, x + 16, y + 16, intent);
            }
            if (useless) {
                uselessLevel = UselessItemOverlay.renderInventoryHudSlotOverlayGeometry(
                        inventorySlot, x, y, player, mc);
            }
        } finally {
            HudGlState.restoreAfterInventoryUnderlay();
        }
        if (capture) {
            InventoryQolDebugCapture.recordSlot(source, inventorySlot, x, y, mc, player,
                    stack, BestArmorDisplay.isEnabled(), bestArmor, bestArmorDraw,
                    intent == null ? -1 : intent.tieType, intent != null && intent.split,
                    UselessItemOverlay.isEnabled(), useless, uselessLevel,
                    glBefore, glDraw, InventoryQolDebugCapture.glState(), pixelsBefore,
                    InventoryQolDebugCapture.framebufferPixels(x, y, mc), "underlay_pass_completed");
        }
    }
}
