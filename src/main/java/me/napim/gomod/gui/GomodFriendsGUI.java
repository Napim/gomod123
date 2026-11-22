package me.napim.gomod.gui;

import net.minecraft.client.gui.GuiScreen;

/**
 * Entry point for gomod Social — implementation is {@link GomodSocialGUI} (preview v2 layout).
 */
public class GomodFriendsGUI extends GomodSocialGUI {

    public GomodFriendsGUI(GuiScreen parent) {
        super(parent);
    }

    public GomodFriendsGUI(GuiScreen parent, String openChatWith) {
        super(parent, openChatWith);
    }
}
