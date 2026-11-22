package me.napim.gomod.features;

import me.napim.gomod.util.ClasspathGomodResourcePack;
import me.napim.gomod.util.GomodBundledSounds;
import me.napim.gomod.util.GomodResourcePackInjector;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.SoundList;
import net.minecraft.client.audio.SoundListSerializer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSound;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityWitch;
import net.minecraft.item.ItemPotion;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.projectile.EntityPotion;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Automatic witch SFX. Use {@code /fixwitch 1|2|3|4} to switch playback/detection strategies.
 */
public final class WitchAutoSound {

    /**
     * 1 = SoundHandler only; 2 = world.playSound only; 3 = both paths (default for Lunar); 4 = both paths + player fallback.
     */
    private static volatile int soundMode = 3;
    private static volatile String lastPlayback = "none";

    private static final Map<Integer, Track> TRACKS = new HashMap<>();

    /** Potion entity ids we already played throw SFX for (widened tick window needs dedup). */
    private static final Set<Integer> POTION_THROW_SFX_DONE = new HashSet<>();

    /** Dedup when vanilla + packet handler both fire the same server sound. */
    private static long lastDrinkPacketMs;
    private static long lastThrowPacketMs;

    /** One ambient witch sound at a time across all witches (vanilla spaces these globally). */
    private static long lastGlobalWitchAmbientMs;
    private static volatile boolean packetReplayEnabled = false;

    private static final float WITCH_SFX_VOLUME = 0.42f;
    // Prewarm should not be audible when loading worlds/lobbies.
    private static final float PREWARM_VOL = 0.0f;
    private static final long PREWARM_STEP_INTERVAL_MS = 220L;
    private static volatile boolean prewarmDone;
    private static World prewarmWorld;
    private static volatile boolean gomodRegistryPrimed;
    private static World gomodRegistryWorld;
    private static long gomodRegistryGeneration;
    private static final List<ResourceLocation> PREWARM_QUEUE = new ArrayList<>();
    private static int prewarmIndex;
    private static long lastPrewarmMs;

    private static final class Track {
        int prevHurtTime = -1;
        float prevHealth = Float.NaN;
        boolean wasAlive = true;
        int throwCooldown;
        int drinkCooldown;
        /** After playing ambient from {@link #onClientWitchLivingTick}; vanilla spaces these out. */
        int ambientCooldown;
        /** Dedup status packet + mixin + tick hurt in the same client tick (one SFX per tick per witch). */
        int lastHurtPlayTick = Integer.MIN_VALUE;
        /** Hard dedup window for hurt SFX across different callbacks (packet/status/tick). */
        long lastHurtPlayMs;
        /** Rising edge when witch shows a potion in hand (drink/heal animation on client). */
        boolean witchHeldPotionPrev;
    }

    private WitchAutoSound() {
    }

    public static int getSoundMode() {
        return soundMode;
    }

    public static void setSoundMode(int mode) {
        soundMode = Math.max(1, Math.min(4, mode));
        TRACKS.clear();
        POTION_THROW_SFX_DONE.clear();
        lastDrinkPacketMs = lastThrowPacketMs = 0L;
        lastGlobalWitchAmbientMs = 0L;
        prewarmDone = false;
        prewarmWorld = null;
        PREWARM_QUEUE.clear();
        prewarmIndex = 0;
        lastPrewarmMs = 0L;
    }

    public static boolean isPacketReplayEnabled() {
        return packetReplayEnabled;
    }

    public static void setPacketReplayEnabled(boolean enabled) {
        packetReplayEnabled = enabled;
    }

