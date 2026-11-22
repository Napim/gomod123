package me.napim.gomod.features;

import me.napim.gomod.mixins.RenderAccessor;
import me.napim.gomod.render.NametagLayerBridge;
import me.napim.gomod.render.PlayerNametagOverlayStack;
import me.napim.gomod.render.VanillaPlayerNametagLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntitySkeleton;
import net.minecraft.entity.monster.EntityWitch;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.passive.EntityAmbientCreature;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort client-side mob target display.
 * Prefers synced AI targets and falls back to a conservative facing-distance heuristic.
 */
public final class MobTargetDisplay {
    private static final String DEFAULT_MARKER_TEXT = "!";
    private static final int MAX_PLAYER_ARROW_DISTANCE = 64;
    private static final int FALLBACK_MAX_DISTANCE = 18;
    private static boolean enabled = false;
    private static World trackedWorld = null;
    private static final Map<Integer, Integer> mobToPlayerIds = new HashMap<>();
    private static final Map<Integer, Integer> playerAggroCounts = new HashMap<>();
    /** Prevents Lunar's renderer-tail fallback from drawing a second marker if the vanilla bridge ran. */
    private static final Set<Integer> renderedStandingEntityIdsThisFrame = new HashSet<Integer>();
    /** Let the normal 50 ms client tick refresh targets before the render fallback needs to scan. */
    private static final long RENDER_FALLBACK_INTERVAL_NANOS = 75_000_000L;
    private static long lastTargetScanNanos;

    static {
        NametagLayerBridge.register(MobTargetDisplay::onAfterVanillaPlayerNameplate);
    }

    private MobTargetDisplay() {
    }

    public static void loadSettings() {
        enabled = ConfigManager.isMobTargetDisplayEnabled();
    }

    /** Visible marker: dark red + bold (§-style codes; used by nameplates and font renderer). */
    public static String getMarkerText() {
        return "" + EnumChatFormatting.DARK_RED + EnumChatFormatting.BOLD + DEFAULT_MARKER_TEXT + EnumChatFormatting.RESET;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Reset renderer deduplication once at the start of each world entity pass. */
    public static void beginWorldNametagFrame() {
        renderedStandingEntityIdsThisFrame.clear();
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        ConfigManager.setMobTargetDisplayEnabled(value);
        if (!value) {
            clearTargets();
        }
    }

    public static void toggle() {
        setEnabled(!enabled);
    }

    public static void clientTick() {
        if (!enabled) {
            clearTargets();
            return;
        }
        refreshTargets(Minecraft.getMinecraft());
    }

    private static void refreshTargets(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            clearTargets();
            return;
        }

        if (trackedWorld != mc.theWorld) {
            clearTargets();
            trackedWorld = mc.theWorld;
        }
        lastTargetScanNanos = System.nanoTime();

        Map<Integer, Integer> nextMobTargets = new HashMap<>();
        Map<Integer, Integer> nextPlayerCounts = new HashMap<>();

        for (Entity entity : (List<Entity>) mc.theWorld.getLoadedEntityList()) {
            if (!(entity instanceof EntityLiving)) {
                continue;
            }

            EntityLiving mob = (EntityLiving) entity;
            if (!isSupportedMob(mc, mob)) {
                continue;
            }
            if (isPassiveMobForMobTarget(mob)) {
                continue;
            }

            EntityPlayer target = resolveTargetPlayer(mc, mob);
            if (target == null) {
                continue;
            }

            nextMobTargets.put(mob.getEntityId(), target.getEntityId());
            Integer count = nextPlayerCounts.get(target.getEntityId());
            nextPlayerCounts.put(target.getEntityId(), count == null ? 1 : count + 1);
        }

        mobToPlayerIds.clear();
        mobToPlayerIds.putAll(nextMobTargets);
        playerAggroCounts.clear();
        playerAggroCounts.putAll(nextPlayerCounts);
    }

    /** True when this player would get the aggro {@code !} row above their nametag (not third-person HUD). */
    public static boolean wouldShowAggroNametagRow(EntityPlayer player) {
        if (!enabled || player == null) {
            return false;
        }
        Integer count = playerAggroCounts.get(player.getEntityId());
        return count != null && count.intValue() > 0;
    }

