package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.BlitzFlameKillEffectTracker;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * Client debug for Blitz Flaming Arrows kill-effect HUD (equip detection + 20s proc timer).
 */
public class FlameKeDebugCommand extends Command {

    public FlameKeDebugCommand() {
        super("flamekedebug");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        if (args.length > 0 && "sim".equalsIgnoreCase(args[0])) {
            if (BlitzFlameKillEffectTracker.debugSimulateProc()) {
                send(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Started 20s flame proc (debug sim).");
            } else {
                send(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Sim failed: mod does not think you have Flaming Arrows equipped. "
                        + EnumChatFormatting.GRAY + "Select it in-game (chat line) or run /tabstats all.");
            }
            return;
        }

        String p = mc.thePlayer.getName();
        String tabKe = KillEffectCommand.getPlayerEffect(p);
        int chat = BlitzFlameKillEffectTracker.getChatEquippedState();
        String chatStr = chat < 0 ? "unknown (need lobby line or /tabstats)" : chat == 1 ? "FLAMING (chat)" : "NOT flame (chat)";
        boolean api = BlitzFlameKillEffectTracker.getApiSaysFlame();
        boolean equipped = BlitzFlameKillEffectTracker.hasEquippedFlamingArrows();
        long rem = BlitzFlameKillEffectTracker.getProcRemainingMs();
        boolean timerRunning = rem > 0;
        boolean showHud = BlitzFlameKillEffectTracker.shouldShowMoreHudFlameTimer(ConfigManager.isMoreHudEnabled());

        send(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "[Flame KE debug]");
        send(EnumChatFormatting.GRAY + "Player: " + EnumChatFormatting.WHITE + p);
        send(EnumChatFormatting.GRAY + "Hypixel: " + (GameContext.isOnHypixel(mc) ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no"));
        send(EnumChatFormatting.GRAY + "Blitz active game: "
                + (GameContext.isBlitzGameActive(mc) ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no"));
        send(EnumChatFormatting.GRAY + "Blitz pregame/cages-ish: "
                + (GameContext.isBlitzPregameLobby(mc) ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no"));
        send(EnumChatFormatting.GRAY + "More HUD master: "
                + (ConfigManager.isMoreHudEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
        send(EnumChatFormatting.GRAY + "Potions (effects) row: "
                + (ConfigManager.isMoreHudPotionsEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                + EnumChatFormatting.GRAY + "  Flame feature toggle: "
                + (ConfigManager.isMoreHudFlameEnabled() ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
        send(EnumChatFormatting.GRAY + "Chat equip state: " + EnumChatFormatting.WHITE + chatStr);
        send(EnumChatFormatting.GRAY + "API (last /tabstats afterkill): "
                + (api ? EnumChatFormatting.GREEN + "FLAME/RAPID_FIRE" : EnumChatFormatting.RED + "no / other")
                + EnumChatFormatting.DARK_GRAY + "  cache: " + EnumChatFormatting.GRAY + (tabKe != null ? tabKe : "(none)"));
        send(EnumChatFormatting.GRAY + "Equipped (for timer): "
                + (equipped ? EnumChatFormatting.GREEN + "YES" : EnumChatFormatting.RED + "NO")
                + EnumChatFormatting.DARK_GRAY + " — need YES for kills to start the 20s HUD");
        send(EnumChatFormatting.GRAY + "Proc timer: "
                + (timerRunning
                ? EnumChatFormatting.GREEN + String.valueOf(rem) + " ms left"
                : EnumChatFormatting.RED + "idle (no active 20s window)"));
        send(EnumChatFormatting.GRAY + "Should show flame in HUD now: "
                + (showHud ? EnumChatFormatting.GREEN + "YES" : EnumChatFormatting.RED + "NO"));
        String keSb = BlitzFlameKillEffectTracker.getFormattedSidebarKillEffectLine();
        send(EnumChatFormatting.GRAY + "Scoreboard KE line: "
                + (keSb != null
                ? EnumChatFormatting.GREEN + "yes " + EnumChatFormatting.DARK_GRAY + "(" + EnumChatFormatting.RESET + keSb
                        + EnumChatFormatting.DARK_GRAY + ")"
                : EnumChatFormatting.RED + "no (need chat line or /tabstats afterkill)")
                + EnumChatFormatting.GRAY + "  Blitz sidebar: "
                + (LocalDisguise.isBlitzSgSidebarActive() ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no"));
        send(EnumChatFormatting.DARK_GRAY + "Tip: if equipped=NO, say the lobby line \"You changed your Kill Effect to Flaming Arrows!\" "
                + "or run /tabstats all. If kill still fails, paste the exact kill chat line.");
        send(EnumChatFormatting.DARK_GRAY + "/flamekedebug sim — force 20s proc (only if equipped=YES).");
    }

    private static void send(String line) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentText(line));
        }
    }
}
