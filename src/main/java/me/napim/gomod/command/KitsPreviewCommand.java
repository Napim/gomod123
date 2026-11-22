package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.util.GuiOpener;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class KitsPreviewCommand extends Command {
    private static final Map<String, Integer> ROMAN_LEVELS = createRomanLevelMap();

    public KitsPreviewCommand() {
        super("kits");
    }

    @Override
    public void handle(String[] args) {
        int previewLevel = 10;
        if (args.length > 1) {
            sendUsage();
            return;
        }
        if (args.length == 1) {
            Integer parsedLevel = parsePreviewLevel(args[0]);
            if (parsedLevel == null) {
                sendUsage();
                return;
            }
            previewLevel = parsedLevel;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }

        GuiOpener.open(
                "me.napim.gomod.gui.KitsPreviewGUI",
                new Class<?>[]{net.minecraft.client.gui.GuiScreen.class, int.class},
                new Object[]{mc.currentScreen, previewLevel}
        );
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Opened Blitz kit preview for "
                + EnumChatFormatting.YELLOW + formatLevelLabel(previewLevel) + EnumChatFormatting.GREEN + ".");
    }

    private static void sendUsage() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: "
                + EnumChatFormatting.WHITE + "/kits [1-10|11|12|p1|p2]");
    }

    private static Integer parsePreviewLevel(String rawText) {
        if (rawText == null) {
            return null;
        }

        String text = rawText.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return null;
        }
        if ("p1".equals(text) || "prestige1".equals(text) || "11".equals(text)) {
            return 11;
        }
        if ("p2".equals(text) || "prestige2".equals(text) || "12".equals(text)) {
            return 12;
        }
        if (ROMAN_LEVELS.containsKey(text)) {
            return ROMAN_LEVELS.get(text);
        }

        try {
            int level = Integer.parseInt(text);
            return level >= 1 && level <= 12 ? level : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatLevelLabel(int previewLevel) {
        if (previewLevel == 11) {
            return "Prestige I";
        }
        if (previewLevel == 12) {
            return "Prestige II";
        }

        String[] romanNumerals = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return "Level " + romanNumerals[Math.max(1, previewLevel) - 1];
    }

    private static Map<String, Integer> createRomanLevelMap() {
        Map<String, Integer> levels = new HashMap<>();
        levels.put("i", 1);
        levels.put("ii", 2);
        levels.put("iii", 3);
        levels.put("iv", 4);
        levels.put("v", 5);
        levels.put("vi", 6);
        levels.put("vii", 7);
        levels.put("viii", 8);
        levels.put("ix", 9);
        levels.put("x", 10);
        return levels;
    }
}
