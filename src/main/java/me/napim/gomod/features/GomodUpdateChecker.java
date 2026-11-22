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

 * Notifies once per session when the relay reports a newer gomod version (first time in a world/lobby).

 */

public final class GomodUpdateChecker {

    /** gomod changelog channel (overridden by relay {@code modRelease.changelogUrl} when set). */

    private static final String DEFAULT_CHANGELOG_URL =

            "https://discord.com/channels/1254469815918133318/1506276042980065410";



    private static volatile boolean enteredWorldThisSession;

    private static volatile String pendingLatestVersion = "";

    private static volatile String pendingDownloadUrl = "";

    private static volatile String pendingChangelogUrl = "";



    private GomodUpdateChecker() {}



    public static void onPollResponse(JsonObject root) {

        if (root == null || !root.has("modRelease") || !root.get("modRelease").isJsonObject()) {

            return;

        }

        JsonObject rel = root.getAsJsonObject("modRelease");

        String latest = rel.has("version") && !rel.get("version").isJsonNull()

                ? rel.get("version").getAsString().trim() : "";

        if (latest.isEmpty()) {

            return;

        }

        pendingLatestVersion = latest;

        pendingDownloadUrl = jsonString(rel, "downloadUrl");

        pendingChangelogUrl = jsonString(rel, "changelogUrl");

        if (enteredWorldThisSession) {

            tryNotifyInWorld();

        }

    }



    /** Call when {@code thePlayer} becomes non-null for the first time this session. */

    public static void onFirstWorldEntry() {

        if (enteredWorldThisSession) {

            return;

        }

        enteredWorldThisSession = true;

        tryNotifyInWorld();

    }



    private static void tryNotifyInWorld() {

        String latest = pendingLatestVersion;

        if (latest.isEmpty() || !GomodVersion.isOlderThan(latest)) {

            return;

        }

        if (latest.equals(ConfigManager.getLastNotifiedModVersion())) {

            return;

        }

        ConfigManager.setLastNotifiedModVersion(latest);

        String installed = GomodVersion.get();

        ChatComponentText line = new ChatComponentText(Main.CHAT_PREFIX + EnumChatFormatting.GOLD

                + "gomod update available: " + EnumChatFormatting.WHITE + installed

                + EnumChatFormatting.GRAY + " \u2192 " + EnumChatFormatting.GREEN + latest);



        String download = pendingDownloadUrl;

        if (download != null && !download.isEmpty()) {

            appendUrlLink(line, EnumChatFormatting.AQUA + " [Download]", download, "Download the new gomod JAR");

        }



        String changelog = pendingChangelogUrl;

        if (changelog == null || changelog.isEmpty()) {

            changelog = DEFAULT_CHANGELOG_URL;

        }

        appendUrlLink(line, EnumChatFormatting.LIGHT_PURPLE + " [Changelog]", changelog,

                "Open the gomod changelog channel on Discord");



        Main.sendChatMessage(line);

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



    private static String jsonString(JsonObject o, String key) {

        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {

            return "";

        }

        return o.get(key).getAsString().trim();

    }

}


