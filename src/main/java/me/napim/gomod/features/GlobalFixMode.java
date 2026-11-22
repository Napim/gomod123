package me.napim.gomod.features;

import me.napim.gomod.util.ClientInputHelper;
import net.minecraft.client.Minecraft;

/**
 * Global "try-many-fixes" profiles so users can quickly test behavior differences with one command.
 */
public final class GlobalFixMode {

    private static volatile int mode = 1;

    private GlobalFixMode() {
    }

    public static int getMode() {
        return mode;
    }

    public static void setMode(int m) {
        mode = Math.max(1, Math.min(8, m));
    }

    public static boolean forcePigRequiresSneak() {
        int m = mode;
        return m != 4 && m != 8;
    }

    public static boolean forcePigRequiresCompass() {
        int m = mode;
        return m != 7 && m != 8;
    }

    public static boolean forcePigTrigger(Minecraft mc, boolean attackDown, boolean clickPulse, boolean prevAttackDown) {
        switch (mode) {
            case 2:
                return clickPulse;
            case 3:
                return attackDown && !prevAttackDown;
            case 4:
                return attackDown || clickPulse;
            case 5:
                return ClientInputHelper.isAttackHeldRawOnly(mc) && !prevAttackDown;
            case 6:
                return ClientInputHelper.isAttackHeldKeybindOnly(mc) && !prevAttackDown;
            case 7:
                return clickPulse || attackDown;
            case 8:
                return clickPulse || attackDown;
            default:
                return clickPulse || (attackDown && !prevAttackDown);
        }
    }

    public static boolean editRequiresSneak() {
        int m = mode;
        return m != 4 && m != 8;
    }

    public static boolean editUseDown(Minecraft mc, boolean synthDown) {
        switch (mode) {
            case 2:
                return synthDown;
            case 3:
                return ClientInputHelper.isUseItemHeldKeybindOnly(mc);
            case 4:
                return ClientInputHelper.isUseItemHeldRawOnly(mc);
            case 5:
                return ClientInputHelper.isUseItemHeld(mc);
            case 6:
                return ClientInputHelper.isUseItemHeldKeybindOnly(mc) || synthDown;
            case 7:
                return ClientInputHelper.isUseItemHeldRawOnly(mc) || synthDown;
            case 8:
                return true;
            default:
                return ClientInputHelper.isUseItemHeld(mc) || synthDown;
        }
    }

    public static boolean sneakActive(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return false;
        }
        switch (mode) {
            case 2:
                return mc.thePlayer.isSneaking();
            case 3:
                return ClientInputHelper.isSneakKeyHeldOnly(mc);
            case 4:
                return true;
            case 5:
                return mc.thePlayer.movementInput != null && mc.thePlayer.movementInput.sneak;
            case 6:
                return ClientInputHelper.isLocalSneakHeld(mc) || (mc.thePlayer.movementInput != null && mc.thePlayer.movementInput.sneak);
            case 7:
                return ClientInputHelper.isSneakKeyHeldOnly(mc) || mc.thePlayer.isSneaking();
            case 8:
                return true;
            default:
                return ClientInputHelper.isLocalSneakHeld(mc)
                        || mc.thePlayer.isSneaking()
                        || (mc.thePlayer.movementInput != null && mc.thePlayer.movementInput.sneak);
        }
    }

    public static String describe(int m) {
        switch (m) {
            case 1:
                return "Hybrid default (poll + click pulse).";
            case 2:
                return "Pulse only for clicks; vanilla sneak.";
            case 3:
                return "Keybind-only inputs.";
            case 4:
                return "Ignore sneak requirement (input stress test).";
            case 5:
                return "Raw mouse focus + movementInput sneak.";
            case 6:
                return "Keybind mouse + extra sneak fallback.";
            case 7:
                return "No compass requirement for forcepig.";
            case 8:
                return "Aggressive fallback: no sneak, no compass, always edit RMB-down.";
            default:
                return "Unknown";
        }
    }
}
