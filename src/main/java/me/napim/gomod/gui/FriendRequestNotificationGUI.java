package me.napim.gomod.gui;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodFriendRelay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

import java.io.IOException;

/**
 * Shown when another player sends a gomod friend request over the relay.
 */
public class FriendRequestNotificationGUI extends GuiScreen {
    private static final int ID_ACCEPT = 1;
    private static final int ID_DECLINE = 2;
    private static final int ID_BACK = 0;

    private final GuiScreen parent;
    private final String fromName;

    public FriendRequestNotificationGUI(GuiScreen parent, String fromName) {
        this.parent = parent;
        this.fromName = fromName == null ? "" : fromName.trim();
    }

    /**
     * Opens on the client thread (commands / HTTP poll may run off-thread).
     */
    public static void open(final String from) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(() -> {
            if (mc.thePlayer == null) {
                return;
            }
            mc.displayGuiScreen(new FriendRequestNotificationGUI(mc.currentScreen, from));
        });
    }

    @Override
    public void initGui() {
        super.initGui();
        int cx = this.width / 2;
        int cy = this.height / 2 + 6;
        this.buttonList.add(new GuiButton(ID_ACCEPT, cx - 105, cy + 14, 100, 20,
                EnumChatFormatting.GREEN + "Accept"));
        this.buttonList.add(new GuiButton(ID_DECLINE, cx + 5, cy + 14, 100, 20,
                EnumChatFormatting.RED + "Decline"));
        this.buttonList.add(new GuiButton(ID_BACK, cx - 100, cy + 44, 200, 20,
                EnumChatFormatting.GRAY + "Back"));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        int cx = this.width / 2;
        int y = this.height / 2 - 44;
        int headSize = 40;
        int textLeft = cx - 52;
        int headX = textLeft - headSize - 10;
        int headY = y - 4;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        if (!this.fromName.isEmpty()) {
            PlayerHeadTextureUtil.drawMinotarAvatar(this.fromName, headX, headY, headSize);
        }
        GlStateManager.popMatrix();

        this.fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "gomod Social",
                textLeft, y, 0xFFFFFF);
        y += 12;
        this.fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.GRAY + "Incoming friend request",
                textLeft, y, 0xAAAAAA);
        y += 14;
        this.fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.WHITE + "From: " + EnumChatFormatting.AQUA + this.fromName,
                textLeft, y, 0xFFFFFF);
        y += 14;
        this.drawCenteredString(this.fontRendererObj,
                EnumChatFormatting.GRAY + "Accept only if you know this player.",
                cx, y, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == ID_DECLINE) {
            me.napim.gomod.features.GomodSocialRequests.removeIncoming(this.fromName);
        }
        if (button.id == ID_BACK || button.id == ID_DECLINE) {
            this.mc.displayGuiScreen(this.parent);
            return;
        }
        if (button.id == ID_ACCEPT) {
            if (this.fromName.isEmpty()) {
                this.mc.displayGuiScreen(this.parent);
                return;
            }
            if (this.mc.thePlayer != null && this.fromName.equalsIgnoreCase(this.mc.thePlayer.getName())) {
                this.mc.displayGuiScreen(this.parent);
                return;
            }
            me.napim.gomod.features.GomodSocialRequests.removeIncoming(this.fromName);
            ConfigManager.addGomodFriend(this.fromName);
            GomodFriendRelay.postFriendAccept(this.fromName);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Accepted "
                    + EnumChatFormatting.AQUA + this.fromName + EnumChatFormatting.GREEN + "'s friend request.");
            this.mc.displayGuiScreen(this.parent);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            if (this.parent != null) {
                this.mc.displayGuiScreen(this.parent);
            } else {
                GomodMenuTransitions.closeToGame(this.mc);
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
