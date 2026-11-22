package me.napim.gomod.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

/**
 * Renders item icons the same way as inventory slots or GUI screens (Kits preview / container slots).
 */
public final class HudGuiItemIcon {

    private static int guiScreenBatchDepth;
    private static boolean itemScissorActive;

    private HudGuiItemIcon() {
    }

    /**
     * Picks inventory-overlay vs open-GuiScreen rendering based on whether a screen is open.
     */
    public static void draw(Minecraft mc, ItemStack stack, int x, int y) {
        if (mc == null || stack == null || stack.getItem() == null) {
            return;
        }
        if (mc.currentScreen instanceof GuiScreen) {
            drawGuiScreenSlot(mc, stack, x, y);
        } else {
            drawInventoryHudSlot(mc, stack, x, y);
        }
    }

    /** Same as {@link me.napim.gomod.features.InventoryHUD} item slots (hotbar / inv HUD copy). */
    public static void drawInventoryHudSlot(Minecraft mc, ItemStack stack, int x, int y) {
        drawInventoryHudSlot(mc, stack, (float) x, (float) y, 1.0f);
    }

    /**
     * Inventory HUD slot item at {@code (x,y)} with HUD scale. Clips enchant glint to the 16×16 item quad so it does
     * not cover the full 18×18 slot cell.
     */
    public static void drawInventoryHudSlot(Minecraft mc, ItemStack stack, float x, float y, float itemScale) {
        drawInventoryHudSlotWithResolution(mc, stack, x, y, itemScale, null);
    }

    /** Inventory HUD path reuses its frame's resolution instead of allocating one for every item. */
    public static void drawInventoryHudSlotWithResolution(Minecraft mc, ItemStack stack, float x, float y,
            float itemScale, ScaledResolution scaledResolution) {
        if (mc == null || stack == null || stack.getItem() == null || itemScale <= 0.0f) {
            return;
        }
        int clip = Math.max(1, Math.round(16.0f * itemScale));
        int left = (int) x;
        int top = (int) y;
        // The scissor only contains enchantment glint. Ordinary item quads already fit the 16x16 icon.
        boolean clipGlint = stack.hasEffect();
        if (clipGlint) {
            // +1px pad: framebuffer scissor rounding was shaving a pixel off item edges.
            enableItemScissor(mc, scaledResolution, left - 1, top - 1, left + clip + 1, top + clip + 1);
        }
        GlStateManager.pushMatrix();
        GlStateManager.enableRescaleNormal();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.translate(x, y, 0.0F);
        GlStateManager.scale(itemScale, itemScale, 1.0F);
        try {
            mc.getRenderItem().zLevel = 200.0F;
            mc.getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
            mc.getRenderItem().zLevel = 0.0F;
        } catch (Throwable ignored) {
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.popMatrix();
        if (clipGlint) {
            disableItemScissor();
        }
        HudGlState.restoreForOverlayText();
    }

    private static void enableItemScissor(Minecraft mc, ScaledResolution scaledResolution, int guiLeft, int guiTop,
            int guiRight, int guiBottom) {
        if (mc == null || guiRight <= guiLeft || guiBottom <= guiTop) {
            return;
        }
        ScaledResolution sr = scaledResolution == null ? new ScaledResolution(mc) : scaledResolution;
        int scale = Math.max(1, sr.getScaleFactor());
        int sh = Math.max(1, sr.getScaledHeight());
        int x = Math.max(0, guiLeft * scale - 1);
        int x2 = guiRight * scale + 1;
        int w = Math.max(1, x2 - x);
        int y = Math.max(0, (sh - guiBottom) * scale - 1);
        int h = Math.max(1, (guiBottom - guiTop) * scale + 2);
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x, y, w, h);
        itemScissorActive = true;
    }

    private static void enableItemScissor(int guiLeft, int guiTop, int guiRight, int guiBottom) {
        enableItemScissor(Minecraft.getMinecraft(), null, guiLeft, guiTop, guiRight, guiBottom);
    }

    private static void disableItemScissor() {
        if (itemScissorActive) {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            itemScissorActive = false;
        }
    }

