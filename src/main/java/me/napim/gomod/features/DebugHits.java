package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.PacketEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * When enabled via {@code /debughits}, prints HP removed on melee hits and crit status to chat.
 * Player HP is read from the scoreboard (slot 2) — same source as vanilla/Hypixel nametag hearts.
 * Mob HP uses synced entity health. Never guesses damage.
 */
public final class DebugHits {

    private static boolean enabled;
    /** Last known combat HP per entity (scoreboard HP for players). */
    private static final Map<Integer, Float> trackedHp = new HashMap<Integer, Float>();
    /** Recent local melee swings awaiting a health drop on the target. */
    private static final Map<Integer, PendingStrike> pendingStrikes = new LinkedHashMap<Integer, PendingStrike>();
    private static final long STRIKE_WINDOW_MS = 1200L;

    private static final class PendingStrike {
        final String name;
        final boolean crit;
        final long expiresMs;

        PendingStrike(String name, boolean crit, long expiresMs) {
            this.name = name;
            this.crit = crit;
            this.expiresMs = expiresMs;
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!enabled) {
            trackedHp.clear();
            pendingStrikes.clear();
        }
        ConfigManager.setDebugHitsEnabled(value);
    }

    public static void toggle() {
        setEnabled(!enabled);
    }

    public static void loadSetting() {
        enabled = ConfigManager.isDebugHitsEnabled();
        if (!enabled) {
            trackedHp.clear();
            pendingStrikes.clear();
        }
    }

    /**
     * Called after a melee attack is sent (not cancelled by CPS limiter / roleplay guards).
     */
    public static void onMeleeAttack(EntityPlayer attacker, Entity target) {
        if (!enabled || attacker == null || target == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || attacker != mc.thePlayer) {
            return;
        }
        if (!(target instanceof EntityLivingBase)) {
            return;
        }

        EntityLivingBase living = (EntityLivingBase) target;
        int entityId = target.getEntityId();
        String targetName = target.getName();
        if (targetName == null || targetName.isEmpty()) {
            targetName = "entity";
        }

        float hp = readCombatHp(living);
        trackedHp.put(entityId, hp);

        long expires = System.currentTimeMillis() + STRIKE_WINDOW_MS;
        pendingStrikes.put(entityId, new PendingStrike(
                targetName,
                GomodBlockOverlayPlayerOutline.wouldNextMeleeBeCrit(attacker),
                expires));
    }

    /**
     * Server hurt animation — nudge tracking so scoreboard/health updates are picked up quickly.
     */
    public static void onEntityHurtStatus(EntityLivingBase living) {
        if (!enabled || living == null || !pendingStrikes.containsKey(living.getEntityId())) {
            return;
        }
        float hp = readCombatHp(living);
        Float prev = trackedHp.get(living.getEntityId());
        if (prev != null) {
            tryReportDrop(living.getEntityId(), prev, hp);
        }
    }

