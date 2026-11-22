package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Saves the live kill graph and grouped TAB rows for reciprocal-kill diagnostics. */
public final class TabVictimsDebugCommand extends Command {
    public TabVictimsDebugCommand() {
        super("tabvictimsdebug");
    }

    @Override
    public void handle(String[] args) {
        try {
            File dir = new File(GomodPaths.getGomod123Dir(), "debugall");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Could not create " + dir.getAbsolutePath());
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
            File output = new File(dir, "tab_victims_" + timestamp + ".log");
            Files.write(output.toPath(), KillCounter.buildTabVictimsDebugReport().getBytes(StandardCharsets.UTF_8));
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Tab Victims debug saved: "
                    + EnumChatFormatting.WHITE + output.getAbsolutePath());
            Main.sendMessage(EnumChatFormatting.YELLOW
                    + "Run it while the broken TAB is open, then share the .log.");
        } catch (Throwable error) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not save Tab Victims debug: "
                    + EnumChatFormatting.WHITE + String.valueOf(error.getMessage()));
        }
    }
}
