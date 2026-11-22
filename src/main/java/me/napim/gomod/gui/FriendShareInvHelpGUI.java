package me.napim.gomod.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

/**
 * Short ShareInv / Friend HUD help opened from {@link FriendInventoryHUDGUI}.
 */
public class FriendShareInvHelpGUI extends GuiScreen {
    private final GuiScreen parent;

    public FriendShareInvHelpGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        this.buttonList.add(new GuiButton(0, this.width / 2 - 100, this.height - 32, 200, 20,
                EnumChatFormatting.GRAY + "Back"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        int x = 24;
        int y = 36;
        int dy = 11;
        this.fontRendererObj.drawString(EnumChatFormatting.AQUA + "" + EnumChatFormatting.BOLD + "ShareInv & Friend HUD",
                x, y, 0xFFFFFF);
        y += dy + 8;
        String[] lines = new String[] {
                EnumChatFormatting.WHITE + "Two players connect to the same relay, accept an invite, then each sees",
                EnumChatFormatting.WHITE + "the other's inventory, armor, hotbar, held slot, health, XP, and more",
                EnumChatFormatting.WHITE + "on the Friend Inventory HUD (gomod menu → Miscellaneous → gomod Service).",
                "",
                EnumChatFormatting.GRAY + "When Friend Inventory HUD is " + EnumChatFormatting.GREEN + "ON"
                        + EnumChatFormatting.GRAY + ", gomod connects to your relay URL automatically (if set).",
                EnumChatFormatting.GRAY + "Turning it " + EnumChatFormatting.RED + "OFF"
                        + EnumChatFormatting.GRAY + " disconnects unless ShareInv auto-connect is enabled in config.",
                "",
                EnumChatFormatting.YELLOW + "/shareinv connect"
                        + EnumChatFormatting.WHITE + " — connect to the relay (also done automatically when HUD is on)",
                EnumChatFormatting.YELLOW + "/shareinv disconnect"
                        + EnumChatFormatting.WHITE + " — leave the relay",
                EnumChatFormatting.YELLOW + "/shareinv <player>"
                        + EnumChatFormatting.WHITE + " — send an invite (exact in-game name)",
                EnumChatFormatting.YELLOW + "/shareinv accept" + EnumChatFormatting.WHITE + " / "
                        + EnumChatFormatting.YELLOW + "/shareinv decline"
                        + EnumChatFormatting.WHITE + " — answer an invite (clickable in chat)",
                EnumChatFormatting.YELLOW + "/shareinv stop"
                        + EnumChatFormatting.WHITE + " — end the live session (stay on relay)",
        };
        for (String line : lines) {
            this.fontRendererObj.drawString(line, x, y, 0xFFFFFF);
            y += dy;
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            this.mc.displayGuiScreen(this.parent);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
