package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.*;
import net.minecraft.util.EnumChatFormatting;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static me.napim.gomod.Main.sendMessage;
import static me.napim.gomod.Main.CHAT_PREFIX;

/**
 * Packet-level spectator tracker.  Intercepts entity spawn / move / destroy
 * packets to maintain a shadow map of <b>all</b> player entities the server
 * has told the client about, including ones the vanilla client may discard
 * (invisible / spectator-mode players).
 * <p>
 * Also compares the TAB player list against world entities to detect
 * "ghost" players (in TAB but not in the world) — these are almost
 * certainly spectators that Hypixel never spawned an entity for.
 */
public final class SpectatorTracker {
    private SpectatorTracker() {}

    // ── Tracked entity data ────────────────────────────────────────────
    /** Shadow entity map: entityId → info.  Updated from network packets. */
    private static final Map<Integer, TrackedPlayer> trackedPlayers = new ConcurrentHashMap<>();

    /** Names already reported in the current game to avoid spam. */
    private static final Set<String> reportedNames = Collections.synchronizedSet(new HashSet<String>());

    private static boolean wasGameActive = false;
    private static long lastScanMs = 0L;
    private static final long SCAN_INTERVAL_MS = 2000L;

    /** Simple holder for a tracked player entity. */
    private static final class TrackedPlayer {
        final int entityId;
        final UUID uuid;
        String name; // resolved later from tab list
        double x, y, z;
        long lastUpdateMs;
        boolean destroyed; // server sent destroy; we keep the record for a while

        TrackedPlayer(int entityId, UUID uuid, double x, double y, double z) {
            this.entityId = entityId;
            this.uuid = uuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastUpdateMs = System.currentTimeMillis();
            this.destroyed = false;
        }
    }

    // ── Public API ─────────────────────────────────────────────────────

    /** Call from the packet interceptor for every inbound packet. */
    public static void onPacketReceived(Packet<?> packet) {
        if (!VisibleSpecs.isSpectatorBypassAllInvisible()) return;
        try {
            if (packet instanceof S0CPacketSpawnPlayer) {
                handleSpawnPlayer((S0CPacketSpawnPlayer) packet);
            } else if (packet instanceof S18PacketEntityTeleport) {
                handleEntityTeleport((S18PacketEntityTeleport) packet);
            } else if (packet instanceof S14PacketEntity) {
                handleEntityRelMove((S14PacketEntity) packet);
            } else if (packet instanceof S13PacketDestroyEntities) {
                handleDestroyEntities((S13PacketDestroyEntities) packet);
            }
        } catch (Throwable ignored) {}
    }