    /**
     * Called from {@link WitchSoundPacketHandler} when the server sends a positioned sound.
     * Catches drink/throw when entity/potion heuristics never run on the client.
     */
    public static void onServerWitchSoundPacket(Minecraft mc, String soundName, double x, double y, double z) {
        if (!ConfigManager.isWitchSoundsEnabled() || mc == null || soundName == null) {
            return;
        }
        String n = soundName.toLowerCase();
        boolean drinkLike = n.contains("drink") || n.contains("potion");
        boolean throwLike = n.contains("throw") || n.contains("bow");
        if (!drinkLike && !throwLike) {
            return;
        }
        boolean hasNearbyWitch = hasWitchNearPoint(mc, x, y, z, 12.0);
        if (!n.contains("witch") && !hasNearbyWitch) {
            return;
        }
        long now = System.currentTimeMillis();
        if (drinkLike) {
            if (now - lastDrinkPacketMs < 120L) {
                return;
            }
            lastDrinkPacketMs = now;
            playWitchSfxTriple(mc, x, y, z, "drink");
        } else if (throwLike) {
            if (now - lastThrowPacketMs < 120L) {
                return;
            }
            lastThrowPacketMs = now;
            playWitchSfxTriple(mc, x, y, z, "throw");
        }
    }

    private static boolean hasWitchNearPoint(Minecraft mc, double x, double y, double z, double radius) {
        if (mc == null || mc.theWorld == null) {
            return false;
        }
        double r2 = radius * radius;
        for (Entity e : mc.theWorld.loadedEntityList) {
            if (!(e instanceof EntityLivingBase) || !entityIsWitchForHurt(e) || !e.isEntityAlive()) {
                continue;
            }
            double dx = e.posX - x;
            double dy = e.posY - y;
            double dz = e.posZ - z;
            if (dx * dx + dy * dy + dz * dz <= r2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called from {@link me.napim.gomod.mixins.EntityWitchLivingSoundMixin} each client
     * {@link EntityWitch#onLivingUpdate} — same phase vanilla uses for {@code playLivingSound()}.
     */
    public static void onClientWitchLivingTick(EntityWitch w) {
        if (!ConfigManager.isWitchSoundsEnabled() || w == null || !w.isEntityAlive()) {
            return;
        }
        if (w.worldObj == null || !w.worldObj.isRemote) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld != w.worldObj || mc.thePlayer == null) {
            return;
        }
        if (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()) {
            return;
        }
        if (mc.thePlayer.getDistanceSqToEntity(w) > 20.0 * 20.0) {
            return;
        }
        Track t = TRACKS.computeIfAbsent(w.getEntityId(), k -> new Track());
        if (t.ambientCooldown > 0) {
            return;
        }
        // Vanilla-ish rarity: ~1/600 living ticks ≈ occasional ambient (see EntityLivingBase + MCP notes).
        if (w.getRNG().nextInt(600) != 0) {
            return;
        }
        long ambientNow = System.currentTimeMillis();
        if (ambientNow - lastGlobalWitchAmbientMs < 900L) {
            return;
        }
        lastGlobalWitchAmbientMs = ambientNow;
        playWitchSfxTriple(mc, w.posX, w.posY, w.posZ, "idle");
        t.ambientCooldown = 25 + w.getRNG().nextInt(55);
    }

    /**
     * Vanilla witch, plus simple-name match (some servers / builds use non-vanilla subclasses).
     * Used in all modes so detection is consistent with Hypixel/Lunar.
     */
    public static boolean entityIsWitchForHurt(Entity e) {
        if (e == null) {
            return false;
        }
        if (e instanceof EntityWitch) {
            return true;
        }
        String n = e.getClass().getSimpleName();
        return n != null && n.contains("Witch");
    }

    /**
     * Called from mixin / packet when the server applies hurt status (byte 2). Uses same cooldown as tick hurt.
     */
    public static void onWitchHurtEntityStatus(Minecraft mc, EntityLivingBase witch) {
        if (!ConfigManager.isWitchSoundsEnabled() || mc == null || witch == null || !witch.isEntityAlive()) {
            return;
        }
        if (!entityIsWitchForHurt(witch)) {
            return;
        }
        tryPlayWitchHurtSound(mc, witch);
    }

    /**
     * Hurt SFX only from real feedback: {@link #onWitchHurtEntityStatus} (server byte 2) and {@link #clientTick}
     * when {@code hurtTime} rises or health drops — not on every client swing (that matched CPS and sounded wrong).
     */
    private static void tryPlayWitchHurtSound(Minecraft mc, EntityLivingBase witch) {
        if (mc == null || witch == null || mc.thePlayer == null) {
            return;
        }
        Track t = TRACKS.computeIfAbsent(witch.getEntityId(), k -> new Track());
        int tick = mc.thePlayer.ticksExisted;
        if (t.lastHurtPlayTick == tick) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - t.lastHurtPlayMs < 220L) {
            return;
        }
        t.lastHurtPlayTick = tick;
        t.lastHurtPlayMs = now;
        playEngine(mc, witch.posX, witch.posY, witch.posZ, "hurt");
    }

    /** Play a specific gomod sound event (e.g. "witch.idle4") once at the given position. */
    public static void playGomodEvent(Minecraft mc, double x, double y, double z, String eventKey) {
        if (mc == null || eventKey == null || eventKey.isEmpty()) {
            return;
        }
        ResourceLocation rl = new ResourceLocation("gomod", eventKey);
        tryPlayHandler(mc, rl, WITCH_SFX_VOLUME, 1.0f, x, y, z);
    }

    /** @deprecated use {@link #onWitchHurtEntityStatus} */
    @Deprecated
    public static void notifyWitchHurtFromEntityStatus(Minecraft mc, EntityLivingBase witch) {
        onWitchHurtEntityStatus(mc, witch);
    }

    public static String debugState() {
        Minecraft mc = Minecraft.getMinecraft();
        boolean enabled = ConfigManager.isWitchSoundsEnabled();
        boolean soundsJsonActive = GomodResourcePackInjector.hasActiveResource(
                new ResourceLocation("gomod", "sounds.json"));
        boolean idleSampleBundled = GomodBundledSounds.hasBundledWitchIdleSample();
        boolean idleSampleActive = GomodResourcePackInjector.hasActiveResource(
                new ResourceLocation("gomod", "sounds/mob/witch/witch_idle1.ogg"));
        String state = "enabled=" + enabled + " soundMode=" + soundMode
                + " globalMode=" + GlobalFixMode.getMode()
                + " packetReplay=" + packetReplayEnabled
                + " idleSample=" + (idleSampleBundled ? "bundled" : "missing")
                + "/" + (idleSampleActive ? "active" : "inactive")
                + " soundsJson=" + (soundsJsonActive ? "active" : "inactive")
                + " last=" + lastPlayback;
        if (mc == null || mc.theWorld == null) {
            return state + " world=null";
        }
        int witchCount = 0;
        int witchLikeCount = 0;
        String sampleClass = "";
        for (Entity e : mc.theWorld.loadedEntityList) {
            if (e instanceof EntityWitch) {
                witchCount++;
            }
            if (entityIsWitchForHurt(e)) {
                witchLikeCount++;
                if (sampleClass.isEmpty() && e != null) {
                    sampleClass = e.getClass().getSimpleName();
                }
            }
        }
        return state + " witches=" + witchCount
                + " matched=" + witchLikeCount + " tracks=" + TRACKS.size()
                + (sampleClass.isEmpty() ? "" : (" ex=" + sampleClass));
    }

    public static void clientTick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!ConfigManager.isWitchSoundsEnabled()) {
            TRACKS.clear();
            return;
        }
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            TRACKS.clear();
            return;
        }
        GomodResourcePackInjector.ensureInstalled();
        if (mc.theWorld != prewarmWorld) {
            prewarmWorld = mc.theWorld;
            prewarmDone = false;
            PREWARM_QUEUE.clear();
            prewarmIndex = 0;
            lastPrewarmMs = 0L;
        }
        maybePrewarmSounds(mc);
        ensureGomodSoundRegistry(mc);
        GomodResourcePackInjector.ensureDomainLoaded();

