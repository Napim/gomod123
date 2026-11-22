package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Loads durable Social history on open and periodically while the relay is enabled.
 */
public final class GomodSocialServiceSync {
    private static final Gson GSON = new Gson();
    private static final JsonParser PARSER = new JsonParser();
    private static final int CONNECT_MS = 3500;
    private static final int READ_MS = 12000;
    private static java.util.UUID backgroundOwner;
    private static long backgroundNext;
    private static boolean backgroundBusy;

    private GomodSocialServiceSync() {}

    public interface Callback {
        void onResult(boolean ok, String error);
    }
    /** Recovers durable DMs missed by an ephemeral poll cursor, even with Social closed. */
    public static synchronized void syncBackground(Minecraft mc){
        java.util.UUID owner=LunarFitSharing.account();long now=System.currentTimeMillis();
        if(owner==null||backgroundBusy)return;
        if(!owner.equals(backgroundOwner)){backgroundOwner=owner;backgroundNext=0;}
        if(now<backgroundNext)return;
        backgroundNext=now+30000;backgroundBusy=true;
        syncOnOpen(mc,(ok,error)->{synchronized(GomodSocialServiceSync.class){backgroundBusy=false;}});
    }

    public static void syncOnOpen(final Minecraft mc, final Callback callback) {
        if (mc == null || GomodSocialPresence.sessionPlayerName(mc).isEmpty()) {
            if (callback != null) {
                callback.onResult(false, "no Minecraft session");
            }
            return;
        }
        final java.util.UUID requestOwner = LunarFitSharing.account();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                String error = "";
                try {
                    if (requestOwner == null || !requestOwner.equals(LunarFitSharing.account())) throw new IllegalStateException("Account changed");
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "social_sync");
                    payload.addProperty("channel", ConfigManager.getGlobalChatChannel());
                    GomodSocialIdentity.attachPoll(payload);
                    GomodSocialIdentity.attachRelayPlayer(payload, mc);
                    payload.addProperty("server", GomodSocialPresence.resolveLocalServer(mc));
                    payload.addProperty("appearOffline", GomodSocialStore.isAppearOffline());
                    String body = postForBody(ConfigManager.getGlobalChatEndpoint(), payload,requestOwner);
                    JsonObject root = PARSER.parse(body).getAsJsonObject();
                    if (!requestOwner.equals(LunarFitSharing.account())) throw new IllegalStateException("Account changed");
                    if (root == null || !root.has("ok") || !root.get("ok").getAsBoolean()) {
                        error = root != null && root.has("error") && !root.get("error").isJsonNull()
                                ? root.get("error").getAsString() : "relay rejected sync";
                    } else {
                        mc.addScheduledTask(()->{
                            boolean applied=false;String failure="";
                            try{
                                if(!requestOwner.equals(LunarFitSharing.account()))throw new IllegalStateException("Account changed");
                                if (root.has("friendsSnapshot") && root.get("friendsSnapshot").isJsonArray()) {
                                    ConfigManager.applyFriendsFromService(root.getAsJsonArray("friendsSnapshot"));
                                }
                                if (root.has("friendRequestResetGeneration")
                                        && !root.get("friendRequestResetGeneration").isJsonNull()) {
                                    ConfigManager.applyGomodFriendRequestReset(
                                            root.get("friendRequestResetGeneration").getAsLong());
                                }
                                JsonArray dmThreads = root.has("dmThreads") && root.get("dmThreads").isJsonArray()
                                        ? root.getAsJsonArray("dmThreads") : new JsonArray();
                                GomodSocialStore.replaceDirectThreadsFromService(dmThreads);
                                GomodServiceStatus.markHttpRelaySuccess();

                                applied=true;
                            }catch(Exception problem){failure=problem.getMessage()==null?"Could not apply Social history":problem.getMessage();}
                            if(callback!=null)callback.onResult(applied,failure);
                        });
                        return;
                    }
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    GomodServiceStatus.markHttpRelayFailure(error);
                }
                final boolean result = ok;
                final String resultError = error;
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (callback != null) {
                            callback.onResult(result, resultError);
                        }
                    }
                });
            }
        }, "gomod-social-service-sync");
        t.setDaemon(true);
        t.start();
    }

    private static String postForBody(String urlStr, JsonObject payload,java.util.UUID owner) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setConnectTimeout(CONNECT_MS);
        conn.setReadTimeout(READ_MS);
        conn.setDoOutput(true);
        if(!owner.equals(LunarFitSharing.account()))throw new IllegalStateException("Account changed");
        GomodRelayAuth.authorize(conn, urlStr);
        if(!owner.equals(LunarFitSharing.account()))throw new IllegalStateException("Account changed");
        byte[] body = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
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
            String detail = sb.toString();
            try {
                JsonObject errorRoot = PARSER.parse(detail).getAsJsonObject();
                if (errorRoot != null && errorRoot.has("message") && !errorRoot.get("message").isJsonNull()) {
                    String message = errorRoot.get("message").getAsString().trim();
                    if (!message.isEmpty()) {
                        detail = message;
                    }
                }
            } catch (Exception ignored) {
            }
            throw new java.io.IOException("HTTP " + code + (detail.isEmpty() ? "" : ": " + detail));
        }
        return sb.toString();
    }
}
