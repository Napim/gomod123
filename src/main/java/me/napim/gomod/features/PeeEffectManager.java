package me.napim.gomod.features;

import me.napim.gomod.util.CompactUuid;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.client.model.ModelBiped;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.particle.EntityReddustFX;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.MathHelper;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.lwjgl.opengl.GL11;

/**
 * Yellow droplet gag rendered for the local player and relayed gomod players on the same server.
 * Runs for a fixed duration after {@link #activate()}; plays {@code liquid.water} once per second from each
 * participating player's position.
 */
public final class PeeEffectManager {

    private static final long DURATION_MS = 6500L;
    /** Last in-series play at {@code scheduleStart + 6000} (seven plays at 0–6s inside a 6.5s window). */
    private static final long LAST_SOUND_OFFSET_MS = 6000L;

    private static volatile long particlesUntilMs;
    /** Lower-case Minecraft name to relay-synchronized effect end time. */
    private static final Map<String, Long> REMOTE_UNTIL_BY_NAME = new ConcurrentHashMap<String, Long>();
    /** Normalized UUID to relay-synchronized effect end time; survives nick/display-name differences. */
    private static final Map<String, Long> REMOTE_UNTIL_BY_UUID = new ConcurrentHashMap<String, Long>();
    /** Relay event timestamp used to distinguish a new /pee from repeated poll delivery of the same event. */
    private static final Map<String, Long> REMOTE_EVENT_TS_BY_NAME = new ConcurrentHashMap<String, Long>();
    private static final Map<String, Long> REMOTE_EVENT_TS_BY_UUID = new ConcurrentHashMap<String, Long>();
    /** Next positional water sound due for each relayed player. */
    private static final Map<String, Long> REMOTE_NEXT_SOUND_BY_NAME = new ConcurrentHashMap<String, Long>();
    private static final Map<String, Long> REMOTE_NEXT_SOUND_BY_UUID = new ConcurrentHashMap<String, Long>();
    /** Wall time of current /pee session start; 0 = never activated this JVM. */
    private static long soundScheduleStartMs;
    /** Next {@link EntityPlayer#playSound} due at this time; only meaningful with non-zero {@link #soundScheduleStartMs}. */
    private static long nextSoundAtMs;

    private static Constructor<EntityReddustFX> reddustCtor;
    /** Remote entity IDs whose yaw was changed by us and must be restored after their event ends. */
    private static final Set<Integer> REMOTE_WIGGLED_ENTITY_IDS =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
    private static WorldClient remoteWiggleWorld;
    private static final long STAIN_LIFETIME_MS = 6000L;
    private static final double SURFACE_PIXEL_SIZE = 1.0D / 16.0D;
    private static final double SURFACE_PIXEL_HALF = SURFACE_PIXEL_SIZE / 2.0D;
    private static final double SURFACE_EPSILON = 0.002D;
    private static final double STREAM_TRAVEL_BLOCKS_PER_TICK = 0.22D;
    private static final long MILLIS_PER_TICK = 50L;
    private static final int MAX_SURFACE_STAINS = 240;
    private static final List<SurfaceStain> SURFACE_STAINS = new ArrayList<SurfaceStain>();
    private static final Map<Integer, Long> LAST_PLAYER_STAIN_RENDER_NS =
            new ConcurrentHashMap<Integer, Long>();
    private static WorldClient stainWorld;
    private static boolean stainSpawnLogged;
    private static boolean stainRenderLogged;

    private PeeEffectManager() {
    }

    private static Constructor<EntityReddustFX> reddustCtor() throws Exception {
        if (reddustCtor == null) {
            Constructor<EntityReddustFX> c = EntityReddustFX.class.getDeclaredConstructor(
                    World.class, double.class, double.class, double.class, float.class, float.class, float.class);
            c.setAccessible(true);
            reddustCtor = c;
        }
        return reddustCtor;
    }

    public static boolean isActive() {
        return System.currentTimeMillis() < particlesUntilMs;
    }

    /** First-person held item/arm should use the centered pee pose instead of vanilla side offsets. */
    public static boolean shouldOverrideFirstPersonHeldItem() {
        Minecraft mc = Minecraft.getMinecraft();
        return isActive()
                && mc != null
                && mc.gameSettings != null
                && mc.gameSettings.thirdPersonView == 0;
    }

    public static boolean isActiveForLocalPlayer(Entity entity) {
        if (!isActive() || entity == null) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && entity.getEntityId() == mc.thePlayer.getEntityId();
    }