        POTION_THROW_SFX_DONE.removeIf(pid -> {
            Entity en = mc.theWorld.getEntityByID(pid);
            return !(en instanceof EntityPotion);
        });
        if (POTION_THROW_SFX_DONE.size() > 400) {
            POTION_THROW_SFX_DONE.clear();
        }

        Set<Integer> seen = new HashSet<>();
        for (Entity e : mc.theWorld.loadedEntityList) {
            if (!entityIsWitchForHurt(e) || !(e instanceof EntityLivingBase)) {
                continue;
            }
            EntityLivingBase w = (EntityLivingBase) e;
            int id = w.getEntityId();
            seen.add(id);
            Track t = TRACKS.computeIfAbsent(id, k -> new Track());

            if (t.throwCooldown > 0) {
                t.throwCooldown--;
            }
            if (t.drinkCooldown > 0) {
                t.drinkCooldown--;
            }
            if (t.ambientCooldown > 0) {
                t.ambientCooldown--;
            }

            if (!w.isEntityAlive()) {
                if (t.wasAlive) {
                    playWitchSfxTriple(mc, w.posX, w.posY, w.posZ, "death");
                }
                t.wasAlive = false;
                t.prevHurtTime = w.hurtTime;
                continue;
            }

            t.wasAlive = true;
            float health = w.getHealth();
            boolean hasPrev = t.prevHurtTime >= 0 && !Float.isNaN(t.prevHealth);
            if (!hasPrev) {
                t.prevHurtTime = w.hurtTime;
                t.prevHealth = health;
                if (w instanceof EntityWitch) {
                    ItemStack hi = w.getHeldItem();
                    t.witchHeldPotionPrev = hi != null && hi.getItem() instanceof ItemPotion;
                } else {
                    t.witchHeldPotionPrev = false;
                }
                continue;
            }

            boolean hurtEdge = w.hurtTime > t.prevHurtTime;
            boolean healthDrop = health < t.prevHealth - 0.01f;
            if (hurtEdge || healthDrop) {
                tryPlayWitchHurtSound(mc, w);
            }

            // Heal / drink: MP often never syncs held potion; health jump is reliable when she drinks.
            boolean healed = health > t.prevHealth + 0.12f;
            if (healed && t.drinkCooldown <= 0) {
                playWitchSfxTriple(mc, w.posX, w.posY, w.posZ, "drink");
                t.drinkCooldown = 22;
            }

            t.prevHurtTime = w.hurtTime;
            t.prevHealth = health;

            if (w instanceof EntityWitch) {
                ItemStack hi = w.getHeldItem();
                boolean potionInHand = hi != null && hi.getItem() instanceof ItemPotion;
                if (potionInHand && !t.witchHeldPotionPrev && t.drinkCooldown <= 0) {
                    playWitchSfxTriple(mc, w.posX, w.posY, w.posZ, "drink");
                    t.drinkCooldown = 22;
                }
                t.witchHeldPotionPrev = potionInHand;
            } else {
                t.witchHeldPotionPrev = false;
            }
        }

