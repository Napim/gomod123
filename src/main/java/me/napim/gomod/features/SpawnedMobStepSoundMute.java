package me.napim.gomod.features;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityBlaze;
import net.minecraft.entity.monster.EntityCreeper;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntitySnowman;
import net.minecraft.entity.monster.EntityWitch;
import net.minecraft.entity.passive.EntityOcelot;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Locale;

/**
 * Mutes stepping sounds ({@code step.*}) emitted by Ocelots and Witches spawned by the local
 * player during Hypixel Blitz Survival Games matches, leaving all other mob sounds and all
 * other players' or enemies' footsteps intact.
 */
public final class SpawnedMobStepSoundMute {

    static final double MAX_MATCH_RADIUS = 2.50D;
    static final double MAX_MATCH_RADIUS_SQ = MAX_MATCH_RADIUS * MAX_MATCH_RADIUS;
    /** Entity#playStepSound emits <= 0.15F; block placement of cloth/ladder is ~0.8F. */
    static final float MAX_STEP_SOUND_VOLUME = 0.50F;

    private static final ThreadLocal<Entity> ACTIVE_STEP_ENTITY = new ThreadLocal<>();
    private static volatile boolean debugEverywhereEnabled;
    private static volatile boolean debugLogEnabled;

    private SpawnedMobStepSoundMute() {
    }

    public static boolean isEverywhereDebugEnabled() {
        return GomodInternalFeatures.isEnabled() && debugEverywhereEnabled;
    }

    public static void setEverywhereDebugEnabled(boolean enabled) {
        debugEverywhereEnabled = GomodInternalFeatures.isEnabled() && enabled;
    }

    public static boolean isDebugLogEnabled() {
        return GomodInternalFeatures.isEnabled() && debugLogEnabled;
    }

    public static void setDebugLogEnabled(boolean enabled) {
        debugLogEnabled = GomodInternalFeatures.isEnabled() && enabled;
    }

    public static boolean isContextAllowed(Minecraft mc) {
        if (mc == null || mc.theWorld == null) {
            return false;
        }
        if (isEverywhereDebugEnabled()) {
            return true;
        }
        return GameContext.isStrictBlitzMatchContext(mc);
    }

