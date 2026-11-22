package me.napim.gomod.command;

import me.napim.gomod.gui.LeaderboardGUI;
import net.minecraft.client.Minecraft;
import net.weavemc.loader.api.command.Command;

/** Opens the leaderboard around a kill-count search, used by clickable death notices. */
public final class GoStatsLeaderboardSearchCommand extends Command {
    public GoStatsLeaderboardSearchCommand() {
        super("gostatslbsearch");
    }

    @Override
    public void handle(String[] args) {
        if (args == null || args.length != 1 || !args[0].matches("\\d{1,15}")) return;
        final long killCount;
        try {
            killCount = Long.parseLong(args[0]);
        } catch (NumberFormatException ignored) {
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) return;
        Runnable openLeaderboard = () -> minecraft.displayGuiScreen(new LeaderboardGUI(null, killCount));
        if (minecraft.isCallingFromMinecraftThread()) {
            openLeaderboard.run();
        } else {
            minecraft.addScheduledTask(openLeaderboard);
        }
    }
}
