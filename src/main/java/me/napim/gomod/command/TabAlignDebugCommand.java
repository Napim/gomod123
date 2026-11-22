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

/** Saves TAB layout plus live hook counters and Lunar renderer discovery details. */
public final class TabAlignDebugCommand extends Command {
    public TabAlignDebugCommand() {
        super("tabaligndebug");
    }

    @Override
    public void handle(String[] args) {
        try {
            File dir = new File(GomodPaths.getGomod123Dir(), "debugall");
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IllegalStateException("Could not create " + dir.getAbsolutePath());
            }
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
            File output = new File(dir, "tab_alignment_" + timestamp + ".txt");
            Files.write(output.toPath(), KillCounter.buildTabAlignmentDebugReport().getBytes(StandardCharsets.UTF_8));
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "TAB alignment report saved: "
                    + EnumChatFormatting.WHITE + output.getAbsolutePath());
            Main.sendMessage(EnumChatFormatting.YELLOW
                    + "Run this while the broken TAB is open, then send the report with an F2 screenshot.");
        } catch (Throwable error) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not save TAB alignment report: "
                    + EnumChatFormatting.WHITE + String.valueOf(error.getMessage()));
        }
    }
}
