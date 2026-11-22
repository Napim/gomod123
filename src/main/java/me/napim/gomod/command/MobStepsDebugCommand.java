package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.SpawnedMobStepSoundMute;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Locale;

/**
 * Dev build only: lets you bypass the Blitz-only check so Silent Mob Steps can be tested in
 * singleplayer/anywhere, and inspect step-sound filtering diagnostics.
 */
public class MobStepsDebugCommand extends Command {

    public MobStepsDebugCommand() {
        super("mobstepsdebug", new String[]{"mobstepdebug", "silentmobstepsdebug"});
    }

    @Override
    public void handle(String[] args) {
        if (!GomodInternalFeatures.isEnabled()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Mob steps debug is not available in this build.");
            return;
        }
        if (args.length == 0) {
            toggleEverywhere(!SpawnedMobStepSoundMute.isEverywhereDebugEnabled());
            return;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if ("on".equals(sub) || "enable".equals(sub) || "true".equals(sub)) {
            toggleEverywhere(true);
            return;
        }
        if ("off".equals(sub) || "disable".equals(sub) || "false".equals(sub)) {
            toggleEverywhere(false);
            return;
        }
        if ("everywhere".equals(sub) || "solo".equals(sub) || "bypass".equals(sub)) {
            boolean next = args.length >= 2
                    ? parseOnOff(args[1], !SpawnedMobStepSoundMute.isEverywhereDebugEnabled())
                    : !SpawnedMobStepSoundMute.isEverywhereDebugEnabled();
            toggleEverywhere(next);
            return;
        }
        if ("log".equals(sub) || "logs".equals(sub)) {
            boolean next = args.length >= 2
                    ? parseOnOff(args[1], !SpawnedMobStepSoundMute.isDebugLogEnabled())
                    : !SpawnedMobStepSoundMute.isDebugLogEnabled();
            SpawnedMobStepSoundMute.setDebugLogEnabled(next);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Silent mob steps debug logging: "
                    + (next ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
            return;
        }
        if ("status".equals(sub)) {
            SpawnedMobStepSoundMute.debugPrintStatus();
            return;
        }
        sendHelp();
    }

    private static void toggleEverywhere(boolean enabled) {
        SpawnedMobStepSoundMute.setEverywhereDebugEnabled(enabled);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Silent mob steps everywhere (solo/test): "
                + (enabled ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
        if (enabled && !ConfigManager.isSilentSpawnedMobStepsEnabled()) {
            Main.sendMessage(EnumChatFormatting.GRAY + "  Note: module toggle is currently "
                    + EnumChatFormatting.RED + "OFF" + EnumChatFormatting.GRAY
                    + " — turn ON \"Silent mob steps\" in Miscellaneous.");
        }
        SpawnedMobStepSoundMute.debugPrintStatus();
    }

    private static boolean parseOnOff(String arg, boolean fallback) {
        String s = arg == null ? "" : arg.toLowerCase(Locale.ROOT);
        if ("on".equals(s) || "enable".equals(s) || "true".equals(s)) {
            return true;
        }
        if ("off".equals(s) || "disable".equals(s) || "false".equals(s)) {
            return false;
        }
        return fallback;
    }

    private static void sendHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/mobstepsdebug"
                + EnumChatFormatting.WHITE + " — toggle everywhere (solo/test) mode");
        Main.sendMessage(EnumChatFormatting.GRAY + "  /mobstepsdebug on | off | everywhere [on|off] | log [on|off] | status");
    }
}
