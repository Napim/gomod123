package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.DisguiseRenamedItemMasker;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

public class DisguiseRenamedItemsDebugCommand extends Command {

    public DisguiseRenamedItemsDebugCommand() {
        super("disguiserenameddebug", "drid", "drdebug");
    }

    @Override
    public void handle(String[] args) {
        List<String> lines = DisguiseRenamedItemMasker.buildDebugLines();
        for (String line : lines) {
            Main.sendMessage(Main.CHAT_PREFIX + line + EnumChatFormatting.RESET);
        }

        // Same folder as gomod config: <.minecraft>/gomod123/ (not JVM cwd, which is usually .minecraft root).
        File dataDir = ConfigManager.getGomodDataDirectory();
        if (!dataDir.isDirectory()) {
            try {
                dataDir.mkdirs();
            } catch (Throwable ignored) {
            }
        }
        File out = new File(dataDir, "disguise-renamed-items-debug.txt");
        try (FileWriter writer = new FileWriter(out, false)) {
            for (String line : lines) {
                // Strip Minecraft formatting codes so the file is plain-text friendly.
                String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line);
                writer.write((plain == null ? "" : plain));
                writer.write(System.lineSeparator());
            }
            Main.sendMessage(
                    Main.CHAT_PREFIX
                            + EnumChatFormatting.GREEN
                            + "Saved renamed-items debug to: "
                            + EnumChatFormatting.YELLOW
                            + out.getAbsolutePath()
                            + EnumChatFormatting.RESET
            );
        } catch (IOException e) {
            Main.sendMessage(
                    Main.CHAT_PREFIX
                            + EnumChatFormatting.RED
                            + "Failed to save renamed-items debug: "
                            + e.getClass().getSimpleName()
                            + " ("
                            + e.getMessage()
                            + ")"
                            + EnumChatFormatting.RESET
            );
        }
    }
}
