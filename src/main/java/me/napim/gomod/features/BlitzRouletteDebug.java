package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.GomodVersion;
import me.napim.gomod.Main;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S45PacketTitle;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Opt-in packet observation; never predicts a target or changes the server/client game state. */
public final class BlitzRouletteDebug {
    private static final long LIMIT_MS = 900000L;
    private static volatile Recording recording;
    private static volatile String lastReport = "none";
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "gomod-roulette-debug-save"); thread.setDaemon(true); return thread;
    });

    private BlitzRouletteDebug() { }

    public static String start() {
        if (recording != null) return "Roulette debug is already recording. " + status();
        Recording next = new Recording();
        Minecraft mc = Minecraft.getMinecraft();
        next.world = mc == null ? null : mc.theWorld;
        next.observe(snapshot(next, mc));
        recording = next;
        return "Roulette debug armed for 15 minutes. Use it before Roulette starts; each result saves automatically."
                + " /roulettedebug status or /roulettedebug stop. No target can be predicted from this capture.";
    }

    public static String stop() {
        Recording current = recording;
        if (current == null) return "Roulette debug is off. Last report: " + lastReport;
        close(current, "manual stop", Minecraft.getMinecraft());
        return "Roulette debug stopped; saving the report.";
    }

    public static String status() {
        Recording current = recording;
        if (current == null) return "Roulette debug is off. Last report: " + lastReport;
        return "Roulette debug: " + current.elapsed() / 1000 + "s / 900s; " + current.trace.roundCount()
                + " rolls, " + current.trace.winnerCount() + " confirmed winners. Last report: " + lastReport;
    }

    /** Called before gomod's shared PacketEvent handlers; copy text now, process it on client ticks. */
    public static void recordInboundPacket(Packet<?> packet) {
        Recording current = recording;
        if (current == null || packet == null) return;
        long at = current.elapsed();
        try {
            if (packet instanceof S45PacketTitle) {
                S45PacketTitle title = (S45PacketTitle) packet;
                String type = title.getType().name();
                String raw = title.getMessage() == null ? "" : title.getMessage().getFormattedText();
                if ("TIMES".equals(type)) raw = "fadeIn=" + title.getFadeInTime() + " stay=" + title.getDisplayTime() + " fadeOut=" + title.getFadeOutTime();
                current.offer(new BlitzRouletteTrace.Event(at, type, raw));
            } else if (packet instanceof S02PacketChat) {
                S02PacketChat chat = (S02PacketChat) packet;
                if (chat.getType() == 2 || chat.getChatComponent() == null) return;
                String raw = chat.getChatComponent().getFormattedText();
                if (BlitzRouletteTrace.isRelevantChat(raw)) current.offer(new BlitzRouletteTrace.Event(at, "CHAT", raw));
            } else if (packet instanceof S38PacketPlayerListItem) {
                S38PacketPlayerListItem list = (S38PacketPlayerListItem) packet;
                if (list.getAction() == S38PacketPlayerListItem.Action.UPDATE_LATENCY) return;
                StringBuilder raw = new StringBuilder("action=" + list.getAction() + " rows=");
                int count = 0;
                for (S38PacketPlayerListItem.AddPlayerData row : list.getEntries()) {
                    if (count++ >= 128) { raw.append(" [rows truncated]"); break; }
                    GameProfile profile = row.getProfile();
                    raw.append(" [").append(profile == null ? "?" : profile.getName()).append('|')
                            .append(profile == null ? "?" : profile.getId()).append('|').append(row.getGameMode());
                    if (row.getDisplayName() != null) raw.append('|').append(row.getDisplayName().getFormattedText());
                    raw.append(']');
                    if (raw.length() > 8000) { raw.append(" [rows truncated]"); break; }
                }
                current.offer(new BlitzRouletteTrace.Event(at, "TAB", raw.toString()));
            } else if (packet instanceof S01PacketJoinGame || packet instanceof S07PacketRespawn) {
                current.offer(new BlitzRouletteTrace.Event(at, "CONNECTION", packet instanceof S01PacketJoinGame ? "join-game" : "respawn/dimension"));
            }
        } catch (Throwable error) {
            current.offer(new BlitzRouletteTrace.Event(at, "HOOK_ERROR", error.getClass().getSimpleName()));
        }
    }

    /** Runs even without a world, so disconnects and the capture limit still save. */
    public static void clientTick(Minecraft mc) {
        Recording current = recording;
        if (current == null) return;
        pump(current, mc);
        long now = current.elapsed();
        current.trace.tick(now);
        if (now >= LIMIT_MS || current.trace.full || current.dropped > 0) {
            close(current, now >= LIMIT_MS ? "15-minute limit" : "bounded buffer limit; inspect dropped_events", mc);
            return;
        }
        int finished = current.trace.finishedCount();
        if (finished > current.savedFinished) {
            current.savedFinished = finished;
            save(current, "round settled; capture remains armed");
        }
    }

    private static void pump(Recording current, Minecraft mc) {
        for (BlitzRouletteTrace.Event event : current.drain()) current.trace.accept(event, current.prior(event.ms));
        World world = mc == null ? null : mc.theWorld;
        if (world != current.world) {
            current.trace.worldChanged(current.elapsed(), world == null ? "disconnected" : "world=" + System.identityHashCode(world));
            current.world = world; current.observations.clear(); current.lastSnapshotMs = -500;
        }
        if (current.elapsed() - current.lastSnapshotMs >= 500) current.observe(snapshot(current, mc));
    }

    private static void close(Recording current, String reason, Minecraft mc) {
        synchronized (current) { current.closed = true; }
        if (recording == current) recording = null;
        pump(current, mc);
        current.trace.tick(current.elapsed());
        save(current, reason);
    }

    private static BlitzRouletteTrace.Snapshot snapshot(Recording current, Minecraft mc) {
        long observed = current.elapsed();
        List<BlitzRouletteTrace.Player> players = new ArrayList<BlitzRouletteTrace.Player>();
        if (mc == null || mc.theWorld == null || mc.getNetHandler() == null) {
            return new BlitzRouletteTrace.Snapshot(observed, "no world", players, "roster unavailable");
        }
        String issue = "", context = "";
        try {
            context = "world=" + System.identityHashCode(mc.theWorld) + " " + GameContext.explainDetection(mc);
            Map<String, Integer> kills = new TreeMap<String, Integer>(String.CASE_INSENSITIVE_ORDER);
            kills.putAll(KillCounter.getAllKillCounts());
            Map<UUID, EntityPlayer> entities = new HashMap<UUID, EntityPlayer>();
            Map<UUID, Integer> entityOrder = new HashMap<UUID, Integer>();
            int index = 0;
            for (EntityPlayer entity : mc.theWorld.playerEntities) {
                if (entity != null) { entities.put(entity.getUniqueID(), entity); entityOrder.put(entity.getUniqueID(), index); }
                index++;
            }
            int tabIndex = 0;
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                if (players.size() >= 128) { issue = "roster truncated at 128 rows"; break; }
                GameProfile profile = info == null ? null : RemoteDisguiseManager.getSourceProfile(info);
                if (profile == null || profile.getName() == null) { issue = "one or more source profiles unavailable"; continue; }
                String name = profile.getName(); UUID uuid = profile.getId();
                Integer tracked = kills.get(name);
                if (tracked == null) for (String alias : KillCounter.getLookupNamesForResults(name)) {
                    tracked = kills.get(alias); if (tracked != null) break;
                }
                EntityPlayer entity = entities.get(uuid);
                String team = ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), name);
                boolean dead = KillCounter.isPlayerDeadOrSpectatorFromTab(name) || (entity != null && entity.isDead);
                players.add(new BlitzRouletteTrace.Player(name, uuid == null ? "unknown" : uuid.toString(),
                        info.getGameType() == null ? "unknown" : info.getGameType().name(), team, tracked, dead,
                        tabIndex++, entityOrder.containsKey(uuid) ? entityOrder.get(uuid) : -1,
                        entity == null ? null : entity.getHealth()));
            }
        } catch (Throwable error) { issue = "partial snapshot: " + error.getClass().getSimpleName(); }
        return new BlitzRouletteTrace.Snapshot(observed, context, players, issue);
    }

    private static void save(Recording current, String reason) {
        final String report = current.trace.report(current.environment, reason, current.elapsed(), current.dropped);
        WRITER.execute(() -> {
            try {
                File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
                Files.createDirectories(directory.toPath());
                File file = Files.createTempFile(directory.toPath(), "roulette_"
                        + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date()) + "_", ".log").toFile();
                Files.write(file.toPath(), report.getBytes(StandardCharsets.UTF_8));
                lastReport = file.getAbsolutePath();
                announce(EnumChatFormatting.GREEN + "Roulette debug saved: " + EnumChatFormatting.WHITE + lastReport);
            } catch (Exception error) { announce(EnumChatFormatting.RED + "Could not save Roulette debug: " + error.getMessage()); }
        });
    }

    private static void announce(String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) mc.addScheduledTask(() -> Main.sendMessage(Main.CHAT_PREFIX + message));
    }

    private static final class Recording {
        final long started = System.nanoTime();
        final BlitzRouletteTrace trace = new BlitzRouletteTrace();
        final ArrayDeque<BlitzRouletteTrace.Event> queue = new ArrayDeque<BlitzRouletteTrace.Event>();
        final List<BlitzRouletteTrace.Snapshot> observations = new ArrayList<BlitzRouletteTrace.Snapshot>();
        final String environment;
        World world;
        long lastSnapshotMs = -500;
        int dropped, savedFinished;
        boolean closed;
        Recording() {
            SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'"); utc.setTimeZone(TimeZone.getTimeZone("UTC"));
            environment = "started=" + utc.format(new Date()) + " mod=" + GomodVersion.get()
                    + " flavor=" + (GomodReleaseBuild.PUBLIC_RELEASE ? "public" : "internal")
                    + " limits=15min,16rounds,4096events,600k-event-text snapshot_interval_ms=500";
        }
        long elapsed() { return (System.nanoTime() - started) / 1000000L; }
        synchronized void offer(BlitzRouletteTrace.Event event) {
            if (closed) return;
            if (queue.size() >= 512) { dropped++; return; }
            queue.addLast(event);
        }
        synchronized List<BlitzRouletteTrace.Event> drain() {
            List<BlitzRouletteTrace.Event> copy = new ArrayList<BlitzRouletteTrace.Event>(queue); queue.clear(); return copy;
        }
        void observe(BlitzRouletteTrace.Snapshot snapshot) {
            observations.add(snapshot); if (observations.size() > 4) observations.remove(0); lastSnapshotMs = snapshot.ms;
        }
        BlitzRouletteTrace.Snapshot prior(long at) {
            for (int i = observations.size() - 1; i >= 0; i--) if (observations.get(i).ms <= at) return observations.get(i);
            return null;
        }
    }
}
