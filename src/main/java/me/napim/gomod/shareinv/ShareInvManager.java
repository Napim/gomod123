package me.napim.gomod.shareinv;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import me.napim.gomod.GomodVersion;
import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodPeerStandaloneCape;
import me.napim.gomod.features.GomodPeerLunarFullAccess;
import me.napim.gomod.features.GomodPeerLunarPlus;
import me.napim.gomod.features.GomodPeerPregameDye;
import me.napim.gomod.features.GomodRelayAuth;
import me.napim.gomod.features.GomodSocialIdentity;
import me.napim.gomod.features.GomodSocialPresence;
import me.napim.gomod.features.GomodSocialStore;
import me.napim.gomod.features.PlayerStandaloneCape;
import me.napim.gomod.util.ClientInputHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.ResourceLocation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket client for {@code /shareinv}: pairs with the Java relay JAR or optional Node server.
 */
public final class ShareInvManager {
    private static final JsonParser JSON_PARSER = new JsonParser();
    private static final int INV_SLOTS = 40;
    /** Hotbar index (0–8) stashed inside {@code items} for relays that only forward type/sessionId/from/items. */
    private static final String ITEMS_EMBEDDED_SELECTED_SLOT = "__gomod_sel";
    private static final int SYNC_INTERVAL_TICKS = 5;
    /** ~4s @ 20tps — hosted relays/proxies often drop idle TCP before 10s without traffic. */
    private static final int KEEPALIVE_INTERVAL_TICKS = 80;
    /** Keep relay error hover text bounded so a verbose close reason cannot stall chat rendering. */
    private static final int MAX_RELAY_NOTICE_DETAIL_CHARS = 240;
    /** The relay's hello limit is one minute; leave margin so a retry cannot race the window reset. */
    private static final long RATE_LIMIT_MIN_BACKOFF_MS = 65_000L;
    private static final long RATE_LIMIT_MAX_BACKOFF_MS = 5L * 60_000L;
    /** Auth challenge and verification endpoints use five-minute fixed windows. */
    private static final long AUTH_RATE_LIMIT_BACKOFF_MS = 5L * 60_000L + 5_000L;
    /** A host-side free-plan quota error is unlikely to clear within the relay's five-minute window. */
    private static final long AUTH_FREE_USAGE_BACKOFF_MS = 60L * 60_000L;
    private static final long AUTH_SERVICE_BACKOFF_BASE_MS = 60_000L;
    private static final long AUTH_SERVICE_BACKOFF_MAX_MS = 15L * 60_000L;

    private static int syncTickCounter;
    private static int keepAliveTicks;
    private static volatile boolean relayHelloAcked;
    private static volatile boolean spectatorRelaySupported;
    private static volatile boolean gameChatRelaySupported;
    private static boolean bootstrappedThisWorld;

    private static volatile WebSocketClient socket;
    private static volatile boolean wantConnected;
    private static volatile String activeSessionId;
    private static volatile String activePeerName;
    private static volatile ShareSessionMode activeSessionMode;
    private static volatile ItemStack[] remoteSlots = emptySlots();
    /** Peer's selected hotbar index (0-8), for highlighting held item. */
    private static volatile int remoteSelectedHotbarSlot;
    /** Synced from peer {@code inv} payloads (vanilla-style HUD). */
    private static volatile float remoteHealth;
    private static volatile float remoteHealthMax = 20.0f;
    private static volatile int remoteFood;
    private static volatile float remoteFoodSat;
    private static volatile int remoteArmorPoints;
    private static volatile int remoteXpLevel;
    private static volatile float remoteXpBar;
    private static volatile String pendingInviteId;
    private static volatile String pendingFrom;
    private static volatile ShareSessionMode pendingInviteMode = ShareSessionMode.SHARE_INV;
    private static volatile ShareSessionMode acceptedInviteModeHint = ShareSessionMode.SHARE_INV;
    private static volatile ShareSessionMode lastSentInviteMode = ShareSessionMode.SHARE_INV;
    /**
     * Peer UI from relay {@code inv_peer} when present: {@code -1} unknown, {@code 0} in-game (multiplayer),
     * {@code 1} title / main menu. Used for friend HUD label; falls back to tab-list heuristic when unknown.
     */
    private static volatile int remotePeerUiState = -1;
    /** Middot between chat actions avoids hyphen bidirectional quirks; omit U+200E — 1.8 font renders it visibly. */
    private static final String CHAT_ACTION_DOT_GAP = " \u00B7 ";

    private static final AtomicInteger reconnectAfterTicks = new AtomicInteger(0);
    private static volatile long relayRateLimitBackoffUntilMs;
    /** Auth challenge/verify cooldown survives a manual disconnect, since the server window does too. */
    private static volatile long authRateLimitBackoffUntilMs;
    /** Exponential retry delay for host-side 5xx responses; avoids hammering an unhealthy service. */
    private static volatile long authServiceBackoffUntilMs;
    private static volatile int consecutiveAuthServiceFailures;
    /** Avoid burning relay challenges when the launcher-provided Minecraft session is rejected. */
    private static volatile long minecraftSessionFailureBackoffUntilMs;
    /** Only one Minecraft session-service authentication exchange may run at a time. */
    private static final AtomicBoolean relayAuthPending = new AtomicBoolean(false);
    /** Show each service failure streak once; clear the notice after the relay accepts hello. */
    private static String lastConnectFailChatKey = "";
    /**
     * Set when the relay had completed {@code hello_ok} and then dropped while {@link #wantConnected}
     * stayed true, so the next {@code hello_ok} can announce reconnection in chat.
     */
    private static volatile boolean announceRelayReconnectedOnNextHello;
    /** Show a relay disconnect notice at most once for this game process. */
    private static volatile boolean relayDisconnectChatShownThisSession;
    /** Show the old-client compatibility notice at most once for this game process. */
    private static volatile boolean relayClientUpdateNoticeShownThisSession;
    private static volatile boolean relayDisconnectChatIgnored;
    /** Avoid red chat floods when stale session packets/errors arrive after a relay drop or session teardown. */
    private static long lastStaleSessionErrorChatMs;
    private static String lastStaleSessionErrorChatKey = "";
    private static final long STALE_SESSION_ERROR_CHAT_COOLDOWN_MS = 8_000L;
    private static volatile boolean helloPending;
    /** True only after {@link #connect()} — suppresses "relay ready" spam on auto-connect at login. */
    private static volatile boolean chatRelayReadyOnHelloOk;
    /** After local {@link #leaveSession()}, hide duplicate session_end from relay. */
    private static volatile boolean suppressSessionEndChat;
    /** When true (see {@code /shareinv debug}), first {@code inv_peer} each session is summarized in chat. */
    private static volatile boolean shareInvDebugToChat;
    private static boolean shareInvDebugLoggedThisSession;
    /** After relay drops mid-session, re-send invite to this peer once {@code hello_ok} (avoids re-typing {@code /shareinv <player>}). */
    private static volatile String resumeShareInvPeerAfterRelay;
    private static volatile ShareSessionMode resumeShareSessionModeAfterRelay = ShareSessionMode.SHARE_INV;
    /** Defer "Sharing inventory with …" until first {@code inv_peer} so relay {@code from} casing is used. */
    private static volatile boolean pendingSessionWelcomeChat;
    /** If no {@code inv_peer} arrives, send welcome after this many client ticks (~4s at 20 tps). */
    private static int pendingWelcomeFallbackTicks;

    public static boolean isShareInvDebugToChat() {
        return shareInvDebugToChat;
    }

    public static void setShareInvDebugToChat(boolean enabled) {
        shareInvDebugToChat = enabled;
        if (enabled) {
            shareInvDebugLoggedThisSession = false;
        }
    }

    public static void toggleShareInvDebugToChat() {
        shareInvDebugToChat = !shareInvDebugToChat;
        if (shareInvDebugToChat) {
            shareInvDebugLoggedThisSession = false;
        }
    }

    public static boolean isRelayDisconnectChatIgnored() {
        return relayDisconnectChatIgnored;
    }

    public static void setRelayDisconnectChatIgnored(boolean ignored) {
        relayDisconnectChatIgnored = ignored;
        if (ignored) {
            // The same relay flap also emits the legacy-client update error.
            // Treat [ignore] as suppressing both notices for this game session.
            relayClientUpdateNoticeShownThisSession = true;
        }
    }

    public static void toggleRelayDisconnectChatIgnored() {
        setRelayDisconnectChatIgnored(!relayDisconnectChatIgnored);
    }

    /** When {@code /shareinv relayignore} is on, suppress disconnect/reconnect session chat (not first-time ready). */
    private static boolean shouldSuppressRelaySessionStatusChat() {
        return relayDisconnectChatIgnored;
    }

    private ShareInvManager() {}

    private static String describeWebSocketError(Exception ex) {
        if (ex == null) {
            return "connection error";
        }
        String cn = ex.getClass().getSimpleName();
        String msg = ex.getMessage();
        if (msg != null && msg.length() > MAX_RELAY_NOTICE_DETAIL_CHARS) {
            msg = msg.substring(0, MAX_RELAY_NOTICE_DETAIL_CHARS - 1) + "\u2026";
        }
        String core = (msg != null && !msg.trim().isEmpty()) ? cn + ": " + msg.trim() : cn;
        String lower = core.toLowerCase();
        if ("host".equalsIgnoreCase(msg != null ? msg.trim() : "")
                || lower.contains("unknown host")
                || lower.contains("unresolved")) {
            core += " — Bad relay address? Open /shareinv url (use a real host like your-app.fly.dev, not the word HOST). Public HTTPS relays need wss://";
        }
        return core;
    }

