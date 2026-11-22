package me.napim.gomod.features;

import me.napim.gomod.hooks.BestArmorGuiContainerDrawHook;
import me.napim.gomod.hooks.BestArmorGuiContainerBackgroundLayerHook;
import me.napim.gomod.hooks.BestArmorGuiContainerDrawSlotHeadHook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import me.napim.gomod.util.HudGlState;
import org.lwjgl.input.Mouse;

import java.util.List;

/**
 * Best-armor survival-inventory additions after container layout. Per-slot color tints are always rendered at
 * {@code GuiContainer.drawSlot} HEAD so item icons and tooltips remain above them.
 */
public final class BestArmorContainerOverlayDriver {

    private static volatile boolean loggedLayoutFailure;
    private static volatile boolean loggedFirstSuccessfulDraw;
    private static long lastLatePaintDedupKey = Long.MIN_VALUE;

    private BestArmorContainerOverlayDriver() {}

    /**
     * Called from bytecode at the end of {@link GuiContainer#drawScreen(int, int, float)} — correct z-order.
     */
    public static void onGuiContainerDrawScreenReturn(GuiContainer gui, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !BestArmorDisplay.isOverlayOrPercentageActiveInContext(mc)) {
            return;
        }
        if (mc.currentScreen != gui) {
            return;
        }
        if (mc.thePlayer == null || mc.thePlayer.inventory == null) {
            return;
        }
        if (BestArmorDisplay.shouldExcludeCurrentContainer()) {
            return;
        }
        int mode = BestArmorContainerRenderStrategy.get();
        boolean debug = ConfigManager.isBestArmorDebugOverlayPaint();
        // Mode 8 draws in per-slot HEAD + background-layer tail. If the hooks didn't transform, fall back
        // to other anchor strategies so something still works.
        if (mode == BestArmorContainerRenderStrategy.MODE_GUICONTAINER_DRAW_SLOT_HEAD_HOOK) {
            boolean hooksActive = BestArmorGuiContainerDrawSlotHeadHook.isPatchActive()
                && BestArmorGuiContainerBackgroundLayerHook.isPatchActive();
            if (hooksActive && !debug) {
                return;
            }
        }
        boolean overlayEnabled = BestArmorDisplay.isOverlayActiveInContext(mc);
        boolean percentageEnabled = BestArmorDisplay.isPercentageActiveInContext(mc);
        if (!overlayEnabled && percentageEnabled && mc.currentScreen instanceof GuiInventory) {
            renderPaint(mc, gui, mouseX, mouseY);
            return;
        }
        // Mode 5 paint normally runs from Minecraft.runTick after drawScreen, but that path is skipped when the
        // GuiContainer tail bytecode hook is active — run the same slot pass here so inventory/chest hotbar gets overlays.
        if (mode != BestArmorContainerRenderStrategy.MODE_GUICONTAINER_TAIL_HOOK
            && mode != BestArmorContainerRenderStrategy.MODE_GUICONTAINER_DRAW_SLOT_HEAD_HOOK
            && mode != BestArmorContainerRenderStrategy.MODE_MINECRAFT_AFTER_DRAW_SCREEN
            && !debug) {
            return;
        }
        // No ms-dedup here: overlay Post can run same ms earlier and would steal the dedup key.
        renderPaint(mc, gui, mouseX, mouseY);
    }

    /**
     * Fallback when {@link BestArmorGuiContainerDrawHook} did not apply (Genesis bake).
     */
    public static void renderContainerOverlaysForWeave(Minecraft mc) {
        if (BestArmorGuiContainerDrawHook.isPatchActive()) {
            return;
        }
        if (mc == null || !BestArmorDisplay.isOverlayOrPercentageActiveInContext(mc)) {
            return;
        }
        if (mc.thePlayer == null || mc.thePlayer.inventory == null) {
            return;
        }
        if (mc.currentScreen == null || !(mc.currentScreen instanceof GuiContainer)) {
            return;
        }
        if (BestArmorDisplay.shouldExcludeCurrentContainer()) {
            return;
        }
        int mode = BestArmorContainerRenderStrategy.get();
        // Mode 8 draws via dedicated GuiContainer hooks, so skip overlay fallback even if debug is ON.
        if (mode == BestArmorContainerRenderStrategy.MODE_GUICONTAINER_DRAW_SLOT_HEAD_HOOK) {
            boolean hooksActive = BestArmorGuiContainerDrawSlotHeadHook.isPatchActive()
                && BestArmorGuiContainerBackgroundLayerHook.isPatchActive();
            if (hooksActive) {
                return;
            }
        }
        GuiContainer gui = (GuiContainer) mc.currentScreen;
        boolean overlayEnabled = BestArmorDisplay.isOverlayActiveInContext(mc);
        boolean percentageEnabled = BestArmorDisplay.isPercentageActiveInContext(mc);
        if (!overlayEnabled && percentageEnabled && mc.currentScreen instanceof GuiInventory) {
            // Ensure percentage can render even when overlay highlights are OFF.
            if (latePaintDedupSkip(gui)) {
                return;
            }
            ScaledResolution sr = new ScaledResolution(mc);
            int dw = Math.max(1, mc.displayWidth);
            int dh = Math.max(1, mc.displayHeight);
            int mx = Mouse.getX() * sr.getScaledWidth() / dw;
            int my = sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / dh - 1;
            renderPaint(mc, gui, mx, my);
            return;
        }
        boolean debug = ConfigManager.isBestArmorDebugOverlayPaint();
        if (mode != BestArmorContainerRenderStrategy.MODE_WEAVE_OVERLAY_POST_FALLBACK
            && mode != BestArmorContainerRenderStrategy.MODE_GUICONTAINER_DRAW_SLOT_HEAD_HOOK
            && !debug) {
            return;
        }
        // gui already read above
        if (latePaintDedupSkip(gui)) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int dw = Math.max(1, mc.displayWidth);
        int dh = Math.max(1, mc.displayHeight);
        int mx = Mouse.getX() * sr.getScaledWidth() / dw;
        int my = sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / dh - 1;
        renderPaint(mc, gui, mx, my);
    }

    /**
     * Mixin after {@code GuiScreen.drawScreen} in {@code Minecraft.runTick} — skip if drawScreen hook ran.
     */
    public static void renderAfterCurrentScreenDraw() {
        if (BestArmorGuiContainerDrawHook.isPatchActive()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !BestArmorDisplay.isOverlayOrPercentageActiveInContext(mc)) {
            return;
        }
        if (mc.thePlayer == null || mc.thePlayer.inventory == null) {
            return;
        }
        if (mc.currentScreen == null || !(mc.currentScreen instanceof GuiContainer)) {
            return;
        }
        if (BestArmorDisplay.shouldExcludeCurrentContainer()) {
            return;
        }
        int mode = BestArmorContainerRenderStrategy.get();
        if (mode == BestArmorContainerRenderStrategy.MODE_GUICONTAINER_DRAW_SLOT_HEAD_HOOK) {
            boolean hooksActive = BestArmorGuiContainerDrawSlotHeadHook.isPatchActive()
                && BestArmorGuiContainerBackgroundLayerHook.isPatchActive();
            if (hooksActive) {
                return;
            }
        }
        GuiContainer gui = (GuiContainer) mc.currentScreen;
        boolean overlayEnabled = BestArmorDisplay.isOverlayActiveInContext(mc);
        boolean percentageEnabled = BestArmorDisplay.isPercentageActiveInContext(mc);
        if (!overlayEnabled && percentageEnabled && mc.currentScreen instanceof GuiInventory) {
            if (latePaintDedupSkip(gui)) {
                return;
            }
            ScaledResolution sr = new ScaledResolution(mc);
            int dw = Math.max(1, mc.displayWidth);
            int dh = Math.max(1, mc.displayHeight);
            int mx = Mouse.getX() * sr.getScaledWidth() / dw;
            int my = sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / dh - 1;
            renderPaint(mc, gui, mx, my);
            return;
        }
        boolean debug = ConfigManager.isBestArmorDebugOverlayPaint();
        if (mode != BestArmorContainerRenderStrategy.MODE_MINECRAFT_AFTER_DRAW_SCREEN
            && mode != BestArmorContainerRenderStrategy.MODE_GUICONTAINER_DRAW_SLOT_HEAD_HOOK
            && !debug) {
            return;
        }
        // gui already read above
        if (latePaintDedupSkip(gui)) {
            return;
        }
        ScaledResolution sr = new ScaledResolution(mc);
        int dw = Math.max(1, mc.displayWidth);
        int dh = Math.max(1, mc.displayHeight);
        int mx = Mouse.getX() * sr.getScaledWidth() / dw;
        int my = sr.getScaledHeight() - Mouse.getY() * sr.getScaledHeight() / dh - 1;
        renderPaint(mc, gui, mx, my);
    }

    private static boolean latePaintDedupSkip(GuiContainer gui) {
        int ms = (int) (System.currentTimeMillis() & 0x3fffffff);
        int sid = System.identityHashCode(gui);
        long dedup = (((long) sid) << 32) | (ms & 0xffffffffL);
        if (dedup == lastLatePaintDedupKey) {
            return true;
        }
        lastLatePaintDedupKey = dedup;
        return false;
    }

    private static void renderPaint(Minecraft mc, GuiContainer gui, int mouseX, int mouseY) {
        if (!BestArmorGuiLayoutAccess.canRead()) {
            if (!loggedLayoutFailure) {
                loggedLayoutFailure = true;
                System.err.println("[gomod] BestArmor: GuiContainer guiLeft/guiTop not found (reflection). Open inventory once; field list follows.");
                BestArmorGuiLayoutAccess.logDiagnosticOnce();
            }
            return;
        }

        int guiLeft;
        int guiTop;
        try {
            guiLeft = BestArmorGuiLayoutAccess.readGuiLeft(gui);
            guiTop = BestArmorGuiLayoutAccess.readGuiTop(gui);
        } catch (Throwable t) {
            return;
        }

        // Normal slot tints never belong in this late pass: it runs after item icons and may run after tooltips.
        // Retain the explicit magenta diagnostic paint because its purpose is to expose the active hook location.
        boolean wantDebugSlotPass = ConfigManager.isBestArmorDebugOverlayPaint();

        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        try {
            if (wantDebugSlotPass) {
                @SuppressWarnings("rawtypes")
                List raw = gui.inventorySlots.inventorySlots;
                for (int i = 0; i < raw.size(); i++) {
                    Object o = raw.get(i);
                    if (!(o instanceof Slot)) {
                        continue;
                    }
                    Slot slot = (Slot) o;
                    paintDebugArmorSlotIfAny(slot, guiLeft, guiTop);
                }
            }

            if (mc.currentScreen instanceof GuiInventory) {
                try {
                    if (!loggedFirstSuccessfulDraw) {
                        loggedFirstSuccessfulDraw = true;
                        String via = BestArmorGuiContainerDrawHook.isPatchActive() ? "GuiContainer.drawScreen hook" : "RenderGameOverlay fallback";
                        System.out.println("[gomod] BestArmor: survival inventory overlay OK (guiLeft=" + guiLeft + " guiTop=" + guiTop + ", " + via + ")");
                    }
                    BestArmorDisplay.renderSurvivalInventoryOverlay(gui, guiLeft, guiTop, mouseX, mouseY);
                } catch (Throwable ignored) {
                }
            }
        } finally {
            /*
             * Do not use pushAttrib/popAttrib here. GL attribute stacks restore the driver state behind
             * GlStateManager's back, leaving Minecraft's cached lighting/alpha/rescale values stale. The vanilla
             * container finishes with different alpha/rescale state depending on whether a tooltip was drawn, so
             * the desync showed up most reliably while hovering or carrying items as a darkened screen.
             *
             * This is a late 2D pass: finish in a single, explicit flat-GUI state and update the cache and driver
             * together through GlStateManager/RenderHelper only.
             */
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            HudGlState.restoreForOverlayText();
            GlStateManager.depthMask(true);
            GlStateManager.enableDepth();
            GlStateManager.depthFunc(515);
            GlStateManager.popMatrix();
        }
    }

    private static void paintDebugArmorSlotIfAny(Slot slot, int guiLeft, int guiTop) {
        ItemStack stack = slot.getStack();
        if (stack == null || !(stack.getItem() instanceof ItemArmor)) {
            return;
        }
        int itemX = guiLeft + slot.xDisplayPosition + 1;
        int itemY = guiTop + slot.yDisplayPosition + 1;
        int x0 = itemX - 1;
        int y0 = itemY - 1;
        int x1 = itemX + 16 - 1;
        int y1 = itemY + 16 - 1;
        GlStateManager.pushMatrix();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        int magentaFill = 0x66FF00FF;
        int magentaBorder = 0xCCFF00FF;
        Gui.drawRect(x0, y0, x1, y1, magentaFill);
        Gui.drawRect(x0, y0, x1, y0 + 1, magentaBorder);
        Gui.drawRect(x0, y1 - 1, x1, y1, magentaBorder);
        Gui.drawRect(x0, y0 + 1, x0 + 1, y1 - 1, magentaBorder);
        Gui.drawRect(x1 - 1, y0 + 1, x1, y1 - 1, magentaBorder);
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    /**
     * Draw every Inventory-QoL slot tint immediately before the slot's item. This is the canonical tint pass for
     * every render strategy: green best-armor, red useless-item, and blue/yellow chest-pick hints all stay below
     * both the icon and any later tooltip.
     */
    public static void onGuiContainerDrawSlotHead(GuiContainer gui, Slot slotIn) {
        if (gui == null || slotIn == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        boolean bestArmorActive = BestArmorDisplay.isOverlayActiveInContext(mc);
        boolean uselessItemActive = UselessItemOverlay.isOverlayActiveInContext(mc);
        boolean chestHintActive = UselessItemOverlay.isChestPickHintActiveInContext(mc);
        if (!bestArmorActive && !uselessItemActive && !chestHintActive) {
            return;
        }
        if (BestArmorDisplay.shouldExcludeCurrentContainer()) {
            return;
        }
        if (mc.currentScreen == null || mc.currentScreen != gui) {
            return;
        }

        boolean debug = ConfigManager.isBestArmorDebugOverlayPaint();
        // GuiContainer.drawScreen has already translated the GL matrix by guiLeft/guiTop before calling drawSlot.
        // Supplying the absolute GUI origin here would apply it twice and shift the tint away from its item.
        HudGlState.restoreForInventoryUnderlay();
        if (debug) {
            try {
                paintDebugArmorSlotIfAny(slotIn, 0, 0);
            } finally {
                HudGlState.restoreAfterInventoryUnderlay();
            }
        } else {
            try {
                BestArmorDisplay.renderSlotBestArmorOverlayIfNeeded(slotIn, 0, 0);
                UselessItemOverlay.renderSlotOverlayIfNeeded(slotIn, 0, 0);
                UselessItemOverlay.renderChestPickHintIfNeeded(slotIn, 0, 0);
            } finally {
                HudGlState.restoreAfterInventoryUnderlay();
            }
        }
    }

    /**
     * Mode 8: draw the survival-inventory panel during the container background layer.
     * We render panel-only (no tooltips) because tooltips would be covered by later slot rendering.
     */
    public static void onGuiContainerBackgroundLayerReturn(GuiContainer gui, int mouseX, int mouseY) {
        if (gui == null || !(gui instanceof GuiInventory)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!BestArmorDisplay.isOverlayActiveInContext(mc)) {
            return;
        }
        if (BestArmorDisplay.shouldExcludeCurrentContainer()) {
            return;
        }
        if (mc.currentScreen == null || mc.currentScreen != gui) {
            return;
        }

        int mode = BestArmorContainerRenderStrategy.get();
        if (mode != BestArmorContainerRenderStrategy.MODE_GUICONTAINER_DRAW_SLOT_HEAD_HOOK) {
            return;
        }

        if (!BestArmorGuiLayoutAccess.canRead()) {
            System.err.println("[gomod] BestArmor mode 8: GuiContainer guiLeft/guiTop reflection failed (obfuscation).");
            return;
        }
        int guiLeft;
        int guiTop;
        try {
            guiLeft = BestArmorGuiLayoutAccess.readGuiLeft(gui);
            guiTop = BestArmorGuiLayoutAccess.readGuiTop(gui);
        } catch (Throwable t) {
            return;
        }

        BestArmorDisplay.renderSurvivalInventoryPanelOnly(gui, guiLeft, guiTop);
    }
}
