package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks mobs the local player spawns via spawn eggs.
 * <p>
 * When a spawn egg is used, a "pending" record is stored with the entity type, the player's
 * position, and a timestamp. On every client tick, loaded entities are scanned: if a recently
 * joined entity matches the pending egg entity type and is within range of the recorded position
 * it is tagged as player-spawned and rendered with a green nametag by {@link MobNametags}.
 * There is no type-agnostic fallback (that matched the wrong mob and consumed the pending spawn).
 * </p>
 */
public final class SpawnedMobTracker {

    /** How long (ms) to keep a pending spawn record while waiting for the entity to arrive. */
    private static final long MATCH_WINDOW_MS = 15000L;
    /** Max blocks from the player's position when the egg was used to consider a match. */
    private static final double MATCH_RADIUS = 72.0;
    /** Entities older than this many ticks are not considered new arrivals. */
    private static final int MAX_ENTITY_AGE_TICKS = 400;
    /** Sibling baby ocelots from EntityOcelot#onInitialSpawn arrive at the same spot within ~1.5s. */
    private static final long OCELOT_SIBLING_WINDOW_MS = 1500L;
    private static final double OCELOT_SIBLING_RADIUS_SQ = 3.5D * 3.5D;

    private static final Set<UUID> spawnedByPlayer = new HashSet<>();
    // 1.8 mob spawn packets do not carry a persistent UUID. Re-tracking the same server entity can create
    // a fresh client UUID, while its entity id remains stable within this world connection.
    private static final Map<Integer, Integer> spawnedEntityTypes = new HashMap<>();
    private static World trackedWorld;
    private static final List<PendingSpawn> pending = new ArrayList<>();
    private static final List<RecentOcelotSpawn> recentOcelotSpawns = new ArrayList<>();
    private static volatile boolean debugEnabled;

    /** Last egg-use line for {@code /spawnedmobdebug status} (client always has stack position; C08 stack may be empty). */
    private static volatile String debugLastEggLine = "(no egg hook yet this session)";
    /** Last tag line (match or client egg). */
    private static volatile String debugLastTagLine = "(no tag yet this session)";

    /** Suppress duplicate {@link #onSpawnEggUsed} when both mixins and outbound C08 fire the same click. */
    private static long lastEggDedupeMs = -1L;
    private static int lastEggDedupeMeta = Integer.MIN_VALUE;
    private static double lastEggDedupePx;
    private static double lastEggDedupePy;
    private static double lastEggDedupePz;

    private SpawnedMobTracker() {
    }

