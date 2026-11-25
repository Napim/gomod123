package me.napim.gomod.features;

import com.google.gson.JsonObject;
import me.napim.gomod.GomodVersion;
import me.napim.gomod.Main;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;

/**
 * Coordinates in-game update notifications with {@link GomodAutoUpdater}.
 * Checks GitHub Releases on first world entry and also merges relay {@code modRelease} updates.
 */
public final class GomodUpdateChecker {

    private static volatile boolean enteredWorldThisSession;
    private static volatile String notifiedVersionThisSession = "";

    private GomodUpdateChecker() {}

    public static void onPollResponse(JsonObject root) {
        if (root == null || !root.has("modRelease") || !root.get("modRelease").isJsonObject()) {
            return;
        }
        GomodAutoUpdater.onRelayRelease(root.getAsJsonObject("modRelease"));
    }

    /** Call when {@code thePlayer} becomes non-null for the first time this session. */
    public static void onFirstWorldEntry() {
        if (enteredWorldThisSession) {
            return;
        }
        enteredWorldThisSession = true;
        GomodAutoUpdater.checkForUpdatesAsync(false, true);
        tryNotifyInWorld();
    }

    /** Dismisses the chat notification for the current latest version. */
    public static void dismissCurrentUpdate() {
        GomodAutoUpdater.ReleaseInfo info = GomodAutoUpdater.getLatestRelease();
        if (info != null && !info.version.isEmpty()) {
            ConfigManager.setLastNotifiedModVersion(info.version);
            notifiedVersionThisSession = info.version;
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Dismissed update notice for " + EnumChatFormatting.WHITE + info.version
                    + EnumChatFormatting.GRAY + ". Run " + EnumChatFormatting.YELLOW + "/gomod update"
                    + EnumChatFormatting.GRAY + " anytime to update.");
        }
    }

    public static void tryNotifyInWorld() {
        if (!enteredWorldThisSession || !GomodAutoUpdater.hasCompletedInitialGitHubCheck()) {
            return;
        }
        GomodAutoUpdater.ReleaseInfo info = GomodAutoUpdater.getLatestRelease();
        if (info == null || info.version.isEmpty() || !GomodVersion.isOlderThan(info.version)) {
            return;
        }
        if (GomodAutoUpdater.isStagedForExit()) {
            return;
        }
        String latest = info.version;
        if (latest.equals(notifiedVersionThisSession) || latest.equals(ConfigManager.getLastNotifiedModVersion())) {
            return;
        }
        notifiedVersionThisSession = latest;

        String installed = GomodVersion.get();
        ChatComponentText line = new ChatComponentText(Main.CHAT_PREFIX + EnumChatFormatting.GOLD
                + "Update available: " + EnumChatFormatting.WHITE + installed
                + EnumChatFormatting.GRAY + " \u2192 " + EnumChatFormatting.GREEN + latest);

        appendCommandLink(line, EnumChatFormatting.GREEN + "" + EnumChatFormatting.BOLD + " [Update]",
                "/gomod update",
                "Open the gomod updater to install " + latest + " (" + GomodAutoUpdater.getLoaderDisplayName() + ")");

        appendUrlLink(line, EnumChatFormatting.AQUA + " [Changelog]",
                info.effectiveChangelogUrl(),
                "Open release notes for " + latest);

        appendCommandLink(line, EnumChatFormatting.GRAY + " [Later]",
                "/gomod update dismiss",
                "Hide this reminder for " + latest);

        Main.sendChatMessage(line);
    }

    private static void appendCommandLink(ChatComponentText parent, String label, String command, String hover) {
        ChatComponentText link = new ChatComponentText(label);
        ChatStyle st = new ChatStyle();
        st.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        st.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.GRAY + hover)));
        link.setChatStyle(st);
        parent.appendSibling(link);
    }

    private static void appendUrlLink(ChatComponentText parent, String label, String url, String hover) {
        ChatComponentText link = new ChatComponentText(label);
        ChatStyle st = new ChatStyle();
        st.setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
        st.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.GRAY + hover)));
        link.setChatStyle(st);
        parent.appendSibling(link);
    }
}
