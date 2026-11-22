package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.TabPerformanceDebug;
import net.weavemc.loader.api.command.Command;

public final class TabLagDebugCommand extends Command {
    public TabLagDebugCommand() { super("tablagdebug", "tabperfdebug"); }
    @Override public void handle(String[] args) {
        String action = args.length == 0 ? "status" : args[0];
        String message;
        boolean players = "players".equalsIgnoreCase(action);
        if (players || "start".equalsIgnoreCase(action)) {
            int seconds = 120;
            if (args.length > 2) { Main.sendMessage(Main.CHAT_PREFIX + "Use /tablagdebug start|players [10-900 seconds]."); return; }
            if (args.length == 2) {
                try { seconds = Integer.parseInt(args[1]); }
                catch (NumberFormatException invalid) { seconds = 0; }
                if (seconds < 10 || seconds > 900) {
                    Main.sendMessage(Main.CHAT_PREFIX + "Capture duration must be 10-900 seconds."); return;
                }
            }
            boolean started = players ? TabPerformanceDebug.startPlayers(seconds) : TabPerformanceDebug.start(seconds);
            message = started
                    ? players
                        ? "Player lag capture started (" + seconds + "s max). Keep TAB closed: look at players 10s, away 10s, back at players 10s. The log saves automatically."
                        : "TAB lag capture started (" + seconds + "s max). Stay in one spot: TAB closed 10s, open 10s, closed 10s; then /tablagdebug stop."
                    : TabPerformanceDebug.status();
        }
        else if ("stop".equalsIgnoreCase(action)) message = TabPerformanceDebug.stop();
        else message = TabPerformanceDebug.status() + " Use /tablagdebug start|players [seconds]|stop|status.";
        Main.sendMessage(Main.CHAT_PREFIX + message);
    }
}
