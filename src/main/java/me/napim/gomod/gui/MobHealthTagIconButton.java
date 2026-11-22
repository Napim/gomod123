package me.napim.gomod.gui;

import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;

import java.util.function.BooleanSupplier;

/** Toggle icon button (armor-status gear style): green = on, red = off. */
public final class MobHealthTagIconButton extends GuiButton {
    private static final int ICON_SIZE = 16;
    private static final int FACE_INSET = 2;
    private static final int OVERLAY_ON = 0x5030AA40;
    private static final int OVERLAY_OFF = 0x50AA3030;
    private static final int SLOT_FRAME = 0xFF373737;
    private static final int SLOT_FRAME_HOVER = 0xFFAAAAAA;
    private static final int SLOT_INNER = 0xFF000000;

    private final ItemStack iconStack;
    private final BooleanSupplier slotOn;

    public MobHealthTagIconButton(int id, int x, int y, int width, int height, ItemStack iconStack,
            BooleanSupplier slotOn) {
        super(id, x, y, width, height, "");
        this.iconStack = iconStack;
        this.slotOn = slotOn;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        drawButtonWithAlpha(mc, mouseX, mouseY, 1.0F);
    }

    public void drawButtonWithAlpha(Minecraft mc, int mouseX, int mouseY, float fadeAlpha) {
        if (!visible) {
            return;
        }
        fadeAlpha = Math.max(0.0F, Math.min(1.0F, fadeAlpha));
        if (fadeAlpha <= 0.01F) {
            return;
        }
        displayString = "";
        int x = xPosition;
        int y = yPosition;
        int w = width;
        int h = height;
        hovered = enabled && mouseX >= x && mouseY >= y && mouseX < x + w && mouseY < y + h;

        HudGlState.restoreForOverlayText();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableLighting();

        int frame = hovered && enabled ? SLOT_FRAME_HOVER : SLOT_FRAME;
        float alpha = (enabled ? 1.0f : 0.5f) * fadeAlpha;
        GlStateManager.color(1.0f, 1.0f, 1.0f, alpha);
        Gui.drawRect(x, y, x + w, y + h, withAlpha(frame, alpha));
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, withAlpha(SLOT_INNER, alpha));
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        int insetL = x + FACE_INSET;
        int insetT = y + FACE_INSET;
        int insetR = x + w - FACE_INSET;
        int insetB = y + h - FACE_INSET;
        if (insetR > insetL && insetB > insetT) {
            Gui.drawRect(insetL, insetT, insetR, insetB,
                    withAlpha(slotOn.getAsBoolean() ? OVERLAY_ON : OVERLAY_OFF, fadeAlpha));
        }

        if (iconStack == null || mc == null || mc.getRenderItem() == null) {
            HudGlState.restoreForOverlayText();
            return;
        }
        int iconX = x + (w - ICON_SIZE) / 2;
        int iconY = y + (h - ICON_SIZE) / 2;
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            mc.getRenderItem().renderItemIntoGUI(iconStack, iconX, iconY);
        } catch (Throwable ignored) {
        }
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableRescaleNormal();
        HudGlState.restoreForOverlayText();
    }

    private static int withAlpha(int argb, float alpha) {
        int baseAlpha = (argb >>> 24) & 0xFF;
        int scaledAlpha = Math.max(0, Math.min(255, Math.round(baseAlpha * alpha)));
        return (scaledAlpha << 24) | (argb & 0x00FFFFFF);
    }
}
