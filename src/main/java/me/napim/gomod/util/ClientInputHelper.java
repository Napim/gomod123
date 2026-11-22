package me.napim.gomod.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

import java.util.Locale;

/**
 * Reliable input for the local player on Lunar / Weave: vanilla sneak state can lag behind the real Shift key.
 */
public final class ClientInputHelper {

    private ClientInputHelper() {
    }

    /**
     * True if the local player is sneaking per game state <b>or</b> the sneak keybind / physical Shift is held.
     */
    public static boolean isLocalSneakHeld(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        if (mc.thePlayer.isSneaking()) {
            return true;
        }
        if (isKeyBindingPhysicallyDown(mc, mc.gameSettings != null ? mc.gameSettings.keyBindSneak : null)) {
            return true;
        }
        // Lunar: sneak bind / Keyboard.poll can desync from real Shift
        try {
            return org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_LSHIFT)
                    || org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_RSHIFT);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isAttackHeld(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) {
            return false;
        }
        if (isAttackHeldKeybindOnly(mc)) {
            return true;
        }
        try {
            return org.lwjgl.input.Mouse.isButtonDown(0);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isUseItemHeld(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) {
            return false;
        }
        if (isUseItemHeldKeybindOnly(mc)) {
            return true;
        }
        try {
            return org.lwjgl.input.Mouse.isButtonDown(1);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Hypixel / resource packs may use a non-singleton compass item; match vanilla and subclasses by name.
     */
    public static boolean isHeldCompass(ItemStack held) {
        if (held == null || held.getItem() == null) {
            return false;
        }
        if (held.getItem() == Items.compass) {
            return true;
        }
        String u = held.getItem().getUnlocalizedName();
        if (u != null && u.toLowerCase(Locale.ROOT).contains("compass")) {
            return true;
        }
        String cn = held.getItem().getClass().getSimpleName();
        return cn != null && cn.toLowerCase(Locale.ROOT).contains("compass");
    }

    public static boolean isSneakKeyHeldOnly(Minecraft mc) {
        return mc != null && mc.gameSettings != null
                && isKeyBindingPhysicallyDown(mc, mc.gameSettings.keyBindSneak);
    }

    public static boolean isAttackHeldKeybindOnly(Minecraft mc) {
        return mc != null && mc.gameSettings != null
                && isKeyBindingPhysicallyDown(mc, mc.gameSettings.keyBindAttack);
    }

    public static boolean isAttackHeldRawOnly(Minecraft mc) {
        try {
            return org.lwjgl.input.Mouse.isButtonDown(0);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isUseItemHeldKeybindOnly(Minecraft mc) {
        return mc != null && mc.gameSettings != null
                && isKeyBindingPhysicallyDown(mc, mc.gameSettings.keyBindUseItem);
    }

    public static boolean isUseItemHeldRawOnly(Minecraft mc) {
        try {
            return org.lwjgl.input.Mouse.isButtonDown(1);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Reliable hold detection for gomod keybinds (keyboard and mouse bind codes). */
    public static boolean isKeyBindingHeld(Minecraft mc, KeyBinding kb) {
        return isKeyBindingPhysicallyDown(mc, kb);
    }

    private static boolean isKeyBindingPhysicallyDown(Minecraft mc, KeyBinding kb) {
        if (kb == null) {
            return false;
        }
        try {
            if (kb.isKeyDown()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        int code = kb.getKeyCode();
        // 1.8.9: mouse binds use negative codes; LWJGL button index = code + 100
        if (code < 0) {
            try {
                return org.lwjgl.input.Mouse.isButtonDown(code + 100);
            } catch (Throwable ignored) {
                return false;
            }
        }
        try {
            return org.lwjgl.input.Keyboard.isKeyDown(code);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
