package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ClientTickHub;
import me.napim.gomod.features.MobNametags;
import me.napim.gomod.features.SpawnedMobTracker;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Chat debug for {@link me.napim.gomod.features.SpawnedMobTracker} (green "your spawn" mob nametags).
 */
public class SpawnedMobTrackerDebugCommand extends Command {

    public SpawnedMobTrackerDebugCommand() {
        super("spawnedmobdebug");
    }

    @Override
    public void handle(String[] args) {
        if (args.length >= 1) {
            String a = args[0].toLowerCase();
            if ("on".equals(a) || "enable".equals(a) || "true".equals(a)) {
                SpawnedMobTracker.setDebugEnabled(true);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Spawned-mob tracker debug: ON");
                sendStatus();
                return;
            }
            if ("off".equals(a) || "disable".equals(a) || "false".equals(a)) {
                SpawnedMobTracker.setDebugEnabled(false);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Spawned-mob tracker debug: OFF");
                return;
            }
            if ("status".equals(a)) {
                sendStatus();
                return;
            }
            if ("scan".equals(a) || "entities".equals(a) || "near".equals(a)) {
                SpawnedMobTracker.debugScanNearbyLiving();
                return;
            }
            if ("clear".equals(a) || "reset".equals(a)) {
                SpawnedMobTracker.debugClearTrackedState();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Spawn tracker state cleared (pending + tagged UUIDs).");
                sendStatus();
                return;
            }
            if ("help".equals(a) || "?".equals(a)) {
                sendHelp();
                return;
            }
        } else {
            boolean next = !SpawnedMobTracker.isDebugEnabled();
            SpawnedMobTracker.setDebugEnabled(next);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Spawned-mob tracker debug: "
                    + (next ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
            sendStatus();
            return;
        }
        sendHelp();
    }

    private void sendHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/spawnedmobdebug"
                + EnumChatFormatting.WHITE + " — toggle or:");
        Main.sendMessage(EnumChatFormatting.GRAY + "  on | off | status | scan | clear | help");
        Main.sendMessage(EnumChatFormatting.GRAY + "  "
                + EnumChatFormatting.WHITE + "scan"
                + EnumChatFormatting.GRAY + " — list nearby mobs: entity id, UUID, typeId, tagged=yes/no");
    }

    private void sendStatus() {
        boolean tickOn = ClientTickHub.isFeatureEnabled("SpawnedMobTracker");
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + SpawnedMobTracker.debugStatusSummary());
        Main.sendMessage(EnumChatFormatting.DARK_GRAY + "  last egg: " + EnumChatFormatting.GRAY
                + SpawnedMobTracker.getDebugLastEggLine());
        Main.sendMessage(EnumChatFormatting.DARK_GRAY + "  last tag: " + EnumChatFormatting.GRAY
                + SpawnedMobTracker.getDebugLastTagLine());
        Main.sendMessage(EnumChatFormatting.GRAY + "TickHub SpawnedMobTracker: "
                + (tickOn ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + " — if OFF, run "
                + EnumChatFormatting.YELLOW + "/gomodlag tick enable SpawnedMobTracker");
        Main.sendMessage(EnumChatFormatting.GRAY + "Mob nametags (needed to see green): "
                + (MobNametags.isEnabled() ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + " — Nametags settings GUI or radial toggle");
        Main.sendMessage(EnumChatFormatting.GRAY + "With debug ON, spawn an egg: chat lines "
                + EnumChatFormatting.WHITE + "[spawnTrack] egg use recorded"
                + EnumChatFormatting.GRAY + " then "
                + EnumChatFormatting.WHITE + "matched …"
                + EnumChatFormatting.GRAY + ". UUID proof: "
                + EnumChatFormatting.YELLOW + "/spawnedmobdebug scan");
    }
}
