package me.napim.gomod.features;

import me.napim.gomod.command.KillEffectCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;

/**
 * Shared nametag text for world badges and {@link me.napim.gomod.mixins.RenderPlayerMixin}.
 */
public final class GomodPlayerNametagLabels {

    private static final ThreadLocal<Integer> CUSTOM_LABEL_RENDER_DEPTH = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    private static final ThreadLocal<Integer> KILLER_LABEL_RENDER_DEPTH = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    private static final ThreadLocal<Integer> PRIMARY_NAME_LABEL_RENDER_DEPTH = new ThreadLocal<Integer>() {
        @Override
        protected Integer initialValue() {
            return 0;
        }
    };

    private GomodPlayerNametagLabels() {}

    /** Prevents the generic player-name rewrite/suppression mixin from altering intentional overlay rows. */
    public static void renderCustomLabel(Runnable renderCall) {
        if (renderCall == null) {
            return;
        }
        int previous = CUSTOM_LABEL_RENDER_DEPTH.get();
        CUSTOM_LABEL_RENDER_DEPTH.set(previous + 1);
        try {
            renderCall.run();
        } finally {
            if (previous == 0) {
                CUSTOM_LABEL_RENDER_DEPTH.remove();
            } else {
                CUSTOM_LABEL_RENDER_DEPTH.set(previous);
            }
        }
    }

    public static boolean isRenderingCustomLabel() {
        return CUSTOM_LABEL_RENDER_DEPTH.get() > 0;
    }

    /** Marks the optional killer row so ONLY_IGN can hide other custom rows without removing it. */
    public static void renderKillerLabel(Runnable renderCall) {
        if (renderCall == null) {
            return;
        }
        int previous = KILLER_LABEL_RENDER_DEPTH.get();
        KILLER_LABEL_RENDER_DEPTH.set(previous + 1);
        try {
            renderCustomLabel(renderCall);
        } finally {
            if (previous == 0) {
                KILLER_LABEL_RENDER_DEPTH.remove();
            } else {
                KILLER_LABEL_RENDER_DEPTH.set(previous);
            }
        }
    }

    public static boolean isRenderingKillerLabel() {
        return KILLER_LABEL_RENDER_DEPTH.get() > 0;
    }

    /** Marks the explicitly supplied main IGN row in replacement nametag stacks. */
    public static void renderPrimaryNameLabel(Runnable renderCall) {
        if (renderCall == null) {
            return;
        }
        int previous = PRIMARY_NAME_LABEL_RENDER_DEPTH.get();
        PRIMARY_NAME_LABEL_RENDER_DEPTH.set(previous + 1);
        try {
            renderCustomLabel(renderCall);
        } finally {
            if (previous == 0) {
                PRIMARY_NAME_LABEL_RENDER_DEPTH.remove();
            } else {
                PRIMARY_NAME_LABEL_RENDER_DEPTH.set(previous);
            }
        }
    }

    public static boolean isRenderingPrimaryNameLabel() {
        return PRIMARY_NAME_LABEL_RENDER_DEPTH.get() > 0;
    }