    @SubscribeEvent
    public void onPacketReceive(PacketEvent.Receive event) {
        if (!enabled) {
            return;
        }
        try {
            if (!(event.getPacket() instanceof S19PacketEntityStatus)) {
                return;
            }
            S19PacketEntityStatus packet = (S19PacketEntityStatus) event.getPacket();
            if (packet.getOpCode() != 2) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null) {
                return;
            }
            Entity entity = packet.getEntity(mc.theWorld);
            if (entity instanceof EntityLivingBase) {
                onEntityHurtStatus((EntityLivingBase) entity);
            }
        } catch (Throwable ignored) {
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent event) {
        if (!enabled || !ClientTickHub.isFeatureEnabled("DebugHits")) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        long now = System.currentTimeMillis();

        Iterator<Map.Entry<Integer, PendingStrike>> pendingIt = pendingStrikes.entrySet().iterator();
        while (pendingIt.hasNext()) {
            if (now >= pendingIt.next().getValue().expiresMs) {
                pendingIt.remove();
            }
        }

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityLivingBase)) {
                continue;
            }
            EntityLivingBase living = (EntityLivingBase) entity;
            int id = living.getEntityId();
            float hp = readCombatHp(living);
            Float prev = trackedHp.get(id);
            if (prev == null) {
                trackedHp.put(id, hp);
                continue;
            }
            if (hp < prev - 0.001F) {
                tryReportDrop(id, prev, hp);
            } else if (hp > prev + 0.001F) {
                trackedHp.put(id, hp);
            }
        }

        // Drop stale entries for unloaded entities.
        Iterator<Map.Entry<Integer, Float>> trackIt = trackedHp.entrySet().iterator();
        while (trackIt.hasNext()) {
            int id = trackIt.next().getKey();
            if (mc.theWorld.getEntityByID(id) == null) {
                trackIt.remove();
            }
        }
    }

    private static void tryReportDrop(int entityId, float prevHp, float newHp) {
        float lost = prevHp - newHp;
        if (lost <= 0.001F) {
            return;
        }
        trackedHp.put(entityId, newHp);

        PendingStrike strike = pendingStrikes.get(entityId);
        if (strike == null || System.currentTimeMillis() >= strike.expiresMs) {
            return;
        }

        reportHit(strike.name, lost, strike.crit);
        pendingStrikes.remove(entityId);
    }

    /**
     * Hypixel/vanilla player HP lives in scoreboard slot 2 (nametag hearts), not {@code getHealth()}.
     */
    static float readCombatHp(EntityLivingBase living) {
        if (living instanceof EntityPlayer) {
            Float scoreHp = readScoreboardHp((EntityPlayer) living);
            if (scoreHp != null) {
                return scoreHp + living.getAbsorptionAmount();
            }
        }
        return living.getHealth() + living.getAbsorptionAmount();
    }

    private static Float readScoreboardHp(EntityPlayer player) {
        if (player == null || player.worldObj == null) {
            return null;
        }
        try {
            Scoreboard board = player.worldObj.getScoreboard();
            if (board == null) {
                return null;
            }
            ScoreObjective objective = board.getObjectiveInDisplaySlot(2);
            if (objective == null) {
                return null;
            }
            Score score = resolvePlayerScore(board, objective, player);
            if (score == null) {
                return null;
            }
            int points = score.getScorePoints();
            if (points <= 0) {
                return null;
            }
            return (float) points;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Score resolvePlayerScore(Scoreboard board, ScoreObjective objective, EntityPlayer player) {
        Score score = board.getValueFromObjective(player.getName(), objective);
        if (score != null) {
            return score;
        }
        try {
            if (player.getGameProfile() != null && player.getGameProfile().getName() != null) {
                score = board.getValueFromObjective(player.getGameProfile().getName(), objective);
                if (score != null) {
                    return score;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String display = player.getDisplayName() == null ? null : player.getDisplayName().getUnformattedText();
            if (display != null && !display.isEmpty()) {
                score = board.getValueFromObjective(display, objective);
                if (score != null) {
                    return score;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void reportHit(String targetName, float hpLost, boolean crit) {
        String critText = crit
                ? EnumChatFormatting.GREEN + "crit"
                : EnumChatFormatting.GRAY + "no crit";
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Hit "
                + EnumChatFormatting.WHITE + targetName
                + EnumChatFormatting.YELLOW + " for "
                + EnumChatFormatting.WHITE + formatHp(hpLost)
                + EnumChatFormatting.YELLOW + " HP ("
                + critText
                + EnumChatFormatting.YELLOW + ")");
    }

    /** Hypixel/Blitz HP = half-hearts (20 HP = full health bar). */
    private static String formatHp(float hpLost) {
        if (Math.abs(hpLost - Math.round(hpLost)) < 0.05F) {
            return Integer.toString(Math.round(hpLost));
        }
        return String.format("%.1f", hpLost);
    }
}