    /** True when this particular player should use the pee pose and held-item transforms. */
    public static boolean isActiveForPlayer(Entity entity) {
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }
        if (isActiveForLocalPlayer(entity)) {
            return true;
        }
        if (!REMOTE_UNTIL_BY_UUID.isEmpty() && entity.getUniqueID() != null) {
            Long until = REMOTE_UNTIL_BY_UUID.get(CompactUuid.key(entity.getUniqueID()));
            if (until != null && System.currentTimeMillis() < until.longValue()) {
                return true;
            }
        }
        if (REMOTE_UNTIL_BY_NAME.isEmpty()) return false;
        String name = entity.getName();
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        Long until = REMOTE_UNTIL_BY_NAME.get(nameKey(name));
        return until != null && System.currentTimeMillis() < until.longValue();
    }

    /** Applies a server-timestamped relay event without restarting already elapsed time. */
    public static void activateRemote(String playerName, long eventTs) {
        activateRemote(playerName, "", eventTs);
    }

    /** Applies a server-timestamped relay event without restarting already elapsed time. */
    public static void activateRemote(String playerName, String playerUuid, long eventTs) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        String uuidKey = normalizeUuid(playerUuid);
        if (mc != null && mc.thePlayer != null
                && (playerName.equalsIgnoreCase(mc.thePlayer.getName())
                || (!uuidKey.isEmpty() && mc.thePlayer.getUniqueID() != null
                && uuidKey.equals(normalizeUuid(mc.thePlayer.getUniqueID().toString()))))) {
            return;
        }
        long now = System.currentTimeMillis();
        long age = Math.max(0L, now - eventTs);
        if (age >= DURATION_MS) {
            return;
        }
        long until = now + (DURATION_MS - age);
        rememberRemoteActivation(REMOTE_UNTIL_BY_NAME, REMOTE_EVENT_TS_BY_NAME, REMOTE_NEXT_SOUND_BY_NAME,
                nameKey(playerName), until, eventTs, now);
        if (!uuidKey.isEmpty()) {
            rememberRemoteActivation(REMOTE_UNTIL_BY_UUID, REMOTE_EVENT_TS_BY_UUID, REMOTE_NEXT_SOUND_BY_UUID,
                    uuidKey, until, eventTs, now);
        }
    }

    private static void rememberRemoteActivation(Map<String, Long> untilByKey, Map<String, Long> eventTsByKey,
            Map<String, Long> nextSoundByKey, String key, long until, long eventTs, long now) {
        if (key == null || key.isEmpty()) {
            return;
        }
        Long previous = untilByKey.get(key);
        if (previous == null || until > previous.longValue()) {
            untilByKey.put(key, until);
        }
        Long previousEventTs = eventTsByKey.get(key);
        if (previousEventTs == null || eventTs > previousEventTs.longValue()) {
            eventTsByKey.put(key, eventTs);
            nextSoundByKey.put(key, now);
        }
    }

    /**
     * Centered first-person hand/item matrix — same groin pose whether the hand is empty or holding something.
     * Vanilla {@code ItemRenderer} item-use paths (block, bow, eat, etc.) add large side offsets; callers skip those
     * before invoking this.
     */
    public static void applyFirstPersonHeldItemTransform(float equippedProgress, float swingProgress, float ageInTicks) {
        float wiggle = MathHelper.sin(ageInTicks * 0.55f) * 0.06f;
        float sideWiggle = MathHelper.sin(ageInTicks * 0.85f) * 0.18f;

        float swingX = -0.4F * MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
        float swingY = 0.2F * MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI * 2.0F);
        float swingZ = -0.2F * MathHelper.sin(swingProgress * (float) Math.PI);
        GlStateManager.translate(swingX, swingY, swingZ);

        GlStateManager.translate(sideWiggle * 0.04F, -0.40F, -0.60F);
        GlStateManager.translate(0.0F, equippedProgress * -0.25F, 0.0F);
        GlStateManager.rotate(72.0F + wiggle * 18.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate(sideWiggle * 14.0F, 0.0F, 1.0F, 0.0F);

        float swingYaw = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
        float swingRoll = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
        GlStateManager.rotate(swingYaw * -12.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(swingRoll * -10.0F, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(swingRoll * -35.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(0.38F, 0.38F, 0.38F);
    }

    /** Native-Weave entry point: apply the held-item matrix and tell the caller to skip vanilla's matrix. */
    public static boolean applyFirstPersonHeldItemTransformIfActive(float equippedProgress, float swingProgress) {
        if (!shouldOverrideFirstPersonHeldItem()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        float ageInTicks = mc != null && mc.thePlayer != null ? mc.thePlayer.ticksExisted : 0.0F;
        applyFirstPersonHeldItemTransform(equippedProgress, swingProgress, ageInTicks);
        return true;
    }

    /** Native-Weave entry point for the first-person player arm model. */
    public static void applyPlayerModelPoseIfActive(ModelBiped model, float ageInTicks, Entity entity) {
        if (model == null || !isActiveForPlayer(entity)) {
            return;
        }
        float wiggle = MathHelper.sin(ageInTicks * 0.55F) * 0.06F;
        Minecraft mc = Minecraft.getMinecraft();
        boolean localPlayer = mc != null && mc.thePlayer != null
                && entity.getEntityId() == mc.thePlayer.getEntityId();
        boolean thirdPerson = !localPlayer || (mc != null && mc.gameSettings != null
                && mc.gameSettings.thirdPersonView != 0);
        float sideWiggle = thirdPerson ? MathHelper.sin(ageInTicks * 0.85F) * 0.18F : 0.0F;
        float bodyTwist = thirdPerson ? MathHelper.sin(ageInTicks * 0.70F) * 0.10F : 0.0F;

        model.bipedRightArm.rotateAngleX = (thirdPerson ? -0.62F : -1.22F) + wiggle;
        model.bipedRightArm.rotateAngleY = -0.38F + sideWiggle;
        model.bipedRightArm.rotateAngleZ = thirdPerson ? -0.42F : 0.30F;
        model.bipedLeftArm.rotateAngleX = (thirdPerson ? -0.58F : -1.18F) - wiggle;
        model.bipedLeftArm.rotateAngleY = 0.38F + sideWiggle;
        model.bipedLeftArm.rotateAngleZ = thirdPerson ? 0.42F : -0.30F;
        model.bipedBody.rotateAngleX = 0.08F;
        model.bipedBody.rotateAngleY = bodyTwist;
        model.bipedHead.rotateAngleX -= 0.06F;

        // ModelPlayer copied these angles before this tail hook ran. Keep jacket sleeves on the moved arms.
        if (model instanceof ModelPlayer) {
            ModelPlayer playerModel = (ModelPlayer) model;
            copyModelPartPose(model.bipedRightArm, playerModel.bipedRightArmwear);
            copyModelPartPose(model.bipedLeftArm, playerModel.bipedLeftArmwear);
        }
    }

    private static void copyModelPartPose(ModelRenderer source, ModelRenderer target) {
        if (source == null || target == null) {
            return;
        }
        target.rotateAngleX = source.rotateAngleX;
        target.rotateAngleY = source.rotateAngleY;
        target.rotateAngleZ = source.rotateAngleZ;
        target.rotationPointX = source.rotationPointX;
        target.rotationPointY = source.rotationPointY;
        target.rotationPointZ = source.rotationPointZ;
    }

    /** Nudge third-person held items toward the groin center after the arm bone attach. */
    public static void applyThirdPersonHeldItemCorrection() {
        GlStateManager.translate(0.10F, -0.43F, 0.06F);
        GlStateManager.rotate(54.0F, 1.0F, 0.0F, 0.0F);
        GlStateManager.rotate(-10.0F, 0.0F, 1.0F, 0.0F);
    }

    /** Starts or extends particles for 6.5s from now and restarts the 1 Hz water sound series. */
    public static void activate() {
        long now = System.currentTimeMillis();
        particlesUntilMs = now + DURATION_MS;
        soundScheduleStartMs = now;
        nextSoundAtMs = now;
    }

    private static void tickScheduledWaterSounds(EntityPlayer player, long now) {
        if (player == null || soundScheduleStartMs == 0L) {
            return;
        }
        final long lastSlot = soundScheduleStartMs + LAST_SOUND_OFFSET_MS;
        while (nextSoundAtMs <= lastSlot && now >= nextSoundAtMs) {
            player.playSound("liquid.water", 1.0f, 2.0f);
            nextSoundAtMs += 1000L;
        }
    }

    private static void tickRemoteWaterSound(EntityPlayer player, long now) {
        if (player == null) {
            return;
        }
        String uuidKey = player.getUniqueID() == null ? "" : CompactUuid.key(player.getUniqueID());
        String nameKey = nameKey(player.getName());
        String soundKey = "";
        Long next = null;
        if (!uuidKey.isEmpty()) {
            next = REMOTE_NEXT_SOUND_BY_UUID.get(uuidKey);
            soundKey = uuidKey;
        }
        if (next == null && !nameKey.isEmpty()) {
            next = REMOTE_NEXT_SOUND_BY_NAME.get(nameKey);
            soundKey = nameKey;
        }
        if (next == null || now < next.longValue()) {
            return;
        }

        // WorldClient#playSoundAtEntity deliberately accepts only the local player, so Entity#playSound silently
        // drops remote cosmetic sounds. Send an explicitly positioned record to the client sound handler instead.
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.getSoundHandler() != null) {
            mc.getSoundHandler().playSound(new RemotePeeWaterSound(
                    player.posX, player.posY + player.height * 0.32D, player.posZ));
        }
        long nextAt = next.longValue() + 1000L;
        while (nextAt <= now) {
            nextAt += 1000L;
        }
        if (!uuidKey.isEmpty() && uuidKey.equals(soundKey)) {
            REMOTE_NEXT_SOUND_BY_UUID.put(uuidKey, nextAt);
        }
        if (!nameKey.isEmpty()) {
            REMOTE_NEXT_SOUND_BY_NAME.put(nameKey, nextAt);
        }
    }

    private static final class RemotePeeWaterSound extends PositionedSound {
        RemotePeeWaterSound(double x, double y, double z) {
            super(new ResourceLocation("liquid.water"));
            this.volume = 1.0F;
            this.pitch = 2.0F;
            this.xPosF = (float) x;
            this.yPosF = (float) y;
            this.zPosF = (float) z;
            this.attenuationType = ISound.AttenuationType.LINEAR;
        }
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc != null ? mc.theWorld : null;
        EntityPlayer player = mc != null ? mc.thePlayer : null;
        long now = System.currentTimeMillis();

        tickScheduledWaterSounds(player, now);

        if (world == null || player == null || mc.effectRenderer == null) {
            return;
        }
        if (remoteWiggleWorld != world) {
            REMOTE_WIGGLED_ENTITY_IDS.clear();
            remoteWiggleWorld = world;
        }
        if (stainWorld != world) {
            SURFACE_STAINS.clear();
            stainWorld = world;
        }
        final Constructor<EntityReddustFX> ctor;
        try {
            ctor = reddustCtor();
        } catch (Exception ex) {
            return;
        }

        if (isActive()) {
            spawnParticles(mc, world, player, ctor);
        }
        for (EntityPlayer candidate : world.playerEntities) {
            if (candidate == null || candidate.getEntityId() == player.getEntityId()) continue;
            boolean remoteActive = isActiveForPlayer(candidate);
            if (remoteActive) {
                tickRemoteWaterSound(candidate, now);
                // Match the same visible side-to-side dance used for our own third-person model.
                float t = (float) ((now % 100000L) / 1000.0);
                float yawWiggle = (float) Math.sin(t * 7.0f) * 8.0f;
                candidate.renderYawOffset = candidate.rotationYaw + yawWiggle;
                candidate.rotationYawHead = candidate.rotationYaw + yawWiggle;
                REMOTE_WIGGLED_ENTITY_IDS.add(candidate.getEntityId());
                spawnParticles(mc, world, candidate, ctor);
            } else if (REMOTE_WIGGLED_ENTITY_IDS.remove(candidate.getEntityId())) {
                candidate.renderYawOffset = candidate.rotationYaw;
                candidate.rotationYawHead = candidate.rotationYaw;
            }
        }

        for (Map.Entry<String, Long> entry : REMOTE_UNTIL_BY_NAME.entrySet()) {
            if (entry.getValue() == null || now >= entry.getValue().longValue()) {
                String key = entry.getKey();
                REMOTE_UNTIL_BY_NAME.remove(key, entry.getValue());
                REMOTE_EVENT_TS_BY_NAME.remove(key);
                REMOTE_NEXT_SOUND_BY_NAME.remove(key);
            }
        }
        for (Map.Entry<String, Long> entry : REMOTE_UNTIL_BY_UUID.entrySet()) {
            if (entry.getValue() == null || now >= entry.getValue().longValue()) {
                String key = entry.getKey();
                REMOTE_UNTIL_BY_UUID.remove(key, entry.getValue());
                REMOTE_EVENT_TS_BY_UUID.remove(key);
                REMOTE_NEXT_SOUND_BY_UUID.remove(key);
            }
        }
    }

    private static String nameKey(String value) {
        return value == null || value.trim().isEmpty() ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeUuid(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().replace("-", "").toLowerCase(Locale.ROOT);
        if (normalized.length() != 32) {
            return "";
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.digit(normalized.charAt(i), 16) < 0) {
                return "";
            }
        }
        return normalized;
    }

    private static void spawnParticles(Minecraft mc, WorldClient world, EntityPlayer player,
                                       Constructor<EntityReddustFX> ctor) {
        Vec3 look = player.getLook(1.0F);
        double yawRad = Math.toRadians(player.rotationYaw);
        double fx = -Math.sin(yawRad) * 0.12;
        double fz = Math.cos(yawRad) * 0.12;
        double groinY = player.posY + Math.min(0.52, player.height * 0.32);
        double px = player.posX + fx;
        double py = groinY;
        double pz = player.posZ + fz;
        java.util.Random r = world.rand;
        for (int i = 0; i < 7; i++) {
            double ox = (r.nextDouble() - 0.5) * 0.06;
            double oy = (r.nextDouble() - 0.5) * 0.05;
            double oz = (r.nextDouble() - 0.5) * 0.06;
            float pr = 0.98f + r.nextFloat() * 0.02f;
            float pg = 0.88f + r.nextFloat() * 0.08f;
            float pb = 0.12f + r.nextFloat() * 0.08f;
            EntityReddustFX dust;
            try {
                dust = ctor.newInstance(world, px + ox, py + oy, pz + oz, pr, pg, pb);
            } catch (Exception ex) {
                return;
            }
            double spread = 0.02;
            dust.motionX = look.xCoord * 0.22 + (r.nextDouble() - 0.5) * spread;
            dust.motionY = look.yCoord * 0.18 - 0.04 + (r.nextDouble() - 0.5) * spread;
            dust.motionZ = look.zCoord * 0.22 + (r.nextDouble() - 0.5) * spread;
            dust.multipleParticleScaleBy(0.65f + r.nextFloat() * 0.35f);
            mc.effectRenderer.addEffect(dust);
        }
        spawnImpactStain(mc, world, player, new Vec3(px, py, pz), look);
    }

    private static void spawnImpactStain(Minecraft mc, WorldClient world, EntityPlayer source,
                                         Vec3 start, Vec3 look) {
        Vec3 direction = new Vec3(look.xCoord, look.yCoord * 0.65D - 0.28D, look.zCoord).normalize();
        Vec3 end = start.addVector(direction.xCoord * 4.2D, direction.yCoord * 4.2D,
                direction.zCoord * 4.2D);
        MovingObjectPosition blockHit = world.rayTraceBlocks(start, end, false, true, false);
        Vec3 hit = blockHit == null ? null : blockHit.hitVec;
        double bestDistance = hit == null ? Double.MAX_VALUE : start.squareDistanceTo(hit);
        EntityPlayer hitPlayer = null;

        AxisAlignedBB sweep = new AxisAlignedBB(
                Math.min(start.xCoord, end.xCoord), Math.min(start.yCoord, end.yCoord),
                Math.min(start.zCoord, end.zCoord), Math.max(start.xCoord, end.xCoord),
                Math.max(start.yCoord, end.yCoord), Math.max(start.zCoord, end.zCoord)).expand(0.35D, 0.35D, 0.35D);
        List<Entity> candidates = world.getEntitiesWithinAABBExcludingEntity(source, sweep);
        for (Entity candidate : candidates) {
            if (!(candidate instanceof EntityPlayer) || !candidate.canBeCollidedWith()) {
                continue;
            }
            MovingObjectPosition intercept = candidate.getEntityBoundingBox().expand(0.18D, 0.18D, 0.18D)
                    .calculateIntercept(start, end);
            if (intercept == null || intercept.hitVec == null) {
                continue;
            }
            double distance = start.squareDistanceTo(intercept.hitVec);
            if (distance < bestDistance) {
                bestDistance = distance;
                hit = intercept.hitVec;
                hitPlayer = (EntityPlayer) candidate;
            }
        }
        if (hit == null) {
            return;
        }

        java.util.Random random = world.rand;
        EnumFacing face = hitPlayer == null && blockHit != null ? blockHit.sideHit : null;
        if (face == null) face = EnumFacing.UP;
        double[] tangent = tangentJitter(face, (random.nextDouble() - 0.5D) * 0.13D,
                (random.nextDouble() - 0.5D) * 0.13D);
        double x = hit.xCoord + tangent[0];
        double y = hit.yCoord + tangent[1];
        double z = hit.zCoord + tangent[2];
        long travelMs = Math.round(Math.sqrt(bestDistance) / STREAM_TRAVEL_BLOCKS_PER_TICK * MILLIS_PER_TICK);
        long impactAtMs = System.currentTimeMillis() + Math.max(0L, travelMs);
        SurfaceStain stain = hitPlayer == null
                ? SurfaceStain.onBlock(x, y, z, face, impactAtMs)
                : SurfaceStain.onPlayer(hitPlayer, x, y, z, impactAtMs);
        SURFACE_STAINS.add(stain);
        if (!stainSpawnLogged) {
            stainSpawnLogged = true;
            System.out.println("[gomod] /pee surface stain created (face=" + face
                    + ", player=" + (hitPlayer != null) + ")");
        }
        while (SURFACE_STAINS.size() > MAX_SURFACE_STAINS) {
            SURFACE_STAINS.remove(0);
        }
    }

    private static EnumFacing closestLocalPlayerFace(double x, double y, double z,
                                                      double halfWidth, double height) {
        double best = Math.abs(y - height);
        EnumFacing face = EnumFacing.UP;
        double distance = Math.abs(y);
        if (distance < best) { best = distance; face = EnumFacing.DOWN; }
        distance = Math.abs(x + halfWidth);
        if (distance < best) { best = distance; face = EnumFacing.WEST; }
        distance = Math.abs(x - halfWidth);
        if (distance < best) { best = distance; face = EnumFacing.EAST; }
        distance = Math.abs(z + halfWidth);
        if (distance < best) { best = distance; face = EnumFacing.NORTH; }
        distance = Math.abs(z - halfWidth);
        if (distance < best) { face = EnumFacing.SOUTH; }
        return face;
    }

    private static double snapSurfacePixel(double value) {
        return Math.floor(value / SURFACE_PIXEL_SIZE) * SURFACE_PIXEL_SIZE + SURFACE_PIXEL_HALF;
    }

    private static double clampSurfacePixel(double value, double min, double max) {
        return Math.max(min, Math.min(max, snapSurfacePixel(value)));
    }

    private static double[] tangentJitter(EnumFacing face, double a, double b) {
        if (face.getAxis() == EnumFacing.Axis.Y) {
            return new double[] {a, 0.0D, b};
        }
        if (face.getAxis() == EnumFacing.Axis.X) {
            return new double[] {0.0D, a, b};
        }
        return new double[] {a, b, 0.0D};
    }

    /** Draws the landed residue as translucent surface color, without a Minecraft particle texture. */
    public static void renderSurfaceStains(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.getRenderViewEntity() == null || SURFACE_STAINS.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Entity camera = mc.getRenderViewEntity();
        double camX = camera.lastTickPosX + (camera.posX - camera.lastTickPosX) * partialTicks;
        double camY = camera.lastTickPosY + (camera.posY - camera.lastTickPosY) * partialTicks;
        double camZ = camera.lastTickPosZ + (camera.posZ - camera.lastTickPosZ) * partialTicks;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.enableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(-1.0F, -10.0F);
            GL11.glShadeModel(GL11.GL_SMOOTH);

            if (!stainRenderLogged) {
                stainRenderLogged = true;
                System.out.println("[gomod] /pee surface stain renderer active");
            }

            Iterator<SurfaceStain> iterator = SURFACE_STAINS.iterator();
            while (iterator.hasNext()) {
                SurfaceStain stain = iterator.next();
                long ageMs = now - stain.createdAtMs;
                if (ageMs < 0L) {
                    continue;
                }
                float remaining = ageMs < 4000L
                        ? 1.0F
                        : 1.0F - (float) (ageMs - 4000L) / 2000.0F;
                if (remaining <= 0.0F || (stain.attachedTo != null && stain.attachedTo.isDead)) {
                    iterator.remove();
                    continue;
                }
                if (stain.attachedTo != null) {
                    continue;
                } else {
                    drawSurfacePixel(stain.x - camX, stain.y - camY, stain.z - camZ, stain.face,
                            0.58F * remaining);
                }
            }
        } finally {
            GlStateManager.doPolygonOffset(0.0F, 0.0F);
            GlStateManager.disablePolygonOffset();
            GlStateManager.depthMask(true);
            GlStateManager.enableCull();
            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
            GL11.glPopAttrib();
        }
    }

    private static void drawSurfacePixel(double x, double y, double z, EnumFacing face, float alpha) {
        double[] a = tangentJitter(face, -SURFACE_PIXEL_HALF, -SURFACE_PIXEL_HALF);
        double[] b = tangentJitter(face, SURFACE_PIXEL_HALF, -SURFACE_PIXEL_HALF);
        double[] c = tangentJitter(face, SURFACE_PIXEL_HALF, SURFACE_PIXEL_HALF);
        double[] d = tangentJitter(face, -SURFACE_PIXEL_HALF, SURFACE_PIXEL_HALF);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(1.0F, 0.84F, 0.04F, alpha);
        GL11.glVertex3d(x + a[0], y + a[1], z + a[2]);
        GL11.glVertex3d(x + b[0], y + b[1], z + b[2]);
        GL11.glVertex3d(x + c[0], y + c[1], z + c[2]);
        GL11.glVertex3d(x + d[0], y + d[1], z + d[2]);
        GL11.glEnd();
    }

    /** Draws player residue in animated model space on the outermost enabled skin box. */
    public static void renderPlayerSkinStains(EntityPlayer player, ModelBiped model, float scale) {
        if (player == null || model == null || scale <= 0.0F || SURFACE_STAINS.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.thePlayer != null && player.isInvisibleToPlayer(mc.thePlayer)) return;
        long now = System.currentTimeMillis();
        boolean hasAny = false;
        for (SurfaceStain stain : SURFACE_STAINS) {
            long ageMs = now - stain.createdAtMs;
            if (stain.attachedTo == player && ageMs >= 0L && ageMs < STAIN_LIFETIME_MS) {
                hasAny = true;
                break;
            }
        }
        if (!hasAny) return;
        long renderNs = System.nanoTime();
        Long previousRenderNs = LAST_PLAYER_STAIN_RENDER_NS.put(player.getEntityId(), renderNs);
        if (previousRenderNs != null && renderNs - previousRenderNs.longValue() < 2000000L) return;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.pushMatrix();
        try {
            GlStateManager.disableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.depthMask(false);
            GlStateManager.enablePolygonOffset();
            GlStateManager.doPolygonOffset(-1.0F, -10.0F);
            for (SurfaceStain stain : SURFACE_STAINS) {
                if (stain.attachedTo != player || stain.bodyPart == null) continue;
                long ageMs = now - stain.createdAtMs;
                if (ageMs < 0L) continue;
                float remaining = ageMs < 4000L ? 1.0F : 1.0F - (float) (ageMs - 4000L) / 2000.0F;
                if (remaining <= 0.0F) continue;
                ModelRenderer part = resolvePlayerModelPart(model, stain.bodyPart);
                if (part == null || part.isHidden || !part.showModel) continue;
                double outerLayerExpansion = resolveOuterLayerExpansion(player, model, stain.bodyPart);
                GlStateManager.pushMatrix();
                part.postRender(scale);
                drawModelSurfacePixel(stain, scale, 0.58F * remaining, outerLayerExpansion);
                GlStateManager.popMatrix();
            }
        } finally {
            GlStateManager.doPolygonOffset(0.0F, 0.0F);
            GlStateManager.disablePolygonOffset();
            GlStateManager.depthMask(true);
            GlStateManager.enableCull();
            GlStateManager.enableLighting();
            GlStateManager.enableTexture2D();
            GlStateManager.disableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
            GL11.glPopAttrib();
        }
    }

    private static ModelRenderer resolvePlayerModelPart(ModelBiped model, PlayerBodyPart part) {
        switch (part) {
            case HEAD: return model.bipedHead;
            case BODY: return model.bipedBody;
            case RIGHT_ARM: return model.bipedRightArm;
            case LEFT_ARM: return model.bipedLeftArm;
            case RIGHT_LEG: return model.bipedRightLeg;
            case LEFT_LEG: return model.bipedLeftLeg;
            default: return null;
        }
    }

    private static double resolveOuterLayerExpansion(EntityPlayer player, ModelBiped model, PlayerBodyPart part) {
        if (!(model instanceof ModelPlayer)) return 0.0D;
        ModelPlayer playerModel = (ModelPlayer) model;
        EnumPlayerModelParts modelPart;
        ModelRenderer outerPart;
        double expansion;
        switch (part) {
            case HEAD:
                modelPart = EnumPlayerModelParts.HAT;
                outerPart = playerModel.bipedHeadwear;
                expansion = 0.5D;
                break;
            case BODY:
                modelPart = EnumPlayerModelParts.JACKET;
                outerPart = playerModel.bipedBodyWear;
                expansion = 0.25D;
                break;
            case RIGHT_ARM:
                modelPart = EnumPlayerModelParts.RIGHT_SLEEVE;
                outerPart = playerModel.bipedRightArmwear;
                expansion = 0.25D;
                break;
            case LEFT_ARM:
                modelPart = EnumPlayerModelParts.LEFT_SLEEVE;
                outerPart = playerModel.bipedLeftArmwear;
                expansion = 0.25D;
                break;
            case RIGHT_LEG:
                modelPart = EnumPlayerModelParts.RIGHT_PANTS_LEG;
                outerPart = playerModel.bipedRightLegwear;
                expansion = 0.25D;
                break;
            case LEFT_LEG:
                modelPart = EnumPlayerModelParts.LEFT_PANTS_LEG;
                outerPart = playerModel.bipedLeftLegwear;
                expansion = 0.25D;
                break;
            default:
                return 0.0D;
        }
        return player.isWearing(modelPart) && outerPart != null && outerPart.showModel && !outerPart.isHidden
                ? expansion : 0.0D;
    }

    private static void drawModelSurfacePixel(SurfaceStain stain, float scale, float alpha,
                                              double outerLayerExpansion) {
        double[] a = tangentJitter(stain.face, -0.5D, -0.5D);
        double[] b = tangentJitter(stain.face, 0.5D, -0.5D);
        double[] c = tangentJitter(stain.face, 0.5D, 0.5D);
        double[] d = tangentJitter(stain.face, -0.5D, 0.5D);
        double[] outward = modelFaceOutwardOffset(stain.face, outerLayerExpansion);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(1.0F, 0.84F, 0.04F, alpha);
        GL11.glVertex3d((stain.x + outward[0] + a[0]) * scale,
                (stain.y + outward[1] + a[1]) * scale, (stain.z + outward[2] + a[2]) * scale);
        GL11.glVertex3d((stain.x + outward[0] + b[0]) * scale,
                (stain.y + outward[1] + b[1]) * scale, (stain.z + outward[2] + b[2]) * scale);
        GL11.glVertex3d((stain.x + outward[0] + c[0]) * scale,
                (stain.y + outward[1] + c[1]) * scale, (stain.z + outward[2] + c[2]) * scale);
        GL11.glVertex3d((stain.x + outward[0] + d[0]) * scale,
                (stain.y + outward[1] + d[1]) * scale, (stain.z + outward[2] + d[2]) * scale);
        GL11.glEnd();
    }

    private static double[] modelFaceOutwardOffset(EnumFacing face, double distance) {
        if (distance <= 0.0D) return new double[] {0.0D, 0.0D, 0.0D};
        switch (face) {
            case EAST: return new double[] {distance, 0.0D, 0.0D};
            case WEST: return new double[] {-distance, 0.0D, 0.0D};
            // Model-space Y grows downward, opposite Minecraft world-space Y.
            case DOWN: return new double[] {0.0D, distance, 0.0D};
            case UP: return new double[] {0.0D, -distance, 0.0D};
            case SOUTH: return new double[] {0.0D, 0.0D, distance};
            case NORTH: return new double[] {0.0D, 0.0D, -distance};
            default: return new double[] {0.0D, 0.0D, 0.0D};
        }
    }

    private enum PlayerBodyPart {
        HEAD(0.0D, 0.0D, -4.0D, 4.0D, -8.0D, 0.0D, -4.0D, 4.0D),
        BODY(0.0D, 0.0D, -4.0D, 4.0D, 0.0D, 12.0D, -2.0D, 2.0D),
        RIGHT_ARM(-5.0D, 2.0D, -3.0D, 1.0D, -2.0D, 10.0D, -2.0D, 2.0D),
        LEFT_ARM(5.0D, 2.0D, -1.0D, 3.0D, -2.0D, 10.0D, -2.0D, 2.0D),
        RIGHT_LEG(-1.9D, 12.0D, -2.0D, 2.0D, 0.0D, 12.0D, -2.0D, 2.0D),
        LEFT_LEG(1.9D, 12.0D, -2.0D, 2.0D, 0.0D, 12.0D, -2.0D, 2.0D);

        final double pivotX;
        final double pivotY;
        final double minX;
        final double maxX;
        final double minY;
        final double maxY;
        final double minZ;
        final double maxZ;

        PlayerBodyPart(double pivotX, double pivotY, double minX, double maxX,
                       double minY, double maxY, double minZ, double maxZ) {
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
    }

    private static EnumFacing closestModelBoxFace(double x, double y, double z, PlayerBodyPart part) {
        double best = Math.abs(y - part.maxY);
        EnumFacing face = EnumFacing.DOWN;
        double distance = Math.abs(y - part.minY);
        if (distance < best) { best = distance; face = EnumFacing.UP; }
        distance = Math.abs(x - part.minX);
        if (distance < best) { best = distance; face = EnumFacing.WEST; }
        distance = Math.abs(x - part.maxX);
        if (distance < best) { best = distance; face = EnumFacing.EAST; }
        distance = Math.abs(z - part.minZ);
        if (distance < best) { best = distance; face = EnumFacing.NORTH; }
        distance = Math.abs(z - part.maxZ);
        if (distance < best) face = EnumFacing.SOUTH;
        return face;
    }

    private static double snapModelPixel(double value, double min, double max) {
        return Math.max(min + 0.5D, Math.min(max - 0.5D, Math.floor(value) + 0.5D));
    }

    private static final class SurfaceStain {
        private final double x;
        private final double y;
        private final double z;
        private final EnumFacing face;
        private final EntityPlayer attachedTo;
        private final PlayerBodyPart bodyPart;
        private final long createdAtMs;

        private SurfaceStain(double x, double y, double z, EnumFacing face,
                             EntityPlayer attachedTo, PlayerBodyPart bodyPart, long createdAtMs) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.face = face;
            this.attachedTo = attachedTo;
            this.bodyPart = bodyPart;
            this.createdAtMs = createdAtMs;
        }

        private static SurfaceStain onBlock(double x, double y, double z, EnumFacing face, long createdAtMs) {
            double snappedX = face.getAxis() == EnumFacing.Axis.X ? x : snapSurfacePixel(x);
            double snappedY = face.getAxis() == EnumFacing.Axis.Y ? y : snapSurfacePixel(y);
            double snappedZ = face.getAxis() == EnumFacing.Axis.Z ? z : snapSurfacePixel(z);
            snappedX += face.getFrontOffsetX() * SURFACE_EPSILON;
            snappedY += face.getFrontOffsetY() * SURFACE_EPSILON;
            snappedZ += face.getFrontOffsetZ() * SURFACE_EPSILON;
            return new SurfaceStain(snappedX, snappedY, snappedZ, face, null, null, createdAtMs);
        }

        private static SurfaceStain onPlayer(EntityPlayer player, double worldX, double worldY,
                                             double worldZ, long createdAtMs) {
            double radians = Math.toRadians(player.renderYawOffset);
            double cos = Math.cos(radians);
            double sin = Math.sin(radians);
            double dx = worldX - player.posX;
            double dz = worldZ - player.posZ;
            double localX = dx * cos + dz * sin;
            double localZ = -dx * sin + dz * cos;
            double entityY = worldY - player.posY;
            double absoluteModelX = -localX * 16.0D;
            double absoluteModelY = (1.5D - entityY) * 16.0D;
            double absoluteModelZ = -localZ * 16.0D;
            PlayerBodyPart part;
            if (absoluteModelY < 0.0D) {
                part = PlayerBodyPart.HEAD;
            } else if (absoluteModelY < 12.0D) {
                part = absoluteModelX < -4.0D ? PlayerBodyPart.RIGHT_ARM
                        : absoluteModelX > 4.0D ? PlayerBodyPart.LEFT_ARM
                        : PlayerBodyPart.BODY;
            } else {
                part = absoluteModelX < 0.0D ? PlayerBodyPart.RIGHT_LEG : PlayerBodyPart.LEFT_LEG;
            }

            double modelX = absoluteModelX - part.pivotX;
            double modelY = absoluteModelY - part.pivotY;
            double modelZ = absoluteModelZ;
            EnumFacing face = closestModelBoxFace(modelX, modelY, modelZ, part);
            final double epsilon = 0.035D;
            if (face.getAxis() == EnumFacing.Axis.X) {
                modelX = face == EnumFacing.EAST ? part.maxX + epsilon : part.minX - epsilon;
                modelY = snapModelPixel(modelY, part.minY, part.maxY);
                modelZ = snapModelPixel(modelZ, part.minZ, part.maxZ);
            } else if (face.getAxis() == EnumFacing.Axis.Y) {
                modelY = face == EnumFacing.DOWN ? part.maxY + epsilon : part.minY - epsilon;
                modelX = snapModelPixel(modelX, part.minX, part.maxX);
                modelZ = snapModelPixel(modelZ, part.minZ, part.maxZ);
            } else {
                modelZ = face == EnumFacing.SOUTH ? part.maxZ + epsilon : part.minZ - epsilon;
                modelX = snapModelPixel(modelX, part.minX, part.maxX);
                modelY = snapModelPixel(modelY, part.minY, part.maxY);
            }
            return new SurfaceStain(modelX, modelY, modelZ, face, player, part, createdAtMs);
        }
    }
}
