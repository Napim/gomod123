package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.BlockVerticalLoadClip;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

/**
 * /bload N — stops drawing terrain at/above (feet block Y + N); chunks stay loaded. /bload off — disable.
 */
public class BloadCommand extends Command {

    public BloadCommand() {
        super("bload");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Join a world first.");
            return;
        }
        if (args == null || args.length == 0) {
            int h = BlockVerticalLoadClip.getClipHeightBlocks();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "/bload <blocks> — hide meshed blocks at/above "
                    + EnumChatFormatting.WHITE + "floor(feet Y) + blocks"
                    + EnumChatFormatting.GRAY + ". Current: " + EnumChatFormatting.AQUA + (h <= 0 ? "off" : String.valueOf(h)));
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.DARK_GRAY + "/bload off — full terrain again (reloads renderers).");
            return;
        }
        String a0 = args[0].trim();
        if ("off".equalsIgnoreCase(a0) || "0".equals(a0)) {
            BlockVerticalLoadClip.setClipHeightBlocks(0);
            try {
                mc.renderGlobal.loadRenderers();
            } catch (Throwable ignored) {
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Vertical block clip: "
                    + EnumChatFormatting.WHITE + "off");
            return;
        }
        int blocks;
        try {
            blocks = Integer.parseInt(a0);
        } catch (NumberFormatException e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Usage: /bload <height> or /bload off (height 1–512).");
            return;
        }
        if (blocks < 1 || blocks > 512) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Height must be 1–512 (or /bload off).");
            return;
        }
        BlockVerticalLoadClip.setClipHeightBlocks(blocks);
        try {
            mc.renderGlobal.loadRenderers();
        } catch (Throwable ignored) {
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Vertical clip: blocks at/above "
                + EnumChatFormatting.WHITE + "feet floor + " + blocks
                + EnumChatFormatting.GREEN + " are not drawn (world mesh only; entities unchanged).");
    }
}
