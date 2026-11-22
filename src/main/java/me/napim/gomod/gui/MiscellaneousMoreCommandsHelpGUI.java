package me.napim.gomod.gui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Help overlay for chat commands not covered by other gomod settings screens.
 * Opened from {@link MiscellaneousGUI}.
 */
public final class MiscellaneousMoreCommandsHelpGUI extends GuiScreen {
    private static final int DIALOG_WIDTH = 400;
    private static final int DIALOG_PAD_X = 14;
    private static final int LINE_H = 11;
    private static final int BORDER_COLOR = 0xFF4CAF50;

    private final GuiScreen parent;
    private GuiButton closeButton;
    private List<HelpRow> rows;
    private int dialogHeight;
    private int dialogY;

    public MiscellaneousMoreCommandsHelpGUI(GuiScreen parent) {
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
        dialogY = Math.max(8, height / 2 - dialogHeight / 2);
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
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "More Commands",
                centerX, dialogY + 10, 0xFFFFFF);

        int textX = dialogX + DIALOG_PAD_X;
        int y = dialogY + 28;
        for (HelpRow row : rows) {
            if (row.blank) {
                y += LINE_H / 2;
                continue;
            }
            if (row.commandHeader) {
                fontRendererObj.drawString(row.lines.get(0), textX, y, 0xFFFF55);
                y += LINE_H;
                for (int i = 1; i < row.lines.size(); i++) {
                    fontRendererObj.drawString(row.lines.get(i), textX, y, 0xFFFFFF);
                    y += LINE_H;
                }
            } else {
                for (String line : row.lines) {
                    fontRendererObj.drawString(line, textX, y, 0xFFFFFF);
                    y += LINE_H;
                }
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
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
        String g = EnumChatFormatting.GRAY.toString();
        String y = EnumChatFormatting.YELLOW.toString();

        List<HelpRow> out = new ArrayList<HelpRow>();

        out.add(commandRow(fr, maxPx, y + "/qstatus",
                w + "Shows Hypixel Blitz queue counts (total, solo, teams, lobby)."));
        out.add(blankRow());

        out.add(commandRow(fr, maxPx, y + "/shareinv <player>",
                w + "Invites a gomod friend to a live inventory HUD session: you both see each other's inventory, armor, hotbar, held slot, health, XP, and more."));
        out.add(blankRow());

        out.add(commandRow(fr, maxPx, y + "/infop <player>",
                w + "Looks up a player on Hypixel: online status, current game/mode/map, Blitz cosmetics, and recent games. "));
        out.add(blankRow());

        out.add(commandRow(fr, maxPx, y + "/fonline",
                w + "Scans your friend list pages and summarizes who is online, in Blitz, away, or busy. "));
        out.add(blankRow());

        out.add(commandRow(fr, maxPx, y + "/selfie",
                w + "Toggles selfie mode: freezes the camera where you stand while your player can move away. "));
        out.add(blankRow());

        out.add(commandRow(fr, maxPx, y + "/schizo",
                w + "Toggles schizo mode: other players face you while rendered. "));
        out.add(blankRow());

        out.add(commandRow(fr, maxPx, y + "/pee",
                w + "Useful for maps that have toilets (City etc..). "));
        out.add(blankRow());

        out.add(commandRow(fr, maxPx, y + "/cps <number>",
                w + "Caps how fast you can attack. Examples: "
                        + g + "/cps 6" + w + ", " + g + "/cps 0.2" + w + ". "
                        + g + "/cps off" + w + " disables the cap; " + g + "/cps status" + w + " shows the current limit."));
        return out;
    }

    private static HelpRow commandRow(FontRenderer fr, int maxPx, String header, String body) {
        List<String> lines = new ArrayList<String>();
        lines.add(header);
        lines.addAll(fr.listFormattedStringToWidth(body, maxPx));
        HelpRow row = new HelpRow();
        row.commandHeader = true;
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

    private static final class HelpRow {
        boolean blank;
        boolean commandHeader;
        List<String> lines = new ArrayList<String>();
        int lineCount;
    }
}
