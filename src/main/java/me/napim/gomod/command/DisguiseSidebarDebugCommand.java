package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.util.GomodPaths;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Prints why Blitz sidebar stats rewrite is active or not (use on Hypixel while disguised).
 */
public class DisguiseSidebarDebugCommand extends Command {

    public DisguiseSidebarDebugCommand() {
        super("disguisesidebardebug", "dsbd", "dsdb", "dsd");
    }

    @Override
    public void handle(String[] args) {
        List<String> lines = LocalDisguise.buildDisguiseSidebarDebugLines();
        for (String line : lines) {
            Main.sendMessage(Main.CHAT_PREFIX + line + EnumChatFormatting.RESET);
        }
        try {
            File folder = new File(GomodPaths.getGomod123Dir(), "debugall");
            Files.createDirectories(folder.toPath());
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
            File log = File.createTempFile("disguise_sidebar_" + stamp + "_", ".log", folder);
            StringBuilder out = new StringBuilder("# Disguise sidebar snapshot\n");
            for (String line : lines) out.append(EnumChatFormatting.getTextWithoutFormattingCodes(line)).append('\n');
            Files.write(log.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
            Main.sendMessage(Main.CHAT_PREFIX + "Disguise sidebar debug saved: " + log.getAbsolutePath());
        } catch (Exception error) {
            Main.sendMessage(Main.CHAT_PREFIX + "Could not save sidebar debug: " + error.getClass().getSimpleName());
        }
    }
}