    /** Periodic scanner — call from ClientTickHub. */
    public static void clientTick() {
        if (!VisibleSpecs.isSpectatorBypassAllInvisible()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) return;

        boolean gameActive = GameContext.isBlitzGameActive(mc);
        if (gameActive && !wasGameActive) {
            // New game started — clear everything
            trackedPlayers.clear();
            reportedNames.clear();
        }
        wasGameActive = gameActive;
        if (!gameActive) return;

        long now = System.currentTimeMillis();
        if (now - lastScanMs < SCAN_INTERVAL_MS) return;
        lastScanMs = now;

        // Purge stale destroyed entries (>30s old)
        trackedPlayers.values().removeIf(tp ->
                tp.destroyed && (now - tp.lastUpdateMs > 30_000L));

        // Resolve names for tracked players from tab list
        NetHandlerPlayClient nh = mc.getNetHandler();
        if (nh != null) {
            for (TrackedPlayer tp : trackedPlayers.values()) {
                if (tp.name == null && tp.uuid != null) {
                    NetworkPlayerInfo info = nh.getPlayerInfo(tp.uuid);
                    if (info != null && info.getGameProfile() != null) {
                        tp.name = info.getGameProfile().getName();
                    }
                }
            }
        }

        // ── Strategy 1: Check packet-tracked entities that are NOT in the world ──
        Set<Integer> worldEntityIds = new HashSet<>();
        for (EntityPlayer ep : mc.theWorld.playerEntities) {
            worldEntityIds.add(ep.getEntityId());
        }

        for (TrackedPlayer tp : trackedPlayers.values()) {
            if (tp.destroyed) continue;
            if (worldEntityIds.contains(tp.entityId)) continue; // visible in world, skip
            String name = tp.name != null ? tp.name : "Entity#" + tp.entityId;
            if (name.equals(mc.thePlayer.getName())) continue;
            if (!reportedNames.add(name)) continue; // already reported

            int px = (int) Math.floor(tp.x);
            int py = (int) Math.floor(tp.y);
            int pz = (int) Math.floor(tp.z);
            sendMessage(CHAT_PREFIX + EnumChatFormatting.LIGHT_PURPLE + "[SpecTracker] "
                    + EnumChatFormatting.WHITE + name
                    + EnumChatFormatting.GRAY + " — entity spawned by server but NOT in world"
                    + EnumChatFormatting.GRAY + " at "
                    + EnumChatFormatting.YELLOW + px + " " + py + " " + pz
                    + EnumChatFormatting.DARK_GRAY + " (ghost entity)");
        }

        // ── Strategy 2: Check world entities that are invisible ──
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer) continue;
            String name = player.getName();
            if (reportedNames.contains(name)) continue;
            try {
                // Read raw invisible flag from data watcher to avoid mixin recursion
                byte flags = player.getDataWatcher().getWatchableObjectByte(0);
                boolean rawInvis = (flags & (1 << 5)) != 0;
                boolean potionInvis = player.isPotionActive(net.minecraft.potion.Potion.invisibility);
                boolean dead = KillCounter.isPlayerDead(name);

                if (!rawInvis && !potionInvis && !dead) continue;
                reportedNames.add(name);

                int px = (int) Math.floor(player.posX);
                int py = (int) Math.floor(player.posY);
                int pz = (int) Math.floor(player.posZ);
                String reason;
                if (dead) reason = "KillCounter dead";
                else if (rawInvis && potionInvis) reason = "invis flag+potion";
                else if (rawInvis) reason = "invis flag";
                else reason = "invis potion";

                sendMessage(CHAT_PREFIX + EnumChatFormatting.LIGHT_PURPLE + "[SpecTracker] "
                        + EnumChatFormatting.WHITE + name
                        + EnumChatFormatting.GRAY + " detected in world at "
                        + EnumChatFormatting.YELLOW + px + " " + py + " " + pz
                        + EnumChatFormatting.DARK_GRAY + " (" + reason + ")");
            } catch (Throwable ignored) {}
        }

        // ── Strategy 3: Tab list players with NO world entity at all ──
        if (nh != null) {
            Set<String> worldNames = new HashSet<>();
            for (EntityPlayer ep : mc.theWorld.playerEntities) {
                worldNames.add(ep.getName());
            }
            for (NetworkPlayerInfo info : nh.getPlayerInfoMap()) {
                if (info.getGameProfile() == null) continue;
                String name = info.getGameProfile().getName();
                if (name == null || name.isEmpty()) continue;
                if (name.equals(mc.thePlayer.getName())) continue;
                if (worldNames.contains(name)) continue;
                if (!reportedNames.add(name)) continue;

                sendMessage(CHAT_PREFIX + EnumChatFormatting.LIGHT_PURPLE + "[SpecTracker] "
                        + EnumChatFormatting.WHITE + name
                        + EnumChatFormatting.GRAY + " — in TAB list but NO entity in world"
                        + EnumChatFormatting.DARK_GRAY + " (no position data — probable spectator)");
            }
        }
    }

    /** Run a one-time full diagnostic and print summary to chat. */
    public static void runDiagnostic() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            sendMessage(CHAT_PREFIX + EnumChatFormatting.RED + "Not in a world.");
            return;
        }

        boolean blitzActive = GameContext.isBlitzGameActive(mc);
        int worldPlayers = mc.theWorld != null ? mc.theWorld.playerEntities.size() - 1 : 0;
        int trackedCount = trackedPlayers.size();

        int tabCount = 0;
        int tabNoEntity = 0;
        NetHandlerPlayClient nh = mc.getNetHandler();
        Set<String> worldNames = new HashSet<>();
        if (mc.theWorld != null) {
            for (EntityPlayer ep : mc.theWorld.playerEntities) {
                worldNames.add(ep.getName());
            }
        }
        if (nh != null) {
            for (NetworkPlayerInfo info : nh.getPlayerInfoMap()) {
                if (info.getGameProfile() == null) continue;
                String name = info.getGameProfile().getName();
                if (name == null || name.equals(mc.thePlayer.getName())) continue;
                tabCount++;
                if (!worldNames.contains(name)) {
                    tabNoEntity++;
                }
            }
        }

        int worldInvis = 0;
        if (mc.theWorld != null) {
            for (EntityPlayer ep : mc.theWorld.playerEntities) {
                if (ep == mc.thePlayer) continue;
                try {
                    byte flags = ep.getDataWatcher().getWatchableObjectByte(0);
                    if ((flags & (1 << 5)) != 0) worldInvis++;
                } catch (Throwable ignored) {}
            }
        }

        sendMessage(CHAT_PREFIX + EnumChatFormatting.AQUA + "=== SpectatorBypass Diagnostic ===");
        sendMessage(CHAT_PREFIX + EnumChatFormatting.GRAY + "Blitz game active: "
                + (blitzActive ? EnumChatFormatting.GREEN + "YES" : EnumChatFormatting.RED + "NO"));
        sendMessage(CHAT_PREFIX + EnumChatFormatting.GRAY + "TAB list players: "
                + EnumChatFormatting.WHITE + tabCount);
        sendMessage(CHAT_PREFIX + EnumChatFormatting.GRAY + "World entity players: "
                + EnumChatFormatting.WHITE + worldPlayers);
        sendMessage(CHAT_PREFIX + EnumChatFormatting.GRAY + "World invisible players: "
                + EnumChatFormatting.WHITE + worldInvis);
        sendMessage(CHAT_PREFIX + EnumChatFormatting.GRAY + "TAB players with NO entity: "
                + EnumChatFormatting.WHITE + tabNoEntity);
        sendMessage(CHAT_PREFIX + EnumChatFormatting.GRAY + "Packet-tracked entities: "
                + EnumChatFormatting.WHITE + trackedCount);

        if (tabNoEntity > 0) {
            sendMessage(CHAT_PREFIX + EnumChatFormatting.YELLOW + "^ " + tabNoEntity
                    + " player(s) are in TAB but have no entity — likely spectators.");
            sendMessage(CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Server does NOT send entity data for them. Positions unknown.");
        }
        if (worldInvis > 0) {
            sendMessage(CHAT_PREFIX + EnumChatFormatting.GREEN + "^ " + worldInvis
                    + " invisible entity(ies) found in world — these CAN be revealed!");
        }
        if (worldPlayers == 0 && tabCount > 0) {
            sendMessage(CHAT_PREFIX + EnumChatFormatting.RED
                    + "No player entities loaded despite TAB entries. Server is not spawning entities for other players.");
        }
    }

    /** Reset all state (call on disconnect / world change). */
    public static void reset() {
        trackedPlayers.clear();
        reportedNames.clear();
        wasGameActive = false;
    }

    // ── Packet handlers ────────────────────────────────────────────────

    private static void handleSpawnPlayer(S0CPacketSpawnPlayer packet) {
        try {
            int eid = packet.getEntityID();
            UUID uuid = packet.getPlayer();
            double x = packet.getX() / 32.0;
            double y = packet.getY() / 32.0;
            double z = packet.getZ() / 32.0;
            TrackedPlayer tp = new TrackedPlayer(eid, uuid, x, y, z);
            trackedPlayers.put(eid, tp);
        } catch (Throwable ignored) {}
    }

    private static void handleEntityTeleport(S18PacketEntityTeleport packet) {
        try {
            int eid = packet.getEntityId();
            TrackedPlayer tp = trackedPlayers.get(eid);
            if (tp == null) return;
            tp.x = packet.getX() / 32.0;
            tp.y = packet.getY() / 32.0;
            tp.z = packet.getZ() / 32.0;
            tp.lastUpdateMs = System.currentTimeMillis();
            tp.destroyed = false;
        } catch (Throwable ignored) {}
    }

    private static void handleEntityRelMove(S14PacketEntity packet) {
        try {
            int eid = packet.getEntity(Minecraft.getMinecraft().theWorld) != null
                    ? packet.getEntity(Minecraft.getMinecraft().theWorld).getEntityId()
                    : -1;
            // The packet carries entity ref; if it resolved, use its ID to look up our record
            // For rel-move, we need the entityId field — use reflection or the entity resolution
            if (eid < 0) return;
            TrackedPlayer tp = trackedPlayers.get(eid);
            if (tp == null) return;
            tp.x += packet.func_149062_c() / 32.0;
            tp.y += packet.func_149061_d() / 32.0;
            tp.z += packet.func_149064_e() / 32.0;
            tp.lastUpdateMs = System.currentTimeMillis();
            tp.destroyed = false;
        } catch (Throwable ignored) {}
    }

    private static void handleDestroyEntities(S13PacketDestroyEntities packet) {
        try {
            int[] ids = packet.getEntityIDs();
            long now = System.currentTimeMillis();
            for (int eid : ids) {
                TrackedPlayer tp = trackedPlayers.get(eid);
                if (tp != null) {
                    tp.destroyed = true;
                    tp.lastUpdateMs = now;
                }
            }
        } catch (Throwable ignored) {}
    }
}
