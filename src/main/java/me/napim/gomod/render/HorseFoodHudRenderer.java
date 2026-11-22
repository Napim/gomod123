package me.napim.gomod.render;

import me.napim.gomod.util.HudGlState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the horse food HUD panel at 2D screen positions projected during entity render.
 * Each panel shows item icons (using vanilla RenderItem) with count text.
 */
public final class HorseFoodHudRenderer {

    private HorseFoodHudRenderer() {}

    public static final class Entry {
        /** Screen-space position (in scaled GUI coords) of the label anchor (top-center). */
        public final float screenX;
        public final float screenY;
        /** Whether the horse was in range / visible this frame. */
        public final ItemStack[] icons;
        public final int[] needed;
        public final int[] have;

        public Entry(float screenX, float screenY, ItemStack[] icons, int[] needed, int[] have) {
            this.screenX = screenX;
            this.screenY = screenY;
            this.icons = icons;
            this.needed = needed;
            this.have = have;
        }
    }

    private static final Map<Integer, Entry> pending = new LinkedHashMap<Integer, Entry>();

    /** Clear entries at the start of each frame (called before entity rendering begins). */
    public static void beginFrame() {
        pending.clear();
    }

    /** Called from HorseDonkeyTracker during entity rendering with projected screen position. */
    public static void addEntry(int entityId, float screenX, float screenY, ItemStack[] icons, int[] needed, int[] have) {
        pending.put(Integer.valueOf(entityId), new Entry(screenX, screenY, icons, needed, have));
    }

    public static boolean hasEntry(int entityId) {
        return pending.containsKey(Integer.valueOf(entityId));
    }

    /** Called from RenderGameOverlayEvent.Post to draw all pending panels. */
    public static void renderOverlay() {
        if (pending.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.fontRendererObj == null) return;

        try {
            Collection<Entry> entries = pending.values();
            for (Entry e : entries) {
                try {
                    renderEntry(mc, e);
                } catch (Throwable ignored) {}
            }
            RenderHelper.disableStandardItemLighting();
            HudGlState.restoreForOverlayText();
        } finally {
            pending.clear();
        }
    }

    private static final int ICON = 10;   // icon render size (scaled by 0.625 from 16)
    private static final int GAP  = 2;    // gap between icon and text
    private static final int PAD  = 3;    // horizontal padding inside background
    private static final int PAD_V = 2;   // vertical padding
    private static final int SEP_W = 4;   // separator "/" width

    private static void renderEntry(Minecraft mc, Entry e) {
        // Build segments: each is (icon, text, color)
        final class Seg {
            final ItemStack icon;
            final String text;
            final boolean enough;
            Seg(ItemStack i, String t, boolean en) { icon = i; text = t; enough = en; }
        }

        boolean noFood = e.icons.length == 0;
        List<Seg> segs = new ArrayList<>();
        for (int i = 0; i < e.icons.length; i++) {
            String countText = "x" + e.needed[i] + "(" + e.have[i] + ")";
            segs.add(new Seg(e.icons[i], countText, e.have[i] >= e.needed[i]));
        }

        // Calculate total width
        int totalW = PAD * 2;
        if (noFood) {
            totalW += mc.fontRendererObj.getStringWidth(EnumChatFormatting.RED + "No food");
        } else {
            for (int i = 0; i < segs.size(); i++) {
                if (i > 0) totalW += SEP_W + mc.fontRendererObj.getStringWidth("/");
                totalW += ICON + GAP + mc.fontRendererObj.getStringWidth(segs.get(i).text);
            }
        }
        int totalH = PAD_V * 2 + ICON;

        float drawX = e.screenX - totalW / 2f;
        float drawY = e.screenY - totalH;

        // Draw background
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
        float bx0 = drawX, bx1 = drawX + totalW, by0 = drawY, by1 = drawY + totalH;
        float ba = 0.5f;
        wr.pos(bx0, by1, 0).color(0f, 0f, 0f, ba).endVertex();
        wr.pos(bx1, by1, 0).color(0f, 0f, 0f, ba).endVertex();
        wr.pos(bx1, by0, 0).color(0f, 0f, 0f, ba).endVertex();
        wr.pos(bx0, by0, 0).color(0f, 0f, 0f, ba).endVertex();
        tess.draw();

        GlStateManager.enableTexture2D();
        GlStateManager.depthMask(true);

        if (noFood) {
            mc.fontRendererObj.drawStringWithShadow(
                    EnumChatFormatting.RED + "No food",
                    drawX + PAD, drawY + PAD_V + (ICON - mc.fontRendererObj.FONT_HEIGHT) / 2f, 0xFFFFFF);
        } else {
            // Scale icon renders: vanilla renderItemAndEffectIntoGUI draws a 16x16 item.
            // We render at scale ICON/16 so it fits in our row height.
            float iconScale = ICON / 16f;
            float curX = drawX + PAD;
            float textY = drawY + PAD_V + (ICON - mc.fontRendererObj.FONT_HEIGHT) / 2f;

            RenderHelper.enableGUIStandardItemLighting();

            for (int i = 0; i < segs.size(); i++) {
                if (i > 0) {
                    // separator
                    mc.fontRendererObj.drawStringWithShadow("/",
                            curX, textY, 0x888888);
                    curX += mc.fontRendererObj.getStringWidth("/") + SEP_W;
                }
                Seg seg = segs.get(i);

                // Render icon scaled
                GlStateManager.pushMatrix();
                GlStateManager.translate(curX, drawY + PAD_V, 0f);
                GlStateManager.scale(iconScale, iconScale, 1f);
                // renderItemAndEffectIntoGUI expects integer coords at 1:1 GUI scale
                mc.getRenderItem().renderItemAndEffectIntoGUI(seg.icon, 0, 0);
                GlStateManager.popMatrix();

                curX += ICON + GAP;

                // Draw count text
                int textColor = seg.enough ? 0x55FF55 : 0xFF5555;
                mc.fontRendererObj.drawStringWithShadow(seg.text, curX, textY, textColor);
                curX += mc.fontRendererObj.getStringWidth(seg.text);
            }

            RenderHelper.disableStandardItemLighting();
        }

        GlStateManager.disableBlend();
        RenderHelper.disableStandardItemLighting();
        HudGlState.restoreForOverlayText();
    }
}
