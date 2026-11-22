package me.napim.gomod.command;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import me.napim.gomod.Main;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.LunarOnlineStatus;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.io.File;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Public diagnostic report containing connection progress and native fingerprints, never credentials. */
public final class LunarConnectionDebugCommand extends Command {
    public LunarConnectionDebugCommand() {
        super("lunarconnectiondebug");
    }

    @Override
    public void handle(String[] args) {
        if (!GomodFixes.isWeaveRuntime()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Lunar FullAccess requires the Lunar Weave build.");
            return;
        }
        try {
            JsonObject report = LunarOnlineStatus.getFullAccessConnectionDiagnostics();
            report.addProperty("capturedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).format(new Date()));
            report.addProperty("lunarOnlineState", LunarOnlineStatus.getLunarOnlineState().name());
            report.addProperty("fullAccessState", LunarOnlineStatus.getFullAccessState().name());
            report.addProperty("fullAccessMessage", LunarOnlineStatus.getFullAccessStatusMessage());
            File directory = new File(GomodPaths.getGomod123Dir(), "debug");
            Files.createDirectories(directory.toPath());
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
            File output = new File(directory, "lunar-connection-" + stamp + ".json");
            try (Writer writer = Files.newBufferedWriter(output.toPath(), StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(report, writer);
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + LunarOnlineStatus.getFullAccessStatusMessage());
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Saved Lunar connection report: "
                    + EnumChatFormatting.WHITE + output.getAbsolutePath());
        } catch (Exception failure) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not save the Lunar connection report: "
                    + failure.getClass().getSimpleName());
        }
    }
}