        for (Iterator<Integer> it = TRACKS.keySet().iterator(); it.hasNext(); ) {
            int id = it.next();
            if (!seen.contains(id)) {
                it.remove();
            }
        }

        for (Entity e : mc.theWorld.loadedEntityList) {
            if (!(e instanceof EntityPotion)) {
                continue;
            }
            EntityPotion pot = (EntityPotion) e;
            if (pot.ticksExisted < 0 || pot.ticksExisted > 30) {
                continue;
            }
            int pe = pot.getEntityId();
            if (POTION_THROW_SFX_DONE.contains(pe)) {
                continue;
            }
            net.minecraft.entity.EntityLivingBase thrower = pot.getThrower();
            EntityLivingBase witch = null;
            if (thrower != null && entityIsWitchForHurt(thrower)) {
                witch = thrower;
            } else {
                witch = findNearestWitchLike(mc, e, 8.0);
                if (witch != null && witch.getDistanceSqToEntity(e) > 8.5 * 8.5) {
                    witch = null;
                }
            }
            if (witch != null) {
                playWitchThrowIfReady(mc, witch);
                POTION_THROW_SFX_DONE.add(pe);
            }
        }
    }

    private static EntityLivingBase findNearestWitchLike(Minecraft mc, Entity near, double maxDist) {
        if (mc.theWorld == null || near == null) {
            return null;
        }
        double maxSq = maxDist * maxDist;
        EntityLivingBase best = null;
        double bestSq = maxSq;
        for (Entity o : mc.theWorld.loadedEntityList) {
            if (!(o instanceof EntityLivingBase) || !entityIsWitchForHurt(o)) {
                continue;
            }
            EntityLivingBase liv = (EntityLivingBase) o;
            if (!liv.isEntityAlive()) {
                continue;
            }
            double d = liv.getDistanceSqToEntity(near);
            if (d < bestSq) {
                bestSq = d;
                best = liv;
            }
        }
        return best;
    }

    public static void playAtWitchLiving(Minecraft mc, EntityLivingBase witch, String kind) {
        if (!ConfigManager.isWitchSoundsEnabled() || witch == null || !witch.isEntityAlive() || mc == null) {
            return;
        }
        if ("throw".equals(kind)) {
            playWitchThrowIfReady(mc, witch);
        } else {
            playWitchSfxTriple(mc, witch.posX, witch.posY, witch.posZ, kind);
        }
    }

    private static void playWitchThrowIfReady(Minecraft mc, EntityLivingBase witch) {
        if (witch == null || !witch.isEntityAlive() || mc == null) {
            return;
        }
        Track t = TRACKS.computeIfAbsent(witch.getEntityId(), k -> new Track());
        if (t.throwCooldown > 0) {
            return;
        }
        playWitchSfxTriple(mc, witch.posX, witch.posY, witch.posZ, "throw");
        t.throwCooldown = 12;
    }

    /**
     * Idle/throw/drink/death: Lunar often drops one playback path — fire SoundHandler + world + player like vanilla audibility.
     */
    private static void playWitchSfxTriple(Minecraft mc, double x, double y, double z, String kind) {
        if (!ConfigManager.isWitchSoundsEnabled()) {
            return;
        }
        playWitchSfxCore(mc, x, y, z, kind);
    }

    /**
     * For {@code /witchsound}: same triple playback as auto SFX, ignores the master witch-sounds toggle.
     */
    public static void playWitchSoundManual(Minecraft mc, double x, double y, double z, String kind) {
        if (mc == null || kind == null || kind.isEmpty()) {
            return;
        }
        playWitchSfxCore(mc, x, y, z, kind);
    }

    private static void playWitchSfxCore(Minecraft mc, double x, double y, double z, String kind) {
        playEngineInternal(mc, x, y, z, kind, true);
    }

    private static String vanillaWitchSoundName(String kind) {
        switch (kind) {
            case "hurt":
                return "mob.witch.hurt";
            case "death":
                return "mob.witch.death";
            case "throw":
                return "mob.witch.throw";
            case "drink":
                return "mob.witch.drink";
            case "idle":
                return "mob.witch.idle";
            case "celebrate":
                return "mob.witch.celebrate";
            default:
                return null;
        }
    }

    private static void playEngine(Minecraft mc, double x, double y, double z, String kind) {
        playEngineInternal(mc, x, y, z, kind, false);
    }

    private static void playEngineInternal(Minecraft mc, double x, double y, double z, String kind, boolean ignoreToggle) {
        if ((!ignoreToggle && !ConfigManager.isWitchSoundsEnabled()) || mc == null) {
            lastPlayback = "skipped: disabled or no client";
            return;
        }
        String vanilla = vanillaWitchSoundName(kind);
        if (vanilla == null) {
            return;
        }
        GomodResourcePackInjector.ensureInstalled();
        GomodResourcePackInjector.ensureDomainLoaded();
        ensureGomodSoundRegistry(mc);
        float pitch = 0.88f + (mc.theWorld != null ? mc.theWorld.rand.nextFloat() * 0.18f : 0.1f);
        String bundledKind = "celebrate".equals(kind) ? "idle" : kind;
        ResourceLocation rlBundled = new ResourceLocation("gomod", "witch." + bundledKind);
        ResourceLocation rlVanilla = new ResourceLocation(vanilla);
        boolean useBundled = GomodBundledSounds.hasBundledWitchKind(bundledKind)
                && GomodResourcePackInjector.hasActiveResource(new ResourceLocation(
                        "gomod", "sounds/mob/witch/witch_" + bundledKind + "1.ogg"));
        ResourceLocation rlPrimary = useBundled ? rlBundled : rlVanilla;
        String soundPrimary = useBundled ? ("gomod:witch." + bundledKind) : vanilla;
        float vol = WITCH_SFX_VOLUME * ConfigManager.getWitchSfxVolumeMultiplier(kind);
        if (vol <= 0.0001f) {
            lastPlayback = "skipped: volume=0 kind=" + kind;
            return;
        }

        lastPlayback = "event=" + soundPrimary + " source=" + (useBundled ? "gomod" : "vanilla")
                + " globalMode=" + GlobalFixMode.getMode() + " soundMode=" + soundMode;
        int fm = GlobalFixMode.getMode();
        switch (fm) {
            case 2:
                tryPlayWorld(mc, x, y, z, soundPrimary, vol, pitch);
                break;
            case 3:
                tryPlayHandler(mc, rlPrimary, vol, pitch, x, y, z);
                break;
            case 4:
                tryPlayHandler(mc, rlPrimary, vol, pitch, x, y, z);
                tryPlayWorld(mc, x, y, z, soundPrimary, vol, pitch);
                break;
            case 5:
                tryPlayPlayer(mc, soundPrimary, vol, pitch);
                break;
            case 6:
                tryPlayHandler(mc, rlPrimary, vol, pitch, x, y, z);
                tryPlayWorld(mc, x, y, z, soundPrimary, vol, pitch);
                tryPlayPlayer(mc, soundPrimary, vol * 0.85f, pitch);
                break;
            case 7:
                tryPlayWorld(mc, x, y, z, soundPrimary, vol, pitch);
                tryPlayPlayer(mc, soundPrimary, vol * 0.4f, pitch);
                break;
            case 8:
                tryPlayHandler(mc, rlPrimary, vol, pitch, x, y, z);
                tryPlayWorld(mc, x, y, z, soundPrimary, vol, pitch);
                tryPlayPlayer(mc, soundPrimary, vol, pitch);
                break;
            default:
                if (soundMode == 2) {
                    tryPlayWorld(mc, x, y, z, soundPrimary, vol, pitch);
                } else if (soundMode == 4) {
                    tryPlayHandler(mc, rlPrimary, vol, pitch, x, y, z);
                    tryPlayWorld(mc, x, y, z, soundPrimary, vol, pitch);
                    tryPlayPlayer(mc, soundPrimary, vol * 0.85f, pitch);
                } else if (soundMode == 3) {
                    tryPlayHandler(mc, rlPrimary, vol, pitch, x, y, z);
                    tryPlayWorld(mc, x, y, z, soundPrimary, vol, pitch);
                } else {
                    tryPlayHandler(mc, rlPrimary, vol, pitch, x, y, z);
                }
                break;
        }
    }

    private static boolean tryPlayHandler(Minecraft mc, ResourceLocation rl, float vol, float pitch, double x, double y, double z) {
        if (mc == null || mc.getSoundHandler() == null || rl == null) {
            return false;
        }
        try {
            mc.getSoundHandler().playSound(new WitchPosSound(rl, vol, pitch, x, y, z));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void maybePrewarmSounds(Minecraft mc) {
        if (prewarmDone || mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return;
        }
        if (!GomodResourcePackInjector.wasSuccessfullyInstalled()) {
            return;
        }
        if (mc.thePlayer.ticksExisted < 25) {
            return;
        }

        if (PREWARM_QUEUE.isEmpty()) {
            // Only prewarm the sounds the user cares about (idle/hurt) to avoid long first-load stalls.
            String[] kinds = new String[]{"hurt", "idle"};
            for (String kind : kinds) {
                if (GomodBundledSounds.hasBundledWitchKind(kind)) {
                    PREWARM_QUEUE.add(new ResourceLocation("gomod", "witch." + kind));
                }
                String vanilla = vanillaWitchSoundName(kind);
                if (vanilla != null) {
                    PREWARM_QUEUE.add(new ResourceLocation(vanilla));
                }
            }
        }

        if (prewarmIndex >= PREWARM_QUEUE.size()) {
            prewarmDone = true;
            PREWARM_QUEUE.clear();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastPrewarmMs < PREWARM_STEP_INTERVAL_MS) {
            return;
        }
        lastPrewarmMs = now;

        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY;
        double z = mc.thePlayer.posZ;
        prewarmSoundHandler(mc, PREWARM_QUEUE.get(prewarmIndex), x, y, z);
        prewarmIndex++;
        if (prewarmIndex >= PREWARM_QUEUE.size()) {
            prewarmDone = true;
            PREWARM_QUEUE.clear();
        }
    }

    private static void prewarmSoundHandler(Minecraft mc, ResourceLocation rl, double x, double y, double z) {
        if (mc == null || rl == null || mc.getSoundHandler() == null) {
            return;
        }
        try {
            mc.getSoundHandler().playSound(new WitchPosSound(rl, PREWARM_VOL, 1.0f, x, y, z));
        } catch (Throwable ignored) {
        }
    }

    private static void ensureGomodSoundRegistry(Minecraft mc) {
        if (mc == null || mc.getSoundHandler() == null || mc.theWorld == null) {
            return;
        }
        if (!GomodResourcePackInjector.isGomodDomainActive()) {
            return;
        }
        long generation = GomodResourcePackInjector.getSoundResourceGeneration();
        if (mc.theWorld != gomodRegistryWorld || generation != gomodRegistryGeneration) {
            gomodRegistryWorld = mc.theWorld;
            gomodRegistryGeneration = generation;
            gomodRegistryPrimed = false;
        }
        if (gomodRegistryPrimed) {
            return;
        }
        gomodRegistryPrimed = tryRegisterGomodSounds(mc);
    }

    private static boolean tryRegisterGomodSounds(Minecraft mc) {
        try {
            ClasspathGomodResourcePack cp = new ClasspathGomodResourcePack();
            ResourceLocation soundsJson = new ResourceLocation("gomod", "sounds.json");
            if (!cp.resourceExists(soundsJson)
                    || !GomodResourcePackInjector.hasActiveResource(soundsJson)) {
                return false;
            }
            InputStream in = cp.getInputStream(soundsJson);
            if (in == null) {
                return false;
            }
            JsonElement root;
            try (InputStreamReader r = new InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                root = new JsonParser().parse(r);
            }
            if (root == null || !root.isJsonObject()) {
                return false;
            }
            SoundHandler sh = mc.getSoundHandler();
            Method load = findSoundLoadMethod();
            if (sh == null || load == null) {
                return false;
            }
            load.setAccessible(true);
            Gson gson = new GsonBuilder().registerTypeAdapter(SoundList.class, new SoundListSerializer()).create();
            JsonObject obj = root.getAsJsonObject();
            boolean any = false;
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String key = e.getKey();
                if (key == null || key.trim().isEmpty()) {
                    continue;
                }
                SoundList list = gson.fromJson(e.getValue(), SoundList.class);
                if (list == null) {
                    continue;
                }
                load.invoke(sh, new ResourceLocation("gomod", key), list);
                any = true;
            }
            return any;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean forceRegisterGomodSounds() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return false;
        }
        return tryRegisterGomodSounds(mc);
    }

    private static Method findSoundLoadMethod() {
        try {
            for (Method m : SoundHandler.class.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == ResourceLocation.class && p[1] == SoundList.class) {
                    return m;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean tryPlayWorld(Minecraft mc, double x, double y, double z, String sound, float vol, float pitch) {
        if (mc == null || mc.theWorld == null || sound == null || sound.isEmpty()) {
            return false;
        }
        try {
            mc.theWorld.playSound(x, y, z, sound, vol, pitch, false);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean tryPlayPlayer(Minecraft mc, String sound, float vol, float pitch) {
        if (mc == null || mc.thePlayer == null || sound == null || sound.isEmpty()) {
            return false;
        }
        try {
            mc.thePlayer.playSound(sound, vol, pitch);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final class WitchPosSound extends PositionedSound {
        WitchPosSound(ResourceLocation loc, float vol, float pit, double x, double y, double z) {
            super(loc);
            this.volume = vol;
            this.pitch = pit;
            this.xPosF = (float) x;
            this.yPosF = (float) y;
            this.zPosF = (float) z;
            this.attenuationType = ISound.AttenuationType.LINEAR;
        }
    }
}
