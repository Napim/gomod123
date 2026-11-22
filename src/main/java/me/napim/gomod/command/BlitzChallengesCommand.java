package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.BlitzChallenges;
import me.napim.gomod.features.BlitzChallenges.ChallengeId;
import me.napim.gomod.util.GuiOpener;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Command for opt-in Blitz SG tiered self-challenges.
 * Usage: /blitzchallenges list|start|stop|progress|badges|hud
 */
public class BlitzChallengesCommand extends Command {

    public BlitzChallengesCommand() {
        super("blitzchallenges", new String[]{"bchallenges", "bch"});
    }

    @Override
    public void handle(String[] args) {
        if (!GomodInternalFeatures.isBlitzChallengesEnabled()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Blitz Challenges are not available in this build.");
            return;
        }
        if (args.length == 0) {
            openGui();
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "gui":
                openGui();
                break;
            case "list":
            case "help":
                showList();
                break;
            case "start":
                handleStart(args);
                break;
            case "stop":
            case "cancel":
                BlitzChallenges.stopChallenge();
                break;
            case "progress":
            case "status":
                showProgress();
                break;
            case "badges":
                showBadges();
                break;
            case "hud":
                handleHud(args);
                break;
            default:
                ChallengeId id = ChallengeId.fromKey(sub);
                if (id != null) {
                    int tier = BlitzChallenges.getNextAvailableTierIndex(id);
                    BlitzChallenges.startChallenge(id, tier);
                } else {
                    showHelp();
                }
                break;
        }
    }

    private void handleStart(String[] args) {
        if (args.length < 2) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Usage: /blitzchallenges start <challenge> [tier]");
            showList();
            return;
        }
        ChallengeId id = ChallengeId.fromKey(args[1]);
        if (id == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown challenge: " + args[1]);
            return;
        }
        int tierIndex = BlitzChallenges.getNextAvailableTierIndex(id);
        if (args.length >= 3) {
            try {
                int tierNum = Integer.parseInt(args[2]);
                tierIndex = tierNum - 1;
            } catch (NumberFormatException e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Tier must be a number.");
                return;
            }
        }
        BlitzChallenges.startChallenge(id, tierIndex);
    }

    private void handleHud(String[] args) {
        if (args.length < 2) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Challenge HUD "
                    + (BlitzChallenges.isHudEnabled()
                    ? EnumChatFormatting.GREEN + "enabled"
                    : EnumChatFormatting.RED + "disabled"));
            return;
        }
        String v = args[1].toLowerCase();
        if ("on".equals(v) || "true".equals(v)) {
            BlitzChallenges.setHudEnabled(true);
        } else if ("off".equals(v) || "false".equals(v)) {
            BlitzChallenges.setHudEnabled(false);
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: /blitzchallenges hud [on|off]");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Challenge HUD "
                + (BlitzChallenges.isHudEnabled()
                ? EnumChatFormatting.GREEN + "enabled"
                : EnumChatFormatting.RED + "disabled"));
    }

    private void openGui() {
        GuiOpener.open(
                "me.napim.gomod.gui.BlitzChallengesGUI",
                new Class<?>[]{net.minecraft.client.gui.GuiScreen.class},
                new Object[]{null}
        );
    }

    private void showHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Blitz Challenges:");
        Main.sendMessage(EnumChatFormatting.GRAY + "/blitzchallenges" + EnumChatFormatting.WHITE
                + " — open GUI");
        Main.sendMessage(EnumChatFormatting.GRAY + "/blitzchallenges gui" + EnumChatFormatting.WHITE
                + " — open GUI");
        Main.sendMessage(EnumChatFormatting.GRAY + "/blitzchallenges list" + EnumChatFormatting.WHITE
                + " — all challenges (* = dodge fails)");
        Main.sendMessage(EnumChatFormatting.GRAY + "/blitzchallenges start <id> [tier]" + EnumChatFormatting.WHITE
                + " — start next or specific tier");
        Main.sendMessage(EnumChatFormatting.GRAY + "/blitzchallenges stop" + EnumChatFormatting.WHITE
                + " — cancel active challenge");
        Main.sendMessage(EnumChatFormatting.GRAY + "/blitzchallenges progress" + EnumChatFormatting.WHITE
                + " — active progress");
        Main.sendMessage(EnumChatFormatting.GRAY + "/blitzchallenges badges" + EnumChatFormatting.WHITE
                + " — completed tiers");
        Main.sendMessage(EnumChatFormatting.GRAY + "/blitzchallenges hud [on|off]");
    }

    private void showList() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Blitz Challenges "
                + EnumChatFormatting.RED + "(* = dodging queue fails):");
        for (String line : BlitzChallenges.buildListLines()) {
            Main.sendMessage(line);
        }
        Main.sendMessage(EnumChatFormatting.GRAY + "IDs: blood_hour, massacre, rampage, first_blood, "
                + "no_mercy, kill_streak, hot_streak");
    }

    private void showProgress() {
        if (!BlitzChallenges.hasActiveChallenge()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "No active challenge.");
            return;
        }
        ChallengeId id = BlitzChallenges.getActiveChallenge();
        int tier = BlitzChallenges.getActiveTierIndex();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + id.getDisplayName()
                + EnumChatFormatting.YELLOW + " " + BlitzChallenges.formatTierRoman(tier));
        Main.sendMessage(EnumChatFormatting.GRAY + id.formatDescription(tier));
        for (String line : BlitzChallenges.buildHudLines()) {
            Main.sendMessage(EnumChatFormatting.AQUA + line);
        }
    }

    private void showBadges() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Badges ("
                + BlitzChallenges.getTotalBadgeCount() + " tiers completed):");
        Main.sendMessage(EnumChatFormatting.GRAY + BlitzChallenges.getBadgeSummary());
    }
}