    /** Identifies only the player's IGN row, not Lunar's independent health/stat rows. */
    public static boolean isPrimaryPlayerNameLabel(EntityPlayer player, String label) {
        if (player == null) {
            return false;
        }
        String cleanLabel = stripFormatting(label).trim();
        if (cleanLabel.isEmpty()) {
            return false;
        }

        String entityName = stripFormatting(player.getName()).trim();
        if (containsNameToken(cleanLabel, entityName)) {
            return true;
        }
        try {
            if (player.getGameProfile() != null
                    && containsNameToken(cleanLabel,
                    stripFormatting(player.getGameProfile().getName()).trim())) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            String displayName = player.getDisplayName() == null
                    ? "" : stripFormatting(player.getDisplayName().getFormattedText()).trim();
            return !displayName.isEmpty()
                    && (cleanLabel.equalsIgnoreCase(displayName)
                    || containsNameToken(displayName, cleanLabel));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean containsNameToken(String text, String name) {
        if (text == null || name == null || name.isEmpty()) {
            return false;
        }
        String lowerText = text.toLowerCase(java.util.Locale.ROOT);
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        int from = 0;
        while (from <= lowerText.length() - lowerName.length()) {
            int at = lowerText.indexOf(lowerName, from);
            if (at < 0) {
                return false;
            }
            int end = at + lowerName.length();
            boolean leftBoundary = at == 0 || !isUsernameChar(lowerText.charAt(at - 1));
            boolean rightBoundary = end == lowerText.length() || !isUsernameChar(lowerText.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = at + 1;
        }
        return false;
    }

    private static boolean isUsernameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static String stripFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '\u00a7' || c == '&') && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')
                        || code == 'k' || code == 'l' || code == 'm'
                        || code == 'n' || code == 'o' || code == 'r') {
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    public static String resolveDisplayedLabel(EntityPlayer ep) {
        if (ep == null) {
            return "";
        }
        String renderedName = "";
        try {
            if (ep.getDisplayName() != null) {
                String text = ep.getDisplayName().getFormattedText();
                if (text != null) {
                    renderedName = text;
                }
            }
        } catch (Throwable ignored) {
        }
        if (renderedName.isEmpty()) {
            renderedName = ep.getName();
        }

        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (LocalDisguise.isEnabled() && mc != null && ep == mc.thePlayer) {
                renderedName = LocalDisguise.buildNametagDisplayName(renderedName, mc.thePlayer.getName());
            }
        } catch (Throwable ignored) {
        }

        try {
            String remoteDisplay = RemoteDisguiseManager.getDisguisedDisplayNameFor(ep, renderedName);
            if (remoteDisplay != null && !remoteDisplay.isEmpty()) {
                renderedName = remoteDisplay;
            } else {
                String remoteName = RemoteDisguiseManager.getDisguisedNameFor(ep);
                if (remoteName != null && !remoteName.isEmpty()) {
                    renderedName = RemoteDisguiseManager.replaceNameInFormatted(renderedName, ep.getName(), remoteName);
                }
            }
        } catch (Throwable ignored) {
        }

        renderedName = applyKillEffectNametagColor(ep, renderedName);

        renderedName = LobbyPingDisplay.decorateNametag(ep, renderedName);
        if (me.napim.gomod.util.GameContext.isHypixelReplayWatching(mc)) {
            String username = ep.getGameProfile() == null ? ep.getName() : ep.getGameProfile().getName();
            return LocalDisguise.rewriteReplayNametagForRender(renderedName, username, mc.thePlayer == ep);
        }
        return SpectatorRelay.colorNametag(ep, BlitzChatNameDisplay.decorateNametag(ep, renderedName));
    }

    public static String getKillEffectNametagColor(EntityPlayer player) {
        if (player == null || !KillCounter.getKillEffectTabDisplayMode().colorsNametags()
                || ConfigManager.isKillCounterChatStyleNametag()) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (!BlitzChatNameDisplay.isActiveContext(mc)
                || me.napim.gomod.util.GameContext.isBlitzPregameLobby(mc)) return null;
        String lookup = player.getName();
        if (player == mc.thePlayer && player.getGameProfile() != null) lookup = player.getGameProfile().getName();
        if (lookup == null || lookup.isEmpty() || KillCounter.isPlayerDead(lookup)) return null;
        String effect = KillEffectCommand.getPlayerEffect(KillCounter.resolveTabLookupName(lookup));
        return effect == null || effect.isEmpty() || "none".equalsIgnoreCase(effect)
                ? null : killEffectColor(effect).toString();
    }

    /** Tint the actual IGN after disguise composition; rank, health and guild rows retain their formatting. */
    public static String applyKillEffectNametagColor(EntityPlayer player, String label) {
        String color = getKillEffectNametagColor(player);
        if (color == null || label == null || label.isEmpty()) return label;
        Minecraft mc = Minecraft.getMinecraft();
        String visible = player == mc.thePlayer && LocalDisguise.isEnabled()
                ? LocalDisguise.getTargetName() : RemoteDisguiseManager.getDisguisedNameFor(player);
        if (visible == null || visible.isEmpty()) visible = player.getName();
        return KillCounter.applyKillEffectColorToIgn(label, visible, color + visible + EnumChatFormatting.RESET);
    }

    private static EnumChatFormatting killEffectColor(String effect) {
        switch (effect.toUpperCase()) {
            case "REGENERATION":
                return EnumChatFormatting.LIGHT_PURPLE;
            case "RESISTANCE":
                return EnumChatFormatting.GOLD;
            case "GRAVEDIGGER":
                return EnumChatFormatting.GREEN;
            case "RANDOM":
                return EnumChatFormatting.DARK_BLUE;
            case "LEVEL_UP":
            case "LEVELUP":
                return EnumChatFormatting.YELLOW;
            case "RAPID_FIRE":
            case "FLAME":
                return EnumChatFormatting.DARK_RED;
            case "SPEED":
                return EnumChatFormatting.AQUA;
            default:
                return EnumChatFormatting.AQUA;
        }
    }
}
