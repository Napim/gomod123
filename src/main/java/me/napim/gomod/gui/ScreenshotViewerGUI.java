package me.napim.gomod.gui;

import me.napim.gomod.Main;
import me.napim.gomod.features.IncomingScreenshotManager;
import me.napim.gomod.util.ScreenshotImageTextureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;

public class ScreenshotViewerGUI extends GuiScreen {
    private final IncomingScreenshotManager.IncomingScreenshot screenshot;
    private final GuiScreen parent;
    private ResourceLocation texture;
    private int imgW = 1;
    private int imgH = 1;

    public ScreenshotViewerGUI(IncomingScreenshotManager.IncomingScreenshot screenshot, GuiScreen parent) {
        this.screenshot = screenshot;
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        super.initGui();
        this.buttonList.add(new GuiButton(2, width / 2 - 102, height - 30, 98, 20, EnumChatFormatting.GREEN + "Save"));
        this.buttonList.add(new GuiButton(0, width / 2 + 4, height - 30, 98, 20, EnumChatFormatting.GRAY + "Back"));
        tryLoadTexture();
    }

    private void tryLoadTexture() {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(screenshot.bytes));
            if (img == null) return;
            img = ScreenshotImageTextureUtil.toArgbCopy(img);
            imgW = Math.max(1, img.getWidth());
            imgH = Math.max(1, img.getHeight());
            texture = ScreenshotImageTextureUtil.registerDynamicTexture(Minecraft.getMinecraft(), img, "gc_open");
        } catch (Exception ignored) {}
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws java.io.IOException {
        if (button.id == 0) {
            goBack();
            return;
        }
        if (button.id == 2) {
            try {
                File f = IncomingScreenshotManager.saveToSharedScreenshots(screenshot);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Saved shared screenshot to "
                        + EnumChatFormatting.AQUA + "gomod/shared_screenshots/" + f.getName());
            } catch (Exception e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to save screenshot: " + e.getMessage());
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        if (keyCode == 1) {
            goBack();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void goBack() {
        mc.displayGuiScreen(parent);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int cx = sr.getScaledWidth() / 2;
        drawCenteredString(fontRendererObj, EnumChatFormatting.LIGHT_PURPLE + "Shared Screenshot", cx, 8, 0xFFFFFF);
        drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + screenshot.sender + " - " + screenshot.fileName, cx, 20, 0xFFFFFF);

        int pad = 12;
        int availW = width - pad * 2;
        int availH = height - 60;
        int drawW = availW;
        int drawH = Math.max(1, (int) ((double) availW * (double) imgH / (double) imgW));
        if (drawH > availH) {
            drawH = availH;
            drawW = Math.max(1, (int) ((double) availH * (double) imgW / (double) imgH));
        }
        int x = cx - drawW / 2;
        int y = 32 + Math.max(0, (availH - drawH) / 2);
        drawRect(x - 1, y - 1, x + drawW + 1, y + drawH + 1, 0xFFFFFFFF);
        if (texture != null) {
            Minecraft.getMinecraft().getTextureManager().bindTexture(texture);
            net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, imgW, imgH, drawW, drawH, imgW, imgH);
        } else {
            drawCenteredString(fontRendererObj, EnumChatFormatting.RED + "Unable to render preview", cx, y + drawH / 2, 0xFFFFFF);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void onGuiClosed() {
        if (texture != null && mc != null && mc.getTextureManager() != null) {
            mc.getTextureManager().deleteTexture(texture);
            texture = null;
        }
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
