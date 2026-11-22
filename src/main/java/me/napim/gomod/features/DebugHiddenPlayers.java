package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import me.napim.gomod.GomodInternalFeatures;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev-build-only render suppression for selected players.
 *
 * Targets keep both UUID and name keys. UUID matching makes a target survive
 * entity replacement and Replay Mod timeline seeks, while the name fallback
 * also works for replay recordings whose profiles do not expose a UUID.
 */
public final class DebugHiddenPlayers {
    private static final Set<UUID> HIDDEN_UUIDS =
            Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
    private static final Set<String> HIDDEN_NAMES =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private DebugHiddenPlayers() {
    }

    public static boolean shouldHide(Entity entity) {
        if (HIDDEN_UUIDS.isEmpty() && HIDDEN_NAMES.isEmpty()
                || !GomodInternalFeatures.isEnabled() || !(entity instanceof EntityPlayer)) {
            return false;
        }

        EntityPlayer player = (EntityPlayer) entity;
        try {
            GameProfile profile = player.getGameProfile();
            if (profile != null) {
                UUID id = profile.getId();
                if (id != null && HIDDEN_UUIDS.contains(id)) {
                    return true;
                }
                if (matchesName(profile.getName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            return matchesName(player.getName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Toggles the loaded player matching {@code requestedName}. If no entity is
     * currently loaded, installs a name-only target so it can match after a
     * replay is opened or its timeline is seeked.
     *
     * @return {@code true} when the target is hidden after this call
     */
    public static boolean toggle(String requestedName) {
        String requested = normalize(requestedName);
        if (requested.isEmpty()) {
            return false;
        }

        EntityPlayer loaded = findLoadedPlayer(requested);
        if (loaded != null && shouldHide(loaded)) {
            removeAllKeysFor(loaded);
            HIDDEN_NAMES.remove(requested);
            return false;
        }
        if (loaded == null && HIDDEN_NAMES.remove(requested)) {
            return false;
        }

        HIDDEN_NAMES.add(requested);
        if (loaded != null) {
            addAllKeysFor(loaded);
        }
        return true;
    }

    public static void clear() {
        HIDDEN_UUIDS.clear();
        HIDDEN_NAMES.clear();
    }

    public static List<String> getHiddenNames() {
        List<String> names = new ArrayList<String>(HIDDEN_NAMES);
        Collections.sort(names);
        return names;
    }

    private static EntityPlayer findLoadedPlayer(String normalizedName) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.theWorld.playerEntities == null) {
                return null;
            }
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (player != null && entityMatchesName(player, normalizedName)) {
                    return player;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean entityMatchesName(EntityPlayer player, String normalizedName) {
        try {
            GameProfile profile = player.getGameProfile();
            if (profile != null && normalizedName.equals(normalize(profile.getName()))) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            return normalizedName.equals(normalize(player.getName()));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void addAllKeysFor(EntityPlayer player) {
        try {
            GameProfile profile = player.getGameProfile();
            if (profile != null) {
                if (profile.getId() != null) {
                    HIDDEN_UUIDS.add(profile.getId());
                }
                addName(profile.getName());
            }
        } catch (Throwable ignored) {
        }
        try {
            addName(player.getName());
        } catch (Throwable ignored) {
        }
    }

    private static void removeAllKeysFor(EntityPlayer player) {
        try {
            GameProfile profile = player.getGameProfile();
            if (profile != null) {
                if (profile.getId() != null) {
                    HIDDEN_UUIDS.remove(profile.getId());
                }
                removeName(profile.getName());
            }
        } catch (Throwable ignored) {
        }
        try {
            removeName(player.getName());
        } catch (Throwable ignored) {
        }
    }

    private static boolean matchesName(String name) {
        String normalized = normalize(name);
        return !normalized.isEmpty() && HIDDEN_NAMES.contains(normalized);
    }

    private static void addName(String name) {
        String normalized = normalize(name);
        if (!normalized.isEmpty()) {
            HIDDEN_NAMES.add(normalized);
        }
    }

    private static void removeName(String name) {
        String normalized = normalize(name);
        if (!normalized.isEmpty()) {
            HIDDEN_NAMES.remove(normalized);
        }
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
