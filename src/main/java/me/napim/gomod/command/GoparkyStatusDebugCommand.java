package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.GoparkyBlitzReactions;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Reports whether each local Goparky animation and GEK render checkpoint has been reached. */
public final class GoparkyStatusDebugCommand extends Command {
    public GoparkyStatusDebugCommand() {
        super("debuggoparky");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        String[] lines = GoparkyBlitzReactions.getDebugStatus(mc);
        for (String line : lines) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + line);
        }
        if (mc == null || mc.mcDataDir == null) return;
        try {
            Path directory = mc.mcDataDir.toPath().resolve("gomod123").resolve("debugall");
            Files.createDirectories(directory);
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
                    .withZone(ZoneId.systemDefault()).format(Instant.now());
            Path file = directory.resolve("goparky_status_" + timestamp + "_"
                    + Long.toUnsignedString(java.util.concurrent.ThreadLocalRandom.current().nextLong())
                    + ".log");
            String contents = String.join(System.lineSeparator(), lines) + System.lineSeparator();
            Files.write(file, contents.getBytes(StandardCharsets.UTF_8));
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + "Goparky debug saved: " + file.toAbsolutePath());
        } catch (IOException error) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Could not save Goparky debug log: " + error.getClass().getSimpleName());
        }
    }
}