    /** Clips drawing to a square GUI cell (used when composite icons are drawn under a scale matrix). */
    public static void beginGuiCellClip(int guiLeft, int guiTop, int guiSize) {
        enableItemScissor(guiLeft - 1, guiTop - 1, guiLeft + guiSize + 1, guiTop + guiSize + 1);
    }

    public static void endGuiCellClip() {
        disableItemScissor();
    }

    /** Same as {@link me.napim.gomod.gui.KitsPreviewGUI} / vanilla container slots. */
    public static void drawGuiScreenSlot(Minecraft mc, ItemStack stack, int x, int y) {
        if (mc == null || stack == null || stack.getItem() == null) {
            return;
        }
        if (guiScreenBatchDepth > 0) {
            drawGuiScreenSlotInBatch(mc, stack, x, y);
            return;
        }
        beginGuiScreenItemBatch(mc);
        try {
            drawGuiScreenSlotInBatch(mc, stack, x, y);
        } finally {
            endGuiScreenItemBatch(mc);
        }
    }

    /** One GL setup for many GUI item icons (e.g. Effects HUD color grid). */
    public static void beginGuiScreenItemBatch(Minecraft mc) {
        if (mc == null) {
            return;
        }
        if (guiScreenBatchDepth++ > 0) {
            return;
        }
        GlStateManager.pushMatrix();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableLighting();
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static void drawGuiScreenSlotInBatch(Minecraft mc, ItemStack stack, int x, int y) {
        if (mc == null || stack == null || stack.getItem() == null) {
            return;
        }
        try {
            mc.getRenderItem().renderItemAndEffectIntoGUI(stack, x, y);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Stack count / durability overlay for a GUI item drawn at {@code (x,y)} (16×16 icon origin).
     * When {@code cellSize} is smaller than 18, applies the same scale as {@link #drawGuiScreenSlotSized}.
     */
    public static void drawGuiScreenSlotOverlayInBatch(Minecraft mc, FontRenderer fontRenderer, ItemStack stack, int x,
            int y) {
        drawGuiScreenSlotOverlayInBatch(mc, fontRenderer, stack, x, y, 18);
    }

    public static void drawGuiScreenSlotOverlayInBatch(Minecraft mc, FontRenderer fontRenderer, ItemStack stack, int x,
            int y, int cellSize) {
        if (mc == null || fontRenderer == null || stack == null || stack.getItem() == null || stack.stackSize <= 1
                || cellSize <= 0) {
            return;
        }
        int inner = guiSlotInnerIconSize(cellSize);
        if (inner >= 16) {
            try {
                mc.getRenderItem().renderItemOverlayIntoGUI(fontRenderer, stack, x, y, null);
            } catch (Throwable ignored) {
            }
            return;
        }
        float scale = inner / 16f;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(scale, scale, 1f);
        try {
            mc.getRenderItem().renderItemOverlayIntoGUI(fontRenderer, stack, 0, 0, null);
        } catch (Throwable ignored) {
        }
        GlStateManager.popMatrix();
    }

    /** Inset inside a GUI slot cell (matches {@code SLOT_SIZE} 18 with icon at +1). */
    private static final int GUI_SLOT_ICON_INSET = 1;

    /** Drawable icon size inside a cell (16px in an 18px slot → {@code cellSize - 2× inset}). */
    public static int guiSlotInnerIconSize(int cellSize) {
        if (cellSize <= 0) {
            return 0;
        }
        if (cellSize >= 18) {
            return 16;
        }
        return Math.max(1, cellSize - 2 * GUI_SLOT_ICON_INSET);
    }

    /** Single GUI item icon in a cell — 1px inset and grid-like inner scale. */
    public static void drawGuiScreenSlotSized(Minecraft mc, ItemStack stack, int cellX, int cellY, int cellSize) {
        if (mc == null || stack == null || stack.getItem() == null || cellSize <= 0) {
            return;
        }
        int inner = guiSlotInnerIconSize(cellSize);
        if (inner >= 16) {
            drawGuiScreenSlotInBatch(mc, stack, cellX + GUI_SLOT_ICON_INSET, cellY + GUI_SLOT_ICON_INSET);
            return;
        }
        float scale = inner / 16f;
        GlStateManager.pushMatrix();
        GlStateManager.translate(cellX + GUI_SLOT_ICON_INSET, cellY + GUI_SLOT_ICON_INSET, 0);
        GlStateManager.scale(scale, scale, 1f);
        try {
            drawGuiScreenSlotInBatch(mc, stack, 0, 0);
        } catch (Throwable ignored) {
        }
        GlStateManager.popMatrix();
    }

    /**
     * Combat-tier composite icon in a cell (same strip layout as the main grid at {@code x+1,y+1}).
     * Clips to the cell; caller must re-apply any outer GUI scissor after this returns.
     */
    public static void drawCompositeGuiScreenSlotSized(Minecraft mc, java.util.List<ItemStack> stacks, int cellX,
            int cellY, int cellSize) {
        if (mc == null || stacks == null || stacks.isEmpty() || cellSize <= 0) {
            return;
        }
        int inner = guiSlotInnerIconSize(cellSize);
        if (inner >= 16) {
            drawCompositeGuiScreenSlotInBatch(mc, stacks, cellX + GUI_SLOT_ICON_INSET, cellY + GUI_SLOT_ICON_INSET, true);
            return;
        }
        enableItemScissor(cellX, cellY, cellX + cellSize, cellY + cellSize);
        float scale = inner / 16f;
        GlStateManager.pushMatrix();
        GlStateManager.translate(cellX + GUI_SLOT_ICON_INSET, cellY + GUI_SLOT_ICON_INSET, 0);
        GlStateManager.scale(scale, scale, 1f);
        try {
            drawCompositeGuiScreenSlotInBatch(mc, stacks, 0, 0, false);
        } catch (Throwable ignored) {
        }
        GlStateManager.popMatrix();
        disableItemScissor();
    }

    /**
     * Side-by-side strips for combat-tier combine groups (same layout as the main item grid).
     * The first icon is nudged 1px left so both materials show about equally in the slot.
     */
    public static void drawCompositeGuiScreenSlotInBatch(Minecraft mc, java.util.List<ItemStack> stacks, int x, int y) {
        drawCompositeGuiScreenSlotInBatch(mc, stacks, x, y, true);
    }

    /**
     * @param stripScissor when false, caller must clip in GUI space (e.g. scaled Observed Chances icon).
     */
    public static void drawCompositeGuiScreenSlotInBatch(Minecraft mc, java.util.List<ItemStack> stacks, int x, int y,
            boolean stripScissor) {
        if (mc == null || stacks == null || stacks.isEmpty()) {
            return;
        }
        int count = 0;
        for (ItemStack stack : stacks) {
            if (stack != null && stack.getItem() != null) {
                count++;
            }
        }
        if (count == 0) {
            return;
        }
        if (count == 1) {
            for (ItemStack stack : stacks) {
                if (stack != null && stack.getItem() != null) {
                    drawGuiScreenSlotInBatch(mc, stack, x, y);
                    return;
                }
            }
            return;
        }
        int size = 16;
        int drawn = 0;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.getItem() == null) {
                continue;
            }
            int x0 = x + (drawn * size) / count;
            int x1 = x + ((drawn + 1) * size) / count;
            int stripW = Math.max(1, x1 - x0);
            int drawX = drawn == 0 ? x0 - 1 : x0;
            if (stripScissor) {
                enableItemScissor(x0, y, x0 + stripW, y + size);
            }
            try {
                drawGuiScreenSlotInBatch(mc, stack, drawX, y);
            } catch (Throwable ignored) {
            }
            if (stripScissor) {
                disableItemScissor();
            }
            drawn++;
        }
    }

    public static void endGuiScreenItemBatch(Minecraft mc) {
        if (mc == null || guiScreenBatchDepth <= 0) {
            return;
        }
        if (--guiScreenBatchDepth > 0) {
            return;
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.disableRescaleNormal();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.popMatrix();
        HudGlState.restoreForOverlayText();
    }
}
