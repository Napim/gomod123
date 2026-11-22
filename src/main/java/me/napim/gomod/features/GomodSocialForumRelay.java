package me.napim.gomod.features;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * HTTP relay for community Threads (create / reply / admin delete). Authority lives on gomod Service.
 */
public final class GomodSocialForumRelay {
    private static final int RELAY_CONNECT_MS = 3500;
    private static final int RELAY_READ_MS = 12000;
    private static final JsonParser PARSER = new JsonParser();

    private GomodSocialForumRelay() {}

    public interface CreateCallback {
        void onResult(boolean ok, String forumId, String threadId, String error);
    }

    public interface SimpleCallback {
        void onResult(boolean ok, String error);
    }

    public static void createThreadAsync(final String title, final CreateCallback cb) {
        final Minecraft mc = Minecraft.getMinecraft();
        final String author = GomodSocialPresence.sessionPlayerName(mc);
        if (author.isEmpty() || title == null || title.trim().isEmpty()) {
            return;
        }
        final String trimmed = title.trim();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String err = "";
                boolean ok = false;
                String forumId = GomodSocialForums.DEFAULT_FORUM_ID;
                String threadId = "";
                try {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "thread_create");
                    payload.addProperty("channel", ConfigManager.getGlobalChatChannel());
                    payload.addProperty("title", trimmed);
                    payload.addProperty("author", author);
                    GomodSocialIdentity.attachSender(payload);
                    GomodSocialIdentity.attachRelayPlayer(payload, mc);
                    String body = post(ConfigManager.getGlobalChatEndpoint(), payload);
                    JsonObject resp = PARSER.parse(body).getAsJsonObject();
                    ok = resp.has("ok") && resp.get("ok").getAsBoolean();
                    if (ok) {
                        if (resp.has("forumId")) {
                            forumId = resp.get("forumId").getAsString();
                        }
                        if (resp.has("threadId")) {
                            threadId = resp.get("threadId").getAsString();
                        }
                    } else if (resp.has("error")) {
                        err = resp.get("error").getAsString();
                    }
                } catch (Exception e) {
                    err = e.getMessage() == null ? "relay error" : e.getMessage();
                }
                final boolean fOk = ok;
                final String fForum = forumId;
                final String fThread = threadId;
                final String fErr = err;
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) {
                            cb.onResult(fOk, fForum, fThread, fErr);
                        }
                    }
                });
            }
        }, "gomod-thread-create");
        t.setDaemon(true);
        t.start();
    }

    public static void postToThreadAsync(final String forumId, final String threadId, final String bodyText,
            final SimpleCallback cb) {
        final Minecraft mc = Minecraft.getMinecraft();
        final String author = GomodSocialPresence.sessionPlayerName(mc);
        if (author.isEmpty() || threadId == null || threadId.isEmpty()) {
            return;
        }
        final String text = bodyText == null ? "" : bodyText.trim();
        if (text.isEmpty()) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String err = "";
                boolean ok = false;
                try {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "thread_post");
                    payload.addProperty("channel", ConfigManager.getGlobalChatChannel());
                    payload.addProperty("forumId", forumId == null ? GomodSocialForums.DEFAULT_FORUM_ID : forumId);
                    payload.addProperty("threadId", threadId);
                    payload.addProperty("author", author);
                    payload.addProperty("body", text);
                    GomodSocialIdentity.attachSender(payload);
                    GomodSocialIdentity.attachRelayPlayer(payload, mc);
                    String respBody = post(ConfigManager.getGlobalChatEndpoint(), payload);
                    JsonObject resp = PARSER.parse(respBody).getAsJsonObject();
                    ok = resp.has("ok") && resp.get("ok").getAsBoolean();
                    if (!ok && resp.has("error")) {
                        err = resp.get("error").getAsString();
                    }
                } catch (Exception e) {
                    err = e.getMessage() == null ? "relay error" : e.getMessage();
                }
                final boolean fOk = ok;
                final String fErr = err;
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (cb != null) {
                            cb.onResult(fOk, fErr);
                        }
                    }
                });
            }
        }, "gomod-thread-post");
        t.setDaemon(true);
        t.start();
    }

    public static void deleteThreadAsync(final String forumId, final String threadId, final SimpleCallback cb) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || threadId == null || threadId.isEmpty()) {
            return;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                String err = "";
                boolean ok = false;
                try {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "thread_delete");
                    payload.addProperty("channel", ConfigManager.getGlobalChatChannel());
                    payload.addProperty("forumId", forumId == null ? GomodSocialForums.DEFAULT_FORUM_ID : forumId);
                    payload.addProperty("threadId", threadId);
                    GomodSocialIdentity.attachSender(payload);
                    GomodSocialIdentity.attachRelayPlayer(payload, mc);
                    String respBody = post(ConfigManager.getGlobalChatEndpoint(), payload);
                    JsonObject resp = PARSER.parse(respBody).getAsJsonObject();
                    ok = resp.has("ok") && resp.get("ok").getAsBoolean();
                    if (!ok && resp.has("error")) {
                        err = resp.get("error").getAsString();
                    }
                } catch (Exception e) {
                    err = e.getMessage() == null ? "relay error" : e.getMessage();
                }
                final boolean fOk = ok;
                final String fErr = err;
                final boolean orphan = !fOk && isThreadNotFoundError(fErr);
                final boolean removed = fOk || orphan;
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (removed) {
                            GomodSocialForums.removeThreadLocal(
                                    forumId == null ? GomodSocialForums.DEFAULT_FORUM_ID : forumId, threadId);
                        }
                        if (cb != null) {
                            cb.onResult(removed, orphan ? "orphan" : fErr);
                        }
                    }
                });
            }
        }, "gomod-thread-delete");
        t.setDaemon(true);
        t.start();
    }

    private static String post(String urlStr, JsonObject payload) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setConnectTimeout(RELAY_CONNECT_MS);
        conn.setReadTimeout(RELAY_READ_MS);
        conn.setDoOutput(true);
        GomodRelayAuth.authorize(conn, urlStr);
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        GomodRelayAuth.noteResponseCode(urlStr, code);
        java.io.InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        if (stream != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
            }
        }
        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + (sb.length() > 0 ? " (" + sb + ")" : ""));
        }
        return sb.toString();
    }

    public static boolean isRelayOutdatedError(String err) {
        return err != null && (err.contains("unknown_type") || err.contains("HTTP 400"));
    }

    /** Thread exists locally but not on gomod Service (offline-only create or relay data reset). */
    public static boolean isThreadNotFoundError(String err) {
        return err != null && err.contains("thread_not_found");
    }

    public static void notifyRelayError(String err) {
        if (err == null || err.isEmpty()) {
            return;
        }
        if (isRelayOutdatedError(err)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Threads need an updated gomod Service relay (redeploy shareinv-relay/server.js).");
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Saved locally for now — other players won't see it until the relay is updated.");
            return;
        }
        if (isThreadNotFoundError(err)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "That thread was not on the relay (local-only or relay was reset).");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Threads relay: " + err);
    }
}
