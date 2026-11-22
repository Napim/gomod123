package me.napim.gomod.gui;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.List;

/**
 * Denicker help overlay — opened from Help on {@link DenickerSettingsGUI} (same pattern as Map Queuer {@code ?}).
 */
public final class DenickerHelpGUI extends GuiScreen {
    private static final int DIALOG_WIDTH = 368;
    private static final int DIALOG_PAD_X = 14;
    private static final int LINE_H = 11;
    private static final int BORDER_COLOR = 0xFF4CAF50;

    private final GuiScreen parent;
    private GuiButton closeButton;
    private List<String> bodyLines;
    private int dialogHeight;
    private int dialogY;

    public DenickerHelpGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        int innerW = DIALOG_WIDTH - DIALOG_PAD_X * 2;
        bodyLines = buildBodyLines(fontRendererObj, innerW);
        dialogHeight = 36 + bodyLines.size() * LINE_H + 32;
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
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Denicker Tools Help",
                centerX, dialogY + 10, 0xFFFFFF);

        int textX = dialogX + DIALOG_PAD_X;
        int y = dialogY + 28;
        for (String line : bodyLines) {
            if (line.isEmpty()) {
                y += LINE_H / 2;
            } else {
                fontRendererObj.drawString(line, textX, y, 0xFFFFFF);
                y += LINE_H;
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

    private static List<String> buildBodyLines(FontRenderer fr, int maxPx) {
        List<String> out = new ArrayList<String>();
        out.addAll(wrapText(fr, EnumChatFormatting.WHITE
                + "In pregame lobbies, Denicker tries to identify Hypixel nicks. When a player is denicked, "
                + "their real IGN is shown in TAB and above their nametag. You can also replace their nick skin "
                + "with their real Mojang skin. Each of these can be turned on or off in Denicker settings.", maxPx));
        out.add("");
        out.addAll(wrapText(fr, EnumChatFormatting.WHITE
                + "Denicker is linked to gomod Service: when one gomod user denicks a nick, that mapping is "
                + "instantly shared with other gomod users.", maxPx));
        out.add("");
        out.addAll(wrapText(fr, EnumChatFormatting.YELLOW + "Note: " + EnumChatFormatting.WHITE
                + "The Nick nametag option that shows the player's Hypixel rank needs a Hypixel API key.", maxPx));
        out.add("");
        String cagesPath = EnumChatFormatting.WHITE + "If "
                + EnumChatFormatting.DARK_RED + "TAB QoL"
                + EnumChatFormatting.WHITE + " \u2192 Players stats scan \u2192 "
                + EnumChatFormatting.GREEN + "When cages open"
                + EnumChatFormatting.WHITE + " is enabled, rank-style denicked nametags only appear after cages open. "
                + "Until then, denicked players use the gold "
                + EnumChatFormatting.GOLD + "(FireRapidGamer25)"
                + EnumChatFormatting.WHITE
                + " style even when the rank style nick nametag is selected.";
        out.addAll(wrapText(fr, cagesPath, maxPx));
        out.add("");
        out.addAll(wrapText(fr, EnumChatFormatting.WHITE
                + "Nick Renamed Items detects renamed kit items on nicked players after kits drop "
                + "and gives a clickable [Search item owner] link in chat to match exact formatting on GoStats.", maxPx));
        out.add("");
        out.addAll(wrapText(fr, EnumChatFormatting.WHITE
                + "Death [SEARCH NICK] adds a clickable [SEARCH NICK] link on death messages if a nick "
                + "has his Kill Counter enabled so you can look up nearby Blitz kill counts on gostats123.", maxPx));
        return out;
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
}
