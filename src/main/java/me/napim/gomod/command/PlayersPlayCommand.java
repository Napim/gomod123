package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.PlayersPlayMode;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * /playersplay — 4-way split nearest-player POV rendering (with toggle chat).
 * For silent chat use {@link PlayersShowCommand} (/playershow).
 */
public class PlayersPlayCommand extends Command {

    public PlayersPlayCommand() {
        super("playersplay");
    }

    @Override
    public void handle(String[] args) {
        handleInternal(args, false);
    }

    /**
     * @param silentChat true for /playershow: no enabled/disabled (or "already off") chat lines.
     */
    public static void handleInternal(String[] args, boolean silentChat) {
        if (args.length > 0 && "off".equalsIgnoreCase(args[0])) {
            if (PlayersPlayMode.isEnabled()) {
                boolean muted = PlayersPlayMode.isChatMutedThisSession();
                PlayersPlayMode.disable();
                PlayersPlayMode.notifyDisabled(muted);
            } else if (!silentChat) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "PlayersPlay is already disabled.");
            }
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            if (!silentChat) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Cannot enable PlayersPlay right now.");
            }
            return;
        }

        boolean stayMode = args.length > 0 && "stay".equalsIgnoreCase(args[0]);
        List<String> requestedNames = parsePlayerNames(args, stayMode ? 1 : 0);

        if (PlayersPlayMode.isEnabled() && args.length == 0) {
            boolean muted = PlayersPlayMode.isChatMutedThisSession();
            PlayersPlayMode.disable();
            PlayersPlayMode.notifyDisabled(muted);
            return;
        }

        if (!requestedNames.isEmpty()) {
            if (requestedNames.size() > 4) {
                if (!silentChat) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "PlayersShow supports up to 4 named players.");
                }
                return;
            }

            List<EntityPlayer> selectedPlayers = new ArrayList<EntityPlayer>();
            for (String playerName : requestedNames) {
                EntityPlayer player = PlayersPlayMode.findPlayerByName(playerName);
                if (player == null) {
                    if (!silentChat) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                + "Could not find player: " + EnumChatFormatting.YELLOW + playerName);
                    }
                    return;
                }
                selectedPlayers.add(player);
            }

            PlayersPlayMode.setChatMutedThisSession(silentChat);
            if (!PlayersPlayMode.enable(selectedPlayers, requestedNames, true)) {
                PlayersPlayMode.setChatMutedThisSession(false);
                if (!silentChat) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to enable PlayersPlay.");
                }
                return;
            }

            PlayersPlayMode.notifyEnabled(requestedNames.size());
            return;
        }

        List<EntityPlayer> nearby = PlayersPlayMode.findNearestPlayers(4);
        if (nearby.isEmpty()) {
            if (!silentChat) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "No nearby players found (within render distance).");
            }
            return;
        }

        PlayersPlayMode.setChatMutedThisSession(silentChat);
        if (!PlayersPlayMode.enable(nearby, stayMode)) {
            PlayersPlayMode.setChatMutedThisSession(false);
            if (!silentChat) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to enable PlayersPlay.");
            }
            return;
        }

        PlayersPlayMode.notifyEnabled(nearby.size());
    }

    private static List<String> parsePlayerNames(String[] args, int startIndex) {
        List<String> names = new ArrayList<String>();
        if (args == null || startIndex >= args.length) {
            return names;
        }

        StringBuilder rawNames = new StringBuilder();
        for (int i = startIndex; i < args.length; i++) {
            if (rawNames.length() > 0) {
                rawNames.append(' ');
            }
            rawNames.append(args[i]);
        }

        String joined = rawNames.toString().trim();
        if (joined.isEmpty()) {
            return names;
        }

        Set<String> seenNames = new HashSet<String>();
        String[] splitNames = joined.split(",");
        for (String rawName : splitNames) {
            String cleanedName = rawName.trim();
            if (cleanedName.isEmpty()) {
                continue;
            }

            String normalized = cleanedName.toLowerCase(Locale.ROOT);
            if (!seenNames.add(normalized)) {
                continue;
            }
            names.add(cleanedName);
        }
        return names;
    }
}
