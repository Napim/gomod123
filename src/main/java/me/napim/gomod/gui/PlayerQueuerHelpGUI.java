package me.napim.gomod.gui;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Player Queuer help overlay — opened from Help on {@link PlayerQueuerGUI}.
 */
public final class PlayerQueuerHelpGUI extends GuiScreen {
    private static final int DIALOG_WIDTH = 400;
    private static final int DIALOG_PAD_X = 14;
    private static final int LINE_H = 11;
    private static final int BORDER_COLOR = 0xFF4CAF50;
    private static final int REQUEUER_ICON_SIZE = 10;
    private static final int REQUEUER_ICON_GAP = 4;
    /**
     * Pixels added to the first line's Y when drawing the Player Requeuer icon in help.
     * More negative = icon moves up; more positive = icon moves down (tune in {@link PlayerQueuerHelpGUI}).
     */
    private static final int REQUEUER_ICON_Y_OFFSET = -1;
    /**
     * Pixels added to the first line's Y when drawing the Glow icon in help.
     * More negative = icon moves up; more positive = icon moves down.
     */
    private static final int GLOW_ICON_Y_OFFSET = -1;

    private final GuiScreen parent;
    private GuiButton closeButton;
    private List<HelpRow> rows;
    private int dialogHeight;
    private int dialogY;

    public PlayerQueuerHelpGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        int innerW = DIALOG_WIDTH - DIALOG_PAD_X * 2;
        rows = buildRows(fontRendererObj, innerW);
        int lineCount = 0;
        for (HelpRow row : rows) {
            lineCount += row.lineCount;
        }
        dialogHeight = 36 + lineCount * LINE_H + 32;
        int centerX = width / 2;
        dialogY = height / 2 - dialogHeight / 2;
        closeButton = new GuiButton(0, centerX - 50, dialogY + dialogHeight - 26, 100, 20,
                EnumChatFormatting.GRAY + "Close");
        buttonList.clear();
        buttonList.add(closeButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int centerX = width / 2;
        int dialogX = centerX - DIALOG_WIDTH / 2;

        drawRect(dialogX, dialogY, dialogX + DIALOG_WIDTH, dialogY + dialogHeight, 0xCC000000);
        drawRect(dialogX, dialogY, dialogX + DIALOG_WIDTH, dialogY + 1, BORDER_COLOR);
        drawRect(dialogX, dialogY, dialogX + 1, dialogY + dialogHeight, BORDER_COLOR);
        drawRect(dialogX + DIALOG_WIDTH - 1, dialogY, dialogX + DIALOG_WIDTH, dialogY + dialogHeight, BORDER_COLOR);
        drawRect(dialogX, dialogY + dialogHeight - 1, dialogX + DIALOG_WIDTH, dialogY + dialogHeight, BORDER_COLOR);

        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Player Queuer Help",
                centerX, dialogY + 10, 0xFFFFFF);

