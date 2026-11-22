package me.napim.gomod.features;

import me.napim.gomod.render.LabelBillboardAngles;
import me.napim.gomod.util.GomodChestWorldOverlayFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;

import java.util.List;

/**
 * Floating numbered labels (and "S" for start) at checkpoint positions in the world.
 */
public final class ChestCheckpointLabels {

    private ChestCheckpointLabels() {
    }

    public static void render(float partialTicks) {
        if (GomodChestWorldOverlayFrame.isCheckpointLabelsAlreadyDrawnThisFrame()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.fontRendererObj == null || mc.getRenderViewEntity() == null) {
            return;
        }
        ChestRouteManager.updateEditDragPreview(partialTicks);
        List<ChestRouteManager.CheckpointLabel> labels = ChestRouteManager.collectCheckpointLabels();
        if (labels.isEmpty()) {
            return;
        }
        Entity cam = mc.getRenderViewEntity();
        double cx = cam.lastTickPosX + (cam.posX - cam.lastTickPosX) * partialTicks;
        double cy = cam.lastTickPosY + (cam.posY - cam.lastTickPosY) * partialTicks;
        double cz = cam.lastTickPosZ + (cam.posZ - cam.lastTickPosZ) * partialTicks;

        RenderManager rm = mc.getRenderManager();
        float py = rm.playerViewY;
        float px = LabelBillboardAngles.entityLabelPitchX(rm, mc);
        FontRenderer fr = mc.fontRendererObj;

        GlStateManager.pushMatrix();
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.color(1f, 1f, 1f, 1f);

        for (ChestRouteManager.CheckpointLabel L : labels) {
            double dx = L.x - cx;
            double dy = L.y - cy + 0.28;
            double dz = L.z - cz;
            double distSq = dx * dx + dy * dy + dz * dz;
            // In double-persenive camera modes the camera may sit far away; keep labels visible for CE.
            if (distSq > 160 * 160) {
                continue;
            }
            GlStateManager.pushMatrix();
            GlStateManager.translate(dx, dy, dz);
            GlStateManager.rotate(-py, 0f, 1f, 0f);
            GlStateManager.rotate(px, 1f, 0f, 0f);
            GlStateManager.scale(-0.028f, -0.028f, 0.028f);
            int w = fr.getStringWidth(L.text);
            int a = (L.colorArgb >>> 24) & 0xFF;
            if (a == 0) {
                a = 255;
            }
            float rf = ((L.colorArgb >> 16) & 0xFF) / 255.0F;
            float gf = ((L.colorArgb >> 8) & 0xFF) / 255.0F;
            float bf = (L.colorArgb & 0xFF) / 255.0F;
            float af = a / 255.0F;
            GlStateManager.color(rf, gf, bf, af);
            fr.drawStringWithShadow(L.text, -w / 2, 0, 0xFFFFFF);
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.popMatrix();
        }

        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
        GomodChestWorldOverlayFrame.markCheckpointLabelsDrawnThisFrame();
    }
}