    public static boolean shouldRenderTargetNametag(EntityLivingBase entity) {
        if (!enabled || entity == null || isPassiveMobForMobTarget(entity)) {
            return false;
        }
        return getTargetPlayerIdForMob(entity) != null;
    }

    public static String decorateMobLabel(EntityLivingBase entity, String baseLabel) {
        if (baseLabel == null || baseLabel.isEmpty()) {
            return baseLabel;
        }
        if (!shouldRenderTargetNametag(entity)) {
            return baseLabel;
        }
        return EnumChatFormatting.RED + baseLabel + EnumChatFormatting.RESET;
    }

    private static void onAfterVanillaPlayerNameplate(AbstractClientPlayer player, Render render,
            double x, double footYOfMainNameLine, double z, float labelScale, double distanceSq,
            String renderedLabel) {
        try {
            if (!enabled || player == null) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || player == mc.thePlayer) {
                return;
            }
            if (!wouldShowAggroNametagRow(player)) {
                return;
            }
            Integer count = playerAggroCounts.get(player.getEntityId());
            String line = buildAggroLabel(count.intValue());
            if (line == null || line.trim().isEmpty()) {
                return;
            }
            int rowIndex = PlayerNametagOverlayStack.countOverlayRowsAboveMainIgn(player);
            double footY = PlayerNametagOverlayStack.footYForRowAboveMainIgn(
                    render, footYOfMainNameLine, labelScale, player, distanceSq, rowIndex);
            final RenderAccessor accessor = (RenderAccessor) render;
            final AbstractClientPlayer target = player;
            final String text = line;
            final double drawX = x;
            final double drawY = footY;
            final double drawZ = z;
            GomodPlayerNametagLabels.renderCustomLabel(new Runnable() {
                @Override
                public void run() {
                    accessor.gomod$invokeRenderLivingLabel(target, text, drawX, drawY, drawZ,
                            MAX_PLAYER_ARROW_DISTANCE);
                }
            });
            renderedStandingEntityIdsThisFrame.add(Integer.valueOf(player.getEntityId()));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Lunar can render a standing player without calling RenderPlayer's nametag bridge. Add the missing aggro row
     * from RendererLivingEntity#doRender TAIL, while deduplicating frames where the normal bridge did run.
     */
    public static void tryRenderStandingAggroMarkerFallback(EntityPlayer player, Render render,
            double x, double y, double z) {
        if (!enabled || !(player instanceof AbstractClientPlayer) || player.isSneaking() || render == null
                || !wouldShowAggroNametagRow(player)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || player == mc.thePlayer
                || VisibleSpecs.isDeferredOpacityPrepassActive() || VisibleSpecs.shouldSuppressNametag(player)
                || renderedStandingEntityIdsThisFrame.contains(Integer.valueOf(player.getEntityId()))) {
            return;
        }
        try {
            Entity view = mc.getRenderManager() == null ? null : mc.getRenderManager().livingPlayer;
            if (view == null) {
                view = mc.thePlayer;
            }
            double distanceSq = player.getDistanceSqToEntity(view);
            if (distanceSq > (double) MAX_PLAYER_ARROW_DISTANCE * (double) MAX_PLAYER_ARROW_DISTANCE) {
                return;
            }
            Integer count = playerAggroCounts.get(Integer.valueOf(player.getEntityId()));
            String line = count == null ? null : buildAggroLabel(count.intValue());
            if (line == null || line.trim().isEmpty()) {
                return;
            }
            AbstractClientPlayer clientPlayer = (AbstractClientPlayer) player;
            float scale = VanillaPlayerNametagLayout.VANILLA_WORLD_LABEL_SCALE;
            double mainFootY = VanillaPlayerNametagLayout.resolveMainIgnFootY(
                    render, clientPlayer, y, scale, distanceSq);
            int rowIndex = PlayerNametagOverlayStack.countOverlayRowsAboveMainIgn(player);
            double footY = VanillaPlayerNametagLayout.footYAfterRowsAboveMainName(
                    render, mainFootY, scale, rowIndex);
            GomodPlayerNametagLabels.renderCustomLabel(new Runnable() {
                @Override
                public void run() {
                    ((RenderAccessor) render).gomod$invokeRenderLivingLabel(
                            clientPlayer, line, x, footY, z, MAX_PLAYER_ARROW_DISTANCE);
                }
            });
            renderedStandingEntityIdsThisFrame.add(Integer.valueOf(player.getEntityId()));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Sneaking: vanilla does not call {@code renderOffsetLivingLabel}, so {@link NametagLayerBridge} never runs.
     * Invoked from {@code renderName} TAIL (same hook as {@link NickRealNametagOverlay}).
     */
    public static void tryRenderSneakingAggroMarker(EntityPlayer player, Render render, double x, double y, double z) {
        try {
            if (!enabled || player == null || !player.isSneaking()) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || player == mc.thePlayer) {
                return;
            }
            if (!wouldShowAggroNametagRow(player)) {
                return;
            }
            Integer count = playerAggroCounts.get(player.getEntityId());
            String line = buildAggroLabel(count.intValue());
            if (line == null || line.trim().isEmpty()) {
                return;
            }
            int rowIndex = PlayerNametagOverlayStack.countOverlayRowsAboveMainIgn(player);
            double footY = PlayerNametagOverlayStack.sneakingFootYForRowAboveMain(render, y, player, rowIndex);
            NickRealNametagOverlay.renderSneakingStyleNametagPlate(player, render, x, footY, z, line);
        } catch (Throwable ignored) {
        }
    }

    /** One copy of {@link #getMarkerText()} per mob (count ≥ 1), separated by spaces — e.g. {@code ! ! !} for three. */
    private static String buildMobTargetMarkerDisplay(String markerText, int count) {
        if (markerText == null) {
            markerText = "";
        }
        int n = Math.max(1, count);
        if (n <= 1 || markerText.isEmpty()) {
            return markerText;
        }
        StringBuilder sb = new StringBuilder(markerText.length() * n + n);
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(markerText);
        }
        return sb.toString();
    }

    private static String buildAggroLabel(int count) {
        return buildMobTargetMarkerDisplay(getMarkerText(), count);
    }

    /**
     * Refresh the target scan if gameplay ticks have stopped reaching this feature. Aggro {@code !} rows are drawn on <em>other</em>
     * players' nametags only ({@link NametagLayerBridge} / sneaking fallback), never on the local player.
     */
    public static void renderOverlay() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            if (trackedWorld != null) clearTargets();
            return;
        }
        if (trackedWorld != mc.theWorld || System.nanoTime() - lastTargetScanNanos >= RENDER_FALLBACK_INTERVAL_NANOS) {
            try {
                refreshTargets(mc);
            } catch (Throwable ignored) {
            }
        }
    }

    private static EntityPlayer resolveTargetPlayer(Minecraft mc, EntityLiving mob) {
        EntityPlayer direct = asTrackedPlayer(mc, mob.getAttackTarget());
        if (direct != null) {
            return direct;
        }

        direct = asTrackedPlayer(mc, mob.getAITarget());
        if (direct != null) {
            return direct;
        }

        int revengeDist = relaxedMaxDistance(mob);
        direct = asTrackedPlayer(mc, mob.getLastAttacker());
        if (direct != null) {
            int maxRevenge = revengeDist + (mob.hurtTime > 0 ? 6 : 0);
            if (mob.getDistanceToEntity(direct) <= maxRevenge) {
                return direct;
            }
        }

        return findHeuristicTarget(mc, mob);
    }

    private static double relaxedLookDotThreshold(EntityLiving mob) {
        if (mob instanceof EntityWitch || mob instanceof EntitySkeleton) {
            return 0.28D;
        }
        return 0.70D;
    }

    private static double relaxedAcceptScoreThreshold(EntityLiving mob) {
        if (mob instanceof EntityWitch || mob instanceof EntitySkeleton) {
            return 0.58D;
        }
        return 0.82D;
    }

    private static int relaxedMaxDistance(EntityLiving mob) {
        if (mob instanceof EntityWitch) {
            return 24;
        }
        return FALLBACK_MAX_DISTANCE;
    }

    private static EntityPlayer findHeuristicTarget(Minecraft mc, EntityLiving mob) {
        EntityPlayer bestPlayer = null;
        double bestScore = 0.0D;
        double secondBest = 0.0D;

        Vec3 look = mob.getLookVec();
        Vec3 mobPos = new Vec3(mob.posX, mob.posY + mob.getEyeHeight(), mob.posZ);
        if (look == null) {
            return null;
        }

        int maxDist = relaxedMaxDistance(mob);
        double lookCut = relaxedLookDotThreshold(mob);
        double acceptCut = relaxedAcceptScoreThreshold(mob);

        for (EntityPlayer player : (List<EntityPlayer>) mc.theWorld.playerEntities) {
            EntityPlayer candidate = asTrackedPlayer(mc, player);
            if (candidate == null) {
                continue;
            }

            double distance = mob.getDistanceToEntity(candidate);
            if (distance <= 0.0D || distance > maxDist) {
                continue;
            }

            Vec3 toPlayer = new Vec3(candidate.posX - mobPos.xCoord,
                    candidate.posY + candidate.getEyeHeight() - mobPos.yCoord,
                    candidate.posZ - mobPos.zCoord);
            double len = toPlayer.lengthVector();
            if (len <= 0.001D) {
                continue;
            }

            Vec3 norm = new Vec3(toPlayer.xCoord / len, toPlayer.yCoord / len, toPlayer.zCoord / len);
            double lookScore = look.dotProduct(norm);
            if (lookScore < lookCut) {
                continue;
            }

            double proximityScore = 1.0D - Math.min(distance, maxDist) / maxDist;
            double score = lookScore * 0.72D + proximityScore * 0.28D;
            if (score > bestScore) {
                secondBest = bestScore;
                bestScore = score;
                bestPlayer = candidate;
            } else if (score > secondBest) {
                secondBest = score;
            }
        }

        if (bestPlayer == null) {
            return null;
        }
        if (bestScore < acceptCut) {
            return null;
        }
        double gap = mob instanceof EntityWitch ? 0.03D : 0.08D;
        if (bestScore - secondBest < gap) {
            return null;
        }
        return bestPlayer;
    }

    private static EntityPlayer asTrackedPlayer(Minecraft mc, Entity entity) {
        if (!(entity instanceof EntityPlayer)) {
            return null;
        }

        EntityPlayer player = (EntityPlayer) entity;
        if (player.isDead || !player.isEntityAlive()) {
            return null;
        }
        if (mc == null || mc.thePlayer == null) {
            return player;
        }
        return player;
    }

    private static Integer getTargetPlayerIdForMob(EntityLivingBase entity) {
        return entity == null ? null : mobToPlayerIds.get(entity.getEntityId());
    }

    private static boolean isSupportedMob(Minecraft mc, EntityLivingBase entity) {
        if (entity == null || entity.isDead || !entity.isEntityAlive()) {
            return false;
        }
        if (entity instanceof EntityPlayer || entity instanceof EntityArmorStand) {
            return false;
        }
        if (mc != null && mc.thePlayer != null && mc.thePlayer.ridingEntity == entity) {
            return false;
        }
        return !isIgnoredLobbyDisplayEntity(entity);
    }

    /**
     * Passive mobs: no target tracking (avoids look-distance false positives) and no red “aggro” nametag tint
     * ({@link MobNametags} uses {@link #shouldRenderTargetNametag}).
     */
    private static boolean isPassiveMobForMobTarget(EntityLivingBase entity) {
        if (entity == null) {
            return false;
        }
        return entity instanceof EntityAnimal
                || entity instanceof EntityAmbientCreature
                || entity instanceof EntityWaterMob
                || entity instanceof EntityVillager;
    }

    private static boolean isIgnoredLobbyDisplayEntity(EntityLivingBase entity) {
        try {
            if (entity instanceof EntityWither) {
                EntityWither wither = (EntityWither) entity;
                return (wither.isInvisible() && wither.hasCustomName())
                        || (wither.posY > 100.0D && wither.hasCustomName());
            }
            if (entity instanceof EntityDragon) {
                EntityDragon dragon = (EntityDragon) entity;
                return (dragon.isInvisible() && dragon.hasCustomName())
                        || (dragon.posY > 100.0D && dragon.hasCustomName());
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void clearTargets() {
        trackedWorld = null;
        mobToPlayerIds.clear();
        playerAggroCounts.clear();
    }

}
