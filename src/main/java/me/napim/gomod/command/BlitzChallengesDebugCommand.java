package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.BlitzChallengeBadgeEquip;
import me.napim.gomod.features.BlitzChallenges;
import me.napim.gomod.features.BlitzChallenges.ChallengeId;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Dev build only: complete or clear Blitz challenge tiers for badge testing.
 */
public class BlitzChallengesDebugCommand extends Command {

    public BlitzChallengesDebugCommand() {
        super("bchdebug", new String[]{"blitzchallengesdebug"});
    }

    @Override
    public void handle(String[] args) {
        if (!GomodInternalFeatures.isBlitzChallengesEnabled()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Blitz Challenges debug is not available in this build.");
            return;
        }
        if (args.length == 0) {
            showHelp();
            return;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "complete":
            case "completeall":
                handleComplete(args, "completeall".equals(sub));
                break;
            case "clear":
            case "clearall":
                handleClear(args, "clearall".equals(sub));
                break;
            case "list":
                showList();
                break;
            default:
                showHelp();
                break;
        }
    }

    private void handleComplete(String[] args, boolean allFlag) {
        if (allFlag || (args.length >= 2 && "all".equalsIgnoreCase(args[1]))) {
            int total = 0;
            for (ChallengeId id : ChallengeId.values()) {
                total += BlitzChallenges.debugCompleteChallenge(id, true);
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Completed all tiers ("
                    + total + " new). Unlocked badges: " + BlitzChallengeBadgeEquip.getUnlockedBadgeCount());
            return;
        }
        if (args.length < 2) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Usage: /bchdebug complete <id|all>");
            return;
        }
        ChallengeId id = ChallengeId.fromKey(args[1]);
        if (id == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown challenge: " + args[1]);
            return;
        }
        boolean allTiers = args.length >= 3 && ("all".equalsIgnoreCase(args[2]) || "full".equalsIgnoreCase(args[2]));
        int added = BlitzChallenges.debugCompleteChallenge(id, allTiers);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Added " + added + " tier(s) for "
                + EnumChatFormatting.GOLD + id.getDisplayName()
                + (BlitzChallenges.isFullyCompleted(id)
                ? EnumChatFormatting.YELLOW + " (badge unlocked)"
                : ""));
    }

    private void handleClear(String[] args, boolean allFlag) {
        if (allFlag || (args.length >= 2 && "all".equalsIgnoreCase(args[1]))) {
            BlitzChallenges.debugClearAllChallenges();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Cleared all challenge progress.");
            return;
        }
        if (args.length < 2) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Usage: /bchdebug clear <id|all>");
            return;
        }
        ChallengeId id = ChallengeId.fromKey(args[1]);
        if (id == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown challenge: " + args[1]);
            return;
        }
        boolean allTiers = args.length >= 3 && ("all".equalsIgnoreCase(args[2]) || "full".equalsIgnoreCase(args[2]));
        int removed = BlitzChallenges.debugClearChallenge(id, allTiers);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Removed " + removed + " tier(s) for "
                + EnumChatFormatting.GOLD + id.getDisplayName());
    }

    private void showList() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Blitz challenge debug status:");
        for (ChallengeId id : ChallengeId.values()) {
            int done = BlitzChallenges.getCompletedTierCount(id);
            String state = BlitzChallenges.isFullyCompleted(id)
                    ? EnumChatFormatting.GREEN + "BADGE"
                    : EnumChatFormatting.GRAY + String.valueOf(done) + "/" + id.getTierCount();
            Main.sendMessage(EnumChatFormatting.AQUA + id.getKey() + EnumChatFormatting.WHITE + " — "
                    + id.getDisplayName() + EnumChatFormatting.GRAY + " [" + state + EnumChatFormatting.GRAY + "]");
        }
        Main.sendMessage(EnumChatFormatting.GRAY + "Equipped: " + BlitzChallengeBadgeEquip.getStatusLabel());
    }

    private void showHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Blitz Challenges debug:");
        Main.sendMessage(EnumChatFormatting.GRAY + "/bchdebug list" + EnumChatFormatting.WHITE + " — tier progress");
        Main.sendMessage(EnumChatFormatting.GRAY + "/bchdebug complete <id> [all]" + EnumChatFormatting.WHITE
                + " — complete next tier or all tiers");
        Main.sendMessage(EnumChatFormatting.GRAY + "/bchdebug completeall" + EnumChatFormatting.WHITE
                + " — complete every challenge");
        Main.sendMessage(EnumChatFormatting.GRAY + "/bchdebug clear <id> [all]" + EnumChatFormatting.WHITE
                + " — remove last tier or all tiers");
        Main.sendMessage(EnumChatFormatting.GRAY + "/bchdebug clearall" + EnumChatFormatting.WHITE
                + " — wipe all progress");
    }
}
