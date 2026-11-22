package me.napim.gomod.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import java.util.Locale;
import java.util.UUID;

/**
 * Local player name/UUID without reading launcher session tokens (reduces jar scanner false positives).
 */
public final class ClientPlayerIdentity {

    private ClientPlayerIdentity() {}

    public static String uuidUndashed(Minecraft mc) {
        UUID id = uuid(mc);
        if (id == null) {
            return "";
        }
        return id.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    public static UUID uuid(Minecraft mc) {
        if (mc == null) {
            return null;
        }
        try {
            EntityPlayer p = mc.thePlayer;
            if (p != null && p.getUniqueID() != null) {
                return p.getUniqueID();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static String displayName(Minecraft mc) {
        if (mc == null) {
            return "";
        }
        try {
            EntityPlayer p = mc.thePlayer;
            if (p == null) {
                return "";
            }
            if (p.getGameProfile() != null && p.getGameProfile().getName() != null) {
                String n = p.getGameProfile().getName().trim();
                if (!n.isEmpty()) {
                    return n;
                }
            }
            if (p.getName() != null) {
                String n = p.getName().trim();
                if (!n.isEmpty()) {
                    return n;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static GameProfile gameProfile(Minecraft mc) {
        if (mc == null || mc.thePlayer == null) {
            return null;
        }
        try {
            return mc.thePlayer.getGameProfile();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
