package me.napim.gomod.features;

import me.napim.gomod.Main;
import me.napim.gomod.util.ClientInputHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.chunk.Chunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Blitz-style client-only compass sneak+attack: spawn two saddled pigs for ~3s (visual only).
 */
public final class ForcePigManager {

    private static volatile boolean enabled;
    private static boolean prevLeftMouseDown;
    /** Wall-clock so spawns work even if {@link me.napim.gomod.features.ClientTickHub} skips ticks. */
    private static long nextForcePigAllowedMs;
    /** Set from {@link me.napim.gomod.mixins.MinecraftClickPulseMixin} when LWJGL/keybind attack state is broken. */
    private static volatile boolean vanillaLeftClickPulse;

    /**
     * Updated every render frame in {@link #pollAttackEdgeEveryFrame} — game-tick polling misses short LMB clicks.
     */
    private static boolean attackPollPrevRawLmb;

    private static final Set<Integer> forcePigEntityIds = new HashSet<>();

    private static final class TrackedPig {
        final EntityPig pig;
        final long spawnWallMs;
        /** 0 = primary (first death SFX); 1 = secondary (delayed SFX at pig 2). */
        final int pairSlot;
        float lastDistWalked;
        long lastStepSoundMs;
        float targetYaw;
        long nextTurnAtMs;
        long lastMoveMs;

        TrackedPig(EntityPig pig, int pairSlot) {
            this.pig = pig;
            this.pairSlot = pairSlot;
            this.spawnWallMs = System.currentTimeMillis();
            this.lastDistWalked = pig.distanceWalkedModified;
            this.lastStepSoundMs = 0L;
            this.targetYaw = pig.rotationYaw;
            this.nextTurnAtMs = 0L;
            this.lastMoveMs = 0L;
        }
    }

    private static final List<TrackedPig> tracked = new ArrayList<>();

    private static EntityPig lastSpawnSecondaryPig;
    private static long secondPigDeathSoundAtMs;
    private static double secondPigDeathX;
    private static double secondPigDeathY;
    private static double secondPigDeathZ;
    /** Last known feet of the secondary pig (pair slot 1) for delayed death SFX. */
    private static double cachedSecondaryX = Double.NaN;
    private static double cachedSecondaryY;
    private static double cachedSecondaryZ;

    /** Client-side lifetime independent of 20 TPS hub ticks. */
    private static final long FORCE_PIG_LIFETIME_MS = 3000L;
    /** Pig movement speed in blocks/sec (client visual). */
    private static volatile double moveSpeedBlocksPerSec = 5.4D;
    /** ForcePig movement driven manually from manager tick (avoids AI/input desync on some clients). */
    private static final boolean MANUAL_MOTION_ENABLED = true;

    private ForcePigManager() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static double getMoveSpeedBlocksPerSec() {
        return moveSpeedBlocksPerSec;
    }

    public static void setMoveSpeedBlocksPerSec(double speed) {
        if (Double.isNaN(speed) || Double.isInfinite(speed)) {
            return;
        }
        moveSpeedBlocksPerSec = Math.max(1.5D, Math.min(12.0D, speed));
    }

    public static void setEnabled(boolean v) {
        enabled = v;
        if (!v) {
            despawnAll();
            prevLeftMouseDown = false;
            Minecraft m = Minecraft.getMinecraft();
            attackPollPrevRawLmb = m != null && ClientInputHelper.isAttackHeldRawOnly(m);
        }
    }

    public static void toggle() {
        setEnabled(!enabled);
    }

    public static boolean isForcePig(net.minecraft.entity.Entity e) {
        return e != null && forcePigEntityIds.contains(e.getEntityId());
    }

    public static boolean isManualMotionEnabled() {
        return MANUAL_MOTION_ENABLED;
    }

    public static void signalVanillaLeftClick() {
        vanillaLeftClickPulse = true;
    }

