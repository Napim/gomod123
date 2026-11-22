package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.features.ItemESP;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.Locale;

/**
 * ItemESP: toggle, nametag height tuning.
 * <ul>
 *   <li>{@code /itemesp} — toggle full item ESP</li>
 *   <li>{@code /itemesp height} — show current label height offset (blocks)</li>
 *   <li>{@code /itemesp height <blocks>} — set extra Y offset above the item (can be negative)</li>
 * </ul>
 */
public class ItemESPCommand extends Command {

    public ItemESPCommand() {
        super("itemesp");
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            Main.sendMessage(ItemESP.toggle());
            return;
        }

        String sub = args[0].trim().toLowerCase(Locale.ROOT);
        if ("height".equals(sub) || "y".equals(sub) || "label".equals(sub)) {
            if (args.length < 2) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Item label extra height: "
                        + EnumChatFormatting.WHITE + formatOffset(ItemESP.getLabelExtraYOffset())
                        + EnumChatFormatting.GRAY + " blocks (above item anchor)");
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Set with: "
                        + EnumChatFormatting.WHITE + "/itemesp height <blocks>");
                return;
            }
            String normalized = args[1].trim().replace(',', '.');
            float value;
            try {
                value = Float.parseFloat(normalized);
            } catch (NumberFormatException e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Invalid number: " + args[1]);
                return;
            }
            ItemESP.setLabelExtraYOffset(value);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Item label height offset set to "
                    + EnumChatFormatting.GREEN + formatOffset(ItemESP.getLabelExtraYOffset())
                    + EnumChatFormatting.YELLOW + " blocks");
            return;
        }

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: "
                + EnumChatFormatting.WHITE + "/itemesp"
                + EnumChatFormatting.GRAY + " | "
                + EnumChatFormatting.WHITE + "/itemesp height [blocks]");
    }

    private static String formatOffset(float v) {
        if (Math.abs(v - Math.rint(v)) < 1e-4f) {
            return String.format(Locale.US, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return String.format(Locale.US, "%.3f", v);
    }
}