    public static boolean isDebugEnabled() {
        return debugEnabled;
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    /** One-line summary for {@code /spawnedmobdebug status} and {@code /gdebug}. */
    public static String debugStatusSummary() {
        return "[spawnTrack] debug=" + debugEnabled
                + " pending=" + pending.size()
                + " tagged=" + spawnedByPlayer.size();
    }

    public static String getDebugLastEggLine() {
        return debugLastEggLine;
    }

    public static String getDebugLastTagLine() {
        return debugLastTagLine;
    }

    /**
     * Clears pending + tagged UUIDs (for testing). Does not toggle debug.
     */
    public static synchronized void debugClearTrackedState() {
        pending.clear();
        recentOcelotSpawns.clear();
        spawnedByPlayer.clear();
        spawnedEntityTypes.clear();
        lastEggDedupeMs = -1L;
        lastEggDedupeMeta = Integer.MIN_VALUE;
        debugLastEggLine = "(cleared)";
        debugLastTagLine = "(cleared)";
    }

    /**
     * Prints nearby non-player living entities with entity id, UUID, and whether our set contains that UUID.
     * Proves the client always has ids; correlation with egg use is the hard part.
     */
    public static void debugScanNearbyLiving() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "[spawnTrack] scan: join a world first");
            return;
        }
        EntityPlayer p = mc.thePlayer;
        final double r = 16.0;
        final double rsq = r * r;
        List<EntityLivingBase> list = new ArrayList<>();
        for (Entity e : (List<Entity>) mc.theWorld.getLoadedEntityList()) {
            if (!(e instanceof EntityLivingBase) || e instanceof EntityPlayer || e.isDead) {
                continue;
            }
            double dx = e.posX - p.posX;
            double dy = e.posY - p.posY;
            double dz = e.posZ - p.posZ;
            if (dx * dx + dy * dy + dz * dz > rsq) {
                continue;
            }
            list.add((EntityLivingBase) e);
        }
        Collections.sort(list, Comparator.comparingDouble(a -> a.getDistanceSqToEntity(p)));
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "[spawnTrack] scan "
                + list.size() + " living (non-player) within " + (int) r + "b — client always has entityId + UUID");
        int n = 0;
        for (EntityLivingBase e : list) {
            if (n++ >= 10) {
                Main.sendMessage(EnumChatFormatting.GRAY + "  … +" + (list.size() - 10) + " more");
                break;
            }
            UUID u = e.getUniqueID();
            boolean tagged = isPlayerSpawned(e);
            int typeId = -999;
            try {
                typeId = EntityList.getEntityID(e);
            } catch (Throwable ignored) {
            }
            String name;
            try {
                name = e.getName();
            } catch (Throwable t) {
                name = "?";
            }
            Main.sendMessage(EnumChatFormatting.GRAY + "  id=" + e.getEntityId()
                    + " typeId=" + typeId
                    + " ticks=" + e.ticksExisted
                    + " tagged=" + (tagged ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no")
                    + EnumChatFormatting.GRAY + " uuid=" + String.valueOf(u));
            Main.sendMessage(EnumChatFormatting.DARK_GRAY + "    class=" + e.getClass().getSimpleName()
                    + " name=" + name);
        }
        if (list.isEmpty()) {
            Main.sendMessage(EnumChatFormatting.GRAY + "  (none — stand closer to mobs or spawn one here)");
        }
    }

    private static void dbg(String msg) {
        if (!debugEnabled) {
            return;
        }
        try {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.DARK_GRAY + "[spawnTrack] "
                    + EnumChatFormatting.GRAY + msg);
        } catch (Throwable ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Called from {@link me.napim.gomod.forge.ForgeEventBridge} on outgoing packets so egg use is
     * recorded even when {@code PlayerControllerMP} mixins do not run (some Lunar paths).
     */
    public static void onOutgoingClientPacket(Packet<?> packet) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return;
        }
        ItemStack stack;
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            stack = ((C08PacketPlayerBlockPlacement) packet).getStack();
            if (stack == null || stack.getItem() == null) {
                stack = mc.thePlayer.getHeldItem();
            }
        } else if (packet instanceof C02PacketUseEntity) {
            C02PacketUseEntity use = (C02PacketUseEntity) packet;
            if (use.getAction() == C02PacketUseEntity.Action.ATTACK) {
                return;
            }
            stack = mc.thePlayer.getHeldItem();
        } else {
            return;
        }
        if (stack == null || stack.getItem() != Items.spawn_egg) {
            return;
        }
        if (stack.hasTagCompound() && stack.getTagCompound().getBoolean("gomod_client")) {
            return;
        }
        int meta = stack.getMetadata();
        onSpawnEggUsed(meta, mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
    }

    /**
     * Called when the player right-clicks a server-side spawn egg.
     * Records a pending match so the arriving entity can be tagged once the server responds.
     */
    public static synchronized void onSpawnEggUsed(int entityTypeId, double px, double py, double pz) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.theWorld != null) ensureWorld(mc.theWorld);
        long now = System.currentTimeMillis();
        // Suppress duplicate hooks from the exact same click (e.g. onPlayerRightClick + C08), while
        // still accepting rapid/held right-clicks (~200ms repeat in vanilla).
        if (lastEggDedupeMs >= 0L && now - lastEggDedupeMs < 40L && lastEggDedupeMeta == entityTypeId) {
            double dx = px - lastEggDedupePx;
            double dy = py - lastEggDedupePy;
            double dz = pz - lastEggDedupePz;
            if (dx * dx + dy * dy + dz * dz < 16.0) {
                dbg("egg use skipped (duplicate hook, same click)");
                return;
            }
        }
        lastEggDedupeMs = now;
        lastEggDedupeMeta = entityTypeId;
        lastEggDedupePx = px;
        lastEggDedupePy = py;
        lastEggDedupePz = pz;

        Set<Integer> existingIds = new HashSet<>();
        if (mc != null && mc.theWorld != null) {
            for (Object value : mc.theWorld.getLoadedEntityList()) {
                if (value instanceof Entity) existingIds.add(((Entity) value).getEntityId());
            }
        }
        pending.add(new PendingSpawn(entityTypeId, px, py, pz, existingIds));
        debugLastEggLine = "meta=" + entityTypeId + " pending=" + pending.size()
                + " @ " + String.format("%.1f,%.1f,%.1f", px, py, pz);
        dbg("egg use recorded meta/typeId=" + entityTypeId + " at "
                + String.format("%.1f,%.1f,%.1f", px, py, pz)
                + " pending=" + pending.size());
    }

    /**
     * Called for client-side spawn eggs that are resolved immediately.
     * Tags the entity UUID directly without waiting for a scan.
     */
    public static synchronized void onClientEntitySpawned(Entity entity) {
        if (entity != null && entity.getUniqueID() != null) {
            ensureWorld(entity.worldObj);
            rememberSpawnedEntity(entity);
            debugLastTagLine = "client id=" + entity.getEntityId() + " uuid=" + entity.getUniqueID();
            dbg("client egg tagged id=" + entity.getEntityId() + " uuid=" + entity.getUniqueID());
        }
    }

    /** Returns true if any spawn-egg clicks are currently waiting to match an arriving entity. */
    public static synchronized boolean hasPendingSpawns() {
        return !pending.isEmpty();
    }

    /** Returns true if the given UUID was previously tagged as player-spawned. */
    public static synchronized boolean isPlayerSpawned(UUID uuid) {
        return uuid != null && spawnedByPlayer.contains(uuid);
    }

    public static synchronized boolean isPlayerSpawned(Entity entity) {
        if (entity == null || entity.worldObj != trackedWorld) return false;
        if (entity.isDead) {
            spawnedEntityTypes.remove(entity.getEntityId());
            spawnedByPlayer.remove(entity.getUniqueID());
            return false;
        }
        if (isPlayerSpawned(entity.getUniqueID())) {
            rememberSpawnedEntity(entity);
            return true;
        }
        Integer type = spawnedEntityTypes.get(entity.getEntityId());
        if (type == null) return false;
        if (type.intValue() != EntityList.getEntityID(entity)) {
            spawnedEntityTypes.remove(entity.getEntityId());
            return false;
        }
        rememberSpawnedEntity(entity);
        return true;
    }

    private static void rememberSpawnedEntity(Entity entity) {
        if (entity.getUniqueID() != null) spawnedByPlayer.add(entity.getUniqueID());
        int type = EntityList.getEntityID(entity);
        if (type > 0) spawnedEntityTypes.put(entity.getEntityId(), type);
    }

    private static void ensureWorld(World world) {
        if (trackedWorld != world) {
            onWorldChange();
            trackedWorld = world;
        }
    }

    /** Clears all state — call when the player disconnects or changes worlds. */
    public static synchronized void onWorldChange() {
        spawnedByPlayer.clear();
        spawnedEntityTypes.clear();
        pending.clear();
        recentOcelotSpawns.clear();
        trackedWorld = null;
        lastEggDedupeMs = -1L;
        lastEggDedupeMeta = Integer.MIN_VALUE;
        debugLastEggLine = "(world changed)";
        debugLastTagLine = "(world changed)";
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public static synchronized void clientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null) {
            return;
        }
        ensureWorld(mc.theWorld);
        long now = System.currentTimeMillis();

        // Expire old pending entries and recent ocelot sibling windows first.
        pending.removeIf(p -> now - p.timestamp > MATCH_WINDOW_MS);
        recentOcelotSpawns.removeIf(o -> now - o.timestamp > OCELOT_SIBLING_WINDOW_MS);
        if (pending.isEmpty() && recentOcelotSpawns.isEmpty()) {
            return;
        }

        // Prefer youngest mobs first so the real spawn is matched before older same-type animals nearby.
        List<EntityLivingBase> living = new ArrayList<>();
        for (Entity entity : (List<Entity>) mc.theWorld.getLoadedEntityList()) {
            if (!(entity instanceof EntityLivingBase) || entity instanceof EntityPlayer || entity.isDead) {
                continue;
            }
            living.add((EntityLivingBase) entity);
        }
        Collections.sort(living, Comparator
                .comparingInt((EntityLivingBase e) -> e.ticksExisted)
                .thenComparingInt(Entity::getEntityId));

        for (EntityLivingBase entity : living) {
            if (isPlayerSpawned(entity)) {
                continue; // already tagged
            }
            if (entity.ticksExisted > MAX_ENTITY_AGE_TICKS) {
                continue; // too old to be a fresh spawn
            }

            int entityTypeId;
            try {
                entityTypeId = EntityList.getEntityID(entity);
            } catch (Throwable ignored) {
                continue;
            }

            PendingSpawn bestStrictMatch = null;
            double bestStrictDistSq = Double.MAX_VALUE;

            for (PendingSpawn p : pending) {
                if (p.entityTypeId != entityTypeId || p.existingEntityIds.contains(entity.getEntityId())) {
                    continue;
                }
                double dx = entity.posX - p.px;
                double dy = entity.posY - p.py;
                double dz = entity.posZ - p.pz;
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq > MATCH_RADIUS * MATCH_RADIUS) {
                    continue;
                }
                if (distSq < bestStrictDistSq) {
                    bestStrictDistSq = distSq;
                    bestStrictMatch = p;
                }
            }

            if (bestStrictMatch != null) {
                UUID taggedUuid = entity.getUniqueID();
                rememberSpawnedEntity(entity);
                if (entity instanceof EntityOcelot) {
                    recentOcelotSpawns.add(new RecentOcelotSpawn(entity.posX, entity.posY, entity.posZ, now));
                }
                pending.remove(bestStrictMatch);
                double dist = Math.sqrt(bestStrictDistSq);
                debugLastTagLine = "match id=" + entity.getEntityId() + " uuid=" + taggedUuid + " strict";
                dbg("matched strict entityId=" + entity.getEntityId()
                        + " uuid=" + taggedUuid
                        + " worldTypeId=" + entityTypeId + " eggMeta=" + bestStrictMatch.entityTypeId
                        + " dist=" + String.format("%.2f", dist)
                        + " ticksExisted=" + entity.ticksExisted
                        + " pendingLeft=" + pending.size());
            } else if (entity instanceof EntityOcelot && ((EntityOcelot) entity).isChild()
                    && entity.ticksExisted <= 40 && isNearRecentOcelotSpawn(entity)) {
                // EntityOcelot#onInitialSpawn has a 1/7 chance of spawning 2 baby ocelots alongside the adult.
                rememberSpawnedEntity(entity);
                debugLastTagLine = "match id=" + entity.getEntityId() + " uuid=" + entity.getUniqueID() + " kitten";
                dbg("matched sibling baby ocelot entityId=" + entity.getEntityId()
                        + " uuid=" + entity.getUniqueID());
            }
        }
    }

    private static boolean isNearRecentOcelotSpawn(Entity entity) {
        for (RecentOcelotSpawn origin : recentOcelotSpawns) {
            double dx = entity.posX - origin.x;
            double dy = entity.posY - origin.y;
            double dz = entity.posZ - origin.z;
            if (dx * dx + dy * dy + dz * dz <= OCELOT_SIBLING_RADIUS_SQ) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private static final class RecentOcelotSpawn {
        final double x, y, z;
        final long timestamp;

        RecentOcelotSpawn(double x, double y, double z, long timestamp) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.timestamp = timestamp;
        }
    }

    private static final class PendingSpawn {
        final int entityTypeId;
        final double px, py, pz;
        final long timestamp;
        final Set<Integer> existingEntityIds;

        PendingSpawn(int entityTypeId, double px, double py, double pz, Set<Integer> existingEntityIds) {
            this.entityTypeId = entityTypeId;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.timestamp = System.currentTimeMillis();
            this.existingEntityIds = existingEntityIds;
        }
    }
}
