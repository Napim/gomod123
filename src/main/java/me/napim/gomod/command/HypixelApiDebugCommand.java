package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.HypixelApiRequestDebug;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.io.File;
import java.util.Locale;

/** Opt-in capture for actual outbound HTTP attempts to the Hypixel API. */
public final class HypixelApiDebugCommand extends Command {
    public HypixelApiDebugCommand() {
        super("hypixelapidebug");
    }

    @Override
    public void handle(String[] args) {
        String action = args == null || args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
        if ("on".equals(action) || "start".equals(action) || "toggle".equals(action)
                && !HypixelApiRequestDebug.isEnabled()) {
            if (HypixelApiRequestDebug.isEnabled()) {
                send(EnumChatFormatting.YELLOW + "Hypixel API request capture is already ON. "
                        + EnumChatFormatting.GRAY + HypixelApiRequestDebug.getStatus());
                return;
            }
            HypixelApiRequestDebug.start();
            send(EnumChatFormatting.GREEN + "Hypixel API request capture ON. "
                    + EnumChatFormatting.GRAY + "After the cooldown message or failed refresh, run /hypixelapidebug off.");
            send(EnumChatFormatting.GRAY + (HypixelApiRequestDebug.isDetailedCaptureEnabled()
                    ? "Tracks HTTP attempts, repeated targets, TAB scan triggers, cache hits and rate-limit waits."
                    : "Tracks Hypixel HTTP attempts, their callers and response status."));
            return;
        }

        if ("toggle".equals(action)) {
            saveAndStop();
            return;
        }
        if ("off".equals(action) || "stop".equals(action)) {
            if (!HypixelApiRequestDebug.isEnabled()) {
                send(EnumChatFormatting.YELLOW + "Hypixel API request capture is already OFF.");
                return;
            }
            saveAndStop();
            return;
        }
        if ("status".equals(action)) {
            send(EnumChatFormatting.YELLOW + "Hypixel API request capture: "
                    + EnumChatFormatting.WHITE + HypixelApiRequestDebug.getStatus());
            return;
        }
        if ("dump".equals(action)) {
            try {
                File report = HypixelApiRequestDebug.saveSnapshot();
                send(EnumChatFormatting.GREEN + "Saved current Hypixel API trace: "
                        + EnumChatFormatting.WHITE + report.getAbsolutePath());
            } catch (Exception failure) {
                send(EnumChatFormatting.RED + "Could not save Hypixel API trace: "
                        + failure.getClass().getSimpleName());
            }
            return;
        }

        send(EnumChatFormatting.YELLOW + "Usage: /hypixelapidebug [on|off|status|dump]");
    }

    private void saveAndStop() {
        try {
            File report = HypixelApiRequestDebug.stopAndSave();
            send(EnumChatFormatting.GREEN + "Hypixel API request capture OFF. Saved report: "
                    + EnumChatFormatting.WHITE + report.getAbsolutePath());
        } catch (Exception failure) {
            send(EnumChatFormatting.RED + "Capture stopped, but the Hypixel API report could not be saved: "
                    + failure.getClass().getSimpleName());
        }
    }

    private void send(String message) {
        Main.sendMessage(Main.CHAT_PREFIX + message);
    }
}
