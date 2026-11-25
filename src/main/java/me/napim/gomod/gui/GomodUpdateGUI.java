package me.napim.gomod.gui;

import me.napim.gomod.GomodVersion;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodAutoUpdater;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Confirmation and status screen for {@link GomodAutoUpdater}.
 * Accessible via {@code /gomod update}, the chat {@code [Update]} button, or Miscellaneous -> gomod Service -> Updates.
 */
public class GomodUpdateGUI extends GuiScreen {

    private static final int ID_BACK = 0;
    private static final int ID_PRIMARY = 1;
    private static final int ID_AUTO_TOGGLE = 2;
    private static final int ID_CHANGELOG = 3;

    private static final int BTN_W = 220;
    private static final int BTN_H = 20;

    private final GuiScreen parent;
    private GuiButton primaryButton;
    private GuiButton autoToggleButton;
    private GuiButton changelogButton;

    public GomodUpdateGUI() {
        this(null);
    }

    public GomodUpdateGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        if (GomodAutoUpdater.getState() == GomodAutoUpdater.State.IDLE) {
            GomodAutoUpdater.checkForUpdatesAsync(false, false);
        }

        int cx = this.width / 2;
        int rowLeft = cx - BTN_W / 2;
        int halfW = (BTN_W - 6) / 2;
        int y = Math.max(152, this.height - 96);

        this.buttonList.clear();
        this.primaryButton = new GuiButton(ID_PRIMARY, rowLeft, y, BTN_W, BTN_H, "");
        this.buttonList.add(this.primaryButton);

        y += BTN_H + 6;
        this.autoToggleButton = new GuiButton(ID_AUTO_TOGGLE, rowLeft, y, BTN_W, BTN_H, "");
        this.buttonList.add(this.autoToggleButton);

        y += BTN_H + 6;
        this.changelogButton = new GuiButton(ID_CHANGELOG, rowLeft, y, halfW, BTN_H,
                EnumChatFormatting.AQUA + "Changelog");
        this.buttonList.add(this.changelogButton);
        this.buttonList.add(new GuiButton(ID_BACK, rowLeft + halfW + 6, y, halfW, BTN_H,
                EnumChatFormatting.GRAY + (this.parent != null ? "Back" : "Close")));

