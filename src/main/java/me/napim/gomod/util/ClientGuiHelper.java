package me.napim.gomod.util;

import net.minecraft.client.Minecraft;

/**
 * When any GUI is open (including chat), world keybinds (chest routes, player caller, CPS sample, etc.)
 * should not fire — otherwise typing in chat can toggle features bound to letter keys.
 */
public final class ClientGuiHelper {

    private ClientGuiHelper() {
    }

    public static boolean shouldSuppressWorldKeybinds(Minecraft mc) {
        return mc != null && mc.currentScreen != null;
    }
}
