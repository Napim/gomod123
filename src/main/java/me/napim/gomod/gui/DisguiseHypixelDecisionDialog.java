package me.napim.gomod.gui;

import java.io.IOException;
import java.util.List;

import me.napim.gomod.command.DisguiseCommand;
import me.napim.gomod.features.LocalDisguise;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

public class DisguiseHypixelDecisionDialog extends GuiScreen {
    private final GuiScreen parent;
    private final LocalDisguise.HypixelDataDecision decision;
    private final LocalDisguise.HypixelDataDecisionHandler handler;
    private GuiButton useCacheButton;
    private GuiButton waitButton;
    private GuiButton cancelButton;

    public DisguiseHypixelDecisionDialog(GuiScreen parent, LocalDisguise.HypixelDataDecision decision) {
        this(parent, decision, null);
    }

    public DisguiseHypixelDecisionDialog(GuiScreen parent, LocalDisguise.HypixelDataDecision decision,
                                         LocalDisguise.HypixelDataDecisionHandler handler) {
        this.parent = parent;
        this.decision = decision;
        this.handler = handler;
    }

    @Override
    public void initGui() {
        super.initGui();
        int centerX = this.width / 2;
        int buttonY = this.height / 2 + 30;
        this.useCacheButton = new GuiButton(0, centerX - 155, buttonY, 100, 20, EnumChatFormatting.GREEN + "Use Cache");
        String waitLabel = EnumChatFormatting.GOLD + "Wait";
        if (this.decision != null && this.decision.waitMillis > 0L) {
            long waitSeconds = Math.max(1L, (this.decision.waitMillis + 999L) / 1000L);
            waitLabel = EnumChatFormatting.GOLD + "Wait " + waitSeconds + "s";
        }
        this.waitButton = new GuiButton(1, centerX - 50, buttonY, 100, 20, waitLabel);
        this.cancelButton = new GuiButton(2, centerX + 55, buttonY, 100, 20, EnumChatFormatting.RED + "Cancel");
        this.useCacheButton.enabled = this.decision != null && this.decision.canUseCache;
        this.waitButton.enabled = this.decision != null && this.decision.canWait;
        this.buttonList.add(this.useCacheButton);
        this.buttonList.add(this.waitButton);
        this.buttonList.add(this.cancelButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        GuiHeader.drawTopLeft((Gui) this);
        int centerX = this.width / 2;
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj,
                EnumChatFormatting.LIGHT_PURPLE.toString() + EnumChatFormatting.BOLD + "Disguise Hypixel Data",
                centerX, this.height / 2 - 56, 0xFFFFFF);
        String targetLine = EnumChatFormatting.GRAY + "Target: " + EnumChatFormatting.YELLOW
                + (this.decision == null || this.decision.targetName == null ? "Unknown" : this.decision.targetName);
        GuiHeader.drawTitleCenteredAtWithShadow(this.fontRendererObj, targetLine, centerX, this.height / 2 - 38, 0xFFFFFF);
        List<String> wrapped = (List<String>) (List<?>) this.fontRendererObj.listFormattedStringToWidth(
                this.decision == null ? EnumChatFormatting.RED + "Hypixel disguise data is unavailable."
                        : EnumChatFormatting.YELLOW + this.decision.reason,
                240);
        int y = this.height / 2 - 14;
        for (String line : wrapped) {
            this.drawCenteredString(this.fontRendererObj, line, centerX, y, 0xFFFFFF);
            y += 12;
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null) {
            return;
        }
        if (button.id == 0 && this.decision != null && this.decision.canUseCache) {
            this.closeToParent();
            if (this.handler != null) {
                this.handler.onUseCache(this.decision);
            } else {
                DisguiseCommand.enableDisguiseAsync(this.decision.targetName, DisguiseCommand.EnableMode.USE_CACHE, 0L);
            }
            return;
        }
        if (button.id == 1 && this.decision != null && this.decision.canWait) {
            this.closeToParent();
            if (this.handler != null) {
                this.handler.onWait(this.decision);
            } else {
                DisguiseCommand.enableDisguiseAsync(this.decision.targetName, DisguiseCommand.EnableMode.FRESH,
                        this.decision.waitMillis);
            }
            return;
        }
        this.closeToParent();
        if (this.handler != null) {
            this.handler.onCancel();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            this.closeToParent();
            if (this.handler != null) {
                this.handler.onCancel();
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void closeToParent() {
        if (this.mc == null) {
            return;
        }
        this.mc.displayGuiScreen(this.parent);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
