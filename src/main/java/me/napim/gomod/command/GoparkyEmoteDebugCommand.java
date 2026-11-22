package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.GoparkyBlitzReactions;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Arrays;

/** Tests Lunar's installed body emote clips on the local GoParky model. */
public final class GoparkyEmoteDebugCommand extends Command {
    public GoparkyEmoteDebugCommand() {
        super("debuggoparkyemote");
    }

    @Override
    public void handle(String[] args) {
        if (args != null && args.length > 1) {
            usage();
            return;
        }
        Minecraft minecraft = Minecraft.getMinecraft();
        if (args != null && args.length == 1 && "list".equalsIgnoreCase(args[0])) {
            me.napim.gomod.features.GoparkyLunarEmoteLibrary.requestLoad(minecraft);
            if (!me.napim.gomod.features.GoparkyLunarEmoteLibrary.isReady()) {
                reportAssetReadiness();
                return;
            }
            String[] names = GoparkyBlitzReactions.goparkyEmoteNames();
            for (int start = 0; start < names.length; start += 8) {
                String[] page = Arrays.copyOfRange(names, start, Math.min(names.length, start + 8));
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "Lunar emotes: " + EnumChatFormatting.YELLOW + String.join(", ", page));
            }
            return;
        }
        String requested = args == null || args.length == 0 ? "random" : args[0];
        String result = GoparkyBlitzReactions.debugEmote(minecraft, requested);
        if (result == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Equip GoParky and enter a world before testing an emote.");
            return;
        }
        if ("invalid".equals(result)) {
            usage();
            return;
        }
        if ("loading".equals(result)) {
            reportAssetReadiness();
            return;
        }
        if ("unavailable".equals(result)) {
            reportAssetReadiness();
            return;
        }
        if ("busy".equals(result)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "GoParky is already doing a taunt or hurt reaction; try again when it finishes.");
            return;
        }
        if ("stopped".equals(result)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Stopped GoParky's current animation.");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                + "Testing Lunar's " + EnumChatFormatting.YELLOW + result
                + EnumChatFormatting.GREEN + " emote on GoParky.");
    }

    private static void usage() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                + "Usage: /debuggoparkyemote [random|list|<Lunar emote name or ID>|stop] (try: wave, floss, orange_justice)");
    }

    private static void reportAssetReadiness() {
        if (me.napim.gomod.features.GoparkyLunarEmoteLibrary.isReady()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                    + "Lunar emotes are loaded; run the command again.");
            return;
        }
        if (me.napim.gomod.features.GoparkyLunarEmoteLibrary.isLoading()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Loading Lunar's installed emote animations; run the command again in a moment.");
            return;
        }
        String failure = me.napim.gomod.features.GoparkyLunarEmoteLibrary.loadFailure();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                + "Could not read Lunar's installed emote assets"
                + (failure == null || failure.isEmpty() ? "." : ": " + failure));
    }
}
