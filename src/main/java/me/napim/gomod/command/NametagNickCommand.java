package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.TabDuplicateNickResolver;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.regex.Pattern;

/**
 * Toggles an extra orange nametag line (real IGN) above nicked players we have resolved;
 * optional {@code /nametagnick nick ign} registers a manual denick hint for TAB and tabstats.
 */
public class NametagNickCommand extends Command {

    private static final Pattern MC_USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    public NametagNickCommand() {
        super("nametagnick", "nnick");
    }

    @Override
    public void handle(String[] args) {
        if (args.length > 0) {
            String a0 = args[0].trim();
            if (a0.equalsIgnoreCase("remove") || a0.equalsIgnoreCase("reset")
                    || a0.equalsIgnoreCase("delete") || a0.equalsIgnoreCase("clear")) {
                if (args.length != 2) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Usage: /nametagnick remove <nick>");
                    return;
                }
                String nick = args[1].trim();
                if (!MC_USERNAME.matcher(nick).matches()) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Nick must be a valid Minecraft name (3–16: letters, digits, _).");
                    return;
                }
                boolean removed = TabDuplicateNickResolver.removeManualNickToRealGuess(nick);
                KillCounter.markTabCompositionDirty();
                if (removed) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Removed manual nick hint for "
                            + EnumChatFormatting.WHITE + nick + EnumChatFormatting.YELLOW + ".");
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "No manual nick hint found for " + EnumChatFormatting.WHITE + nick + EnumChatFormatting.RED + ".");
                }
                return;
            }
        }

        if (args.length >= 2) {
            String nick = args[0].trim();
            String ign = args[1].trim();
            if (args.length > 2) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Usage: /nametagnick <nick> <IGN> — no spaces in names (3–16 chars each).");
                return;
            }
            if (!MC_USERNAME.matcher(nick).matches() || !MC_USERNAME.matcher(ign).matches()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Nick and IGN must be valid Minecraft names (3–16: letters, digits, _).");
                return;
            }
            if (nick.equalsIgnoreCase(ign)) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Nick and IGN must differ.");
                return;
            }
            TabDuplicateNickResolver.registerManualNickToRealGuess(nick, ign);
            KillCounter.markTabCompositionDirty();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Registered "
                    + EnumChatFormatting.WHITE + nick + EnumChatFormatting.GREEN + " → "
                    + EnumChatFormatting.GOLD + ign + EnumChatFormatting.GRAY
                    + " for TAB / tabstats. If TAB later denicks to a different Mojang name, that name replaces this.");
            return;
        }

        if (args.length > 0) {
            String a0 = args[0].trim();
            if (a0.equalsIgnoreCase("on")) {
                ConfigManager.setNickRealNametagOverlayEnabled(true);
            } else if (a0.equalsIgnoreCase("off")) {
                ConfigManager.setNickRealNametagOverlayEnabled(false);
            } else if (a0.equalsIgnoreCase("rank")) {
                if (args.length >= 2) {
                    String a1 = args[1].trim();
                    if (a1.equalsIgnoreCase("on")) {
                        ConfigManager.setNickRealNametagRankPlateEnabled(true);
                    } else if (a1.equalsIgnoreCase("off")) {
                        ConfigManager.setNickRealNametagRankPlateEnabled(false);
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                + "Usage: /nametagnick rank  |  /nametagnick rank on  |  /nametagnick rank off");
                        return;
                    }
                } else {
                    ConfigManager.setNickRealNametagRankPlateEnabled(!ConfigManager.isNickRealNametagRankPlateEnabled());
                }
                boolean rk = ConfigManager.isNickRealNametagRankPlateEnabled();
                KillCounter.markTabCompositionDirty();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Nametag rank line (Hypixel tag + colors) "
                        + (rk ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "off")
                        + EnumChatFormatting.GRAY + " — TAB (real IGN) uses rank colors when on; needs /tabstats for API");
                return;
            } else if (a0.equalsIgnoreCase("help") || a0.equals("?")) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "/nametagnick — toggle  |  on  |  off  |  "
                        + EnumChatFormatting.WHITE + "<nick> <IGN>"
                        + EnumChatFormatting.YELLOW + " — manual denick hint; real-IGN line above players");
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "/nametagnick rank — toggle  |  rank on  |  rank off  "
                        + EnumChatFormatting.GRAY + "(nametag + TAB denick: Hypixel rank tag/colors vs server/gold styling)");
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "/nametagnick remove " + EnumChatFormatting.WHITE + "<nick>"
                        + EnumChatFormatting.YELLOW + " — delete a manual hint");
                return;
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Use /nametagnick, on, off, rank, help, /nametagnick <nick> <IGN>, or /nametagnick remove <nick>.");
                return;
            }
        } else {
            ConfigManager.setNickRealNametagOverlayEnabled(!ConfigManager.isNickRealNametagOverlayEnabled());
        }
        boolean on = ConfigManager.isNickRealNametagOverlayEnabled();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Real-IGN nametag overlay "
                + (on ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "off")
                + EnumChatFormatting.GRAY + " (needs nick→real from TAB / teams finder / /nametagnick nick ign)");
    }
}