        int textX = dialogX + DIALOG_PAD_X;
        int innerW = DIALOG_WIDTH - DIALOG_PAD_X * 2;
        int y = dialogY + 28;
        for (HelpRow row : rows) {
            if (row.blank) {
                y += LINE_H / 2;
                continue;
            }
            if (row.withRequeuerIcon) {
                y = drawRequeuerBlock(textX, y, row.lines);
            } else if (row.withGlowIcon) {
                y = drawGlowBlock(textX, y, row.lines);
            } else {
                for (String line : row.lines) {
                    fontRendererObj.drawString(line, textX, y, 0xFFFFFF);
                    y += LINE_H;
                }
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private int drawRequeuerBlock(int textX, int y, List<String> lines) {
        if (lines.isEmpty()) {
            return y;
        }
        int iconIndent = REQUEUER_ICON_SIZE + REQUEUER_ICON_GAP;
        int iconY = y + REQUEUER_ICON_Y_OFFSET;
        PlayerQueuerGUI.drawDeathRequeuerIconForHelp(textX, iconY);
        fontRendererObj.drawString(lines.get(0), textX + iconIndent, y, 0xFFFFFF);
        y += LINE_H;
        for (int i = 1; i < lines.size(); i++) {
            fontRendererObj.drawString(lines.get(i), textX, y, 0xFFFFFF);
            y += LINE_H;
        }
        return y;
    }

    private int drawGlowBlock(int textX, int y, List<String> lines) {
        if (lines.isEmpty()) {
            return y;
        }
        int iconIndent = REQUEUER_ICON_SIZE + REQUEUER_ICON_GAP;
        int iconY = y + GLOW_ICON_Y_OFFSET;
        PlayerQueuerGUI.drawGlowIconForHelp(textX, iconY);
        fontRendererObj.drawString(lines.get(0), textX + iconIndent, y, 0xFFFFFF);
        y += LINE_H;
        for (int i = 1; i < lines.size(); i++) {
            fontRendererObj.drawString(lines.get(i), textX, y, 0xFFFFFF);
            y += LINE_H;
        }
        return y;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static List<HelpRow> buildRows(FontRenderer fr, int maxPx) {
        String w = EnumChatFormatting.WHITE.toString();
        String g = EnumChatFormatting.GREEN.toString();
        String dr = EnumChatFormatting.DARK_RED.toString();
        String o = EnumChatFormatting.GOLD.toString();
        String y = EnumChatFormatting.YELLOW.toString();
        String b = EnumChatFormatting.AQUA.toString();

        List<HelpRow> out = new ArrayList<HelpRow>();

        out.add(textRow(fr, maxPx, b + "Queue list" + w
                + " = players/rules that make you stay in the game when they match."));
        out.add(textRow(fr, maxPx, dr + "Dodge list" + w
                + " = players/rules that make you requeue when they match."));
        out.add(blankRow());

        out.add(textRow(fr, maxPx, g + "Queue: ANY" + w
                + " = stay when one queued player/rule matches."));
        out.add(textRow(fr, maxPx, o + "Queue: ALL" + w
                + " = stay only when every queued player/rule matches."));
        out.add(blankRow());

        out.add(textRow(fr, maxPx, g + "Dodge: ANY" + w
                + " = requeue when one dodged player/rule matches."));
        out.add(textRow(fr, maxPx, o + "Dodge: ALL" + w
                + " = requeue only when every dodged player/rule matches. Useful for dodging a group while still "
                + "allowing them separately."));
        out.add(blankRow());

        out.add(textRow(fr, maxPx, y + "Rules" + w
                + " can add dynamic targets: " + b + "Queue nicks" + w + ", " + dr + "Dodge nicks" + w
                + ", or WLR value rules like " + y + ">=0.85 WLR" + w + "."));
        out.add(textRow(fr, maxPx, y + "WLR rules" + w
                + " need a Hypixel API key and " + g + "\"Player stats scan\"" + w + " set to " + g + "\"When players join\"" + w + "."));
        out.add(blankRow());

        out.add(textRow(fr, maxPx, y + "*"
                + dr + "Dodge" + w + " takes priority over " + b + "Queue" + w
                + ". If a game matches a Dodge player/rule, Player Queuer will requeue even if a Queue player/rule "
                + "also matches."));
        out.add(blankRow());

        String requeuerBody = w + "Player Requeuer: Click the icon next to a name to toggle. Whenever the player dies or wins, "
                + "it will instantly requeue the game (even if you're still alive!).";
        out.add(requeuerRow(fr, maxPx, requeuerBody));
        out.add(blankRow());

        String glowBody = w + "Glow: Applies an effect around the player to spot them easier.";
        out.add(glowRow(fr, maxPx, glowBody));
        return out;
    }

    private static HelpRow textRow(FontRenderer fr, int maxPx, String body) {
        List<String> lines = wrapText(fr, body, maxPx);
        HelpRow row = new HelpRow();
        row.lines = lines;
        row.lineCount = lines.size();
        return row;
    }

    private static HelpRow requeuerRow(FontRenderer fr, int maxPx, String body) {
        HelpRow row = iconPrefixedRow(fr, maxPx, body);
        row.withRequeuerIcon = true;
        return row;
    }

    private static HelpRow glowRow(FontRenderer fr, int maxPx, String body) {
        HelpRow row = iconPrefixedRow(fr, maxPx, body);
        row.withGlowIcon = true;
        return row;
    }

    private static HelpRow iconPrefixedRow(FontRenderer fr, int maxPx, String body) {
        int firstLineMax = Math.max(40, maxPx - REQUEUER_ICON_SIZE - REQUEUER_ICON_GAP);
        List<String> lines = fr.listFormattedStringToWidth(body, firstLineMax);
        if (lines.size() > 1) {
            StringBuilder rest = new StringBuilder();
            for (int i = 1; i < lines.size(); i++) {
                if (i > 1) {
                    rest.append(' ');
                }
                String part = lines.get(i);
                if (!part.isEmpty() && part.charAt(0) == ' ') {
                    part = part.substring(1);
                }
                rest.append(part);
            }
            List<String> merged = new ArrayList<String>();
            merged.add(lines.get(0));
            merged.addAll(fr.listFormattedStringToWidth(rest.toString(), maxPx));
            lines = merged;
        }
        HelpRow row = new HelpRow();
        row.lines = lines;
        row.lineCount = lines.size();
        return row;
    }

    private static HelpRow blankRow() {
        HelpRow row = new HelpRow();
        row.blank = true;
        row.lineCount = 1;
        return row;
    }

    private static List<String> wrapText(FontRenderer fr, String body, int maxPx) {
        List<String> out = new ArrayList<String>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        String[] tokens = body.split(" ");
        StringBuilder line = new StringBuilder();
        for (String token : tokens) {
            String candidate = line.length() == 0 ? token : line + " " + token;
            if (fr.getStringWidth(candidate) > maxPx && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                line.append(token);
            } else if (fr.getStringWidth(token) > maxPx) {
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                String rest = token;
                while (rest.length() > 0) {
                    int cut = 1;
                    while (cut < rest.length() && fr.getStringWidth(rest.substring(0, cut + 1)) <= maxPx) {
                        cut++;
                    }
                    out.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
            } else {
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(token);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private static final class HelpRow {
        boolean blank;
        boolean withRequeuerIcon;
        boolean withGlowIcon;
        List<String> lines = new ArrayList<String>();
        int lineCount;
    }
}
