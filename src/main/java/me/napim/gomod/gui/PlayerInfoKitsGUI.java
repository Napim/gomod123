package me.napim.gomod.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class PlayerInfoKitsGUI extends GuiScreen {
    private final GuiScreen parent;
    private final String title;
    private final List<String> lines;
    private int scrollOffset;

    public PlayerInfoKitsGUI(GuiScreen parent, String title, List<String> lines) {
        this.parent = parent;
        this.title = title == null ? "Blitz kits" : title;
        this.lines = lines == null ? new ArrayList<>() : new ArrayList<>(lines);
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(0, this.width / 2 - 50, this.height - 28, 100, 20, "Done"));
        clampScroll();
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            this.mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scrollOffset += wheel < 0 ? 18 : -18;
            clampScroll();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            this.mc.displayGuiScreen(parent);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        this.drawCenteredString(this.fontRendererObj, title, this.width / 2, 16, 0xFFFFFFFF);
        this.drawCenteredString(this.fontRendererObj,
                EnumChatFormatting.GRAY + "Local display only - scroll for more",
                this.width / 2, 28, 0xFFFFFFFF);

        int y = 48 - scrollOffset;
        int left = Math.max(20, this.width / 2 - 120);
        int bottom = this.height - 36;
        for (String line : lines) {
            if (y >= 40 && y <= bottom) {
                this.fontRendererObj.drawStringWithShadow(line, left, y, 0xFFFFFFFF);
            }
            y += 12;
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void clampScroll() {
        int visibleHeight = Math.max(1, this.height - 86);
        int contentHeight = lines.size() * 12;
        int max = Math.max(0, contentHeight - visibleHeight);
        if (scrollOffset < 0) {
            scrollOffset = 0;
        } else if (scrollOffset > max) {
            scrollOffset = max;
        }
    }
}