    /**
     * Called from {@code PlayerControllerMP} when the client starts digging a block or attacks an entity.
     * Bypasses broken mouse/keybind polling on some Lunar builds (compass + sneak still required by default).
     */
    public static void onLeftClickWorldInteractionAttempt() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (GlobalFixMode.forcePigRequiresCompass() && !ClientInputHelper.isHeldCompass(player.getHeldItem())) {
            return;
        }
        if (GlobalFixMode.forcePigRequiresSneak() && !GlobalFixMode.sneakActive(mc)) {
            return;
        }
        if (!canSpawnForcePigNow()) {
            return;
        }
        armForcePigCooldown();
        spawnPair(mc, mc.theWorld, player);
    }

    /**
     * Call from {@code GuiIngame.renderGameOverlay} HEAD so we see LWJGL button edges between 20 TPS hub ticks.
     */
    public static void pollAttackEdgeEveryFrame() {
        Minecraft mc = Minecraft.getMinecraft();
        if (enabled && mc != null && mc.theWorld != null) {
            try {
                tickTrackedPigsEveryFrame(mc, mc.theWorld);
            } catch (Throwable ignored) {
            }
        }
        if (!enabled) {
            return;
        }
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        if (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) {
            attackPollPrevRawLmb = ClientInputHelper.isAttackHeldRawOnly(mc);
            return;
        }
        boolean raw = ClientInputHelper.isAttackHeldRawOnly(mc);
        boolean edge = raw && !attackPollPrevRawLmb;
        attackPollPrevRawLmb = raw;
        if (!edge) {
            return;
        }
        EntityPlayerSP player = mc.thePlayer;
        if (GlobalFixMode.forcePigRequiresCompass() && !ClientInputHelper.isHeldCompass(player.getHeldItem())) {
            return;
        }
        if (GlobalFixMode.forcePigRequiresSneak() && !GlobalFixMode.sneakActive(mc)) {
            return;
        }
        if (!canSpawnForcePigNow()) {
            return;
        }
        armForcePigCooldown();
        spawnPair(mc, mc.theWorld, player);
    }

    private static boolean canSpawnForcePigNow() {
        return System.currentTimeMillis() >= nextForcePigAllowedMs;
    }

    private static void armForcePigCooldown() {
        nextForcePigAllowedMs = System.currentTimeMillis() + 170L;
    }

    private static WorldClient lastWorldForPigs = null;

    public static void clientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        WorldClient world = mc.theWorld;
        if (world != lastWorldForPigs) {
            onWorldChange();
            lastWorldForPigs = world;
        }
        EntityPlayerSP player = mc.thePlayer;

        if (world != null) {
            tickTrackedPigsEveryFrame(mc, world);
        }

        boolean pulse = vanillaLeftClickPulse;
        vanillaLeftClickPulse = false;

        if (!enabled || world == null || player == null) {
            syncAttackPrev(mc);
            return;
        }
        if (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) {
            syncAttackPrev(mc);
            return;
        }

        boolean sneak = GlobalFixMode.sneakActive(mc);
        ItemStack held = player.getHeldItem();
        boolean compass = ClientInputHelper.isHeldCompass(held);

        boolean leftDown = ClientInputHelper.isAttackHeld(mc);
        boolean rising = GlobalFixMode.forcePigTrigger(mc, leftDown, pulse, prevLeftMouseDown);
        prevLeftMouseDown = leftDown;

        boolean sneakOk = !GlobalFixMode.forcePigRequiresSneak() || sneak;
        boolean compassOk = !GlobalFixMode.forcePigRequiresCompass() || compass;
        if (rising && sneakOk && compassOk && canSpawnForcePigNow()) {
            armForcePigCooldown();
            spawnPair(mc, world, player);
        }
    }

    private static void syncAttackPrev(Minecraft mc) {
        prevLeftMouseDown = ClientInputHelper.isAttackHeld(mc);
    }

    public static String debugState() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return "mc=null";
        EntityPlayerSP player = mc.thePlayer;
        if (player == null) return "player=null";
        boolean sneakVanilla = player.isSneaking();
        boolean sneakHeld = GlobalFixMode.sneakActive(mc);
        ItemStack held = player.getHeldItem();
        boolean compass = ClientInputHelper.isHeldCompass(held);
        boolean attackHeld = ClientInputHelper.isAttackHeld(mc);
        boolean gui = mc.currentScreen != null;
        boolean pulse = vanillaLeftClickPulse;
        boolean rising = GlobalFixMode.forcePigTrigger(mc, attackHeld, pulse, prevLeftMouseDown);
        boolean sneakOk = !GlobalFixMode.forcePigRequiresSneak() || sneakHeld;
        boolean compassOk = !GlobalFixMode.forcePigRequiresCompass() || compass;
        String heldName = held != null && held.getItem() != null
                ? held.getItem().getUnlocalizedName() : "null";
        return "fixMode=" + GlobalFixMode.getMode()
                + " enabled=" + enabled + " sneakVanilla=" + sneakVanilla + " sneakHeld=" + sneakHeld
                + " compass=" + compass + " heldId=" + heldName
                + " attackHeld=" + attackHeld + " prevLMB=" + prevLeftMouseDown
                + " clickPulseMixin=" + pulse + " rising=" + rising
                + " sneakOk=" + sneakOk + " compassOk=" + compassOk
                + " gui=" + gui
                + " speedBps=" + String.format(java.util.Locale.ROOT, "%.2f", moveSpeedBlocksPerSec)
                + " cdMs=" + Math.max(0L, nextForcePigAllowedMs - System.currentTimeMillis())
                + " tracked=" + tracked.size();
    }

    /** Uniform random point in horizontal disk radius 2 around player; Y prefers your floor (not chunk roof under buildings). */
    private static void randomSpawnColumn(EntityPlayerSP player, WorldClient world, double[] outXyz) {
        java.util.Random r = world.rand;
        double ang = r.nextDouble() * Math.PI * 2.0;
        double dist = r.nextDouble() * 2.0;
        double x = player.posX + Math.cos(ang) * dist;
        double z = player.posZ + Math.sin(ang) * dist;
        double y = chooseSpawnFeetY(world, player, x, z);
        outXyz[0] = x;
        outXyz[1] = y;
        outXyz[2] = z;
    }

    /**
     * Chunk {@link Chunk#getHeightValue} is world-surface / roof — under a building that puts pigs on the roof.
     * Prefer an open spot near the player's feet, then take the lower of that and column surface.
     */
    private static double chooseSpawnFeetY(WorldClient world, EntityPlayerSP player, double x, double z) {
        double roofOrTerrain = surfaceYAtColumn(world, x, z, player.posY);
        double nearFeet = findOpenFeetNearPlayerY(world, player, x, z);
        if (!Double.isNaN(nearFeet)) {
            return Math.min(roofOrTerrain, nearFeet);
        }
        return Math.min(roofOrTerrain, player.posY);
    }

    private static double findOpenFeetNearPlayerY(WorldClient world, EntityPlayerSP player, double x, double z) {
        int bx = MathHelper.floor_double(x);
        int bz = MathHelper.floor_double(z);
        if (!world.getChunkProvider().chunkExists(bx >> 4, bz >> 4)) {
            return Double.NaN;
        }
        EntityPig probe = new EntityPig(world);
        probe.setGrowingAge(0);
        int center = MathHelper.floor_double(player.posY);
        for (int dy = 3; dy >= -10; dy--) {
            double y = center + dy;
            if (y < 1.0) {
                continue;
            }
            probe.setPosition(x, y + 0.02, z);
            probe.motionX = probe.motionY = probe.motionZ = 0;
            try {
                List<?> hits = world.getCollidingBoundingBoxes(probe, probe.getEntityBoundingBox());
                if (hits != null && !hits.isEmpty()) {
                    continue;
                }
                int fy = MathHelper.floor_double(y);
                if (!hasSupportingBlockBelow(world, bx, fy - 1, bz)) {
                    continue;
                }
                return y + 0.02;
            } catch (Throwable ignored) {
            }
        }
        return Double.NaN;
    }

    private static boolean hasSupportingBlockBelow(WorldClient world, int bx, int by, int bz) {
        if (by < 0) {
            return true;
        }
        try {
            return world.getBlockState(new BlockPos(bx, by, bz)).getBlock().getMaterial().blocksMovement();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Client-only pigs: integrate motion with collision (vanilla AI / moveEntityWithHeading often no-ops here). */
    private static boolean tryMoveForcePig(WorldClient world, EntityPig p, double dx, double dy, double dz) {
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            return true;
        }
        try {
            AxisAlignedBB next = p.getEntityBoundingBox().offset(dx, dy, dz);
            List<?> list = world.getCollidingBoundingBoxes(p, next);
            if (list != null && !list.isEmpty()) {
                return false;
            }
            p.setPosition(p.posX + dx, p.posY + dy, p.posZ + dz);
            p.prevPosX = p.lastTickPosX = p.posX;
            p.prevPosY = p.lastTickPosY = p.posY;
            p.prevPosZ = p.lastTickPosZ = p.posZ;
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double surfaceYAtColumn(WorldClient world, double x, double z, double fallbackY) {
        int bx = MathHelper.floor_double(x);
        int bz = MathHelper.floor_double(z);
        if (!world.getChunkProvider().chunkExists(bx >> 4, bz >> 4)) {
            return fallbackY;
        }
        Chunk chunk = world.getChunkFromBlockCoords(new BlockPos(bx, 0, bz));
        int hy = chunk.getHeightValue(bx & 15, bz & 15);
        return hy + 0.02D;
    }

    private static void spawnPair(Minecraft mc, WorldClient world, EntityPlayerSP player) {
        double[] col1 = new double[3];
        double[] col2 = new double[3];
        randomSpawnColumn(player, world, col1);
        randomSpawnColumn(player, world, col2);

        EntityPig p1 = createPig(world, col1[0], col1[1], col1[2], player);
        EntityPig p2 = createPig(world, col2[0], col2[1], col2[2], player);
        if (p1 == null || p2 == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "[ForcePig] Failed to create pig entities!");
            return;
        }

        world.spawnEntityInWorld(p1);
        world.spawnEntityInWorld(p2);
        FakeBreak.registerClientEntity(p1);
        FakeBreak.registerClientEntity(p2);
        registerForcePig(p1);
        registerForcePig(p2);
        lastSpawnSecondaryPig = p2;
        cachedSecondaryX = p2.posX;
        cachedSecondaryY = p2.posY;
        cachedSecondaryZ = p2.posZ;
        tracked.add(new TrackedPig(p1, 0));
        tracked.add(new TrackedPig(p2, 1));
        playPigDeath(mc, p1.posX, p1.posY, p1.posZ, 0.85f + world.rand.nextFloat() * 0.2f);
        secondPigDeathX = p2.posX;
        secondPigDeathY = p2.posY;
        secondPigDeathZ = p2.posZ;
        secondPigDeathSoundAtMs = System.currentTimeMillis() + 1250L;

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "[ForcePig] Spawned 2 pigs!");
    }

    private static void playPigDeath(Minecraft mc, double x, double y, double z, float pitch) {
        if (mc == null) {
            return;
        }
        try {
            // Primary: vanilla 1.8 name maps to sounds/mob/pig/death.ogg
            if (mc.theWorld != null) {
                mc.theWorld.playSoundEffect(x, y, z, "mob.pig.death", 1.0f, pitch);
            }
            if (mc.thePlayer != null) {
                mc.thePlayer.playSound("mob.pig.death", 0.55f, pitch);
            }
            if (mc.getSoundHandler() != null) {
                ResourceLocation rl = new ResourceLocation("mob.pig.death");
                mc.getSoundHandler().playSound(new PigPosSound(rl, 1.0f, pitch, x, y, z));
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class PigPosSound extends PositionedSound {
        PigPosSound(ResourceLocation loc, float vol, float pit, double x, double y, double z) {
            super(loc);
            this.volume = vol;
            this.pitch = pit;
            this.xPosF = (float) x;
            this.yPosF = (float) y;
            this.zPosF = (float) z;
            this.attenuationType = ISound.AttenuationType.LINEAR;
        }
    }

    private static EntityPig createPig(WorldClient world, double x, double y, double z, EntityPlayerSP player) {
        try {
            EntityPig p = new EntityPig(world);
            p.setGrowingAge(0);
            p.setSaddled(true);
            p.rotationYaw = world.rand.nextFloat() * 360f;
            p.rotationPitch = 0f;
            double py = player != null ? player.posY : y;
            positionPigWithClearance(world, p, x, z, y, y + 0.35, y + 0.7, y + 1.0, py, py + 0.2, py + 0.5);
            return p;
        } catch (Throwable t) {
            System.err.println("[gomod][ForcePig] createPig failed: " + t);
            return null;
        }
    }

    private static void positionPigWithClearance(WorldClient world, EntityPig pig, double x, double z, double... tryY) {
        for (double y : tryY) {
            pig.setPosition(x, y, z);
            pig.motionX = pig.motionY = pig.motionZ = 0;
            try {
                List<?> hit = world.getCollidingBoundingBoxes(pig, pig.getEntityBoundingBox());
                if (hit == null || hit.isEmpty()) {
                    pig.prevPosX = pig.lastTickPosX = pig.posX;
                    pig.prevPosY = pig.lastTickPosY = pig.posY;
                    pig.prevPosZ = pig.lastTickPosZ = pig.posZ;
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        pig.setPosition(x, tryY[0], z);
        pig.prevPosX = pig.lastTickPosX = pig.posX;
        pig.prevPosY = pig.lastTickPosY = pig.posY;
        pig.prevPosZ = pig.lastTickPosZ = pig.posZ;
    }

    private static void registerForcePig(EntityPig p) {
        forcePigEntityIds.add(p.getEntityId());
    }

    private static void unregisterForcePig(int id) {
        forcePigEntityIds.remove(id);
        FakeBreak.unregisterClientEntity(id);
    }

    private static void tickTrackedPigsEveryFrame(Minecraft mc, WorldClient world) {
        if (world == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (secondPigDeathSoundAtMs > 0L && now >= secondPigDeathSoundAtMs) {
            playPigDeath(mc, secondPigDeathX, secondPigDeathY, secondPigDeathZ, 0.82f + world.rand.nextFloat() * 0.22f);
            secondPigDeathSoundAtMs = 0L;
        }
        if (tracked.isEmpty()) {
            return;
        }
        cachedSecondaryX = Double.NaN;
        for (TrackedPig t : tracked) {
            if (t.pairSlot == 1 && t.pig != null && t.pig.isEntityAlive()) {
                cachedSecondaryX = t.pig.posX;
                cachedSecondaryY = t.pig.posY;
                cachedSecondaryZ = t.pig.posZ;
                break;
            }
        }
        Iterator<TrackedPig> it = tracked.iterator();
        while (it.hasNext()) {
            TrackedPig t = it.next();
            EntityPig p = t.pig;
            if (p == null || !p.isEntityAlive()) {
                if (p != null) {
                    unregisterForcePig(p.getEntityId());
                }
                it.remove();
                continue;
            }
            if (now - t.spawnWallMs >= FORCE_PIG_LIFETIME_MS) {
                int id = p.getEntityId();
                try {
                    p.setDead();
                } catch (Throwable ignored) {
                }
                world.removeEntity(p);
                unregisterForcePig(id);
                it.remove();
                continue;
            }
            if (MANUAL_MOTION_ENABLED) {
                if (t.lastMoveMs == 0L) {
                    t.lastMoveMs = now;
                }
                long dtMs = now - t.lastMoveMs;
                if (dtMs < 16L) {
                    continue;
                }
                if (dtMs > 100L) {
                    dtMs = 100L;
                }
                t.lastMoveMs = now;
                double dt = dtMs / 1000.0;
                if (now >= t.nextTurnAtMs) {
                    t.targetYaw = world.rand.nextFloat() * 360.0f;
                    t.nextTurnAtMs = now + 170L + world.rand.nextInt(320);
                }
                float diff = MathHelper.wrapAngleTo180_float(t.targetYaw - p.rotationYaw);
                float maxTurn = (float) (360.0 * dt);
                if (diff > maxTurn) {
                    diff = maxTurn;
                }
                if (diff < -maxTurn) {
                    diff = -maxTurn;
                }
                float yaw = p.rotationYaw + diff;
                p.rotationYaw = yaw;
                p.renderYawOffset = yaw;
                p.rotationYawHead = yaw;
                p.prevRotationYaw = yaw;

                double dist = moveSpeedBlocksPerSec * dt;
                double yawRad = Math.toRadians(-yaw + 90.0);
                double dx = Math.cos(yawRad) * dist;
                double dz = Math.sin(yawRad) * dist;
                if (!tryMoveForcePig(world, p, dx, 0.0, dz)) {
                    if (!tryMoveForcePig(world, p, dx, 0.0, 0.0)) {
                        tryMoveForcePig(world, p, 0.0, 0.0, dz);
                    }
                }
                double fall = Math.min(32.0 * dt, 0.42);
                tryMoveForcePig(world, p, 0.0, -fall, 0);
            }
            float walked = p.distanceWalkedModified;
            if (Math.abs(walked - t.lastDistWalked) > 0.16f && now - t.lastStepSoundMs > 240L) {
                t.lastDistWalked = walked;
                t.lastStepSoundMs = now;
                try {
                    float pit = 0.88f + world.rand.nextFloat() * 0.14f;
                    world.playSoundEffect(p.posX, p.posY, p.posZ, "mob.pig.step", 0.38f, pit);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void despawnAll() {
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc != null ? mc.theWorld : null;
        for (TrackedPig t : new ArrayList<>(tracked)) {
            try {
                if (world != null && t.pig != null) {
                    try {
                        t.pig.setDead();
                    } catch (Throwable ignored) {
                    }
                    world.removeEntity(t.pig);
                }
            } catch (Throwable ignored) {
            }
            try {
                unregisterForcePig(t.pig.getEntityId());
            } catch (Throwable ignored) {
            }
        }
        tracked.clear();
        lastSpawnSecondaryPig = null;
        secondPigDeathSoundAtMs = 0L;
        cachedSecondaryX = Double.NaN;
        forcePigEntityIds.clear();
    }

    /** Call when changing worlds (optional external hook). */
    public static void onWorldChange() {
        despawnAll();
        prevLeftMouseDown = false;
        nextForcePigAllowedMs = 0L;
        Minecraft mc = Minecraft.getMinecraft();
        attackPollPrevRawLmb = mc != null && ClientInputHelper.isAttackHeldRawOnly(mc);
    }
}
