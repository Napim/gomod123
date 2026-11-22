package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

/** Bounds server-created hologram nameplates, which are normally invisible named armor stands. */
public final class EntityNametagLimiter {
    public static final int MAX_ARMOR_STAND_NAMETAGS = 64;

    private static final EntityArmorStand[] firstStands = new EntityArmorStand[MAX_ARMOR_STAND_NAMETAGS];
    private static final Candidate[] candidates = new Candidate[MAX_ARMOR_STAND_NAMETAGS];
    private static final PriorityQueue<Candidate> nearest = new PriorityQueue<>(
            MAX_ARMOR_STAND_NAMETAGS, (a, b) -> Double.compare(b.distanceSq, a.distanceSq));
    private static final int[] allowedArmorStandIds = new int[MAX_ARMOR_STAND_NAMETAGS];
    private static final List<EntityArmorStand> armorStands = new ArrayList<>();
    private static WorldClient cachedWorld;
    private static long cachedTick;
    private static int cachedEntityCount = -1;
    private static int candidateCount;
    private static int limitedCount;

    static {
        for (int i = 0; i < candidates.length; i++) {
            candidates[i] = new Candidate();
        }
    }

    private EntityNametagLimiter() {
    }

    /** Select the nearest labels once at the start of the normal world entity pass. */
    public static void beginFrame(Minecraft mc, Entity viewEntity) {
        candidateCount = 0;
        limitedCount = 0;
        if (mc == null || mc.theWorld == null || viewEntity == null) {
            clear();
            return;
        }

        try {
            collectArmorStands(mc.theWorld);
            for (int i = 0; i < armorStands.size(); i++) {
                EntityArmorStand stand = armorStands.get(i);
                if (stand.isDead || !hasRenderableCustomName(stand)) {
                    continue;
                }
                int index = candidateCount++;
                if (index < MAX_ARMOR_STAND_NAMETAGS) {
                    firstStands[index] = stand;
                    continue;
                }
                if (index == MAX_ARMOR_STAND_NAMETAGS) {
                    // Only compute distances once a label actually needs to be excluded.
                    for (int j = 0; j < firstStands.length; j++) {
                        EntityArmorStand first = firstStands[j];
                        offer(candidates[j], first, first.getDistanceSqToEntity(viewEntity));
                    }
                }
                double distanceSq = stand.getDistanceSqToEntity(viewEntity);
                if (distanceSq < nearest.peek().distanceSq) {
                    offer(nearest.poll(), stand, distanceSq);
                }
            }
            if (!nearest.isEmpty()) {
                int index = 0;
                for (Candidate candidate : nearest) {
                    allowedArmorStandIds[index++] = candidate.entityId;
                }
                Arrays.sort(allowedArmorStandIds);
                limitedCount = candidateCount - MAX_ARMOR_STAND_NAMETAGS;
            }
        } catch (Throwable ignored) {
            // Fail open on transformed clients with an unusual entity list.
            clear();
        } finally {
            Arrays.fill(firstStands, null);
            nearest.clear();
        }
    }

    private static void collectArmorStands(WorldClient world) {
        long tick = world.getTotalWorldTime();
        int entityCount = world.loadedEntityList.size();
        if (cachedWorld == world && cachedTick == tick && cachedEntityCount == entityCount) {
            return;
        }
        armorStands.clear();
        for (Object entity : world.loadedEntityList) {
            if (entity instanceof EntityArmorStand) {
                armorStands.add((EntityArmorStand) entity);
            }
        }
        cachedWorld = world;
        cachedTick = tick;
        cachedEntityCount = entityCount;
    }

    /** Covers same-tick membership changes, including swaps that leave the list size unchanged. */
    public static void onEntityListChanged(WorldClient world, Entity entity) {
        if (world == cachedWorld && entity instanceof EntityArmorStand) {
            cachedEntityCount = -1;
        }
    }

    private static void offer(Candidate candidate, EntityArmorStand stand, double distanceSq) {
        candidate.entityId = stand.getEntityId();
        candidate.distanceSq = distanceSq;
        nearest.offer(candidate);
    }

    /** Player and real-mob labels never reach this budget; only named armor stands do. */
    public static boolean shouldSuppress(Entity entity) {
        return limitedCount > 0
                && entity instanceof EntityArmorStand
                && hasRenderableCustomName((EntityArmorStand) entity)
                && Arrays.binarySearch(allowedArmorStandIds, entity.getEntityId()) < 0;
    }

    public static int getCandidateCount() {
        return candidateCount;
    }

    public static int getLimitedCount() {
        return limitedCount;
    }

    public static void clear() {
        candidateCount = 0;
        limitedCount = 0;
        armorStands.clear();
        cachedWorld = null;
        cachedEntityCount = -1;
    }

    private static boolean hasRenderableCustomName(EntityArmorStand stand) {
        try {
            if (!stand.hasCustomName()) {
                return false;
            }
            String name = stand.getCustomNameTag();
            return name != null && !name.trim().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final class Candidate {
        private int entityId;
        private double distanceSq;
    }
}