        syncButtonStates();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj,
                EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + "gomod Updates",
                this.width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj,
                EnumChatFormatting.GRAY + "Installed: " + EnumChatFormatting.WHITE + GomodVersion.get()
                        + EnumChatFormatting.DARK_GRAY + "  \u00b7  "
                        + EnumChatFormatting.GRAY + GomodAutoUpdater.getLoaderDisplayName(),
                this.width, GuiHeader.STANDARD_SUBTITLE_Y, 0xAAAAAA);

        syncButtonStates();

        int cx = this.width / 2;
        int infoTop = 58;
        GomodAutoUpdater.State st = GomodAutoUpdater.getState();
        GomodAutoUpdater.ReleaseInfo info = GomodAutoUpdater.getLatestRelease();

        // Status line
        String statusLine = buildStatusLine(st, info);
        GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj, statusLine, this.width, infoTop, 0xFFFFFF);

        int nextY = infoTop + 14;
        if (st == GomodAutoUpdater.State.DOWNLOADING) {
            drawProgressBar(cx - 110, nextY, 220, 10, GomodAutoUpdater.getDownloadPercent());
            nextY += 16;
        } else if (st == GomodAutoUpdater.State.ERROR && !GomodAutoUpdater.getLastError().isEmpty()) {
            String err = trimToWidth(GomodAutoUpdater.getLastError(), 280);
            GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj,
                    EnumChatFormatting.RED + err, this.width, nextY, 0xFF5555);
            nextY += 12;
        } else if (GomodAutoUpdater.isDevOrInternalBuild() && GomodAutoUpdater.isUpdateAvailable()) {
            GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj,
                    EnumChatFormatting.YELLOW + "Internal/dev build: installing replaces it with the public release.",
                    this.width, nextY, 0xFFFF55);
            nextY += 12;
        } else if (st == GomodAutoUpdater.State.READY_ON_EXIT) {
            GuiHeader.drawTitleCenteredWithShadow(this.fontRendererObj,
                    EnumChatFormatting.GRAY + "Old JAR will be replaced automatically after Minecraft exits.",
                    this.width, nextY, 0xAAAAAA);
            nextY += 12;
        }

        // Release notes / info card
        int cardTop = nextY + 6;
        int cardBottom = (this.primaryButton != null ? this.primaryButton.yPosition : this.height - 96) - 10;
        if (cardBottom - cardTop >= 32) {
            int cardLeft = cx - 150;
            int cardRight = cx + 150;
            Gui.drawRect(cardLeft, cardTop, cardRight, cardBottom, 0x66000000);
            drawCardBorder(cardLeft, cardTop, cardRight, cardBottom, 0x44FFFFFF);

            List<String> lines = buildNotesLines(info, 286);
            int maxLines = Math.max(1, (cardBottom - cardTop - 10) / 10);
            int ly = cardTop + 6;
            for (int i = 0; i < Math.min(lines.size(), maxLines); i++) {
                this.fontRendererObj.drawStringWithShadow(lines.get(i), cardLeft + 7, ly, 0xDDDDDD);
                ly += 10;
            }
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_BACK:
                this.mc.displayGuiScreen(this.parent);
                break;
            case ID_PRIMARY: {
                GomodAutoUpdater.State st = GomodAutoUpdater.getState();
                GomodAutoUpdater.ReleaseInfo info = GomodAutoUpdater.getLatestRelease();
                if (st == GomodAutoUpdater.State.DOWNLOADING || st == GomodAutoUpdater.State.READY_ON_EXIT) {
                    break;
                }
                if (GomodAutoUpdater.isUpdateAvailable() && info != null && info.hasDirectJarDownload()) {
                    GomodAutoUpdater.startDownloadAndStageAsync(false);
                } else {
                    GomodAutoUpdater.checkForUpdatesAsync(true, false);
                }
                break;
            }
            case ID_AUTO_TOGGLE: {
                boolean next = !ConfigManager.isAutoUpdateEnabled();
                ConfigManager.setAutoUpdateEnabled(next);
                syncButtonStates();
                break;
            }
            case ID_CHANGELOG: {
                GomodAutoUpdater.ReleaseInfo info = GomodAutoUpdater.getLatestRelease();
                String url = info != null ? info.effectiveChangelogUrl() : GomodAutoUpdater.GITHUB_RELEASES_PAGE_URL;
                openExternalUrl(url);
                break;
            }
            default:
                break;
        }
    }

    private void syncButtonStates() {
        GomodAutoUpdater.State st = GomodAutoUpdater.getState();
        GomodAutoUpdater.ReleaseInfo info = GomodAutoUpdater.getLatestRelease();
        if (this.primaryButton != null) {
            if (st == GomodAutoUpdater.State.CHECKING) {
                this.primaryButton.enabled = false;
                this.primaryButton.displayString = EnumChatFormatting.YELLOW + "Checking for updates...";
            } else if (st == GomodAutoUpdater.State.DOWNLOADING) {
                this.primaryButton.enabled = false;
                this.primaryButton.displayString = EnumChatFormatting.YELLOW
                        + "Downloading... " + GomodAutoUpdater.getDownloadPercent() + "%";
            } else if (st == GomodAutoUpdater.State.READY_ON_EXIT) {
                this.primaryButton.enabled = false;
                this.primaryButton.displayString = EnumChatFormatting.GREEN
                        + "Ready (" + GomodAutoUpdater.getStagedVersion() + ") - Close Game";
            } else if (GomodAutoUpdater.isUpdateAvailable() && info != null && info.hasDirectJarDownload()) {
                this.primaryButton.enabled = true;
                this.primaryButton.displayString = EnumChatFormatting.GREEN + "" + EnumChatFormatting.BOLD
                        + "Install Update (" + info.version + ")";
            } else {
                this.primaryButton.enabled = true;
                this.primaryButton.displayString = EnumChatFormatting.AQUA + "Check for Updates";
            }
        }
        if (this.autoToggleButton != null) {
            boolean auto = ConfigManager.isAutoUpdateEnabled();
            this.autoToggleButton.displayString = EnumChatFormatting.WHITE + "Auto-Update on Exit: "
                    + (auto ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF");
        }
    }

    private String buildStatusLine(GomodAutoUpdater.State st, GomodAutoUpdater.ReleaseInfo info) {
        switch (st) {
            case CHECKING:
                return EnumChatFormatting.YELLOW + "Checking GitHub Releases...";
            case DOWNLOADING:
                return EnumChatFormatting.YELLOW + "Downloading "
                        + (info != null ? info.version : "") + " (" + GomodAutoUpdater.getDownloadPercent() + "%)";
            case READY_ON_EXIT:
                return EnumChatFormatting.GREEN + "Update " + GomodAutoUpdater.getStagedVersion()
                        + " verified & ready on exit!";
            case AVAILABLE:
                return EnumChatFormatting.GOLD + "Update available: "
                        + EnumChatFormatting.WHITE + GomodVersion.get()
                        + EnumChatFormatting.GRAY + " \u2192 "
                        + EnumChatFormatting.GREEN + (info != null ? info.version : "");
            case UP_TO_DATE:
                return EnumChatFormatting.GREEN + "You are on the latest version (" + GomodVersion.get() + ")";
            case ERROR:
                return EnumChatFormatting.RED + GomodAutoUpdater.getStatusMessage();
            default:
                return EnumChatFormatting.GRAY + "Click Check for Updates to query GitHub Releases.";
        }
    }

    private List<String> buildNotesLines(GomodAutoUpdater.ReleaseInfo info, int maxWidth) {
        List<String> out = new ArrayList<String>();
        if (info == null) {
            out.add(EnumChatFormatting.GRAY + "Releases are fetched from GitHub (" + GomodAutoUpdater.GITHUB_REPO + ").");
            out.add(EnumChatFormatting.DARK_GRAY + "Updates are verified before swapping on game exit.");
            return out;
        }
        String fileLabel = !info.assetName.isEmpty() ? info.assetName : ("gomod123-" + info.version + ".jar");
        out.add(EnumChatFormatting.AQUA + "Release " + info.version
                + EnumChatFormatting.DARK_GRAY + " (" + info.source + ")  "
                + EnumChatFormatting.GRAY + fileLabel);
        if (!info.releaseNotes.isEmpty()) {
            String[] rawLines = info.releaseNotes.replace("\r", "").split("\n");
            for (String raw : rawLines) {
                String clean = stripBasicMarkdown(raw);
                if (clean.isEmpty()) {
                    continue;
                }
                List<String> wrapped = this.fontRendererObj.listFormattedStringToWidth(clean, maxWidth);
                for (String w : wrapped) {
                    out.add(EnumChatFormatting.GRAY + w);
                }
            }
        } else {
            out.add(EnumChatFormatting.GRAY + "Click Changelog to view full release notes.");
        }
        return out;
    }

    private void drawProgressBar(int x, int y, int w, int h, int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        Gui.drawRect(x, y, x + w, y + h, 0x88000000);
        int fillW = (int) ((w - 2) * (clamped / 100.0));
        if (fillW > 0) {
            Gui.drawRect(x + 1, y + 1, x + 1 + fillW, y + h - 1, 0xFF55FF55);
        }
        drawCardBorder(x, y, x + w, y + h, 0x66FFFFFF);
    }

    private void drawCardBorder(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, top + 1, color);
        Gui.drawRect(left, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top, left + 1, bottom, color);
        Gui.drawRect(right - 1, top, right, bottom, color);
    }

    private static String stripBasicMarkdown(String line) {
        if (line == null) {
            return "";
        }
        String s = line.trim();
        while (s.startsWith("#")) {
            s = s.substring(1).trim();
        }
        s = s.replace("**", "").replace("`", "");
        return s.trim();
    }

    private String trimToWidth(String text, int maxWidth) {
        if (text == null || this.fontRendererObj.getStringWidth(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        return this.fontRendererObj.trimStringToWidth(text, Math.max(20, maxWidth - 10)) + "...";
    }

    private static void openExternalUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url.trim()));
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
