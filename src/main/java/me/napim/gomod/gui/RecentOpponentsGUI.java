package me.napim.gomod.gui;

import me.napim.gomod.features.RecentOpponents;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.TeamSpamManager;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Dev-only opponent history list, using the compact scrolling rows from Teams Nick Finder. */
public final class RecentOpponentsGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private static final int ROW_PITCH = 16;
    private static final int PLAYERS_PER_PAGE = 18;
    private static final int SCROLL_STEP = 3;

    private final GuiScreen parent;
    private GuiTextField searchField;
    private GuiButton groupDenicksButton;
    private GuiButton sortButton;
    private List<RecentOpponents.Record> visible = new ArrayList<RecentOpponents.Record>();
    private int scrollOffset;
    private int maxScroll;
    private int visibleRows;
    private boolean draggingScrollBar;
    private int scrollGrabbedAtY;
    private int scrollBarLeft;
    private int scrollBarTop;
    private int scrollBarRight;
    private int scrollBarBottom;
    private int scrollTrackTop;
    private int scrollTrackBottom;
    private int scrollThumbHeight;

    public RecentOpponentsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(searchField);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        buttonList.add(new GuiButton(0, width / 2 - 75, GuiHeader.backButtonY(height, 20), 150, 20,
                EnumChatFormatting.GRAY + "Back"));
        groupDenicksButton = new GuiButton(2, width / 2 - 214, 78, 208, 20, groupDenicksLabel());
        sortButton = new GuiButton(3, width / 2 + 6, 78, 208, 20, sortLabel());
        buttonList.add(groupDenicksButton);
        buttonList.add(sortButton);
        searchField = new GuiTextField(1, fontRendererObj, width / 2 - 88, 54, 190, 18);
        searchField.setMaxStringLength(32);
        refreshRows();
    }

    private int listBaseY() {
        return 132;
    }

    private int listVisibleBottom() {
        return GuiHeader.contentBottomAboveBackBar(height, 20, 10);
    }

    private void refreshRows() {
        String query = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        visible = new ArrayList<RecentOpponents.Record>();
        for (RecentOpponents.Record record : RecentOpponents.getRecords()) {
            if (query.isEmpty() || RecentOpponents.getDisplayName(record).toLowerCase(Locale.ROOT).contains(query)) {
                visible.add(record);
            }
        }
        visibleRows = Math.max(0, Math.min(PLAYERS_PER_PAGE,
                (listVisibleBottom() - listBaseY()) / ROW_PITCH));
        maxScroll = Math.max(0, visible.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            mc.displayGuiScreen(parent);
        } else if (button.id == 2) {
            ConfigManager.setRecentOpponentsGroupDenickedPlayers(
                    !ConfigManager.isRecentOpponentsGroupDenickedPlayers());
            if (groupDenicksButton != null) groupDenicksButton.displayString = groupDenicksLabel();
            scrollOffset = 0;
            refreshRows();
        } else if (button.id == 3) {
            ConfigManager.setRecentOpponentsSortMode((ConfigManager.getRecentOpponentsSortMode() + 1) % 5);
            if (sortButton != null) sortButton.displayString = sortLabel();
            scrollOffset = 0;
            refreshRows();
        }
    }

    private static String groupDenicksLabel() {
        boolean enabled = ConfigManager.isRecentOpponentsGroupDenickedPlayers();
        return EnumChatFormatting.GRAY + "Combine denicked players: "
                + (enabled ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.YELLOW + "OFF");
    }

    private static String sortLabel() {
        String mode;
        switch (ConfigManager.getRecentOpponentsSortMode()) {
            case 1: mode = "Most games"; break;
            case 2: mode = "Best K/D"; break;
            case 3: mode = "Worst K/D"; break;
            case 4: mode = "Most fought"; break;
            default: mode = "Last seen"; break;
        }
        return EnumChatFormatting.GRAY + "Sort: " + EnumChatFormatting.YELLOW + mode;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj,
                EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Recent Opponents", width / 2,
                GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        drawString(fontRendererObj, EnumChatFormatting.GRAY + "Search", width / 2 - 130, 59, 0xFFFFFFFF);
        searchField.drawTextBox();
        refreshRows();

        int centerX = width / 2;
        int leftX = centerX - 260;
        int rightX = centerX + 260;
        int baseY = listBaseY();
        String relayStatus = RecentOpponents.getRelaySyncStatus();
        EnumChatFormatting relayColor = "synced".equals(relayStatus) ? EnumChatFormatting.GREEN
                : relayStatus.contains("pending") || relayStatus.contains("queued") || relayStatus.contains("offline")
                || relayStatus.contains("unavailable")
                ? EnumChatFormatting.YELLOW : EnumChatFormatting.GRAY;
        String relaySummary = EnumChatFormatting.WHITE + "Players seen (" + visible.size() + ")"
                + EnumChatFormatting.DARK_GRAY + "  |  " + EnumChatFormatting.GRAY + "Relay: "
                + relayColor + relayStatus;
        drawCenteredString(fontRendererObj, relaySummary, centerX, 105, 0xFFFFFFFF);

        int opponentCenter = centerX - 163;
        int resultCenter = centerX - 27;
        int kdRatioCenter = centerX + 45;
        int gamesCenter = centerX + 111;
        int lastSeenCenter = centerX + 211;
        int headerY = baseY - 14;
        int rowRectLeft = leftX - 6;
        int rowRectRight = rightX + 6;
        drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "Opponent", opponentCenter, headerY, 0xFFFFFFFF);
        drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "K/D", resultCenter, headerY, 0xFFFFFFFF);
        drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "K/D ratio", kdRatioCenter, headerY, 0xFFFFFFFF);
        drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "Games", gamesCenter, headerY, 0xFFFFFFFF);
        drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + "Last seen", lastSeenCenter, headerY, 0xFFFFFFFF);

        int end = Math.min(visible.size(), scrollOffset + visibleRows);
        for (int i = scrollOffset; i < end; i++) {
            int y = baseY + (i - scrollOffset) * ROW_PITCH;
            int rowRectTop = y - 2;
            int rowRectBottom = y + fontRendererObj.FONT_HEIGHT + 2;
            if (((i - scrollOffset) & 1) == 0) {
                Gui.drawRect(rowRectLeft, rowRectTop, rowRectRight, rowRectBottom, 0x30000000);
            }
        }
        for (int i = scrollOffset; i < end; i++) {
            RecentOpponents.Record record = visible.get(i);
            int y = baseY + (i - scrollOffset) * ROW_PITCH;
            int rowRectTop = y - 2;
            int rowRectBottom = y + fontRendererObj.FONT_HEIGHT + 2;
            boolean hovered = mouseX >= rowRectLeft && mouseX < rowRectRight
                    && mouseY >= rowRectTop && mouseY < rowRectBottom;

            if (hovered) {
                Gui.drawRect(rowRectLeft, rowRectTop, rowRectRight, rowRectBottom, 0x304488FF);
            }

            String prefix = hovered
                    ? EnumChatFormatting.GREEN + ">> " + EnumChatFormatting.RESET
                    : EnumChatFormatting.GRAY + "" + (i + 1) + ". ";
            String rateText = record.getKillDeathRatioText();
            int prefixWidth = fontRendererObj.getStringWidth("500. ");
            int headSize = 10;
            int rowStartX = leftX + 8;
            int headX = rowStartX + prefixWidth + 3;
            int textX = headX + headSize + 4;
            String displayName = fitRecordName(record, hovered, textX, resultCenter);
            fontRendererObj.drawStringWithShadow(prefix, rowStartX, y, 0xFFFFFF);
            drawPlayerHead(record.uuid, headX, y - 1, headSize);
            fontRendererObj.drawStringWithShadow(displayName, textX, y, 0xFFFFFF);

            String result = EnumChatFormatting.GREEN + String.valueOf(record.wins)
                    + EnumChatFormatting.GRAY + " / " + EnumChatFormatting.RED + record.losses;
            drawCenteredString(fontRendererObj, result, resultCenter, y, 0xFFFFFFFF);
            drawCenteredString(fontRendererObj, EnumChatFormatting.WHITE + rateText, kdRatioCenter, y, 0xFFFFFFFF);
            drawCenteredString(fontRendererObj, EnumChatFormatting.WHITE + String.valueOf(record.matches), gamesCenter, y, 0xFFFFFFFF);
            String lastSeen = hovered ? formatRelativeTime(record.lastSeen) : formatTimestamp(record.lastSeen);
            drawCenteredString(fontRendererObj, EnumChatFormatting.WHITE + lastSeen, lastSeenCenter, y, 0xFFFFFFFF);
        }

        if (visible.isEmpty()) {
            String empty = searchField.getText().isEmpty()
                    ? "No opponents recorded yet. History starts when Blitz cages open."
                    : "No players match this search.";
            drawCenteredString(fontRendererObj, EnumChatFormatting.GRAY + empty, centerX, baseY + 8, 0xFFFFFFFF);
        }
        drawScrollbar(mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (groupDenicksButton != null
                && mouseX >= groupDenicksButton.xPosition
                && mouseX < groupDenicksButton.xPosition + groupDenicksButton.width
                && mouseY >= groupDenicksButton.yPosition
                && mouseY < groupDenicksButton.yPosition + groupDenicksButton.height) {
            drawHoveringText(Collections.singletonList(EnumChatFormatting.RESET
                    + "Accumulate the stats of nicked players under their actual IGNs"), mouseX, mouseY);
        } else if (visibleRecordIndexAt(mouseX, mouseY) >= 0
                && visible.get(visibleRecordIndexAt(mouseX, mouseY)).legacyUnverified) {
            drawHoveringText(Collections.singletonList(EnumChatFormatting.GRAY
                    + "Includes locally imported history that the relay cannot verify."), mouseX, mouseY);
        } else if (mouseY >= 102 && mouseY < 116
                && mouseX >= centerX - fontRendererObj.getStringWidth(relaySummary) / 2 - 4
                && mouseX <= centerX + fontRendererObj.getStringWidth(relaySummary) / 2 + 4) {
            List<String> detailLines = fontRendererObj.listFormattedStringToWidth(
                    EnumChatFormatting.GRAY + RecentOpponents.getRelaySyncStatusDetail(), 240);
            drawHoveringText(detailLines, mouseX, mouseY);
        }
    }

    private static String formatRecordName(RecentOpponents.Record record, boolean hovered) {
        String realName = RecentOpponents.resolveDenickedRealName(record);
        String plainNameColor = hovered ? EnumChatFormatting.YELLOW.toString() : EnumChatFormatting.WHITE.toString();
        if (realName == null && !RecentOpponents.hasGeneratedNickUuid(record)) return plainNameColor + record.name;
        String nick = EnumChatFormatting.RED + "" + EnumChatFormatting.BOLD + record.name + EnumChatFormatting.RESET;
        if (realName == null) return nick;
        return nick + EnumChatFormatting.GRAY + " ("
                + (hovered ? EnumChatFormatting.YELLOW : EnumChatFormatting.WHITE) + realName
                + EnumChatFormatting.GRAY + ")";
    }

    private String fitRecordName(RecentOpponents.Record record, boolean hovered, int textX, int resultCenter) {
        int maximumWidth = Math.max(24, resultCenter - 10 - textX);
        return fontRendererObj.trimStringToWidth(formatRecordName(record, hovered), maximumWidth);
    }

    private int visibleRecordIndexAt(int mouseX, int mouseY) {
        int rowRectLeft = width / 2 - 260 - 6;
        int rowRectRight = width / 2 + 260 + 6;
        if (mouseX < rowRectLeft || mouseX >= rowRectRight) return -1;
        int end = Math.min(visible.size(), scrollOffset + visibleRows);
        for (int i = scrollOffset; i < end; i++) {
            int y = listBaseY() + (i - scrollOffset) * ROW_PITCH;
            int rowRectTop = y - 2;
            int rowRectBottom = y + fontRendererObj.FONT_HEIGHT + 2;
            if (mouseY >= rowRectTop && mouseY < rowRectBottom) return i;
        }
        return -1;
    }

    private void drawScrollbar(int mouseY) {
        scrollBarLeft = scrollBarTop = scrollBarRight = scrollBarBottom = 0;
        if (maxScroll <= 0 || visibleRows <= 0) return;
        int trackX = width - 10;
        int trackWidth = 4;
        scrollTrackTop = listBaseY() - 2;
        scrollTrackBottom = Math.min(listVisibleBottom(), listBaseY() + visibleRows * ROW_PITCH);
        int trackHeight = Math.max(1, scrollTrackBottom - scrollTrackTop);
        scrollThumbHeight = Math.max(20, (int) (trackHeight * (visibleRows / (float) visible.size())));
        scrollThumbHeight = Math.min(scrollThumbHeight, trackHeight);
        int maxThumbTravel = trackHeight - scrollThumbHeight;
        int thumbY = scrollTrackTop + (int) (maxThumbTravel * (scrollOffset / (float) maxScroll));
        Gui.drawRect(trackX, scrollTrackTop, trackX + trackWidth, scrollTrackBottom, 0x60000000);
        Gui.drawRect(trackX, thumbY, trackX + trackWidth, thumbY + scrollThumbHeight, 0x90FFFFFF);
        scrollBarLeft = trackX;
        scrollBarTop = thumbY;
        scrollBarRight = trackX + trackWidth;
        scrollBarBottom = thumbY + scrollThumbHeight;
        if (draggingScrollBar) {
            int relative = mouseY - scrollTrackTop - scrollGrabbedAtY;
            int clamped = Math.max(0, Math.min(maxThumbTravel, relative));
            scrollOffset = maxThumbTravel <= 0 ? 0
                    : Math.round(maxScroll * (clamped / (float) maxThumbTravel));
        }
    }

    private static String formatTimestamp(long timestamp) {
        if (timestamp <= 0L) return "—";
        return new SimpleDateFormat("MMM d, HH:mm", Locale.ROOT).format(new Date(timestamp));
    }

    private static String formatRelativeTime(long timestamp) {
        if (timestamp <= 0L) return "—";
        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        if (elapsedSeconds < 60L) return "just now";
        long minutes = elapsedSeconds / 60L;
        if (minutes < 60L) return minutes + (minutes == 1L ? " minute ago" : " minutes ago");
        long hours = minutes / 60L;
        if (hours < 24L) return hours + (hours == 1L ? " hour ago" : " hours ago");
        long days = hours / 24L;
        if (days < 30L) return days + (days == 1L ? " day ago" : " days ago");
        long months = days / 30L;
        if (months < 12L) return months + (months == 1L ? " month ago" : " months ago");
        long years = days / 365L;
        return years + (years == 1L ? " year ago" : " years ago");
    }

    private void drawPlayerHead(String uuid, int x, int y, int size) {
        try {
            String textureKey = uuid == null || uuid.isEmpty()
                    ? "00000000000000000000000000000000" : uuid.replace("-", "");
            ResourceLocation location = new ResourceLocation("minotar/avatar/" + textureKey);
            TextureManager textureManager = mc.getTextureManager();
            ITextureObject texture = textureManager.getTexture(location);
            if (texture == null) {
                String url = "https://minotar.net/avatar/" + textureKey + "/16";
                textureManager.loadTexture(location, new ThreadDownloadImageData(null, url, null, null));
            }
            textureManager.bindTexture(location);
            float oldZ = zLevel;
            zLevel = 50.0F;
            GlStateManager.pushMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableBlend();
            Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, 16, 16, size, size, 16, 16);
            GlStateManager.popMatrix();
            zLevel = oldZ;
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (searchField != null && searchField.textboxKeyTyped(typedChar, keyCode)) {
            scrollOffset = 0;
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (searchField != null) searchField.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton == 0 && mc.currentScreen == this) {
            int index = visibleRecordIndexAt(mouseX, mouseY);
            if (index >= 0) {
                RecentOpponents.Record record = visible.get(index);
                String profileName = RecentOpponents.resolveDenickedRealName(record);
                boolean nicked = RecentOpponents.isNick(record);
                String denickedNick = nicked ? record.name : null;
                if (profileName == null || profileName.isEmpty()) profileName = record.name;
                String profileUuid = record.uuid;
                if (nicked && !profileName.equalsIgnoreCase(record.name)) profileUuid = "";
                if (!profileName.equalsIgnoreCase(record.name)) {
                    for (RecentOpponents.Record candidate : RecentOpponents.getRecords()) {
                        if (candidate.name.equalsIgnoreCase(profileName) && candidate.uuid != null
                                && !candidate.uuid.isEmpty()) {
                            profileUuid = candidate.uuid;
                            break;
                        }
                    }
                }
                mc.displayGuiScreen(new TeamPlayerProfileGUI(this,
                        new TeamSpamManager.Player(profileName, profileUuid), denickedNick, true));
                return;
            }
        }
        if (mouseButton == 0 && mouseX >= scrollBarLeft && mouseX <= scrollBarRight
                && mouseY >= scrollBarTop && mouseY <= scrollBarBottom) {
            draggingScrollBar = true;
            scrollGrabbedAtY = mouseY - scrollBarTop;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (draggingScrollBar) drawScrollbar(mouseY);
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        draggingScrollBar = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + (wheel > 0 ? -SCROLL_STEP : SCROLL_STEP)));
        }
    }
}
