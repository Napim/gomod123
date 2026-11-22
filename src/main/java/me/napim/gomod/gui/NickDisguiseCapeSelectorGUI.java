package me.napim.gomod.gui;

import net.minecraft.client.gui.GuiScreen;

/**
 * @deprecated Use {@link MinecraftCapeSelectorGUI} with {@link MinecraftCapeSelectorGUI.Mode#DISGUISE}.
 */
@Deprecated
public class NickDisguiseCapeSelectorGUI extends MinecraftCapeSelectorGUI {

    public NickDisguiseCapeSelectorGUI(GuiScreen parent) {
        super(parent, Mode.DISGUISE);
    }
}