    /** Called at {@code HEAD} of {@code Entity#playStepSound}. */
    public static boolean shouldMuteEntityStepSound(Entity entity) {
        try {
            if (entity == null || entity.isDead || !ConfigManager.isSilentSpawnedMobStepsEnabled()) {
                return false;
            }
            if (!isTargetMobTypeEnabled(entity,
                    ConfigManager.isSilentSpawnedOcelotSteps(),
                    ConfigManager.isSilentSpawnedWitchSteps())) {
                return false;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (!isContextAllowed(mc)) {
                logTargetEntityStep(entity, false, "context_blocked");
                return false;
            }
            flushPendingSpawnMatches();
            boolean owned = isOwnedTargetMob(mc, entity);
            logTargetEntityStep(entity, owned, owned ? "muted_entity_step" : "not_player_spawned");
            return owned;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Records the non-cancelled entity currently executing {@code Entity#playStepSound}. */
    public static void enterPlayStepSound(Entity entity) {
        if (entity != null) {
            ACTIVE_STEP_ENTITY.set(entity);
        }
    }

    /** Clears the active {@code Entity#playStepSound} entity when that call returns. */
    public static void exitPlayStepSound(Entity entity) {
        if (entity != null && ACTIVE_STEP_ENTITY.get() == entity) {
            ACTIVE_STEP_ENTITY.remove();
        }
    }

    /**
     * Called at {@code HEAD} of {@code SoundManager#playSound(ISound)} to filter server-sent
     * positional step sounds ({@code S29PacketSoundEffect}) from our spawned Ocelots/Witches.
     */
    public static boolean shouldMutePlayedSound(ISound sound) {
        try {
            if (sound == null || !ConfigManager.isSilentSpawnedMobStepsEnabled()) {
                return false;
            }
            ResourceLocation location = sound.getSoundLocation();
            String path = location == null ? null : location.getResourcePath();
            if (!isStepSoundPath(path)) {
                return false;
            }
            if (!isStepSoundVolume(sound.getVolume())) {
                dbg("step kept path=" + path + " vol=" + sound.getVolume() + " reason=high_volume");
                return false;
            }
            // Sounds triggered inside a non-cancelled Entity#playStepSound (such as the local player's
            // own footsteps) belong directly to that entity and must never be misattributed by position.
            Entity activeEntity = ACTIVE_STEP_ENTITY.get();
            if (activeEntity != null) {
                dbg("step kept path=" + path + " activeEntity=" + activeEntity.getClass().getSimpleName()
                        + " id=" + activeEntity.getEntityId() + " reason=local_entity_step");
                return false;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                return false;
            }
            if (!isContextAllowed(mc)) {
                dbg("step kept path=" + path + " vol=" + String.format(Locale.ROOT, "%.2f", sound.getVolume())
                        + " reason=not_blitz_ctx (" + GameContext.explainDetection(mc) + ")");
                return false;
            }
            flushPendingSpawnMatches();

            boolean muteOcelots = ConfigManager.isSilentSpawnedOcelotSteps();
            boolean muteWitches = ConfigManager.isSilentSpawnedWitchSteps();
            if (!muteOcelots && !muteWitches) {
                return false;
            }

            return matchesClosestOwnedTargetMob(mc, path, sound.getVolume(),
                    sound.getXPosF(), sound.getYPosF(), sound.getZPosF(),
                    muteOcelots, muteWitches);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Prints current configuration, Blitz/everywhere gating, and nearby Ocelots/Witches to chat. */
    @SuppressWarnings("unchecked")
    public static void debugPrintStatus() {
        Minecraft mc = Minecraft.getMinecraft();
        boolean master = ConfigManager.isSilentSpawnedMobStepsEnabled();
        boolean ocelot = ConfigManager.isSilentSpawnedOcelotSteps();
        boolean witch = ConfigManager.isSilentSpawnedWitchSteps();
        boolean everywhere = isEverywhereDebugEnabled();
        boolean log = isDebugLogEnabled();
        boolean strictBlitz = mc != null && GameContext.isStrictBlitzMatchContext(mc);
        boolean active = master && (everywhere || strictBlitz);

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "[mobSteps] "
                + EnumChatFormatting.GRAY + "enabled=" + flag(master)
                + " ocelot=" + flag(ocelot)
                + " witch=" + flag(witch)
                + " everywhere=" + flag(everywhere)
                + " log=" + flag(log));
        Main.sendMessage(EnumChatFormatting.GRAY + "  contextAllowed=" + flag(active)
                + " strictBlitz=" + flag(strictBlitz)
                + " " + SpawnedMobTracker.debugStatusSummary());
        if (mc != null && mc.theWorld != null) {
            Main.sendMessage(EnumChatFormatting.DARK_GRAY + "  ctx: " + GameContext.explainDetection(mc));
            int found = 0;
            for (Entity entity : (List<Entity>) mc.theWorld.loadedEntityList) {
                if (!(entity instanceof EntityOcelot || entity instanceof EntityWitch) || entity.isDead) {
                    continue;
                }
                found++;
                if (found <= 6) {
                    boolean tracker = SpawnedMobTracker.isPlayerSpawned(entity);
                    boolean tamedOwner = isTamedOwnedOcelot(mc, entity);
                    Main.sendMessage(EnumChatFormatting.GRAY + "  mob " + entity.getClass().getSimpleName()
                            + " id=" + entity.getEntityId()
                            + " tracked=" + flag(tracker)
                            + " tamedOwner=" + flag(tamedOwner)
                            + String.format(Locale.ROOT, " pos=(%.1f,%.1f,%.1f)",
                                    entity.posX, entity.posY, entity.posZ));
                }
            }
            if (found == 0) {
                Main.sendMessage(EnumChatFormatting.DARK_GRAY + "  (no loaded Ocelots or Witches in world)");
            }
        }
    }

    static boolean isStepSoundPath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.startsWith("step.")
                || lower.contains(":step.")
                || lower.contains("footstep");
    }

    static boolean isStepSoundVolume(float volume) {
        return volume >= 0.0F && volume <= MAX_STEP_SOUND_VOLUME;
    }

    static boolean isTargetMobTypeEnabled(Entity entity, boolean muteOcelots, boolean muteWitches) {
        return (muteOcelots && entity instanceof EntityOcelot)
                || (muteWitches && entity instanceof EntityWitch);
    }

    static boolean selectMuteDecision(double bestMutedDistSq, double bestCompetingDistSq) {
        return bestMutedDistSq <= MAX_MATCH_RADIUS_SQ && bestMutedDistSq < bestCompetingDistSq;
    }

    private static void flushPendingSpawnMatches() {
        if (SpawnedMobTracker.hasPendingSpawns()) {
            SpawnedMobTracker.clientTick();
        }
    }

    private static boolean isOwnedTargetMob(Minecraft mc, Entity entity) {
        return SpawnedMobTracker.isPlayerSpawned(entity) || isTamedOwnedOcelot(mc, entity);
    }

    private static boolean isTamedOwnedOcelot(Minecraft mc, Entity entity) {
        if (entity instanceof EntityOcelot) {
            EntityOcelot ocelot = (EntityOcelot) entity;
            if (ocelot.isTamed() && mc != null && mc.thePlayer != null) {
                if (ocelot.isOwner(mc.thePlayer)) {
                    return true;
                }
                String ownerId = ocelot.getOwnerId();
                if (ownerId != null && !ownerId.isEmpty() && mc.thePlayer.getUniqueID() != null) {
                    String localDashless = mc.thePlayer.getUniqueID().toString().replace("-", "");
                    return localDashless.equalsIgnoreCase(ownerId.replace("-", ""));
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesClosestOwnedTargetMob(
            Minecraft mc, String path, float volume, double sx, double sy, double sz,
            boolean muteOcelots, boolean muteWitches) {
        List<Entity> entities = mc.theWorld.loadedEntityList;
        if (entities == null || entities.isEmpty()) {
            return false;
        }

        double bestMutedDistSq = Double.MAX_VALUE;
        Entity bestMutedEntity = null;
        double bestCompetingDistSq = Double.MAX_VALUE;
        Entity bestCompetingEntity = null;

        for (Entity entity : entities) {
            if (entity == null || entity.isDead || entity == mc.thePlayer) {
                continue;
            }
            if (isTargetMobTypeEnabled(entity, muteOcelots, muteWitches) && isOwnedTargetMob(mc, entity)) {
                double distSq = minEntityDistanceSq(entity, sx, sy, sz);
                if (distSq < bestMutedDistSq) {
                    bestMutedDistSq = distSq;
                    bestMutedEntity = entity;
                }
                continue;
            }
            if (isCompetingStepSoundEntity(entity)) {
                double distSq = minEntityDistanceSq(entity, sx, sy, sz);
                if (distSq < bestCompetingDistSq) {
                    bestCompetingDistSq = distSq;
                    bestCompetingEntity = entity;
                }
            }
        }
        boolean mute = selectMuteDecision(bestMutedDistSq, bestCompetingDistSq);
        if (isDebugLogEnabled() && bestMutedEntity != null) {
            dbg(String.format(Locale.ROOT,
                    "step %s path=%s vol=%.2f target=%s#%d(d=%.2f) comp=%s(d=%s)",
                    mute ? "MUTED" : "KEPT",
                    path, volume,
                    bestMutedEntity.getClass().getSimpleName(), bestMutedEntity.getEntityId(),
                    Math.sqrt(bestMutedDistSq),
                    bestCompetingEntity == null ? "none" : bestCompetingEntity.getClass().getSimpleName(),
                    bestCompetingEntity == null ? "-" : String.format(Locale.ROOT, "%.2f", Math.sqrt(bestCompetingDistSq))));
        } else if (isDebugLogEnabled()) {
            dbg(String.format(Locale.ROOT,
                    "step KEPT path=%s vol=%.2f target=none(everywhere=%s) at=(%.1f,%.1f,%.1f)",
                    path, volume, isEverywhereDebugEnabled(), sx, sy, sz));
        }
        return mute;
    }

    private static boolean isCompetingStepSoundEntity(Entity entity) {
        if (entity instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) entity;
            return !player.isSpectator() && !BlitzPregamePlayerToggle.shouldHide(player);
        }
        return entity instanceof EntityOcelot
                || entity instanceof EntityWitch
                || entity instanceof EntityCreeper
                || entity instanceof EntityVillager
                || entity instanceof EntityBlaze
                || entity instanceof EntityIronGolem
                || entity instanceof EntitySnowman;
    }

    private static double minEntityDistanceSq(Entity entity, double sx, double sy, double sz) {
        double dx = entity.posX - sx;
        double dy = entity.posY - sy;
        double dz = entity.posZ - sz;
        double best = dx * dx + dy * dy + dz * dz;
        if (entity.serverPosX != 0 || entity.serverPosY != 0 || entity.serverPosZ != 0) {
            double sdx = (entity.serverPosX / 32.0D) - sx;
            double sdy = (entity.serverPosY / 32.0D) - sy;
            double sdz = (entity.serverPosZ / 32.0D) - sz;
            double serverDistSq = sdx * sdx + sdy * sdy + sdz * sdz;
            if (serverDistSq < best) {
                best = serverDistSq;
            }
        }
        return best;
    }

    private static void logTargetEntityStep(Entity entity, boolean muted, String reason) {
        if (!isDebugLogEnabled()) {
            return;
        }
        dbg("entityPlayStepSound " + entity.getClass().getSimpleName() + "#" + entity.getEntityId()
                + " muted=" + muted + " reason=" + reason);
    }

    private static void dbg(String msg) {
        if (!isDebugLogEnabled()) {
            return;
        }
        try {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.DARK_GRAY + "[mobSteps] "
                    + EnumChatFormatting.GRAY + msg);
        } catch (Throwable ignored) {
        }
    }

    private static String flag(boolean value) {
        return value ? EnumChatFormatting.GREEN + "ON" + EnumChatFormatting.GRAY
                : EnumChatFormatting.RED + "OFF" + EnumChatFormatting.GRAY;
    }
}
