package me.napim.gomod.features;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.command.PeeCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import net.minecraft.entity.player.EntityPlayer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
/**
 * Cross-server chat client. Polls a relay endpoint and posts messages.
 * Relay polling is always subscribed from {@link me.napim.gomod.Main} so screenshots and friend
 * requests work without running {@code /gchat on}.
 */
public class GlobalChatClient {
    private static final Gson GSON = new Gson();

    private volatile long lastMessageTs = 0L;
    /** Cursor for relay poll; kept separate so presence pings do not hide friend DMs. */
    private volatile long lastRelayPollTs = 0L;
    /** Separate cursor so frequently refreshed presence records cannot hide short-lived cosmetic events. */
    private volatile long lastPeeEventTs = 0L;
    /** Opaque relay cursors for large snapshots; absent until the relay returns each cursor. */
    private volatile String friendsSnapshotRevision = "";
    private volatile String gomodPeersSnapshotRevision = "";
    private volatile String lunarFullAccessSnapshotRevision = "";
    private volatile String presenceSnapshotRevision = "";
    /** Prevents the first relay snapshot from replaying historical Public-message toasts. */
    private boolean completedInitialPoll;
    private int tickCounter = 0;
    /** Coalesces Forge/Weave tick events with the vanilla runTick fallback. */
    private volatile long lastTickDispatchMs;
    private static final int RELAY_CONNECT_MS = 3500;
    private static final int RELAY_READ_MS = 12000;
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private boolean hadPlayerThisSession;
    private Object lastPollWorld;
    private String lastPollServer = "";
    private volatile boolean immediatePollRequested;
    private final ExecutorService relayExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "gomod-global-chat-relay");
            t.setDaemon(true);
            return t;
        }
    });

    /** Enables global text chat lines in addition to relay polling (screenshots / friends). */
    public void start() {
        ConfigManager.setGlobalChatEnabled(true);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Global chat enabled on channel '" + ConfigManager.getGlobalChatChannel() + "'.");
    }

    public void stop() {
        ConfigManager.setGlobalChatEnabled(false);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Global chat disabled (relay inbox still polls for screenshots and friends).");
    }

    /** Re-poll relay on the next tick (e.g. after standalone cape wear changes). */
    public void requestImmediatePoll() {
        immediatePollRequested = true;
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        long _profT = FeatureProfiler.begin("GlobalChatClient");
        try {
        long dispatchNow = System.currentTimeMillis();
        if (dispatchNow - lastTickDispatchMs < 25L) {
            return;
        }
        lastTickDispatchMs = dispatchNow;
        Minecraft mc = Minecraft.getMinecraft();
        GomodAppearanceLongPoll.tick(mc);
        if (!GomodSocialPresence.shouldRelayPoll(mc)) {
            return;
        }
        PlayerStandaloneCape.refreshRelayWearIfNeeded();
        String currentServer = GomodSocialPresence.resolveLocalServer(mc);
        if (mc.theWorld != lastPollWorld || (currentServer != null && !currentServer.equalsIgnoreCase(lastPollServer))) {
            lastPollWorld = mc.theWorld;
            lastPollServer = currentServer == null ? "" : currentServer;
            requestImmediatePoll();
        }
        if (mc.thePlayer != null && !hadPlayerThisSession) {
            hadPlayerThisSession = true;
            GomodUpdateChecker.onFirstWorldEntry();
        }

        tickCounter++;
        GomodSocialServiceSync.syncBackground(mc);
        int pollEvery = Math.max(1, ConfigManager.getGlobalChatPollSeconds()) * 20; // ticks
        if (!immediatePollRequested && tickCounter % pollEvery != 0) return;

        if (!pollInFlight.compareAndSet(false, true)) {
            return;
        }
        immediatePollRequested = false;
        relayExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final java.util.UUID pollOwner = LunarFitSharing.account();
                    final String response = pollMessages();
                    if (response != null && !response.isEmpty()) {
                        GomodServiceStatus.markHttpRelaySuccess();
                        final String toProcess = response;
                        final Minecraft pollMc = Minecraft.getMinecraft();
                        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                            @Override
                            public void run() {
                                if (pollOwner == null || !pollOwner.equals(LunarFitSharing.account())) return;
                                DenickCrowdSync.maybePullAfterRelayPoll(pollMc);
                                processPolledMessages(toProcess);
                            }
                        });
                    } else {
                        GomodServiceStatus.markHttpRelayFailure("empty response");
                    }
                } catch (Exception e) {
                    GomodServiceStatus.markHttpRelayFailure(e.getMessage());
                } finally {
                    pollInFlight.set(false);
                }
            }
        });
        } finally { FeatureProfiler.end("GlobalChatClient", _profT); }
    }

    /** Vanilla runTick fallback for launch paths that do not post Weave/Forge tick events on menus. */
    public void pollGuaranteedRuntimeTick() {
        onTick(null);
    }

    public void send(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        String player = GomodSocialPresence.sessionPlayerName(mc);
        if (player.isEmpty()) return;
        String server = GomodSocialPresence.resolveLocalServer(mc);
        String channel = ConfigManager.getGlobalChatChannel();
        String endpoint = ConfigManager.getGlobalChatEndpoint();

        final String senderPlayer = player;
        final String senderServer = server;
        final String senderChannel = channel;
        final String senderEndpoint = endpoint;
        final String senderMessage = message;
        relayExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "send");
                    payload.addProperty("channel", senderChannel);
                    payload.addProperty("sender", senderPlayer);
                    payload.addProperty("server", senderServer);
                    payload.addProperty("message", senderMessage);

                    post(senderEndpoint, payload);
                } catch (Exception e) {
                    Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to send global chat message.");
                        }
                    });
                }
            }
        });
    }

    /** Announces the local-only command to gomod clients currently playing on the same server. */
    public void broadcastPee() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        String player = mc.thePlayer.getName();
        String server = GomodSocialPresence.resolveLocalServer(mc);
        if (player == null || player.trim().isEmpty() || server == null
                || GomodSocialPresence.SERVER_SINGLEPLAYER.equalsIgnoreCase(server)
                || GomodSocialPresence.SERVER_MAIN_MENU.equalsIgnoreCase(server)) {
            return;
        }

        final String senderPlayer = player.trim();
        final String senderServer = server;
        final String senderEndpoint = ConfigManager.getGlobalChatEndpoint();
        relayExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", "pee_event");
                    payload.addProperty("sender", senderPlayer);
                    payload.addProperty("server", senderServer);
                    GomodSocialIdentity.attachRelayPlayer(payload, Minecraft.getMinecraft());
                    post(senderEndpoint, payload);
                } catch (Exception ignored) {
                    // The command remains usable locally when the cosmetic relay is unavailable.
                }
            }
        });
    }

    private String pollMessages() {
        String endpoint = ConfigManager.getGlobalChatEndpoint();
        String channel = ConfigManager.getGlobalChatChannel();

        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("type", "poll");
            payload.addProperty("relaySnapshotProtocol", 1);
            payload.addProperty("channel", channel);
            payload.addProperty("since", lastRelayPollTs);
            payload.addProperty("peeSince", lastPeeEventTs);
            addSnapshotRevision(payload, "friendsSnapshotRevision", friendsSnapshotRevision);
            addSnapshotRevision(payload, "gomodPeersOnServerRevision", gomodPeersSnapshotRevision);
            addSnapshotRevision(payload, "lunarFullAccessPeersOnServerRevision", lunarFullAccessSnapshotRevision);
            addSnapshotRevision(payload, "presenceSnapshotRevision", presenceSnapshotRevision);
            GomodSocialIdentity.attachPoll(payload);
            LunarOnlineStatus.attachUsageStatus(payload);
            // Service demand also exists on Lunar's main/menu screens, before
            // a world player (and before the FullAccess Assets socket) exists.
            GomodPeerLunarFullAccess.attachLocalState(payload);
            payload.addProperty("threadsSince", GomodSocialForums.getLastThreadEventTs());
            Minecraft pollMc = Minecraft.getMinecraft();
            GomodSocialIdentity.attachRelayPlayer(payload, pollMc);
            if (pollMc != null && pollMc.thePlayer != null) {
                GomodPeerStandaloneCape.attachLocalWearTo(payload);
                GomodPeerPregameDye.attachLocalStateTo(payload);
                GomodPeerDisguise.attachLocalState(payload);
                RemoteDisguiseManager.attachCapeTargets(payload);
                GomodPeerDisguise.attachTargetCapeTargets(payload);
                attachVisiblePlayers(payload, pollMc);
            }
            String playerName = GomodSocialPresence.sessionPlayerName(pollMc);
            if (playerName != null && !playerName.isEmpty()) {
                payload.addProperty("player", playerName);
                payload.addProperty("appearOffline", GomodSocialStore.isAppearOffline());
                String server = GomodSocialPresence.resolveLocalServer(pollMc);
                if (server != null) {
                    payload.addProperty("server", server);
                }
            }
            String body = post(endpoint, payload);
            if (body != null && !body.isEmpty()) {
                GomodServiceStatus.markHttpRelaySuccess();
            } else {
                clearSnapshotRevisions();
            }
            return body;
        } catch (Exception e) {
            clearSnapshotRevisions();
            GomodServiceStatus.markHttpRelayFailure(e.getMessage());
        }
        return null;
    }

    private void clearSnapshotRevisions() {
        friendsSnapshotRevision = "";
        gomodPeersSnapshotRevision = "";
        lunarFullAccessSnapshotRevision = "";
        presenceSnapshotRevision = "";
    }

    private void processPolledMessages(String response) {
        if (response == null || response.isEmpty()) return;
        try {
            JsonObject obj = GSON.fromJson(response, JsonObject.class);
            if (obj == null) {
                return;
            }
            final boolean notifyPublicMessages = completedInitialPoll;
            completedInitialPoll = true;
            GomodUpdateChecker.onPollResponse(obj);
            if (obj.has("socialAdmin") && !obj.get("socialAdmin").isJsonNull()) {
                GomodServiceStatus.setSocialAdmin(obj.get("socialAdmin").getAsBoolean());
            }
            if (obj.has("friendsSnapshot") && obj.get("friendsSnapshot").isJsonArray()) {
                ConfigManager.applyFriendsFromService(obj.getAsJsonArray("friendsSnapshot"));
            }
            GomodFriendRelay.postFriendsBootstrapIfNeeded();
            boolean receivedThreadSnapshot = obj.has("threadsSnapshot") && obj.get("threadsSnapshot").isJsonObject();
            if (receivedThreadSnapshot) {
                GomodSocialForums.ensureAccount(Minecraft.getMinecraft());
                GomodSocialForums.applyServiceSnapshot(obj.getAsJsonObject("threadsSnapshot"));
            }
            if (obj.has("threadEvents") && obj.get("threadEvents").isJsonArray()) {
                GomodSocialForums.ensureAccount(Minecraft.getMinecraft());
                JsonArray threadEv = obj.getAsJsonArray("threadEvents");
                for (int ti = 0; ti < threadEv.size(); ti++) {
                    if (threadEv.get(ti).isJsonObject()) {
                        GomodSocialForums.applyThreadEvent(threadEv.get(ti).getAsJsonObject(),
                                !receivedThreadSnapshot);
                    }
                }
            }
            GomodPeerStandaloneCape.onPollResponse(obj);
            GomodPeerDisguise.onTargetCapesPollResponse(obj);
            GomodPeerPregameDye.onPollResponse(obj);
            if (obj.has("peeEvents") && obj.get("peeEvents").isJsonArray()) {
                JsonArray peeEvents = obj.getAsJsonArray("peeEvents");
                for (int i = 0; i < peeEvents.size(); i++) {
                    if (!peeEvents.get(i).isJsonObject()) continue;
                    JsonObject event = peeEvents.get(i).getAsJsonObject();
                    long ts = event.has("ts") ? event.get("ts").getAsLong() : System.currentTimeMillis();
                    if (ts > lastPeeEventTs) lastPeeEventTs = ts;
                    String sender = safeGet(event, "sender");
                    String senderUuid = safeGet(event, "playerUuid");
                    if (senderUuid.isEmpty()) {
                        senderUuid = safeGet(event, "mcProfileUuid");
                    }
                    if (!isSelfPlayerName(sender) && !isSelfPlayerUuid(senderUuid)) {
                        PeeEffectManager.activateRemote(sender, senderUuid, ts);
                        PeeCommand.showRemotePeeMessage(sender, senderUuid);
                    }
                }
            }
            if (!obj.has("messages")) {
                GomodPeerStandaloneCape.applyNetworkOverrides(Minecraft.getMinecraft());
                return;
            }
            JsonArray arr = obj.getAsJsonArray("messages");
            if (obj.has("presenceSnapshot") && obj.get("presenceSnapshot").isJsonArray()) {
                arr.addAll(obj.getAsJsonArray("presenceSnapshot"));
            }
            if (obj.has("presenceHeartbeats") && obj.get("presenceHeartbeats").isJsonArray()) {
                arr.addAll(obj.getAsJsonArray("presenceHeartbeats"));
            }
            for (int i = 0; i < arr.size(); i++) {
                JsonObject m = arr.get(i).getAsJsonObject();
                long ts = m.has("ts") ? m.get("ts").getAsLong() : System.currentTimeMillis();
                String sender = safeGet(m, "sender");
                String server = safeGet(m, "server");
                String msg = safeGet(m, "message");
                String kind = safeGet(m, "kind");
                if (ts > lastRelayPollTs) lastRelayPollTs = ts;

                rememberRelaySocialIds(m, sender);

                if ("temp_group_invite".equalsIgnoreCase(kind)) {
                    String from = safeGet(m, "from");
                    if (from.isEmpty()) from = sender;
                    if (m.has("group") && m.get("group").isJsonObject()) {
                        GomodSocialTempGroups.Group group =
                                GomodSocialTempGroups.applyInvite(m.getAsJsonObject("group"));
                        if (group != null) {
                            IncomingPublicPingOverlay.showGroup(from, group.name, group.id);
                        }
                    }
                } else if ("friend_dm".equalsIgnoreCase(kind)) {
                    String text = msg;
                    if (text.isEmpty()) {
                        text = safeGet(m, "message");
                    }
                    String from = safeGet(m, "from");
                    if (from.isEmpty()) {
                        from = sender;
                    }
                    String friendKey = FriendListScanner.matchFriendSender(from);
                    if (friendKey == null && ConfigManager.isGomodFriend(from)) {
                        friendKey = ConfigManager.getGomodFriendDisplayName(from);
                    }
                    if (friendKey != null || ConfigManager.isAcceptIncomingNonFriendMessages()) {
                        GomodSocialChat.handleIncomingDm(from, text, ts);
                    }
                } else if ("cape_accept_receipt".equalsIgnoreCase(kind)) {
                    String text = msg;
                    if (text.isEmpty()) {
                        text = safeGet(m, "message");
                    }
                    String from = safeGet(m, "from");
                    if (from.isEmpty()) {
                        from = sender;
                    }
                    String friendKey = FriendListScanner.matchFriendSender(from);
                    if (friendKey == null && ConfigManager.isGomodFriend(from)) {
                        friendKey = ConfigManager.getGomodFriendDisplayName(from);
                    }
                    if (friendKey != null) {
                        GomodSocialChat.handleIncomingCapeAcceptReceipt(from, text, ts,
                                safeGet(m, "capeLabel"), safeGet(m, "capeImageBase64"),
                                m.has("automatic") && !m.get("automatic").isJsonNull() && m.get("automatic").getAsBoolean());
                    }
                } else if ("presence".equalsIgnoreCase(kind)) {
                    String from = safeGet(m, "from");
                    if (from.isEmpty()) {
                        from = sender;
                    }
                    GomodPeerStandaloneCape.onRelayPeerMessage(m);
                    GomodPeerPregameDye.onRelayPeerMessage(m);
                    if (ConfigManager.isGomodFriend(from)) {
                        GomodSocialChat.handlePresence(from, ts, safeGet(m, "server"));
                    }
                } else if ("cape_design".equalsIgnoreCase(kind)) {
                    String capeFrom = sender;
                    if (capeFrom.isEmpty()) {
                        capeFrom = safeGet(m, "from");
                    }
                    String friendKey = FriendListScanner.matchFriendSender(capeFrom);
                    if (friendKey == null && ConfigManager.isGomodFriend(capeFrom)) {
                        friendKey = ConfigManager.getGomodFriendDisplayName(capeFrom);
                    }
                    if (friendKey == null) {
                        continue;
                    }
                    String capeLabel = safeGet(m, "capeLabel");
                    String imageBase64 = safeGet(m, "imageBase64");
                    IncomingCapeDesigns.receive(friendKey, capeLabel, imageBase64);
                } else if ("screenshot".equalsIgnoreCase(kind)) {
                    if (!ConfigManager.isAcceptIncomingFriendScreenshots()) {
                        continue;
                    }
                    String shotFrom = sender;
                    if (shotFrom.isEmpty()) {
                        shotFrom = safeGet(m, "from");
                    }
                    String friendKey = FriendListScanner.matchFriendSender(shotFrom);
                    if (friendKey == null && ConfigManager.isGomodFriend(shotFrom)) {
                        friendKey = ConfigManager.getGomodFriendDisplayName(shotFrom);
                    }
                    if (friendKey == null) {
                        continue;
                    }
                    String fileName = safeGet(m, "fileName");
                    String mimeType = safeGet(m, "mimeType");
                    String imageBase64 = safeGet(m, "imageBase64");
                    IncomingScreenshotManager.handleIncoming(shotFrom, fileName, mimeType, imageBase64, ts);
                    playIncomingScreenshotSound();
                    ChatComponentText imgLine = new ChatComponentText(Main.CHAT_PREFIX + EnumChatFormatting.LIGHT_PURPLE
                            + "[IMG] " + EnumChatFormatting.YELLOW + shotFrom + EnumChatFormatting.WHITE
                            + " sent you a screenshot.");
                    ChatStyle st = new ChatStyle();
                    st.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gcopenimg"));
                    st.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(
                            EnumChatFormatting.GRAY + "Click to open this screenshot")));
                    imgLine.setChatStyle(st);
                    Main.sendChatMessage(imgLine);
                } else if ("friend_request".equalsIgnoreCase(kind)) {
                    String from = safeGet(m, "from");
                    if (from.isEmpty()) {
                        from = sender;
                    }
                    if (isSelfPlayerName(from)) {
                        continue;
                    }
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc != null) {
                        GomodSocialAccount.refresh(mc);
                    }
                    from = GomodSocialRequests.resolveIncomingPeer(from);
                    if (from.isEmpty()) {
                        continue;
                    }
                    if (!IncomingFriendRequestOverlay.show(from)) {
                        // The relay may expose the same item through both the name and Social-ID inbox.
                        // Only the first copy should play a sound, show a toast, or print to chat.
                        continue;
                    }
                    ChatComponentText line = new ChatComponentText(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                            + "Friend request from " + EnumChatFormatting.AQUA + from + EnumChatFormatting.GREEN + " ");
                    ChatComponentText view = new ChatComponentText(EnumChatFormatting.YELLOW + "[View]");
                    ChatStyle viewStyle = new ChatStyle();
                    viewStyle.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gfriend gui requests"));
                    viewStyle.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new ChatComponentText(EnumChatFormatting.YELLOW + "Open gomod Social Requests")));
                    view.setChatStyle(viewStyle);
                    line.appendSibling(view);
                    Main.sendChatMessage(line);
                } else if ("friend_accepted".equalsIgnoreCase(kind)) {
                    String cp = safeGet(m, "counterparty");
                    if (cp.isEmpty()) {
                        cp = safeGet(m, "from");
                    }
                    if (cp.isEmpty()) {
                        cp = sender;
                    }
                    GomodSocialRequests.removeIncoming(cp);
                    GomodSocialRequests.cancelOutgoing(cp);
                    ConfigManager.addGomodFriend(cp);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "You are now friends with "
                            + EnumChatFormatting.AQUA + cp + EnumChatFormatting.GREEN + ".");
                } else if ("friend_removed".equalsIgnoreCase(kind)) {
                    String cp = safeGet(m, "counterparty");
                    if (cp.isEmpty()) {
                        cp = safeGet(m, "from");
                    }
                    if (cp.isEmpty()) {
                        cp = sender;
                    }
                    if (ConfigManager.removeGomodFriend(cp)) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + EnumChatFormatting.AQUA + cp
                                + EnumChatFormatting.GRAY + " removed you as a gomod friend.");
                    }
                } else {
                    if (msg != null && !msg.isEmpty()) {
                        GomodSocialPublicChat.ingest(ts, sender, server, msg, notifyPublicMessages);
                    }
                    if (!ConfigManager.isGlobalChatEnabled()) {
                        continue;
                    }
                    if (ts > lastMessageTs) {
                        lastMessageTs = ts;
                    }
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "[GC] " + EnumChatFormatting.YELLOW + sender +
                            EnumChatFormatting.GRAY + "@" + server + EnumChatFormatting.WHITE + ": " + msg);
                }
            }
            GomodPeerStandaloneCape.applyNetworkOverrides(Minecraft.getMinecraft());
            friendsSnapshotRevision = readSnapshotRevision(obj, "friendsSnapshotRevision", friendsSnapshotRevision);
            gomodPeersSnapshotRevision = readSnapshotRevision(
                    obj, "gomodPeersOnServerRevision", gomodPeersSnapshotRevision);
            lunarFullAccessSnapshotRevision = readSnapshotRevision(
                    obj, "lunarFullAccessPeersOnServerRevision", lunarFullAccessSnapshotRevision);
            presenceSnapshotRevision = readSnapshotRevision(
                    obj, "presenceSnapshotRevision", presenceSnapshotRevision);
        } catch (Exception ignored) {}
    }

    private static void playIncomingScreenshotSound() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.getSoundHandler() != null) {
                mc.getSoundHandler().playSound(
                        PositionedSoundRecord.create(new ResourceLocation("note.pling"), 1.8f));
            }
        } catch (Exception ignored) {}
    }

    private static void rememberRelaySocialIds(JsonObject m, String sender) {
        if (m == null) {
            return;
        }
        String fromSocialId = m.has("fromSocialId") && !m.get("fromSocialId").isJsonNull()
                ? m.get("fromSocialId").getAsString() : "";
        if (fromSocialId.isEmpty()) {
            fromSocialId = m.has("senderSocialId") && !m.get("senderSocialId").isJsonNull()
                    ? m.get("senderSocialId").getAsString() : "";
        }
        String fromName = m.has("from") && !m.get("from").isJsonNull() ? m.get("from").getAsString() : sender;
        if (fromName.isEmpty()) {
            fromName = sender;
        }
        if (!fromSocialId.isEmpty() && !fromName.isEmpty()) {
            ConfigManager.rememberFriendSocialId(fromName, fromSocialId);
        }
    }

    private String safeGet(JsonObject m, String key) {
        return m != null && m.has(key) && !m.get(key).isJsonNull() ? m.get(key).getAsString() : "";
        
    }

    private static boolean isSelfPlayerName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        String self = GomodSocialPresence.sessionPlayerName(mc);
        return !self.isEmpty() && name.equalsIgnoreCase(self);
    }

    public static void attachVisiblePlayers(JsonObject payload, Minecraft mc) {
        if (payload == null || mc == null || mc.theWorld == null || mc.theWorld.playerEntities == null) {
            return;
        }
        JsonArray visible = new JsonArray();
        int added = 0;
        for (Object value : mc.theWorld.playerEntities) {
            if (!(value instanceof EntityPlayer)) {
                continue;
            }
            EntityPlayer player = (EntityPlayer) value;
            String name = player.getName();
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            visible.add(new JsonPrimitive(name.trim()));
            if (++added >= 160) {
                break;
            }
        }
        if (visible.size() > 0) {
            payload.add("visiblePlayers", visible);
        }
    }

    private static boolean isSelfPlayerUuid(String uuid) {
        if (uuid == null || uuid.trim().isEmpty()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        String selfUuid = GomodSocialIdentity.profileUuidUndashed(mc);
        if (selfUuid.isEmpty()) {
            return false;
        }
        return uuid.trim().replace("-", "").equalsIgnoreCase(selfUuid);
    }

    private String post(String urlStr, JsonObject payload) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept-Encoding", "gzip");
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
        if (code >= 200 && code < 300) {
            InputStream input = conn.getInputStream();
            if ("gzip".equalsIgnoreCase(conn.getContentEncoding())) {
                input = new GZIPInputStream(input);
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return sb.toString();
            }
        }
        throw new Exception("HTTP " + code);
    }

    private static void addSnapshotRevision(JsonObject payload, String key, String revision) {
        if (revision != null && revision.matches("[a-f0-9]{64}")) {
            payload.addProperty(key, revision);
        }
    }

    private static String readSnapshotRevision(JsonObject response, String key, String previous) {
        if (response == null || !response.has(key) || !response.get(key).isJsonPrimitive()) {
            return previous;
        }
        try {
            String revision = response.get(key).getAsString();
            return revision.matches("[a-f0-9]{64}") ? revision : previous;
        } catch (RuntimeException ignored) {
            return previous;
        }
    }
}