    private static String sanitizeRelayNoticeDetail(String detail) {
        if (detail == null) {
            return "Disconnected. Reconnecting...";
        }
        String bounded = detail.length() > MAX_RELAY_NOTICE_DETAIL_CHARS
                ? detail.substring(0, MAX_RELAY_NOTICE_DETAIL_CHARS - 1) + "\u2026"
                : detail;
        String clean = bounded.replace('\n', ' ').replace('\r', ' ').trim();
        while (clean.contains("  ")) {
            clean = clean.replace("  ", " ");
        }
        if (clean.isEmpty()) {
            clean = "Disconnected.";
        }
        if (!clean.endsWith(".") && !clean.endsWith("!") && !clean.endsWith("?") && !clean.endsWith("\u2026")) {
            clean = clean + ".";
        }
        if (!clean.endsWith("Reconnecting...") && !clean.endsWith("Reconnecting…")) {
            clean = clean + " Reconnecting...";
        }
        return clean;
    }

    private static void sendRelayDisconnectNotice(String detail) {
        if (relayDisconnectChatIgnored || relayDisconnectChatShownThisSession) {
            return;
        }
        relayDisconnectChatShownThisSession = true;
        ChatComponentText line = new ChatComponentText("");
        line.appendSibling(new ChatComponentText(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "gomod Service: disconnected. Reconnecting… "));

        ChatComponentText moreInfo = new ChatComponentText(EnumChatFormatting.AQUA + "[more info]");
        ChatStyle moreStyle = new ChatStyle();
        moreStyle.setUnderlined(false);
        moreStyle.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.GRAY + sanitizeRelayNoticeDetail(detail))));
        moreInfo.setChatStyle(moreStyle);
        line.appendSibling(moreInfo);

        line.appendSibling(new ChatComponentText(EnumChatFormatting.GRAY + " "));

        ChatComponentText ignore = new ChatComponentText(EnumChatFormatting.YELLOW + "[ignore]");
        ChatStyle ignoreStyle = new ChatStyle();
        ignoreStyle.setUnderlined(false);
        ignoreStyle.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/shareinv relayignore on"));
        ignoreStyle.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.YELLOW + "Hide relay disconnect/error chat for "
                        + EnumChatFormatting.WHITE + "this session only "
                        + EnumChatFormatting.YELLOW + "(shows again after you restart the game).")));
        ignore.setChatStyle(ignoreStyle);
        line.appendSibling(ignore);

        Main.sendChatMessage(line);
    }

    private static int ticksForMillis(long millis) {
        if (millis <= 0L) {
            return 0;
        }
        long ticks = (millis + 49L) / 50L;
        return ticks >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ticks;
    }

    /** Keep the longest pending reconnect delay when close/error callbacks race the chat handler. */
    private static void delayReconnectAtLeast(int ticks) {
        int wanted = Math.max(0, ticks);
        while (true) {
            int current = reconnectAfterTicks.get();
            if (current >= wanted || reconnectAfterTicks.compareAndSet(current, wanted)) {
                return;
            }
        }
    }

    private static void delayReconnectAtLeastWithRateLimit(int ticks) {
        long backoffUntil = Math.max(Math.max(Math.max(relayRateLimitBackoffUntilMs, authRateLimitBackoffUntilMs),
                authServiceBackoffUntilMs), minecraftSessionFailureBackoffUntilMs);
        long remaining = backoffUntil - System.currentTimeMillis();
        delayReconnectAtLeast(Math.max(ticks, ticksForMillis(remaining)));
    }

    private static boolean isRelayRateLimitBackoffActive() {
        long now = System.currentTimeMillis();
        return relayRateLimitBackoffUntilMs > now || authRateLimitBackoffUntilMs > now || authServiceBackoffUntilMs > now;
    }

    private static long rateLimitRetryAfterMs(JsonObject msg) {
        long retryAfterMs = 0L;
        try {
            if (msg != null && msg.has("retryAfterMs") && msg.get("retryAfterMs").isJsonPrimitive()) {
                retryAfterMs = msg.get("retryAfterMs").getAsLong();
            }
        } catch (Exception ignored) {}
        if (retryAfterMs <= 0L) {
            retryAfterMs = RATE_LIMIT_MIN_BACKOFF_MS;
        }
        return Math.max(RATE_LIMIT_MIN_BACKOFF_MS, Math.min(RATE_LIMIT_MAX_BACKOFF_MS, retryAfterMs));
    }

    private static void noteRelayRateLimited(JsonObject msg) {
        long until = System.currentTimeMillis() + rateLimitRetryAfterMs(msg);
        if (until > relayRateLimitBackoffUntilMs) {
            relayRateLimitBackoffUntilMs = until;
        }
        delayReconnectAtLeastWithRateLimit(0);
    }

    private static boolean isAuthRateLimited(Throwable error) {
        if (authHttpStatusCode(error) == 429 || isFreeUsageLimit(error)) return true;
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && (message.toLowerCase(Locale.ROOT).contains("rate_limited")
                    || message.toLowerCase(Locale.ROOT).contains("http 429"))) {
                return true;
            }
        }
        return false;
    }

    private static int authHttpStatusCode(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof GomodRelayAuth.HttpException) {
                return ((GomodRelayAuth.HttpException) current).getStatusCode();
            }
        }
        return 0;
    }

    private static boolean isFreeUsageLimit(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("free usage limit") || lower.contains("usage limit reached")) return true;
            }
        }
        return false;
    }

    private static long noteAuthRateLimited(Throwable error) {
        long backoffMs = isFreeUsageLimit(error) ? AUTH_FREE_USAGE_BACKOFF_MS : AUTH_RATE_LIMIT_BACKOFF_MS;
        long until = System.currentTimeMillis() + backoffMs;
        if (until > authRateLimitBackoffUntilMs) {
            authRateLimitBackoffUntilMs = until;
        }
        delayReconnectAtLeastWithRateLimit(0);
        return Math.max(backoffMs, authRateLimitBackoffUntilMs - System.currentTimeMillis());
    }

    private static boolean isAuthServiceUnavailable(Throwable error) {
        int status = authHttpStatusCode(error);
        return status == 408 || status >= 500 && status <= 599;
    }

    private static long noteAuthServiceUnavailable() {
        int failureCount = Math.min(8, ++consecutiveAuthServiceFailures);
        int shift = Math.min(4, failureCount - 1);
        long backoffMs = Math.min(AUTH_SERVICE_BACKOFF_MAX_MS, AUTH_SERVICE_BACKOFF_BASE_MS << shift);
        long until = System.currentTimeMillis() + backoffMs;
        if (until > authServiceBackoffUntilMs) authServiceBackoffUntilMs = until;
        delayReconnectAtLeastWithRateLimit(0);
        return Math.max(backoffMs, authServiceBackoffUntilMs - System.currentTimeMillis());
    }

    private static boolean isMinecraftSessionInvalid(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if ("InvalidCredentialsException".equals(current.getClass().getSimpleName())) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("invalidcredentialsexception")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMinecraftSessionFailureBackoffActive() {
        return minecraftSessionFailureBackoffUntilMs > System.currentTimeMillis();
    }

    private static void noteMinecraftSessionInvalid() {
        long until = System.currentTimeMillis() + AUTH_RATE_LIMIT_BACKOFF_MS;
        if (until > minecraftSessionFailureBackoffUntilMs) {
            minecraftSessionFailureBackoffUntilMs = until;
        }
        delayReconnectAtLeastWithRateLimit(0);
    }

    private static ItemStack[] emptySlots() {
        return new ItemStack[INV_SLOTS];
    }

    public static boolean isWantConnected() {
        return wantConnected;
    }

    public static void setWantConnected(boolean v) {
        wantConnected = v;
    }

    public static boolean isRelayOpen() {
        WebSocketClient s = socket;
        return s != null && s.isOpen();
    }

    public static boolean isSessionActive() {
        return activeSessionId != null && activePeerName != null;
    }

    public static ShareSessionMode getActiveSessionMode() {
        return activeSessionMode;
    }

    public static String getPeerName() {
        return activePeerName;
    }

    /**
     * Name to show on the friend HUD: match in-world player or tab list (correct casing), else relay string.
     */
    public static String getPeerHudName() {
        return resolveMinecraftUsernameCasing(activePeerName);
    }

    /**
     * True only when the peer's last {@code inv_peer} payload explicitly says title/main menu ({@code inTitle},
     * {@code inMenu}, {@code inGame:false}, or a matching {@code screen} string). We do not infer from the local
     * tab list: peers on singleplayer or another server are never listed there, which used to false-trigger this.
     */
    public static boolean shouldAnnotatePeerMcMenu() {
        return remotePeerUiState == 1;
    }

    private static void parsePeerUiStateFromInvPeer(JsonObject msg) {
        boolean set = false;
        if (msg.has("inTitle") && msg.get("inTitle").isJsonPrimitive()) {
            remotePeerUiState = msg.get("inTitle").getAsBoolean() ? 1 : 0;
            set = true;
        } else if (msg.has("inMenu") && msg.get("inMenu").isJsonPrimitive()) {
            remotePeerUiState = msg.get("inMenu").getAsBoolean() ? 1 : 0;
            set = true;
        } else if (msg.has("inGame") && msg.get("inGame").isJsonPrimitive()) {
            remotePeerUiState = msg.get("inGame").getAsBoolean() ? 0 : 1;
            set = true;
        }
        if (!set && msg.has("screen") && msg.get("screen").isJsonPrimitive()) {
            String sc = msg.get("screen").getAsString().toLowerCase(Locale.ROOT);
            if (sc.contains("title") || "main".equals(sc) || sc.contains("main_menu")) {
                remotePeerUiState = 1;
            } else if (!sc.isEmpty()) {
                remotePeerUiState = 0;
            }
        }
    }

    /**
     * Best-effort Mojang/server casing for a username from relay (handles Hypixel delay: world often loads before tab).
     */
    public static String resolveMinecraftUsernameCasing(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.theWorld != null && mc.thePlayer != null) {
                for (EntityPlayer ep : mc.theWorld.playerEntities) {
                    if (ep != mc.thePlayer && raw.equalsIgnoreCase(ep.getName())) {
                        return ep.getName();
                    }
                }
            }
            if (mc != null && mc.getNetHandler() != null) {
                for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                    if (info.getGameProfile() != null
                            && raw.equalsIgnoreCase(info.getGameProfile().getName())) {
                        return info.getGameProfile().getName();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return raw;
    }

    private static void playShareInvSound(String sound, float volume, float pitch) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.thePlayer != null) {
                mc.thePlayer.playSound(sound, volume, pitch);
            }
        } catch (Throwable ignored) {}
    }

    /** Incoming ShareInv / friend-request / DM toast ding ({@code note.pling}). */
    public static void playIncomingInvitePling() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getSoundHandler() != null) {
                mc.getSoundHandler().playSound(
                        PositionedSoundRecord.create(new ResourceLocation("note.pling"), 1.5f));
            }
        } catch (Throwable ignored) {
            playShareInvSound("note.pling", 1.0f, 1.5f);
        }
    }

    private static String formatShareSessionRelayError(String code, String inviteTargetName, ShareSessionMode mode) {
        if (code == null) {
            return "Unknown error";
        }
        ShareSessionMode safeMode = mode != null ? mode : ShareSessionMode.SHARE_INV;
        switch (code) {
            case "target_offline":
                if (inviteTargetName != null && !inviteTargetName.trim().isEmpty()
                        && isLikelyOnlineOnTab(inviteTargetName.trim())) {
                    return inviteTargetName + " is on tab but hasn't connected to the websocket relay yet — "
                            + "they need /" + safeMode.commandName() + " connect (not just gomod or the service menu).";
                }
                return "Offline, or they're not connected to the relay (/"
                        + safeMode.commandName() + " connect).";
            case "invite_expired":
                return "That invite expired";
            case "not_your_invite":
                return "Not your invite";
            case "sender_gone":
                return "Inviter disconnected";
            case "bad_name":
                return "Invalid name";
            case "bad_secret":
                return "Wrong relay secret";
            case "send_hello_first":
                return "Not connected to relay yet";
            case "no_session":
                return "No active session";
            case "bad_session":
                return "Session mismatch";
            case "invalid_json":
            case "bad_message":
                return "Relay protocol error";
            default:
                return code;
        }
    }

    /**
     * Builds a ShareInv error as rich chat. Relay compatibility notices contain
     * a {@code Latest version: https://...} suffix; current clients can make that
     * URL clickable, while older clients still receive the plain-text fallback.
     * The old {@code Download:} form remains supported for relays that have not
     * been redeployed yet.
     */
    private static void appendShareSessionErrorChat(ChatComponentText line, String text) {
        if (line == null || text == null || text.isEmpty()) {
            return;
        }
        String linkMarker = " Latest version: ";
        String linkHover = "Open gomod changelog";
        int marker = text.lastIndexOf(linkMarker);
        if (marker < 0) {
            linkMarker = " Changelog: ";
            marker = text.lastIndexOf(linkMarker);
        }
        if (marker < 0) {
            linkMarker = " Download: ";
            linkHover = "Open gomod download link";
            marker = text.lastIndexOf(linkMarker);
        }
        if (marker >= 0) {
            int urlStart = marker + linkMarker.length();
            String url = text.substring(urlStart).trim();
            if (isSafeDownloadUrl(url)) {
                appendShareSessionErrorChunk(line, text.substring(0, urlStart));
                ChatComponentText link = new ChatComponentText(url);
                ChatStyle linkStyle = new ChatStyle();
                linkStyle.setColor(EnumChatFormatting.RED);
                linkStyle.setUnderlined(true);
                linkStyle.setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
                linkStyle.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ChatComponentText(EnumChatFormatting.YELLOW + linkHover)));
                link.setChatStyle(linkStyle);
                line.appendSibling(link);
                return;
            }
        }
        appendShareSessionErrorChunk(line, text);
    }

    private static void appendShareSessionErrorChunk(ChatComponentText line, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ChatComponentText chunk = new ChatComponentText(text);
        ChatStyle style = new ChatStyle();
        style.setColor(EnumChatFormatting.RED);
        chunk.setChatStyle(style);
        line.appendSibling(chunk);
    }

    private static boolean isSafeDownloadUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isRelayClientUpdateNotice(JsonObject msg, String message) {
        if (msg != null && msg.has("code") && msg.get("code").isJsonPrimitive()
                && "client_update_required".equals(msg.get("code").getAsString())) {
            return true;
        }
        return message != null && message.startsWith("Update gomod to ");
    }

    private static boolean shouldSuppressShareSessionErrorChat(String code, ShareSessionMode mode) {
        if (!"no_session".equals(code) && !"bad_session".equals(code)) {
            return false;
        }
        String key = (mode == null ? ShareSessionMode.SHARE_INV : mode).wireName() + "|" + code;
        long now = System.currentTimeMillis();
        if (key.equals(lastStaleSessionErrorChatKey)
                && now - lastStaleSessionErrorChatMs < STALE_SESSION_ERROR_CHAT_COOLDOWN_MS) {
            return true;
        }
        lastStaleSessionErrorChatKey = key;
        lastStaleSessionErrorChatMs = now;
        return false;
    }

    private static void clearLocalSessionAfterRelayReject(String code) {
        if (!"no_session".equals(code) && !"bad_session".equals(code)) {
            return;
        }
        activeSessionId = null;
        activePeerName = null;
        activeSessionMode = null;
        remoteSlots = emptySlots();
        remoteSelectedHotbarSlot = 0;
        resetRemoteStats();
        remotePeerUiState = -1;
        shareInvDebugLoggedThisSession = false;
        pendingSessionWelcomeChat = false;
        pendingWelcomeFallbackTicks = 0;
        syncTickCounter = 0;
        suppressSessionEndChat = false;
        resumeShareInvPeerAfterRelay = null;
        resumeShareSessionModeAfterRelay = ShareSessionMode.SHARE_INV;
        acceptedInviteModeHint = ShareSessionMode.SHARE_INV;
    }

    private static boolean isLikelyOnlineOnTab(String name) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getNetHandler() == null) {
                return false;
            }
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (info != null && info.getGameProfile() != null
                        && name.equalsIgnoreCase(info.getGameProfile().getName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static ItemStack[] getRemoteSlots() {
        return remoteSlots;
    }

    public static int getRemoteSelectedHotbarSlot() {
        return remoteSelectedHotbarSlot;
    }

    public static float getRemoteHealth() {
        return remoteHealth;
    }

    public static float getRemoteHealthMax() {
        return remoteHealthMax;
    }

    public static int getRemoteFood() {
        return remoteFood;
    }

    public static float getRemoteFoodSat() {
        return remoteFoodSat;
    }

    public static int getRemoteArmorPoints() {
        return remoteArmorPoints;
    }

    public static int getRemoteXpLevel() {
        return remoteXpLevel;
    }

    public static float getRemoteXpBar() {
        return remoteXpBar;
    }

    /** {@code remoteHealth < 0} until first {@code inv_peer} with health (avoid showing DEAD while syncing). */
    private static void resetRemoteStats() {
        remoteHealth = -1.0f;
        remoteHealthMax = 20.0f;
        remoteFood = 0;
        remoteFoodSat = 0.0f;
        remoteArmorPoints = 0;
        remoteXpLevel = 0;
        remoteXpBar = 0.0f;
    }

    /** True if any synced slot (main, hotbar, armor) has an item. */
    public static boolean hasRemoteInventoryContent() {
        ItemStack[] r = remoteSlots;
        if (r == null) {
            return false;
        }
        for (ItemStack st : r) {
            if (st != null && st.stackSize > 0) {
                return true;
            }
        }
        return false;
    }

    private static ShareSessionMode messageMode(JsonObject msg, ShareSessionMode fallback) {
        if (msg != null && msg.has("mode") && msg.get("mode").isJsonPrimitive()) {
            return ShareSessionMode.fromWire(msg.get("mode").getAsString());
        }
        return fallback != null ? fallback : ShareSessionMode.SHARE_INV;
    }

    private static String activeSessionDisplayName(ShareSessionMode mode) {
        return (mode != null ? mode : ShareSessionMode.SHARE_INV).displayName();
    }

    private static String activeCommandName(ShareSessionMode mode) {
        return (mode != null ? mode : ShareSessionMode.SHARE_INV).commandName();
    }

    private static String activeSessionVerb(ShareSessionMode mode) {
        return "Sharing inventory with ";
    }

    public static boolean hasPendingInvite() {
        return pendingInviteId != null;
    }

    public static String getPendingInviteId() {
        return pendingInviteId;
    }

    /** Sender of the inbound relay invite awaiting accept/decline, or {@code null} if none. */
    public static String getPendingInviteFrom() {
        return pendingFrom;
    }

    /** Mode for the inbound invite if any (always ShareInv). */
    public static ShareSessionMode getPendingInviteModeFlag() {
        return pendingInviteMode;
    }

    private static boolean isShareViewWire(JsonObject msg) {
        if (msg == null || !msg.has("mode") || !msg.get("mode").isJsonPrimitive()) {
            return false;
        }
        return ShareSessionMode.isObsoleteShareViewWire(msg.get("mode").getAsString());
    }

    private static void declineInviteById(String inviteId, boolean notifyUser, String notifyMessage) {
        WebSocketClient s = socket;
        if (s != null && s.isOpen() && inviteId != null) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "decline");
            o.addProperty("inviteId", inviteId);
            try {
                s.send(o.toString());
            } catch (Throwable ignored) {
            }
        }
        if (pendingInviteId != null && pendingInviteId.equals(inviteId)) {
            clearPendingInvite();
        }
        if (notifyUser && notifyMessage != null && !notifyMessage.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + notifyMessage);
        }
    }

    public static void onTick(Minecraft mc) {
        if (mc.theWorld != null && mc.thePlayer != null) {
            if (!bootstrappedThisWorld) {
                bootstrappedThisWorld = true;
                boolean relayOk = ConfigManager.isShareInvRelayConfigured();
                if (relayOk && (ConfigManager.isShareInvAutoConnect() || ConfigManager.isFriendInvHudEnabled()
                        || ConfigManager.isSeeSpectatorsWhileAliveEnabled() || ConfigManager.isForceGameChatsEnabled())) {
                    wantConnected = true;
                }
            }
        } else {
            bootstrappedThisWorld = false;
        }

        int cd = reconnectAfterTicks.get();
        if (cd > 0) {
            reconnectAfterTicks.set(cd - 1);
            WebSocketClient current = socket;
            if (!relayHelloAcked || current == null || !current.isOpen()) {
                return;
            }
        }

        if (!wantConnected || mc.thePlayer == null) {
            return;
        }
        String url = ConfigManager.getShareInvRelayUrl();

        WebSocketClient s = socket;
        if (s == null || s.isClosed()) {
            tryConnect(url);
            return;
        }
        if (!s.isOpen()) {
            return;
        }

        if (helloPending && mc.thePlayer != null) {
            sendHello(mc);
            helloPending = false;
        }

        if (relayHelloAcked) {
            keepAliveTicks++;
            if (keepAliveTicks >= KEEPALIVE_INTERVAL_TICKS) {
                keepAliveTicks = 0;
                JsonObject ping = new JsonObject();
                ping.addProperty("type", "ping");
                try {
                    s.send(ping.toString());
                } catch (Throwable ignored) {}
            }
        } else {
            keepAliveTicks = 0;
        }

        if (activeSessionId != null && mc.theWorld != null) {
            syncTickCounter++;
            if (syncTickCounter >= SYNC_INTERVAL_TICKS) {
                syncTickCounter = 0;
                sendInventorySnapshot(mc, activeSessionId);
            }
        } else {
            syncTickCounter = 0;
        }

        if (pendingSessionWelcomeChat && activeSessionId != null && mc.theWorld != null) {
            pendingWelcomeFallbackTicks++;
            if (pendingWelcomeFallbackTicks >= 80) {
                pendingWelcomeFallbackTicks = 0;
                pendingSessionWelcomeChat = false;
                String peerDisplay = getPeerHudName();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + activeSessionVerb(activeSessionMode)
                        + EnumChatFormatting.AQUA + peerDisplay + EnumChatFormatting.GREEN
                        + ". /" + activeCommandName(activeSessionMode) + " stop to end.");
            }
        } else if (!pendingSessionWelcomeChat || activeSessionId == null) {
            pendingWelcomeFallbackTicks = 0;
        }
    }

    public static void connect() {
        wantConnected = true;
        if (isMinecraftSessionFailureBackoffActive()) {
            delayReconnectAtLeastWithRateLimit(0);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Minecraft rejected the current session. Sign out and back in to Lunar, restart Minecraft, then run /shareinv connect.");
            return;
        }
        if (isRelayRateLimitBackoffActive()) {
            delayReconnectAtLeastWithRateLimit(0);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "gomod Service is temporarily unavailable or rate-limited; it will retry automatically when the cooldown ends.");
            return;
        }
        reconnectAfterTicks.set(0);
        Minecraft mc = Minecraft.getMinecraft();
        String url = ConfigManager.getShareInvRelayUrl();
        if (mc.thePlayer == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Join a world first, then run the share connect command again.");
            return;
        }
        WebSocketClient existing = socket;
        if (existing != null && existing.isOpen() && relayHelloAcked) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "gomod Service: already connected.");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "gomod Service: connecting to " + EnumChatFormatting.WHITE + url
                + EnumChatFormatting.GRAY + " …");
        chatRelayReadyOnHelloOk = true;
        tryConnect(url);
    }

    public static void disconnect() {
        wantConnected = false;
        announceRelayReconnectedOnNextHello = false;
        resumeShareInvPeerAfterRelay = null;
        resumeShareSessionModeAfterRelay = ShareSessionMode.SHARE_INV;
        acceptedInviteModeHint = ShareSessionMode.SHARE_INV;
        relayHelloAcked = false;
        relayRateLimitBackoffUntilMs = 0L;
        chatRelayReadyOnHelloOk = false;
        keepAliveTicks = 0;
        leaveSessionQuiet();
        WebSocketClient s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {}
        }
        clearPendingInvite();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "gomod Service disconnected.");
    }

    private static void leaveSessionQuiet() {
        WebSocketClient s = socket;
        String sid = activeSessionId;
        if (s != null && s.isOpen() && sid != null) {
            JsonObject o = new JsonObject();
            o.addProperty("type", "leave");
            try {
                s.send(o.toString());
            } catch (Throwable ignored) {}
        }
        activeSessionId = null;
        activePeerName = null;
        activeSessionMode = null;
        remoteSlots = emptySlots();
        remoteSelectedHotbarSlot = 0;
        resetRemoteStats();
        shareInvDebugLoggedThisSession = false;
        pendingSessionWelcomeChat = false;
        pendingWelcomeFallbackTicks = 0;
        resumeShareInvPeerAfterRelay = null;
        resumeShareSessionModeAfterRelay = ShareSessionMode.SHARE_INV;
        acceptedInviteModeHint = ShareSessionMode.SHARE_INV;
    }

    public static void leaveSession() {
        ShareSessionMode endedMode = activeSessionMode;
        suppressSessionEndChat = true;
        leaveSessionQuiet();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "You left the "
                + activeSessionDisplayName(endedMode) + " session.");
    }

    private static String formatSessionEndForUser(String reason, ShareSessionMode mode) {
        String feature = activeSessionDisplayName(mode);
        if (reason == null) {
            reason = "";
        }
        String r = reason.toLowerCase(Locale.ROOT);
        if ("peer_left".equals(r)) {
            return feature + " ended: your friend stopped sharing (/" + activeCommandName(mode) + " stop).";
        }
        if ("peer_disconnected".equals(r)) {
            return feature + " ended: your friend disconnected (closed the game or lost connection to the relay).";
        }
        if ("ended".equals(r) || r.isEmpty()) {
            return feature + " session ended.";
        }
        return feature + " session ended (" + reason + ").";
    }

    /**
     * gomod Social ShareInv button — connects to the relay when needed, then sends the invite.
     */
    public static void sendInviteFromSocial(String targetName) {
        String target = targetName == null ? "" : targetName.trim();
        if (target.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Select a friend first.");
            return;
        }
        if (!ConfigManager.isGomodFriend(target)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "ShareInv is only available for gomod friends.");
            return;
        }
        target = ConfigManager.getGomodFriendDisplayName(target);
        if (isRelayOpen()) {
            sendInvite(target);
            return;
        }
        if (!ConfigManager.isShareInvRelayConfigured()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "gomod Service relay is not configured. Set it in gomod Service settings or run "
                    + EnumChatFormatting.YELLOW + "/shareinv connect" + EnumChatFormatting.RED + ".");
            return;
        }
        resumeShareInvPeerAfterRelay = target;
        resumeShareSessionModeAfterRelay = ShareSessionMode.SHARE_INV;
        connect();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                + "Connecting to gomod Service — ShareInv invite will send when ready.");
    }

    public static void sendInvite(String targetName) {
        WebSocketClient s = socket;
        if (s == null || !s.isOpen()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + ShareSessionMode.SHARE_INV.displayName()
                    + ": relay connection dropped. "
                    + EnumChatFormatting.YELLOW + "/shareinv connect " + EnumChatFormatting.GRAY + "then try "
                    + EnumChatFormatting.WHITE + "/shareinv " + targetName.trim()
                    + EnumChatFormatting.GRAY + " again.");
            return;
        }
        lastSentInviteMode = ShareSessionMode.SHARE_INV;
        JsonObject o = new JsonObject();
        o.addProperty("type", "invite");
        o.addProperty("to", targetName.trim());
        o.addProperty("mode", ShareSessionMode.SHARE_INV.wireName());
        s.send(o.toString());
    }

    public static void acceptInvite() {
        acceptInvite(pendingInviteMode);
    }

    public static void acceptInvite(ShareSessionMode mode) {
        WebSocketClient s = socket;
        String id = pendingInviteId;
        if (s == null || !s.isOpen()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Not connected.");
            return;
        }
        if (id == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No pending invite. Wait for a request or check spelling.");
            return;
        }
        acceptedInviteModeHint = mode != null ? mode : pendingInviteMode;
        JsonObject o = new JsonObject();
        o.addProperty("type", "accept");
        o.addProperty("inviteId", id);
        s.send(o.toString());
        clearPendingInvite();
    }

    public static void declineInvite() {
        WebSocketClient s = socket;
        String id = pendingInviteId;
        if (s == null || !s.isOpen() || id == null) {
            clearPendingInvite();
            acceptedInviteModeHint = ShareSessionMode.SHARE_INV;
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "decline");
        o.addProperty("inviteId", id);
        s.send(o.toString());
        clearPendingInvite();
        acceptedInviteModeHint = ShareSessionMode.SHARE_INV;
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Invite declined.");
    }

    private static void clearPendingInvite() {
        pendingInviteId = null;
        pendingFrom = null;
        pendingInviteMode = ShareSessionMode.SHARE_INV;
    }

    private static void tryConnect(String url) {
        if (isMinecraftSessionFailureBackoffActive()) {
            delayReconnectAtLeastWithRateLimit(0);
            return;
        }
        if (isRelayRateLimitBackoffActive()) {
            delayReconnectAtLeastWithRateLimit(0);
            return;
        }
        relayHelloAcked = false;
        keepAliveTicks = 0;
        if (!GomodRelayAuth.hasValidToken(url)) {
            authenticateThenConnect(url);
            return;
        }
        try {
            URI uri = new URI(url.trim());
            WebSocketClient old = socket;
            WebSocketClient c = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc.thePlayer != null) {
                        mc.addScheduledTask(() -> sendHello(mc));
                    } else {
                        helloPending = true;
                    }
                }

                @Override
                public void onMessage(String message) {
                    Minecraft mc = Minecraft.getMinecraft();
                    mc.addScheduledTask(() -> handleMessage(message));
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    // Ignore closes from a replaced connection — they must not clear the live socket.
                    if (socket != this) {
                        return;
                    }
                    boolean hadHelloOk = relayHelloAcked;
                    String sidSnapshot = activeSessionId;
                    String peerSnapshot = activePeerName;
                    socket = null;
                    long authRetryMs = 0L;
                    if (code == 4001 || code == 4002
                            || (reason != null && reason.toLowerCase(Locale.ROOT).contains("authentication"))) {
                        authRetryMs = GomodRelayAuth.noteWebSocketAuthenticationRejected(url);
                    }
                    relayHelloAcked = false;
                    spectatorRelaySupported = false;
                    gameChatRelaySupported = false;
                    chatRelayReadyOnHelloOk = false;
                    keepAliveTicks = 0;
                    if (wantConnected) {
                        delayReconnectAtLeastWithRateLimit(authRetryMs > 0L ? ticksForMillis(authRetryMs) : 40);
                        if (hadHelloOk) {
                            announceRelayReconnectedOnNextHello = true;
                        }
                    }
                    if (wantConnected && sidSnapshot != null && peerSnapshot != null && !peerSnapshot.trim().isEmpty()) {
                        resumeShareInvPeerAfterRelay = peerSnapshot.trim();
                        resumeShareSessionModeAfterRelay = activeSessionMode != null
                                ? activeSessionMode
                                : ShareSessionMode.SHARE_INV;
                    }
                    activeSessionId = null;
                    activePeerName = null;
                    activeSessionMode = null;
                    remoteSlots = emptySlots();
                    remoteSelectedHotbarSlot = 0;
                    resetRemoteStats();
                    pendingSessionWelcomeChat = false;
                    pendingWelcomeFallbackTicks = 0;
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc != null && mc.thePlayer != null && wantConnected) {
                        final int closeCode = code;
                        final String r = reason != null && !reason.isEmpty() ? reason : ("code " + code);
                        mc.addScheduledTask(() -> {
                            if (!wantConnected || relayDisconnectChatIgnored) {
                                return;
                            }
                            String detail;
                            if (closeCode == 1000) {
                                detail = "Closed normally (for example a relay redeploy)";
                            } else if (closeCode == 1006) {
                                detail = "Connection dropped (1006, often proxy or host idle timeout)";
                            } else {
                                detail = r;
                            }
                            sendRelayDisconnectNotice(detail);
                        });
                    }
                }

                @Override
                public void onError(Exception ex) {
                    if (socket != this) {
                        return;
                    }
                    final String detail = describeWebSocketError(ex);
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc != null && mc.thePlayer != null) {
                        mc.addScheduledTask(() -> {
                            if (relayDisconnectChatIgnored || !wantConnected) {
                                return;
                            }
                            sendRelayDisconnectNotice(detail);
                        });
                    }
                }
            };
            // Library keep-alive probe; complements our JSON {@code ping} every few seconds.
            c.setConnectionLostTimeout(0);
            socket = c;
            if (old != null && old != c) {
                try {
                    old.close();
                } catch (Throwable ignored) {}
            }
            c.connect();
        } catch (Throwable t) {
            chatRelayReadyOnHelloOk = false;
            maybeAnnounceConnectFailure(t);
            delayReconnectAtLeastWithRateLimit(80);
        }
    }

    private static void authenticateThenConnect(final String url) {
        if (!relayAuthPending.compareAndSet(false, true)) {
            return;
        }
        Thread authThread = new Thread(new Runnable() {
            @Override
            public void run() {
                Throwable failure = null;
                try {
                    GomodRelayAuth.tokenForWebSocket(url);
                } catch (Throwable t) {
                    failure = t;
                } finally {
                    relayAuthPending.set(false);
                }

                final Throwable authFailure = failure;
                Minecraft mc = Minecraft.getMinecraft();
                if (mc == null) {
                    delayReconnectAtLeastWithRateLimit(80);
                    return;
                }
                mc.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        if (authFailure != null) {
                            chatRelayReadyOnHelloOk = false;
                            if (isAuthRateLimited(authFailure)) {
                                long retryMs = noteAuthRateLimited(authFailure);
                                maybeAnnounceConnectFailure(authFailure, retryMs);
                            } else if (isMinecraftSessionInvalid(authFailure)) {
                                noteMinecraftSessionInvalid();
                                maybeAnnounceConnectFailure(authFailure, AUTH_RATE_LIMIT_BACKOFF_MS);
                            } else if (isAuthServiceUnavailable(authFailure)) {
                                long retryMs = noteAuthServiceUnavailable();
                                maybeAnnounceConnectFailure(authFailure, retryMs);
                            } else {
                                long retryMs = authFailure instanceof GomodRelayAuth.AuthRetryDeferredException
                                        ? ((GomodRelayAuth.AuthRetryDeferredException) authFailure).getRetryAfterMs()
                                        : GomodRelayAuth.retryAfterMs();
                                maybeAnnounceConnectFailure(authFailure, retryMs);
                                delayReconnectAtLeastWithRateLimit(retryMs > 0L
                                        ? ticksForMillis(retryMs) : 80);
                            }
                        } else if (wantConnected) {
                            tryConnect(url);
                        }
                    }
                });
            }
        }, "gomod-relay-auth");
        authThread.setDaemon(true);
        authThread.start();
    }

    private static void maybeAnnounceConnectFailure(Throwable t) {
        maybeAnnounceConnectFailure(t, 0L);
    }

    private static void maybeAnnounceConnectFailure(Throwable t, long retryAfterMs) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        String detail = t.getMessage();
        int status = authHttpStatusCode(t);
        if (isMinecraftSessionInvalid(t)) {
            detail = "Minecraft rejected the current session. Sign out and back in to Lunar, then restart Minecraft before connecting again.";
        }
        if (t instanceof GomodRelayAuth.HttpException) {
            detail = ((GomodRelayAuth.HttpException) t).getResponseDetail();
        }
        if (detail == null || detail.isEmpty()) {
            detail = t.getClass().getSimpleName();
        }
        String key = status != 0 ? "gomod-service-http-error"
                : (isMinecraftSessionInvalid(t) ? "minecraft-session-invalid" : t.getClass().getName() + "|" + detail);
        if (key.equals(lastConnectFailChatKey)) {
            return;
        }
        lastConnectFailChatKey = key;

        String notice;
        if (isFreeUsageLimit(t)) {
            notice = "gomod Service: free usage limit reached" + (status == 0 ? "" : " (HTTP " + status + ")")
                    + ". It will retry automatically in "
                    + retryDelayText(retryAfterMs) + ".";
        } else if (status == 429) {
            notice = "gomod Service: authentication is rate-limited (HTTP 429). It will retry automatically in "
                    + retryDelayText(retryAfterMs) + ".";
        } else if (status >= 500 && status <= 599) {
            notice = "gomod Service: temporarily unavailable (HTTP " + status + "). It will retry automatically in "
                    + retryDelayText(retryAfterMs) + ".";
        } else if (retryAfterMs > 0L && !isMinecraftSessionInvalid(t)) {
            notice = "The gomod Service is taking a little longer to respond. I'll try again in "
                    + retryDelayText(retryAfterMs) + ".";
        } else {
            notice = "gomod Service: connection failed: " + detail;
        }
        String hint = detail.contains("LoggerFactory") || detail.contains("slf4j")
                ? " (gomod Service WebSocket logging library missing — update gomod JAR)"
                : "";
        Main.sendChatMessage(connectFailureComponent(notice + hint));
    }

    private static ChatComponentText connectFailureComponent(String message) {
        ChatComponentText line = new ChatComponentText("");

        ChatComponentText openBracket = new ChatComponentText("[");
        openBracket.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY).setBold(true));
        line.appendSibling(openBracket);

        ChatComponentText modName = new ChatComponentText("gomod");
        modName.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GREEN).setBold(true));
        line.appendSibling(modName);

        ChatComponentText closeBracket = new ChatComponentText("] ");
        closeBracket.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.DARK_GRAY).setBold(true));
        line.appendSibling(closeBracket);

        ChatComponentText body = new ChatComponentText(message);
        body.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.RED));
        line.appendSibling(body);
        return line;
    }

    private static String retryDelayText(long retryAfterMs) {
        if (retryAfterMs <= 0L) return "shortly";
        long minutes = Math.max(1L, (retryAfterMs + 59_999L) / 60_000L);
        if (minutes >= 60L && minutes % 60L == 0L) {
            long hours = minutes / 60L;
            return "about " + hours + (hours == 1L ? " hour" : " hours");
        }
        if (minutes >= 60L) return "about " + (minutes / 60L) + " hour " + (minutes % 60L) + " min";
        return "about " + minutes + (minutes == 1L ? " minute" : " minutes");
    }

    /** A separate bounded relay channel; does not require an inventory-sharing session. */
    public static boolean sendCompanionPositions(JsonObject packet) {
        WebSocketClient s = socket;
        if (!relayHelloAcked || s == null || !s.isOpen() || s.hasBufferedData()) return false;
        try { s.send(packet.toString()); return true; } catch (Throwable ignored) { return false; }
    }

    public static void requestSpectatorRelayConnection() {
        if (ConfigManager.isShareInvRelayConfigured()) wantConnected = true;
    }

    public static boolean sendSpectatorState(JsonObject packet) {
        return spectatorRelaySupported && sendCompanionPositions(packet);
    }

    public static boolean sendGameChatPacket(JsonObject packet) {
        return gameChatRelaySupported && sendCompanionPositions(packet);
    }

    /** Local diagnostic flags only; never includes relay credentials or inventory/chat payloads. */
    public static String relayFeatureDebugStatus() {
        WebSocketClient current = socket;
        return "relay wantConnected="+wantConnected+" hello="+relayHelloAcked+" socketOpen="+(current!=null&&current.isOpen())
                +" buffered="+(current!=null&&current.hasBufferedData())+" chatCapability="+gameChatRelaySupported
                +" spectatorCapability="+spectatorRelaySupported;
    }

    private static void sendHello(Minecraft mc) {
        WebSocketClient s = socket;
        if (s == null || !s.isOpen() || mc.thePlayer == null) {
            return;
        }
        String relayUrl = ConfigManager.getShareInvRelayUrl();
        String authToken = GomodRelayAuth.cachedTokenForWebSocket(relayUrl);
        if (authToken.isEmpty()) {
            // Token refresh may block on HTTP and Mojang session verification. Never do it from
            // the client tick/chat path; authenticate on the existing worker and reconnect there.
            authenticateThenConnect(relayUrl);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "hello");
        o.addProperty("spectatorRelay", true);
        o.addProperty("gameChatRelay", true);
        o.addProperty("companionPositionSync", me.napim.gomod.features.GomodFixes.isWeaveRuntime());
        o.addProperty("authToken", authToken);
        o.addProperty("name", mc.thePlayer.getName());
        o.addProperty("secret", ConfigManager.getShareInvSecret());
        o.addProperty("gomodSocialId", ConfigManager.getOrCreateGomodSocialId());
        o.addProperty("modVersion", GomodVersion.get());
        o.addProperty("clientBuild", GomodSocialIdentity.clientBuild());
        o.addProperty("releaseFlavor", GomodSocialIdentity.releaseFlavor());
        String jarSha256 = me.napim.gomod.features.GomodJarSha256.current();
        if (!jarSha256.isEmpty()) o.addProperty("modJarSha256", jarSha256);
        o.addProperty("appearOffline", GomodSocialStore.isAppearOffline());
        GomodSocialIdentity.attachRelayPlayer(o, mc);
        PlayerStandaloneCape.attachRelayWearFields(o);
        GomodPeerLunarPlus.attachLocalState(o);
        GomodPeerLunarFullAccess.attachLocalState(o);
        me.napim.gomod.features.GomodPeerDisguise.attachLocalState(o);
        GomodPeerPregameDye.attachLocalStateTo(o);
        String server = GomodSocialPresence.resolveLocalServer(mc);
        if (server != null) {
            o.addProperty("server", server);
        }
        s.send(o.toString());
    }

    /** Re-send hello so relay peers pick up standalone cape wear changes without reconnecting. */
    public static void resendHelloIfConnected() {
        WebSocketClient s = socket;
        Minecraft mc = Minecraft.getMinecraft();
        if (s == null || !s.isOpen() || mc == null || mc.thePlayer == null) {
            return;
        }
        if (activeSessionId != null) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (activeSessionId != null) {
                    return;
                }
                sendHello(mc);
            }
        });
    }

    private static JsonObject buildInventorySnapshot(Minecraft mc, String sessionId) {
        if (mc == null || mc.thePlayer == null || mc.thePlayer.inventory == null) {
            return null;
        }
        InventoryPlayer inv = mc.thePlayer.inventory;
        JsonObject root = new JsonObject();
        root.addProperty("type", "inv");
        root.addProperty("sessionId", sessionId);
        EntityPlayer p = mc.thePlayer;
        int sel = inv.currentItem;
        if (sel < 0) {
            sel = 0;
        }
        if (sel > 8) {
            sel = 8;
        }
        root.addProperty("selectedSlot", sel);
        float absorption = 0.0f;
        try {
            absorption = p.getAbsorptionAmount();
        } catch (Throwable ignored) {}
        float hpVal = p.getHealth() + absorption;
        root.addProperty("health", hpVal);
        root.addProperty("hp", hpVal);
        root.addProperty("healthMax", p.getMaxHealth());
        root.addProperty("maxHealth", p.getMaxHealth());
        root.addProperty("food", p.getFoodStats().getFoodLevel());
        root.addProperty("foodSat", p.getFoodStats().getSaturationLevel());
        root.addProperty("armorPoints", p.getTotalArmorValue());
        root.addProperty("xpLevel", p.experienceLevel);
        root.addProperty("level", p.experienceLevel);
        float xpFill = p.experience;
        root.addProperty("xpBar", xpFill);
        root.addProperty("experience", xpFill);
        // Redundant nested copy: some hosted relays strip flat keys; full forward still sends this object.
        JsonObject hud = new JsonObject();
        hud.addProperty("selectedSlot", sel);
        hud.addProperty("health", hpVal);
        hud.addProperty("hp", hpVal);
        hud.addProperty("healthMax", p.getMaxHealth());
        hud.addProperty("maxHealth", p.getMaxHealth());
        hud.addProperty("food", p.getFoodStats().getFoodLevel());
        hud.addProperty("foodSat", p.getFoodStats().getSaturationLevel());
        hud.addProperty("armorPoints", p.getTotalArmorValue());
        hud.addProperty("xpLevel", p.experienceLevel);
        hud.addProperty("level", p.experienceLevel);
        hud.addProperty("xpBar", xpFill);
        hud.addProperty("experience", xpFill);
        root.add("hud", hud);
        JsonObject items = new JsonObject();
        for (int i = 0; i < INV_SLOTS; i++) {
            ItemStack st = inv.getStackInSlot(i);
            if (st == null || st.stackSize <= 0) {
                continue;
            }
            Item it = st.getItem();
            if (it == null) {
                continue;
            }
            int id = Item.getIdFromItem(it);
            JsonArray a = new JsonArray();
            a.add(new JsonPrimitive(id));
            a.add(new JsonPrimitive(st.stackSize));
            a.add(new JsonPrimitive(st.getMetadata()));
            items.add(String.valueOf(i), a);
        }
        items.addProperty(ITEMS_EMBEDDED_SELECTED_SLOT, sel);
        root.add("items", items);
        // So the remote client clears "unknown" UI state and never relies on tab-list heuristics for us.
        root.addProperty("inGame", mc.theWorld != null);
        return root;
    }

    private static void sendInventorySnapshot(Minecraft mc, String sessionId) {
        WebSocketClient s = socket;
        JsonObject root = buildInventorySnapshot(mc, sessionId);
        if (s == null || !s.isOpen() || root == null) {
            return;
        }
        try {
            s.send(root.toString());
        } catch (Throwable ignored) {}
    }

    private static float readRemoteFloat(JsonObject msg, String key) {
        if (!msg.has(key) || !msg.get(key).isJsonPrimitive()) {
            return Float.NaN;
        }
        com.google.gson.JsonPrimitive p = msg.getAsJsonPrimitive(key);
        try {
            if (p.isNumber()) {
                return p.getAsFloat();
            }
            if (p.isString()) {
                return Float.parseFloat(p.getAsString().trim());
            }
        } catch (Exception ignored) {}
        return Float.NaN;
    }

    private static int readRemoteInt(JsonObject msg, String key) {
        if (!msg.has(key) || !msg.get(key).isJsonPrimitive()) {
            return Integer.MIN_VALUE;
        }
        com.google.gson.JsonPrimitive p = msg.getAsJsonPrimitive(key);
        try {
            if (p.isNumber()) {
                return p.getAsInt();
            }
            if (p.isString()) {
                return Integer.parseInt(p.getAsString().trim(), 10);
            }
        } catch (Exception ignored) {}
        return Integer.MIN_VALUE;
    }

    private static void applyRemoteStatsFromMessage(JsonObject msg) {
        try {
            JsonObject hud = msg.has("hud") && msg.get("hud").isJsonObject() ? msg.getAsJsonObject("hud") : null;
            float h = readRemoteFloat(msg, "health");
            if (Float.isNaN(h)) {
                h = readRemoteFloat(msg, "hp");
            }
            if (Float.isNaN(h) && hud != null) {
                h = readRemoteFloat(hud, "health");
                if (Float.isNaN(h)) {
                    h = readRemoteFloat(hud, "hp");
                }
            }
            if (!Float.isNaN(h)) {
                remoteHealth = h;
            }
            float hm = readRemoteFloat(msg, "healthMax");
            if (Float.isNaN(hm)) {
                hm = readRemoteFloat(msg, "maxHealth");
            }
            if (Float.isNaN(hm) && hud != null) {
                hm = readRemoteFloat(hud, "healthMax");
                if (Float.isNaN(hm)) {
                    hm = readRemoteFloat(hud, "maxHealth");
                }
            }
            if (!Float.isNaN(hm)) {
                remoteHealthMax = Math.max(0.5f, hm);
            }
            int food = readRemoteInt(msg, "food");
            if (food == Integer.MIN_VALUE && hud != null) {
                food = readRemoteInt(hud, "food");
            }
            if (food != Integer.MIN_VALUE) {
                remoteFood = food;
            }
            float fs = readRemoteFloat(msg, "foodSat");
            if (Float.isNaN(fs) && hud != null) {
                fs = readRemoteFloat(hud, "foodSat");
            }
            if (!Float.isNaN(fs)) {
                remoteFoodSat = fs;
            }
            int ap = readRemoteInt(msg, "armorPoints");
            if (ap == Integer.MIN_VALUE && hud != null) {
                ap = readRemoteInt(hud, "armorPoints");
            }
            if (ap != Integer.MIN_VALUE) {
                remoteArmorPoints = ap;
            }
            int xl = readRemoteInt(msg, "xpLevel");
            if (xl == Integer.MIN_VALUE) {
                xl = readRemoteInt(msg, "level");
            }
            if (xl == Integer.MIN_VALUE && hud != null) {
                xl = readRemoteInt(hud, "xpLevel");
                if (xl == Integer.MIN_VALUE) {
                    xl = readRemoteInt(hud, "level");
                }
            }
            if (xl != Integer.MIN_VALUE) {
                remoteXpLevel = xl;
            }
            float xb = readRemoteFloat(msg, "xpBar");
            if (Float.isNaN(xb)) {
                xb = readRemoteFloat(msg, "experience");
            }
            if (Float.isNaN(xb) && hud != null) {
                xb = readRemoteFloat(hud, "xpBar");
                if (Float.isNaN(xb)) {
                    xb = readRemoteFloat(hud, "experience");
                }
            }
            if (!Float.isNaN(xb)) {
                remoteXpBar = Math.max(0.0f, Math.min(1.0f, xb));
            }
        } catch (Exception ignored) {}
    }

    private static void handleMessage(String raw) {
        JsonObject msg;
        try {
            JsonElement el = JSON_PARSER.parse(raw);
            if (el == null || !el.isJsonObject()) {
                return;
            }
            msg = el.getAsJsonObject();
        } catch (Exception e) {
            return;
        }
        if (!msg.has("type")) {
            return;
        }
        String type = msg.get("type").getAsString();

        switch (type) {
            case "hello_ok":
                relayHelloAcked = true;
                spectatorRelaySupported = msg.has("spectatorRelay") && msg.get("spectatorRelay").getAsBoolean();
                gameChatRelaySupported = msg.has("gameChatRelay") && msg.get("gameChatRelay").getAsBoolean();
                relayRateLimitBackoffUntilMs = 0L;
                authRateLimitBackoffUntilMs = 0L;
                authServiceBackoffUntilMs = 0L;
                consecutiveAuthServiceFailures = 0;
                minecraftSessionFailureBackoffUntilMs = 0L;
                lastConnectFailChatKey = "";
                keepAliveTicks = 0;
                if (announceRelayReconnectedOnNextHello) {
                    announceRelayReconnectedOnNextHello = false;
                    if (!chatRelayReadyOnHelloOk && !shouldSuppressRelaySessionStatusChat()) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "gomod Service: reconnected.");
                    }
                }
                if (chatRelayReadyOnHelloOk) {
                    chatRelayReadyOnHelloOk = false;
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "gomod Service ready.");
                }
                {
                    WebSocketClient s = socket;
                    if (s != null && s.isOpen()) {
                        JsonObject ping = new JsonObject();
                        ping.addProperty("type", "ping");
                        try {
                            s.send(ping.toString());
                        } catch (Throwable ignored) {}
                    }
                }
                {
                    String resume = resumeShareInvPeerAfterRelay;
                    if (resume != null && !resume.trim().isEmpty() && activeSessionId == null) {
                        ShareSessionMode resumeMode = resumeShareSessionModeAfterRelay != null
                                ? resumeShareSessionModeAfterRelay
                                : ShareSessionMode.SHARE_INV;
                        resumeShareInvPeerAfterRelay = null;
                        resumeShareSessionModeAfterRelay = ShareSessionMode.SHARE_INV;
                        WebSocketClient s2 = socket;
                        if (s2 != null && s2.isOpen()) {
                            JsonObject inv = new JsonObject();
                            inv.addProperty("type", "invite");
                            inv.addProperty("to", resume.trim());
                            inv.addProperty("mode", resumeMode.wireName());
                            try {
                                s2.send(inv.toString());
                            } catch (Throwable ignored) {}
                            if (!shouldSuppressRelaySessionStatusChat()) {
                                String disp = resolveMinecraftUsernameCasing(resume.trim());
                                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + resumeMode.displayName()
                                        + ": reconnected — sent invite to "
                                        + EnumChatFormatting.AQUA + disp + EnumChatFormatting.GRAY + " to restore sharing. "
                                        + EnumChatFormatting.YELLOW + "/" + resumeMode.commandName() + " accept "
                                        + EnumChatFormatting.GRAY + "if prompted.");
                            }
                        }
                    }
                }
                break;
            case "pong":
                break;
            case "gomod_presence":
                GomodPeerStandaloneCape.onRelayPeerMessage(msg);
                GomodPeerPregameDye.onRelayPeerMessage(msg);
                GomodPeerStandaloneCape.applyNetworkOverrides(Minecraft.getMinecraft());
                break;
            case "companion_positions":
                me.napim.gomod.features.GomodCompanionPositionSync.receive(msg);
                break;
            case "spectator_state":
                me.napim.gomod.features.SpectatorRelay.receive(msg);
                break;
            case "game_chat_message":
                me.napim.gomod.features.ForceGameChat.receive(msg);
                break;
            case "spectator_clear":
                me.napim.gomod.features.SpectatorRelay.removeSource(msg);
                break;
            case "error": {
                String m = msg.has("message") ? msg.get("message").getAsString() : "error";
                if ("rate_limited".equals(m)) {
                    noteRelayRateLimited(msg);
                    return;
                }
                if (isRelayClientUpdateNotice(msg, m)) {
                    if (relayClientUpdateNoticeShownThisSession || relayDisconnectChatIgnored) {
                        break;
                    }
                    relayClientUpdateNoticeShownThisSession = true;
                }
                if ("authentication_required".equals(m) || "authentication_expired".equals(m)) {
                    String relayUrl = ConfigManager.getShareInvRelayUrl();
                    long retryMs = GomodRelayAuth.noteWebSocketAuthenticationRejected(relayUrl);
                    relayHelloAcked = false;
                    delayReconnectAtLeastWithRateLimit(retryMs > 0L ? ticksForMillis(retryMs) : 20);
                    WebSocketClient current = socket;
                    if (current != null) {
                        try {
                            current.close();
                        } catch (Throwable ignored) {}
                    }
                    break;
                }
                String to = msg.has("to") && msg.get("to").isJsonPrimitive() ? msg.get("to").getAsString() : null;
                ShareSessionMode mode = messageMode(msg, lastSentInviteMode);
                clearLocalSessionAfterRelayReject(m);
                if (shouldSuppressShareSessionErrorChat(m, mode)) {
                    break;
                }
                // Keep the error color in the component style instead of embedding a raw
                // formatting code in one long string.  Minecraft's chat line wrapping can
                // reset those inline codes on the next line, which made wrapped update
                // notices appear white.
                ChatComponentText line = new ChatComponentText("");
                line.appendSibling(new ChatComponentText(Main.CHAT_PREFIX));
                String errorLabel = isRelayClientUpdateNotice(msg, m)
                        ? "gomod Social" : mode.displayName();
                appendShareSessionErrorChat(line, errorLabel
                        + ": " + formatShareSessionRelayError(m, to, mode));
                Main.sendChatMessage(line);
                break;
            }
            case "invite_sent":
                lastSentInviteMode = messageMode(msg, lastSentInviteMode);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + lastSentInviteMode.displayName()
                        + " invite sent. They have 5 minutes to /" + lastSentInviteMode.commandName() + " accept.");
                break;
            case "invite_in": {
                String id = msg.get("inviteId").getAsString();
                String from = msg.get("from").getAsString();
                if (isShareViewWire(msg)) {
                    declineInviteById(id, true,
                            EnumChatFormatting.GRAY + "Declined ShareView invite (feature removed).");
                    break;
                }
                if (!ConfigManager.isAcceptIncomingShareInv()) {
                    declineInviteById(id, true,
                            EnumChatFormatting.GRAY + "ShareInv invite blocked — enable in "
                                    + EnumChatFormatting.AQUA + "gomod Social"
                                    + EnumChatFormatting.GRAY + ".");
                    break;
                }
                ShareSessionMode inviteMode = messageMode(msg, ShareSessionMode.SHARE_INV);
                if (!ConfigManager.isGomodFriend(from)) {
                    WebSocketClient s = socket;
                    if (s != null && s.isOpen()) {
                        JsonObject o = new JsonObject();
                        o.addProperty("type", "decline");
                        o.addProperty("inviteId", id);
                        try {
                            s.send(o.toString());
                        } catch (Throwable ignored) {}
                    }
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Blocked "
                            + inviteMode.displayName() + " invite from non-friend "
                            + EnumChatFormatting.AQUA + from
                            + EnumChatFormatting.RED + ". Add with /gfriend add " + from + " if trusted.");
                    break;
                }
                pendingInviteId = id;
                pendingFrom = from;
                pendingInviteMode = inviteMode;
                playShareInvSound("note.pling", 1.0f, 1.5f);
                String fromDisplay = resolveMinecraftUsernameCasing(from);
                ChatComponentText line = new ChatComponentText("");
                line.appendSibling(new ChatComponentText(Main.CHAT_PREFIX));
                ChatComponentText nameLine = new ChatComponentText(fromDisplay);
                ChatStyle nameLineSt = new ChatStyle();
                nameLineSt.setColor(EnumChatFormatting.AQUA);
                nameLine.setChatStyle(nameLineSt);
                line.appendSibling(nameLine);
                // Newline on this sibling (not a lone "\n" piece) so hover/click regions line up with rendering.
                ChatComponentText inviteRest = new ChatComponentText(
                        " wants to share " + inviteMode.invitePlural() + ".\n");
                ChatStyle inviteRestSt = new ChatStyle();
                inviteRestSt.setColor(EnumChatFormatting.WHITE);
                inviteRest.setChatStyle(inviteRestSt);
                line.appendSibling(inviteRest);

                // [ACCEPT] · [DECLINE]; color via ChatStyle (no § in text); middot spacer; § reset bold if embedded.
                ChatComponentText accept = new ChatComponentText(EnumChatFormatting.BOLD + "[ACCEPT]" + EnumChatFormatting.RESET);
                ChatStyle accStyle = new ChatStyle();
                accStyle.setColor(EnumChatFormatting.GREEN);
                accStyle.setBold(false);
                accStyle.setUnderlined(false);
                accStyle.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + inviteMode.commandName() + " accept"));
                accStyle.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ChatComponentText(EnumChatFormatting.YELLOW + "Click to accept")));
                accept.setChatStyle(accStyle);
                line.appendSibling(accept);

                ChatComponentText sep = new ChatComponentText(CHAT_ACTION_DOT_GAP);
                ChatStyle sepStyle = new ChatStyle();
                sepStyle.setColor(EnumChatFormatting.DARK_GRAY);
                sepStyle.setBold(false);
                sepStyle.setUnderlined(false);
                sep.setChatStyle(sepStyle);
                line.appendSibling(sep);

                ChatComponentText decline = new ChatComponentText(EnumChatFormatting.BOLD + "[DECLINE]" + EnumChatFormatting.RESET);
                ChatStyle decStyle = new ChatStyle();
                decStyle.setColor(EnumChatFormatting.RED);
                decStyle.setBold(false);
                decStyle.setUnderlined(false);
                decStyle.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/" + inviteMode.commandName() + " decline"));
                decStyle.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        new ChatComponentText(EnumChatFormatting.YELLOW + "Click to decline")));
                decline.setChatStyle(decStyle);
                line.appendSibling(decline);

                ChatComponentText ttl = new ChatComponentText(" (5 min to accept)");
                ChatStyle ttlSt = new ChatStyle();
                ttlSt.setColor(EnumChatFormatting.GRAY);
                ttl.setChatStyle(ttlSt);
                line.appendSibling(ttl);
                Main.sendChatMessage(line);
                break;
            }
            case "invite_declined":
                lastSentInviteMode = messageMode(msg, lastSentInviteMode);
                playShareInvSound("random.break", 0.5f, 0.5f);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Your "
                        + lastSentInviteMode.displayName() + " invite was declined.");
                break;
            case "session_start": {
                final String startedSid = msg.get("sessionId").getAsString();
                ShareSessionMode sessionHint = acceptedInviteModeHint != ShareSessionMode.SHARE_INV
                        ? acceptedInviteModeHint
                        : lastSentInviteMode;
                ShareSessionMode startedMode = messageMode(msg, sessionHint);
                activeSessionId = startedSid;
                activePeerName = resolveMinecraftUsernameCasing(msg.get("peer").getAsString());
                activeSessionMode = startedMode;
                resumeShareInvPeerAfterRelay = null;
                resumeShareSessionModeAfterRelay = ShareSessionMode.SHARE_INV;
                lastStaleSessionErrorChatKey = "";
                lastStaleSessionErrorChatMs = 0L;
                acceptedInviteModeHint = ShareSessionMode.SHARE_INV;
                remoteSlots = emptySlots();
                remoteSelectedHotbarSlot = 0;
                resetRemoteStats();
                remotePeerUiState = -1;
                shareInvDebugLoggedThisSession = false;
                pendingSessionWelcomeChat = true;
                pendingWelcomeFallbackTicks = 0;
                clearPendingInvite();
                String role = msg.has("role") && msg.get("role").isJsonPrimitive()
                        ? msg.get("role").getAsString() : "";
                if (isShareViewWire(msg)) {
                    leaveSessionQuiet();
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                            + "ShareView session ended (feature removed).");
                    break;
                }
                boolean soundForThisRole = "host".equalsIgnoreCase(role);
                if (soundForThisRole) {
                    playShareInvSound("random.levelup", 1.0f, 1.6f);
                }
                break;
            }
            case "session_end": {
                boolean skipChat = suppressSessionEndChat;
                suppressSessionEndChat = false;
                String reason = msg.has("reason") && msg.get("reason").isJsonPrimitive()
                        ? msg.get("reason").getAsString() : "ended";
                ShareSessionMode endedMode = activeSessionMode;
                acceptedInviteModeHint = ShareSessionMode.SHARE_INV;
                resumeShareInvPeerAfterRelay = null;
                resumeShareSessionModeAfterRelay = ShareSessionMode.SHARE_INV;
                activeSessionId = null;
                activePeerName = null;
                activeSessionMode = null;
                remoteSlots = emptySlots();
                remoteSelectedHotbarSlot = 0;
                resetRemoteStats();
                remotePeerUiState = -1;
                if (!skipChat) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + formatSessionEndForUser(reason, endedMode));
                }
                shareInvDebugLoggedThisSession = false;
                pendingSessionWelcomeChat = false;
                pendingWelcomeFallbackTicks = 0;
                break;
            }
            case "inv_peer": {
                if (shareInvDebugToChat && !shareInvDebugLoggedThisSession) {
                    shareInvDebugLoggedThisSession = true;
                    StringBuilder dbg = new StringBuilder();
                    dbg.append("keys=[");
                    boolean first = true;
                    for (java.util.Map.Entry<String, JsonElement> e : msg.entrySet()) {
                        if (!first) {
                            dbg.append(',');
                        }
                        first = false;
                        dbg.append(e.getKey());
                    }
                    dbg.append("] ");
                    for (String k : new String[]{"from", "health", "hp", "healthMax", "maxHealth", "xpLevel", "level", "xpBar",
                            "experience", "selectedSlot"}) {
                        if (msg.has(k)) {
                            dbg.append(k).append('=').append(msg.get(k)).append(' ');
                        }
                    }
                    if (msg.has("hud") && msg.get("hud").isJsonObject()) {
                        dbg.append("hud=yes ");
                    } else {
                        dbg.append("hud=no ");
                    }
                    if (msg.has("items") && msg.get("items").isJsonObject()) {
                        JsonObject io = msg.getAsJsonObject("items");
                        dbg.append("items=").append(io.entrySet().size()).append("keys ");
                        if (io.has(ITEMS_EMBEDDED_SELECTED_SLOT)) {
                            dbg.append("embSel=").append(io.get(ITEMS_EMBEDDED_SELECTED_SLOT)).append(' ');
                        }
                    } else {
                        dbg.append("items=absent");
                    }
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.DARK_GRAY + "[ShareInv debug] first inv_peer: "
                            + EnumChatFormatting.WHITE + dbg.toString().trim());
                }
                if (msg.has("from") && msg.get("from").isJsonPrimitive()) {
                    String from = msg.get("from").getAsString();
                    if (from != null && !from.isEmpty()
                            && (activePeerName == null || from.equalsIgnoreCase(activePeerName))) {
                        activePeerName = resolveMinecraftUsernameCasing(from);
                    }
                }
                parsePeerUiStateFromInvPeer(msg);
                if (pendingSessionWelcomeChat && activeSessionId != null) {
                    String msgSid = msg.has("sessionId") && msg.get("sessionId").isJsonPrimitive()
                            ? msg.get("sessionId").getAsString() : null;
                    if (msgSid == null || msgSid.equals(activeSessionId)) {
                        pendingSessionWelcomeChat = false;
                        pendingWelcomeFallbackTicks = 0;
                        final String sidCheck = activeSessionId;
                        final ShareSessionMode welcomeMode = activeSessionMode;
                        final Minecraft wmc = Minecraft.getMinecraft();
                        if (wmc != null) {
                            wmc.addScheduledTask(() -> {
                                if (sidCheck == null || !sidCheck.equals(activeSessionId)) {
                                    return;
                                }
                                String peerDisplay = getPeerHudName();
                                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + activeSessionVerb(welcomeMode)
                                        + EnumChatFormatting.AQUA + peerDisplay + EnumChatFormatting.GREEN
                                        + ". /" + activeCommandName(welcomeMode) + " stop to end.");
                            });
                        } else {
                            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + activeSessionVerb(activeSessionMode)
                                    + EnumChatFormatting.AQUA + getPeerHudName() + EnumChatFormatting.GREEN
                                    + ". /" + activeCommandName(activeSessionMode) + " stop to end.");
                        }
                    }
                }
                int sslot = readRemoteInt(msg, "selectedSlot");
                if (sslot == Integer.MIN_VALUE && msg.has("hud") && msg.get("hud").isJsonObject()) {
                    sslot = readRemoteInt(msg.getAsJsonObject("hud"), "selectedSlot");
                }
                if (sslot != Integer.MIN_VALUE) {
                    remoteSelectedHotbarSlot = Math.max(0, Math.min(8, sslot));
                }
                applyRemoteStatsFromMessage(msg);
                if (msg.has("items") && msg.get("items").isJsonObject()) {
                    applyRemoteItems(msg.getAsJsonObject("items"));
                }
                break;
            }
            default:
                break;
        }
    }

    private static void applyRemoteItems(JsonObject itemsEl) {
        if (itemsEl.has(ITEMS_EMBEDDED_SELECTED_SLOT) && itemsEl.get(ITEMS_EMBEDDED_SELECTED_SLOT).isJsonPrimitive()) {
            try {
                int emb = itemsEl.getAsJsonPrimitive(ITEMS_EMBEDDED_SELECTED_SLOT).getAsInt();
                remoteSelectedHotbarSlot = Math.max(0, Math.min(8, emb));
            } catch (Exception ignored) {}
        }
        ItemStack[] next = emptySlots();
        for (Map.Entry<String, JsonElement> e : itemsEl.entrySet()) {
            try {
                if (ITEMS_EMBEDDED_SELECTED_SLOT.equals(e.getKey())) {
                    continue;
                }
                int slot = Integer.parseInt(e.getKey());
                if (slot < 0 || slot >= INV_SLOTS) {
                    continue;
                }
                JsonArray a = e.getValue().getAsJsonArray();
                int id = a.get(0).getAsInt();
                int cnt = a.get(1).getAsInt();
                int dmg = a.get(2).getAsInt();
                Item it = Item.getItemById(id);
                if (it != null && cnt > 0) {
                    next[slot] = new ItemStack(it, cnt, dmg);
                }
            } catch (Exception ignored) {}
        }
        remoteSlots = next;
    }

}
