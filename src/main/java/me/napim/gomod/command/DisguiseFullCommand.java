package me.napim.gomod.command;

import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.LunarFullDisguise;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.UUID;
import java.util.regex.Pattern;

/** Full local Lunar appearance disguise for a player currently loaded in the world. */
public class DisguiseFullCommand extends Command {
    private static final Pattern MC_USERNAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private final boolean shareWithGomodPeers;

    public DisguiseFullCommand() {
        this("disguisefull", false);
    }

    protected DisguiseFullCommand(String commandName, boolean shareWithGomodPeers) {
        super(commandName, new String[0]);
        this.shareWithGomodPeers = shareWithGomodPeers;
    }

    @Override
    public void handle(String[] args) {
        if (!GomodInternalFeatures.isEnabled() || !GomodFixes.isWeaveRuntime()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + (shareWithGomodPeers ? "/disguisefullgomod" : "/disguisefull")
                    + " is available only in the internal Lunar Weave build.");
            return;
        }
        if (args.length == 1 && ("off".equalsIgnoreCase(args[0])
                || "disable".equalsIgnoreCase(args[0]))) {
            DisguiseCommand.disableActiveDisguiseWithMessage();
            return;
        }
        if (args.length != 1 || !MC_USERNAME.matcher(args[0]).matches()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Usage: " + EnumChatFormatting.WHITE + "/"
                    + (shareWithGomodPeers ? "disguisefullgomod" : "disguisefull")
                    + " <player>" + EnumChatFormatting.YELLOW + " or "
                    + EnumChatFormatting.WHITE + "/"
                    + (shareWithGomodPeers ? "disguisefullgomod" : "disguisefull") + " off");
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Join a world first, then choose a player who is currently in it.");
            return;
        }

        for (Object candidate : mc.theWorld.playerEntities) {
            if (!(candidate instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) candidate;
            if (player == mc.thePlayer || player.getGameProfile() == null
                    || player.getGameProfile().getName() == null
                    || !player.getGameProfile().getName().equalsIgnoreCase(args[0])) {
                continue;
            }
            UUID targetUuid = player.getUniqueID();
            String targetName = player.getGameProfile().getName();
            if (targetUuid == null || targetUuid.equals(mc.thePlayer.getUniqueID())) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Choose another player, not yourself.");
                return;
            }
            if (shareWithGomodPeers) {
                DisguiseCommand.enableFullGomodDisguiseAsync(targetName, targetUuid);
            } else {
                DisguiseCommand.enableFullDisguiseAsync(targetName, targetUuid);
            }
            return;
        }

        try {
            LunarFullDisguise.CachedTarget cached = LunarFullDisguise.findCachedTarget(args[0]);
            if (cached != null) {
                if (cached.playerId.equals(mc.thePlayer.getUniqueID())) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Choose another player, not yourself.");
                    return;
                }
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "Using the saved Lunar appearance for " + EnumChatFormatting.WHITE
                        + cached.playerName + EnumChatFormatting.GRAY + ".");
                if (shareWithGomodPeers) {
                    DisguiseCommand.enableFullGomodDisguiseAsync(cached.playerName, cached.playerId);
                } else {
                    DisguiseCommand.enableFullDisguiseAsync(cached.playerName, cached.playerId);
                }
                return;
            }
        } catch (Exception error) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Could not read the saved Lunar appearance cache: "
                    + EnumChatFormatting.WHITE + error.getClass().getSimpleName());
            return;
        }

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Player "
                + EnumChatFormatting.WHITE + args[0] + EnumChatFormatting.RED
                + " is not loaded, and there is no saved Lunar appearance for them yet. "
                + "Use /disguisefull while they are in-world once to cache their cosmetics.");
    }
}
