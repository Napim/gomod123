package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Goparky smoke for every visible owner, with local Blitz reactions and shared reaction timers. */
public final class GoparkyBlitzReactions {
    private static final int GOPARKY_COSMETIC_ID = 2_000_418_004;
    private static final long KILL_COOLDOWN_MS = 650L;
    private static final long WIN_COOLDOWN_MS = 15_000L;
    private static final long RECENT_BLITZ_ACTIVITY_MS = 15L * 60L * 1000L;
    private static final double COMPANION_TRAIL_DISTANCE = 4.7D;
    private static final long CIGARETTE_SMOKE_PARTICLE_INTERVAL_MS = 280L;
    private static final double GOPARKY_CLICK_REACH = 3.0D;
    private static final long GOPARKY_HIT_COOLDOWN_MS = 280L;
    private static final long GOPARKY_HIT_RECOIL_PUSH_MS = 120L;
    private static final double GOPARKY_MAX_HIT_DISPLACEMENT = 1.25D;
    private static final long GOPARKY_HIT_FLASH_DURATION_MS = 2_000L;
    private static final double GOPARKY_HIT_RECOIL_DISTANCE = 0.78D;
    private static final long GOPARKY_EMOTE_MIN_INTERVAL_MS = 45_000L;
    private static final long GOPARKY_EMOTE_MAX_INTERVAL_MS = 120_000L;
    private static final long PLAYER_INTERACTION_RETRY_MS = 4_000L;
    private static final long PLAYER_INTERACTION_APPROACH_TIMEOUT_MS = 30_000L;
    private static final double PLAYER_INTERACTION_APPROACH_DISTANCE = 1.6D;
    private static final long GOPARKY_TAUNT_PATH_TIMEOUT_MIN_MS = 8_000L;
    private static final long GOPARKY_TAUNT_PATH_TIMEOUT_MAX_MS = 30_000L;
    private static final String GOPARKY_TEXTURE_PATH =
            "cosmetics/models/gek/companions/gomod_mascot_v14/gomodmascot.png";
    private static final String GOPARKY_TAUNT_TEXTURE_PATH =
            "cosmetics/models/gek/companions/gomod_mascot_v14/gomodmascot_taunt.png";
    private static final String GOPARKY_HIT_TEXTURE_PATH =
            "cosmetics/models/gek/companions/gomod_mascot_v14/gomodmascot_hit.png";
    private static final String GOPARKY_HIT_MEDIUM_TEXTURE_PATH =
            "cosmetics/models/gek/companions/gomod_mascot_v14/gomodmascot_hit_medium.png";
    private static final String GOPARKY_HIT_LIGHT_TEXTURE_PATH =
            "cosmetics/models/gek/companions/gomod_mascot_v14/gomodmascot_hit_light.png";
    private static final String GOPARKY_HIT_FAINT_TEXTURE_PATH =
            "cosmetics/models/gek/companions/gomod_mascot_v14/gomodmascot_hit_faint.png";
    private static final long GOPARKY_TAUNT_DURATION_MS = GoparkyTauntMotion.DURATION_MS;
    private static final GoparkyTauntMotion PEER_TAUNT_FALLBACK = new GoparkyTauntMotion(1.0D, 60, 60, 0L);
    private static final String NATIVE_RESOURCE_CLASS =
            "com.moonsworth.lunar.IHHHICHHRICRCIOHCIOHHOHIOIORRH."
                    + "OIORRCRHOIHCIORCOOOORICHCIRHHI.OIORRCRHOIHCIORCOOOORICHCIRHHI";
    private static final String NATIVE_RESOURCE_FACTORY_METHOD = "OOIOHHHCIICRRRCICOIIIRRRICOCIC";
    private static final String NATIVE_TEXTURE_BIND_METHOD = "bridge$bindTexture";
    private static final Random RANDOM = new Random();
    private static final Map<UUID, WeakReference<Object>> GOPARKY_ACTORS =
            new ConcurrentHashMap<UUID, WeakReference<Object>>();
    private static final Map<Class<?>, Method> LUNAR_TEXTURE_BINDERS =
            new ConcurrentHashMap<Class<?>, Method>();
    private static final java.util.Set<String> VERIFIED_LUNAR_SKINS =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static volatile ClassLoader lunarClassLoader;
    private static UUID cigaretteSmokeOwnerId;
    private static Object cigaretteSmokeWorld;
    private static final Map<UUID, Long> CIGARETTE_SMOKE_PARTICLES = new java.util.HashMap<>();
    private static boolean cigaretteSmokeReported;
    private static boolean goparkyActorReported;
    private static boolean goparkyRenderRecoilReported;
    private static boolean goparkyRenderTintReported;
    private static boolean goparkyNativeTextureReported;
    private static boolean goparkyHitTextureReported;
    private static boolean goparkyTauntCrouchReported;
    private static boolean goparkyHitReported;
    private static boolean goparkyLunarTintFailureReported;
    private static long lastGoparkyHitMs;
    private static volatile long goparkyHitFlashStartedAtMs;
    private static volatile long goparkyHitFlashUntilMs;
    private static volatile UUID goparkyHitFlashOwnerId;
    private static volatile HitRecoil hitRecoil;
    private static volatile UUID goparkyTauntOwnerId;
    private static volatile Object goparkyTauntWorld;
    private static volatile long goparkyTauntStartedAtMs;
    private static volatile long goparkyTauntUntilMs;
    private static volatile long goparkyTauntPathUntilMs;
    private static volatile long goparkyTauntDanceStartedAtMs;
    private static volatile GoparkyTauntMotion goparkyTauntMotion;
    private static volatile GoparkyTauntTravel goparkyTauntTravel;
    private static volatile PlayerInteraction playerInteraction;
    private static volatile long nextPlayerInteractionCheckMs;
    private static volatile String goparkyTauntDestinationState = "none";
    private static volatile String goparkyTauntVictim = "";
    private static final ThreadLocal<Object> RENDERING_ACTOR = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> HIT_GL_STATE_PUSHED = new ThreadLocal<>();
    private static volatile long lastKillReactionMs;
    private static volatile long lastWinReactionMs;
    private static volatile long lastBlitzActivityMs;
    private static volatile boolean lookupFailureReported;
    private static volatile boolean killReactionReported;
    private static volatile boolean winReactionReported;
    private static volatile boolean killSkipReported;
    private static volatile boolean winSkipReported;
    private static volatile int lastReportedEquippedState = -1;
    private static volatile long goparkyLocalDrawCount;
    private static volatile long goparkyLastLocalDrawAtMs;
    private static volatile long goparkyMolangEvaluatorCallCount;
    private static volatile long goparkyMolangActorMatchCount;
    private static volatile long goparkyLocalCrouchQueryCount;
    private static volatile long goparkyLocalActiveCrouchQueryCount;
    private static volatile long goparkyLastCrouchQueryAtMs;
    private static volatile long goparkyBaseTextureBindCount;
    private static volatile long goparkyRedTextureRequestCount;
    private static volatile long goparkyRedTextureBindCount;
    private static volatile long goparkyRedTextureBindFailureCount;
    private static volatile long goparkyRedTextureAssetFailureCount;
    private static volatile long goparkyDirectTintDrawCount;
    private static volatile long goparkyContextTintDrawCount;
    private static volatile long goparkyRecoilFrameCount;
    private static volatile long goparkyAcceptedHitCount;
    private static volatile long goparkyLastHitAtMs;
    private static volatile double goparkyLastPushDirectionX;
    private static volatile double goparkyLastPushDirectionZ;
    private static volatile double goparkyLastPushOffsetX;
    private static volatile double goparkyLastPushOffsetZ;
    private static volatile long goparkyTauntStartCount;
    private static volatile long goparkyLastTauntAtMs;
    private static volatile int goparkyEmoteId;
    private static volatile int goparkyLastRandomEmoteId;
    private static volatile long goparkyEmoteStartedAtMs;
    private static volatile long goparkyEmoteUntilMs;
    private static volatile long goparkyNextRandomEmoteAtMs;
    private static volatile long goparkyEmoteStartCount;
    private static volatile long goparkyLastEmoteAtMs;
    private static volatile String goparkyEmoteSource = "none";
    private static volatile long goparkyNativeMoveTargetOverrides;
    private static volatile long goparkyNativeMoveTargetFailures;
    private static volatile long goparkyNativeLookTargetOverrides;
    private static volatile long goparkyNativeLookTargetFailures;
    private static volatile double[] goparkyLastNativeMoveTarget;

    private GoparkyBlitzReactions() {
    }

    /** Called by Lunar's companion renderer once it is active, so later event checks use its classloader. */
    public static void rememberLunarRenderer(Object renderer) {
        if (renderer != null && renderer.getClass().getClassLoader() != null) {
            lunarClassLoader = renderer.getClass().getClassLoader();
        }
    }

    /**
     * Lunar's GEK companion actor is not a Minecraft Entity, so vanilla ray tracing cannot
     * select it. Capture the live actor when Lunar constructs the Goparky for its owner.
     */
    public static void rememberGoparkyActor(Object actor, Object lunarOwner, Object companionDefinition) {
        if (actor == null || lunarOwner == null || companionDefinition == null) return;
        try {
            Object rawOwnerId = lunarOwner.getClass().getMethod("bridge$getUniqueID").invoke(lunarOwner);
            Object rawCosmeticId = Long.valueOf(LunarCosmeticRuntime.id(companionDefinition));
            if (!(rawOwnerId instanceof UUID) || !(rawCosmeticId instanceof Number)
                    || ((Number) rawCosmeticId).longValue() != GOPARKY_COSMETIC_ID) {
                return;
            }

            UUID ownerId = (UUID) rawOwnerId;
            GOPARKY_ACTORS.put(ownerId, new WeakReference<Object>(actor));
            ClassLoader loader = actor.getClass().getClassLoader();
            if (loader != null) lunarClassLoader = loader;
            if (!goparkyActorReported) {
                goparkyActorReported = true;
                System.out.println("[gomod] Goparky Lunar actor tracked: owner=" + ownerId);
            }
        } catch (Throwable error) {
            // The effect has a small follow-position fallback if Lunar changes this constructor API.
        }
    }

    public static void onLocalKill(Minecraft mc) {
        onLocalKill(mc, "");
    }

    public static void onLocalKill(Minecraft mc, String victim) {
        schedule(mc, false);
        scheduleTaunt(mc, victim);
    }

    public static void onLocalWin(Minecraft mc) {
        schedule(mc, true);
    }

    /** Each viewer emits smoke at each visible Goparky's own native rendered position. */
    public static void clientTickCigaretteSmoke(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            cigaretteSmokeOwnerId = null;
            cigaretteSmokeWorld = null;
            CIGARETTE_SMOKE_PARTICLES.clear();
            hitRecoil = null;
            goparkyHitFlashStartedAtMs = 0L;
            goparkyHitFlashUntilMs = 0L;
            goparkyHitFlashOwnerId = null;
            goparkyLastPushDirectionX = 0.0D;
            goparkyLastPushDirectionZ = 0.0D;
            goparkyLastPushOffsetX = 0.0D;
            goparkyLastPushOffsetZ = 0.0D;
            clearTaunt();
            clearGoparkyEmote();
            goparkyNextRandomEmoteAtMs = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        UUID ownerId = mc.thePlayer.getUniqueID();
        boolean scopeChanged = !ownerId.equals(cigaretteSmokeOwnerId)
                || cigaretteSmokeWorld != mc.theWorld;
        if (scopeChanged) {
            if (goparkyTauntWorld != null && goparkyTauntWorld != mc.theWorld) {
                clearTaunt();
            }
            clearGoparkyEmote();
            goparkyNextRandomEmoteAtMs = 0L;
            cigaretteSmokeOwnerId = ownerId;
            cigaretteSmokeWorld = mc.theWorld;
            CIGARETTE_SMOKE_PARTICLES.clear();
        }
        updateRandomGoparkyEmote(mc, ownerId, now);
        java.util.Set<UUID> owners = GomodCompanionPositionSync.renderedGoparkyOwners();
        CIGARETTE_SMOKE_PARTICLES.keySet().retainAll(owners);
        for (UUID visibleOwner : owners) {
            EntityPlayer player = visibleOwner.equals(ownerId) ? mc.thePlayer
                    : mc.theWorld.getPlayerEntityByUUID(visibleOwner);
            if (player == null || !player.isEntityAlive() || player.isDead
                    || now - CIGARETTE_SMOKE_PARTICLES.getOrDefault(visibleOwner, 0L)
                    < CIGARETTE_SMOKE_PARTICLE_INTERVAL_MS) continue;
            double[] position = getCigarettePosition(mc, visibleOwner);
            if (position == null) continue;
            // A single small wisp per companion, with an independent owner timer.
            mc.theWorld.spawnParticle(EnumParticleTypes.SMOKE_NORMAL,
                    position[0], position[1], position[2],
                    (RANDOM.nextDouble() - 0.5D) * 0.0015D,
                    0.006D + RANDOM.nextDouble() * 0.004D,
                    (RANDOM.nextDouble() - 0.5D) * 0.0015D);
            CIGARETTE_SMOKE_PARTICLES.put(visibleOwner, now);
            if (!cigaretteSmokeReported) {
                cigaretteSmokeReported = true;
                System.out.println("[gomod] Goparky cigarette smoke active for local and visible peer actors");
            }
        }
    }

    /** Handles a click on the local custom companion and prevents an attack packet on another target. */
    public static boolean interceptGoparkyHit(Minecraft mc) {
        if (mc == null || mc.currentScreen != null || mc.thePlayer == null || mc.theWorld == null) return false;
        UUID ownerId = mc.thePlayer.getUniqueID();
        if (!isGoparkyEquipped(ownerId)) return false;

        long now = System.currentTimeMillis();
        if (now - lastGoparkyHitMs < GOPARKY_HIT_COOLDOWN_MS) return false;

        double[] actor = getActorTransform(ownerId);
        double hitboxWidth = actor == null ? 0.95D : actor[5];
        double hitboxHeight = actor == null ? 1.05D : actor[6];
        double x;
        double y;
        double z;
        if (actor != null) {
            x = actor[0];
            y = actor[1];
            z = actor[2];
        } else {
            // Fallback if Lunar changes its actor constructor; Tiny Mini Me normally trails here.
            double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
            x = mc.thePlayer.posX + Math.sin(yaw) * COMPANION_TRAIL_DISTANCE;
            y = mc.thePlayer.posY;
            z = mc.thePlayer.posZ - Math.cos(yaw) * COMPANION_TRAIL_DISTANCE;
        }

        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = mc.thePlayer.getLook(1.0F);
        Vec3 end = eye.addVector(look.xCoord * GOPARKY_CLICK_REACH,
                look.yCoord * GOPARKY_CLICK_REACH, look.zCoord * GOPARKY_CLICK_REACH);
        double halfWidth = Math.max(0.16D, Math.min(0.30D, hitboxWidth * 0.38D));
        double height = Math.max(0.75D, Math.min(1.10D, hitboxHeight * 0.88D));
        AxisAlignedBB hitbox = new AxisAlignedBB(
                x - halfWidth, y, z - halfWidth,
                x + halfWidth, y + height, z + halfWidth);
        Vec3 center = new Vec3(x, y + height * 0.5D, z);
        // Clamp by the companion's center too: an oversized visual box must not make a
        // target whose body is farther than three blocks feel hittable at long range.
        if (eye.squareDistanceTo(center) > GOPARKY_CLICK_REACH * GOPARKY_CLICK_REACH) return false;
        MovingObjectPosition petHit = hitbox.calculateIntercept(eye, end);
        if (petHit == null || petHit.hitVec == null) return false;
        double petDistanceSq = eye.squareDistanceTo(petHit.hitVec);
        if (petDistanceSq > GOPARKY_CLICK_REACH * GOPARKY_CLICK_REACH + 1.0E-4D) return false;

        MovingObjectPosition blockHit = mc.theWorld.rayTraceBlocks(eye, end, false, true, false);
        if (blockHit != null && blockHit.hitVec != null
                && eye.squareDistanceTo(blockHit.hitVec) + 1.0E-4D < petDistanceSq) {
            return false;
        }
        MovingObjectPosition vanillaHit = mc.objectMouseOver;
        if (vanillaHit != null && vanillaHit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && vanillaHit.hitVec != null
                && eye.squareDistanceTo(vanillaHit.hitVec) + 1.0E-4D < petDistanceSq) {
            return false;
        }

        lastGoparkyHitMs = now;
        goparkyAcceptedHitCount++;
        goparkyLastHitAtMs = now;
        goparkyHitFlashOwnerId = ownerId;
        goparkyHitFlashStartedAtMs = now;
        goparkyHitFlashUntilMs = now + GOPARKY_HIT_FLASH_DURATION_MS;
        interruptGoparkyEmote(now);
        startHitRecoil(mc, ownerId, getGoparkyActor(ownerId), look.xCoord, look.zCoord);
        if (!goparkyHitReported) {
            goparkyHitReported = true;
            System.out.println("[gomod] Goparky local hit accepted eyeToCenter="
                    + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(eye.squareDistanceTo(center)))
                    + " max=3.00");
        }
        mc.thePlayer.swingItem();
        mc.theWorld.playSound(x, y + height * 0.55D, z,
                "mob.villager.hit", 0.36F, 1.75F, false);
        mc.theWorld.spawnParticle(EnumParticleTypes.CRIT,
                x, y + height * 0.55D, z, 0.0D, 0.025D, 0.0D);
        return true;
    }

    /** Swap only Goparky's texture during the brief local hurt-flash window. */
    public static ResourceLocation getHitTintedTexture(ResourceLocation texture) {
        if (texture == null || !GOPARKY_TEXTURE_PATH.equals(texture.getResourcePath())) {
            return texture;
        }
        String tintedPath = getHitTintedPath(texture.getResourcePath());
        if (tintedPath == null) return texture;
        return new ResourceLocation("lunar", tintedPath);
    }

    /**
     * Lunar 2.23.1 binds GEK textures through its own bridge texture manager, bypassing
     * Minecraft's TextureManager hook. Bind the matching local red skin through Lunar.
     */
    public static void bindLunarCompanionTexture(Object textureManager, Object texture) {
        if (textureManager == null || texture == null) return;
        try {
            String path = getNativeResourceString(texture, "bridge$getPath", "getResourcePath");
            boolean localActor = isLocalGoparkyActor(RENDERING_ACTOR.get());
            if (GOPARKY_TEXTURE_PATH.equals(path) && localActor) {
                goparkyBaseTextureBindCount++;
                if (!goparkyNativeTextureReported) {
                    goparkyNativeTextureReported = true;
                    System.out.println("[gomod] Goparky base skin reached Lunar GEK texture binder");
                }
            }
            String tintedPath = getHitTintedPath(path);
            boolean showingHit = tintedPath != null;
            if (tintedPath != null) {
                if (localActor && showingHit) goparkyRedTextureRequestCount++;
                // The bundle is mounted as a Lunar asset pack. Use the same `lunar` namespace
                // that the cosmetic descriptor and native GEK model already consume.
                if (isBundledLunarSkinAvailable(tintedPath)) {
                    Object tintedTexture = createNativeResource("lunar", tintedPath, texture);
                    if (tintedTexture != null && bindLunarTexture(textureManager, tintedTexture)) {
                        if (localActor && showingHit) goparkyRedTextureBindCount++;
                        if (showingHit && !goparkyHitTextureReported) {
                            goparkyHitTextureReported = true;
                            System.out.println("[gomod] Goparky red hurt skin bind requested; lunar PNG verified");
                        }
                        return;
                    }
                    if (localActor && showingHit) goparkyRedTextureBindFailureCount++;
                    reportLunarTintFailure("native red texture binding was unavailable");
                } else {
                    if (localActor && showingHit) goparkyRedTextureAssetFailureCount++;
                    reportLunarTintFailure("gomod hurt texture is unavailable in the active resource manager");
                }
            }
            if (!bindLunarTexture(textureManager, texture)) {
                reportLunarTintFailure("native texture binding method was unavailable");
            }
        } catch (Throwable error) {
            reportLunarTintFailure(error.getClass().getSimpleName());
            bindLunarTexture(textureManager, texture);
        }
    }

    /** Render-frame recoil offset for the tracked actor, called only at Lunar's pet transform. */
    public static double[] getGoparkyRenderRecoilOffset(Object actor) {
        if (actor == null) return null;
        HitRecoil recoil = hitRecoil;
        if (recoil == null || recoil.actor.get() != actor) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld != recoil.world
                || !recoil.ownerId.equals(mc.thePlayer.getUniqueID())) {
            hitRecoil = null;
            return null;
        }

        long elapsedNanos = Math.max(0L, System.nanoTime() - recoil.startedAtNanos);
        double pushNanos = GOPARKY_HIT_RECOIL_PUSH_MS * 1_000_000.0D;
        double amount = smoothStep(elapsedNanos / pushNanos);
        if (isLocalGoparkyActor(actor)) goparkyRecoilFrameCount++;
        if (!goparkyRenderRecoilReported) {
            goparkyRenderRecoilReported = true;
            System.out.println("[gomod] Goparky hit displacement applied in Lunar render frames");
        }
        return new double[] {
                recoil.fromX + (recoil.toX - recoil.fromX) * amount,
                recoil.fromZ + (recoil.toZ - recoil.fromZ) * amount
        };
    }

    /** GEK consumes a per-draw color even when its texture is in the JIT cache. */
    public static void beginCompanionDraw(Object actor) {
        RENDERING_ACTOR.set(actor);
        HIT_GL_STATE_PUSHED.set(Boolean.FALSE);
        boolean localGoparky = isLocalGoparkyActor(actor);
        if (localGoparky) {
            goparkyLocalDrawCount++;
            goparkyLastLocalDrawAtMs = System.currentTimeMillis();
        }
        long hurtRemaining = getLocalHurtRemaining(actor);
        if (hurtRemaining <= 0L) hurtRemaining = GomodCompanionPositionSync.hurtRemaining(actor);
        if (hurtRemaining <= 0L) return;
        try {
            GL11.glPushAttrib(GL11.GL_CURRENT_BIT);
            HIT_GL_STATE_PUSHED.set(Boolean.TRUE);
            GL11.glColor4f(1.0F, 0.12F, 0.12F, 1.0F);
            if (localGoparky) goparkyDirectTintDrawCount++;
            if (!goparkyRenderTintReported) {
                goparkyRenderTintReported = true;
                System.out.println("[gomod] Goparky direct red hurt tint applied around its model draw");
            }
        } catch (Throwable error) {
            if (Boolean.TRUE.equals(HIT_GL_STATE_PUSHED.get())) {
                try { GL11.glPopAttrib(); } catch (Throwable ignored) {}
            }
            HIT_GL_STATE_PUSHED.set(Boolean.FALSE);
            reportLunarTintFailure("direct model tint: " + error.getClass().getSimpleName());
        }
    }
    public static Object renderingActor() { return RENDERING_ACTOR.get(); }
    public static void endCompanionDraw() {
        try {
            if (Boolean.TRUE.equals(HIT_GL_STATE_PUSHED.get())) GL11.glPopAttrib();
        } catch (Throwable ignored) {
        } finally {
            HIT_GL_STATE_PUSHED.remove();
            RENDERING_ACTOR.remove();
        }
    }

    /** GEK consumes a per-draw color even when its texture is in the JIT cache. */
    public static Object tintRenderContext(Object actor, Object context) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || actor == null || context == null) return context;
        long remaining = getLocalHurtRemaining(actor);
        if (remaining <= 0L) remaining = GomodCompanionPositionSync.hurtRemaining(actor);
        if (remaining <= 0L) return context;
        if (isLocalGoparkyActor(actor)) goparkyContextTintDrawCount++;
        double strength = Math.min(1.0D, remaining / 200.0D);
        int channel = (int) Math.round(255.0D - 215.0D * strength);
        try {
            Object tinted = context.getClass().getMethod("CRRHRIRIHOHRHRCCHRCOICHOOIHOIC", java.awt.Color.class)
                    .invoke(context, new java.awt.Color(255, channel, channel, 255));
            if (!goparkyRenderTintReported) {
                goparkyRenderTintReported = true;
                System.out.println("[gomod] Goparky red hurt color applied to live GEK draw context");
            }
            return tinted == null ? context : tinted;
        } catch (Throwable error) {
            reportLunarTintFailure("companion draw color: " + error.getClass().getSimpleName());
            return context;
        }
    }

    public static long getLocalHurtRemaining(Object actor) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && actor != null
                && getGoparkyActor(mc.thePlayer.getUniqueID()) == actor
                ? Math.max(0L, goparkyHitFlashUntilMs - System.currentTimeMillis()) : 0L;
    }

    /** Start the same short local taunt used by a real kill, for a named render test. */
    public static boolean debugTaunt(Minecraft mc, String victim) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null
                || !isGoparkyEquipped(mc.thePlayer.getUniqueID())) return false;
        scheduleTaunt(mc, victim);
        return true;
    }

    /** Force one Lunar body emote on the local companion; never dispatches a player emote. */
    public static String debugEmote(Minecraft mc, String requested) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null
                || !isGoparkyEquipped(mc.thePlayer.getUniqueID())
                || getGoparkyActor(mc.thePlayer.getUniqueID()) == null) return null;
        GoparkyLunarEmoteLibrary.requestLoad(mc);
        if (!GoparkyLunarEmoteLibrary.isReady()) {
            return GoparkyLunarEmoteLibrary.isLoading() ? "loading" : "unavailable";
        }
        if (requested == null) return "invalid";
        String selection = requested.trim().toLowerCase(java.util.Locale.ROOT);
        if ("stop".equals(selection)) {
            clearGoparkyEmote();
            goparkyNextRandomEmoteAtMs = System.currentTimeMillis()
                    + GOPARKY_EMOTE_MIN_INTERVAL_MS + RANDOM.nextInt(30_001);
            return "stopped";
        }
        GoparkyLunarEmoteLibrary.Emote emote = "random".equals(selection)
                ? GoparkyLunarEmoteLibrary.random(RANDOM, goparkyLastRandomEmoteId)
                : GoparkyLunarEmoteLibrary.find(selection);
        if (emote == null) return "invalid";
        long now = System.currentTimeMillis();
        Object actor = getGoparkyActor(mc.thePlayer.getUniqueID());
        if (goparkyHitFlashUntilMs > now || getLocalTauntRemaining(actor) > 0L
                || isLocalTauntPathOrDanceActive(actor, now)) return "busy";
        startGoparkyEmote(emote, "debug", now);
        goparkyNextRandomEmoteAtMs = goparkyEmoteUntilMs
                + GOPARKY_EMOTE_MIN_INTERVAL_MS + RANDOM.nextInt(75_001);
        return emote.name;
    }

    public static String[] goparkyEmoteNames() {
        return GoparkyLunarEmoteLibrary.playableNames();
    }

    /** Current local animation selector written into Lunar's GEK Molang evaluator. */
    public static double getGoparkyEmoteQuery(Object actor) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || actor == null
                || getGoparkyActor(mc.thePlayer.getUniqueID()) != actor) return 0.0D;
        long now = System.currentTimeMillis();
        if (now >= goparkyEmoteUntilMs || goparkyEmoteId <= 0
                || !GoparkyLunarEmoteLibrary.isReady()
                || goparkyHitFlashUntilMs > now
                || getLocalTauntRemaining(actor) > 0L
                || isLocalTauntPathOrDanceActive(actor, now)) return 0.0D;
        return 1.0D;
    }

    public static double[] getGoparkyLunarEmotePose(Object actor) {
        if (getGoparkyEmoteQuery(actor) <= 0.0D) return null;
        return GoparkyLunarEmoteLibrary.samplePose(goparkyEmoteId,
                Math.max(0L, System.currentTimeMillis() - goparkyEmoteStartedAtMs));
    }

    /** Returns observable runtime checkpoints for the local Goparky render path. */
    public static String[] getDebugStatus(Minecraft mc) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            return new String[] {"Goparky debug: join a world first."};
        }
        UUID ownerId = mc.thePlayer.getUniqueID();
        Object actor = getGoparkyActor(ownerId);
        boolean equipped = isGoparkyEquipped(ownerId);
        boolean tracked = actor != null;
        boolean transform = getActorTransform(actor) != null;
        long now = System.currentTimeMillis();
        long tauntRemaining = getLocalTauntRemaining(actor);
        long hitRemaining = getLocalHurtRemaining(actor);
        boolean baseSkin = isBundledLunarSkinAvailable(GOPARKY_TEXTURE_PATH);
        boolean tauntSkin = isBundledLunarSkinAvailable(GOPARKY_TAUNT_TEXTURE_PATH);
        boolean hitSkin = isBundledLunarSkinAvailable(GOPARKY_HIT_TEXTURE_PATH)
                && isBundledLunarSkinAvailable(GOPARKY_HIT_MEDIUM_TEXTURE_PATH)
                && isBundledLunarSkinAvailable(GOPARKY_HIT_LIGHT_TEXTURE_PATH)
                && isBundledLunarSkinAvailable(GOPARKY_HIT_FAINT_TEXTURE_PATH);
        long lastHitAge = goparkyLastHitAtMs == 0L ? -1L : Math.max(0L, now - goparkyLastHitAtMs);
        long lastTauntAge = goparkyLastTauntAtMs == 0L ? -1L : Math.max(0L, now - goparkyLastTauntAtMs);
        long lastDrawAge = goparkyLastLocalDrawAtMs == 0L ? -1L : Math.max(0L, now - goparkyLastLocalDrawAtMs);
        long lastCrouchAge = goparkyLastCrouchQueryAtMs == 0L ? -1L
                : Math.max(0L, now - goparkyLastCrouchQueryAtMs);
        long danceElapsed = goparkyTauntDanceStartedAtMs == 0L ? 0L
                : Math.max(0L, now - goparkyTauntDanceStartedAtMs);
        PlayerInteraction interaction = playerInteraction;
        String interactionPhase = interaction == null ? "idle"
                : (interaction.startedAtMs > 0L ? "taunting" : "approaching");
        double[] actorPosition = getActorTransform(actor);
        GoparkyTauntTravel travel = goparkyTauntTravel;
        double[] destination = travel == null ? null : travel.destination();
        double routeDistance = actorPosition == null || destination == null ? -1D
                : distance(actorPosition[0], actorPosition[1], actorPosition[2],
                destination[0], destination[1], destination[2]);
        String interactionTarget = interaction == null || interaction.target == null
                ? "none" : interaction.target.getName();
        return new String[] {
                "Goparky equip=" + equipped + " actor=" + tracked + " transform=" + transform
                        + " localDraws=" + goparkyLocalDrawCount + " drawAgeMs=" + lastDrawAge,
                "smokeOwners=" + CIGARETTE_SMOKE_PARTICLES.size()
                        + " peerSmokeOwners=" + CIGARETTE_SMOKE_PARTICLES.keySet().stream()
                        .filter(id -> !ownerId.equals(id)).count()
                        + " sharedMovement=false",
                "tauntMs=" + tauntRemaining + " starts=" + goparkyTauntStartCount
                        + " ageMs=" + lastTauntAge + " evaluatorCalls=" + goparkyMolangEvaluatorCallCount
                        + " actorMatches=" + goparkyMolangActorMatchCount
                        + " crouchQueries=" + goparkyLocalCrouchQueryCount
                        + " activeQueries=" + goparkyLocalActiveCrouchQueryCount
                        + " crouchAgeMs=" + lastCrouchAge,
                "skin64 base=" + baseSkin + " taunt=" + tauntSkin + " hit=" + hitSkin
                        + " baseBinds=" + goparkyBaseTextureBindCount + " redRequests="
                        + goparkyRedTextureRequestCount + " redBinds=" + goparkyRedTextureBindCount
                        + " bindFails=" + goparkyRedTextureBindFailureCount
                        + " assetFails=" + goparkyRedTextureAssetFailureCount,
                "hits=" + goparkyAcceptedHitCount + " lastHitAgeMs=" + lastHitAge
                        + " flashMs=" + hitRemaining + " redDraws=" + goparkyDirectTintDrawCount
                        + "/" + goparkyContextTintDrawCount
                        + " recoilFrames=" + goparkyRecoilFrameCount
                        + " pushDir=" + String.format(java.util.Locale.ROOT, "%.2f,%.2f",
                        goparkyLastPushDirectionX, goparkyLastPushDirectionZ)
                        + " pushOffset=" + String.format(java.util.Locale.ROOT, "%.2f,%.2f",
                        goparkyLastPushOffsetX, goparkyLastPushOffsetZ),
                "motionWrites=" + GoparkyTauntQueries.writes() + " motionFailures=" + GoparkyTauntQueries.failures()
                        + " compassDraws=" + GoparkyCompassRenderer.draws() + " compassFailures=" + GoparkyCompassRenderer.failures()
                        + " destination=" + goparkyTauntDestinationState
                        + " pathActive=" + (goparkyTauntTravel != null && goparkyTauntDanceStartedAtMs == 0L
                        && now < goparkyTauntPathUntilMs)
                        + " pathRemainingMs=" + (goparkyTauntDanceStartedAtMs > 0L ? 0L
                        : Math.max(0L, goparkyTauntPathUntilMs - now))
                        + " danceElapsedMs=" + danceElapsed
                        + " routeDistance=" + String.format(java.util.Locale.ROOT, "%.2f", routeDistance)
                        + " victim=" + (goparkyTauntVictim.isEmpty() ? "none" : goparkyTauntVictim)
                        + " interactionTarget=" + interactionTarget
                        + " randomInteractions=" + GoparkyTauntSettings.get().randomPlayerInteractions
                        + " interaction=" + interactionPhase
                        + " moveTargetOverrides=" + goparkyNativeMoveTargetOverrides
                        + " moveTargetFailures=" + goparkyNativeMoveTargetFailures
                        + " lookTargetOverrides=" + goparkyNativeLookTargetOverrides
                        + " lookTargetFailures=" + goparkyNativeLookTargetFailures
                        + " lastMoveTarget=" + formatPosition(goparkyLastNativeMoveTarget)
                        + " travelMs=" + (goparkyTauntTravel == null ? 0L : goparkyTauntTravel.durationMs())
                        + " travelDistance=" + (goparkyTauntTravel == null ? "0"
                        : String.format(java.util.Locale.ROOT, "%.1f", goparkyTauntTravel.distance()))
                        + " config=" + GoparkyTauntSettings.get().fullCommand(),
                "emote=" + (goparkyEmoteId > 0 ? goparkyEmoteName(goparkyEmoteId) : "idle")
                        + " source=" + goparkyEmoteSource
                        + " remainingMs=" + Math.max(0L, goparkyEmoteUntilMs - now)
                        + " starts=" + goparkyEmoteStartCount
                        + " ageMs=" + (goparkyLastEmoteAtMs == 0L ? -1L : Math.max(0L, now - goparkyLastEmoteAtMs))
                        + " nextRandomMs=" + Math.max(0L, goparkyNextRandomEmoteAtMs - now)
        };
    }

    /** Called with Lunar's actual companion actor, not an obfuscated context/wrapper lookup. */
    public static boolean shouldGoparkyActorCrouch(Object actor) {
        goparkyMolangEvaluatorCallCount++;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null || actor == null) return false;

        Object trackedActor = getGoparkyActor(mc.thePlayer.getUniqueID());
        if (actor != trackedActor) {
            // The peer stream identifies the cosmetic ID before exposing its taunt timer.
            // Other companions and non-Gomod clients keep their native false query.
            return GomodCompanionPositionSync.tauntRemaining(actor) > 0L;
        }
        goparkyMolangActorMatchCount++;
        goparkyLocalCrouchQueryCount++;
        goparkyLastCrouchQueryAtMs = System.currentTimeMillis();
        long remaining = getLocalTauntRemaining(trackedActor);
        if (isTauntApproachActive(trackedActor, System.currentTimeMillis())) return false;
        if (remaining <= 0L) {
            if (isLocalTauntPathOrDanceActive(trackedActor, System.currentTimeMillis())) return true;
            PlayerInteraction interaction = updatePlayerInteraction(trackedActor);
            return interaction != null && interaction.startedAtMs > 0L;
        }
        clearPlayerInteraction(System.currentTimeMillis() + PLAYER_INTERACTION_RETRY_MS);
        goparkyLocalActiveCrouchQueryCount++;
        if (!goparkyTauntCrouchReported) {
            goparkyTauntCrouchReported = true;
            System.out.println("[gomod] Goparky native actor crouch query active for joint-based taunt animation");
        }
        return true;
    }

    public static double[] getTauntPose(Object actor) {
        long now = System.currentTimeMillis();
        double[] localPose = getLocalTauntPose(actor, now);
        if (localPose != null) return localPose;
        long remaining = GomodCompanionPositionSync.tauntRemaining(actor);
        if (remaining > 0L) return PEER_TAUNT_FALLBACK.sample(GOPARKY_TAUNT_DURATION_MS - remaining);
        PlayerInteraction interaction = updatePlayerInteraction(actor);
        if (interaction == null || interaction.startedAtMs <= 0L) return new double[3];
        long elapsed = Math.max(0L, System.currentTimeMillis() - interaction.startedAtMs);
        long crouchPhase = elapsed % (interaction.showButt ? 520L : 700L);
        double crouch = crouchPhase < 150L ? 1.0D : 0.0D;
        long swingPhase = elapsed % (interaction.showButt ? 1_250L : 950L);
        double swing = swingPhase < 135L
                ? 0.5D - 0.5D * Math.cos(swingPhase * Math.PI / 135.0D) : 0.0D;
        return new double[] {crouch, interaction.showButt ? 55.0D : 0.0D, swing};
    }

    /** Run gait while Lunar's native pathfinder still has ground to cover. */
    public static double getTauntTravelRunAmount(Object actor) {
        updateLocalTauntTravelPhase(actor, System.currentTimeMillis());
        GoparkyTauntTravel travel = goparkyTauntTravel;
        UUID owner = goparkyTauntOwnerId;
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.currentTimeMillis();
        if (isLocalTauntActor(actor, travel, owner, mc) && now < goparkyTauntPathUntilMs
                && goparkyTauntDanceStartedAtMs == 0L) {
            double[] pos = getActorTransform(actor);
            double[] goal = travel.destination();
            return pos != null && distance(pos[0], pos[1], pos[2], goal[0], goal[1], goal[2])
                    > Math.max(1.0D, GoparkyTauntSettings.get().tauntRadiusBlocks) ? 1.0D : 0.0D;
        }
        PlayerInteraction interaction = updatePlayerInteraction(actor);
        if (interaction == null || interaction.startedAtMs > 0L) return 0.0D;
        double[] pos = getActorTransform(actor);
        return pos != null && distance(pos[0], pos[1], pos[2], interaction.target.posX,
                interaction.target.posY, interaction.target.posZ) > PLAYER_INTERACTION_APPROACH_DISTANCE
                ? 1.0D : 0.0D;
    }

    private static double[] getLocalTauntPose(Object actor, long now) {
        updateLocalTauntTravelPhase(actor, now);
        GoparkyTauntMotion motion = goparkyTauntMotion;
        if (motion == null || !isLocalGoparkyActor(actor)
                || mcWorldMismatchForTaunt()) return null;
        GoparkyTauntTravel travel = goparkyTauntTravel;
        if (travel == null) {
            return getLocalTauntRemaining(actor) > 0L
                    ? motion.sample(now - goparkyTauntStartedAtMs) : null;
        }
        if (goparkyTauntDanceStartedAtMs <= 0L) return new double[3];
        long elapsed = now - goparkyTauntDanceStartedAtMs;
        if (elapsed < GOPARKY_TAUNT_DURATION_MS) return motion.sample(elapsed);
        finishLocalTauntMotion(now);
        return new double[3];
    }

    /** Start dancing only after the native actor reaches the death spot; expire a genuinely stuck route cleanly. */
    private static void updateLocalTauntTravelPhase(Object actor, long now) {
        GoparkyTauntTravel travel = goparkyTauntTravel;
        if (travel == null || goparkyTauntDanceStartedAtMs > 0L
                || !isLocalTauntActor(actor, travel, goparkyTauntOwnerId, Minecraft.getMinecraft())) return;
        double[] position = getActorTransform(actor);
        double[] destination = travel.destination();
        boolean arrived = position != null && distance(position[0], position[1], position[2],
                destination[0], destination[1], destination[2])
                <= Math.max(1.0D, GoparkyTauntSettings.get().tauntRadiusBlocks);
        if (arrived) {
            goparkyTauntDanceStartedAtMs = now;
            goparkyTauntUntilMs = now + GOPARKY_TAUNT_DURATION_MS;
            goparkyTauntDestinationState = "arrived";
        } else if (now >= goparkyTauntPathUntilMs) {
            // Never play the taunt at a random intermediate point just because an estimated travel timer elapsed.
            goparkyTauntDestinationState = "path-timeout";
            goparkyTauntMotion = null;
            goparkyTauntTravel = null;
            goparkyTauntPathUntilMs = 0L;
            goparkyTauntUntilMs = now;
        }
    }

    private static boolean mcWorldMismatchForTaunt() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc == null || mc.thePlayer == null || mc.theWorld == null
                || mc.theWorld != goparkyTauntWorld
                || !mc.thePlayer.getUniqueID().equals(goparkyTauntOwnerId);
    }

    private static boolean isLocalTauntPathOrDanceActive(Object actor, long now) {
        updateLocalTauntTravelPhase(actor, now);
        GoparkyTauntTravel travel = goparkyTauntTravel;
        if (!isLocalTauntActor(actor, travel, goparkyTauntOwnerId, Minecraft.getMinecraft())) return false;
        if (goparkyTauntDanceStartedAtMs > 0L) {
            return now - goparkyTauntDanceStartedAtMs < GOPARKY_TAUNT_DURATION_MS;
        }
        return now < goparkyTauntPathUntilMs;
    }

    /** Keep the companion upright during its native walk; crouch only once the taunt begins. */
    private static boolean isTauntApproachActive(Object actor, long now) {
        updateLocalTauntTravelPhase(actor, now);
        GoparkyTauntTravel travel = goparkyTauntTravel;
        if (!isLocalTauntActor(actor, travel, goparkyTauntOwnerId, Minecraft.getMinecraft())
                || goparkyTauntDanceStartedAtMs > 0L) return false;
        return distanceToTauntDestination(actor)
                > Math.max(1.0D, GoparkyTauntSettings.get().tauntRadiusBlocks);
    }

    private static void finishLocalTauntMotion(long now) {
        boolean reachedDeathLocation = "arrived".equals(goparkyTauntDestinationState);
        goparkyTauntMotion = null;
        goparkyTauntTravel = null;
        goparkyTauntPathUntilMs = 0L;
        goparkyTauntDanceStartedAtMs = 0L;
        goparkyTauntUntilMs = now;
        goparkyTauntDestinationState = reachedDeathLocation ? "completed" : "completed-without-destination";
    }

    private static boolean isLocalTauntActor(Object actor, GoparkyTauntTravel travel,
                                             UUID owner, Minecraft mc) {
        return travel != null && owner != null && mc != null && mc.thePlayer != null
                && owner.equals(mc.thePlayer.getUniqueID()) && mc.theWorld == goparkyTauntWorld
                && actor != null && actor == getGoparkyActor(owner);
    }

    /** Replace Lunar's native move_to_owner destination, while keeping its pathfinding intact. */
    public static Object overrideNativeMoveTarget(Object actor, Object nativeTarget) {
        if (!isLocalGoparkyActor(actor) || nativeTarget == null) return nativeTarget;
        boolean routeActive = isLocalTauntPathOrDanceActive(actor, System.currentTimeMillis());
        if (!routeActive && updatePlayerInteraction(actor) == null) return nativeTarget;
        try {
            double[] target = nativeMovementTarget(actor);
            if (target == null) return nativeTarget;
            Constructor<?> constructor = nativeTarget.getClass()
                    .getDeclaredConstructor(int.class, int.class, int.class);
            constructor.setAccessible(true);
            Object replacement = constructor.newInstance(
                    floorToInt(target[0]), floorToInt(target[1]), floorToInt(target[2]));
            goparkyNativeMoveTargetOverrides++;
            goparkyLastNativeMoveTarget = target.clone();
            return replacement;
        } catch (Throwable ignored) {
            goparkyNativeMoveTargetFailures++;
            return nativeTarget;
        }
    }

    /** Direct Lunar look task target: the victim spot, a player's eyes, or the butt gag's ground point. */
    public static Object overrideNativeLookTarget(Object actor, Object nativeTarget) {
        if (!isLocalGoparkyActor(actor) || nativeTarget == null) return nativeTarget;
        boolean routeActive = isLocalTauntPathOrDanceActive(actor, System.currentTimeMillis());
        if (!routeActive && updatePlayerInteraction(actor) == null) return nativeTarget;
        try {
            double[] target = nativeLookTarget(actor);
            if (target == null) return nativeTarget;
            Constructor<?> constructor = nativeTarget.getClass()
                    .getDeclaredConstructor(double.class, double.class, double.class);
            constructor.setAccessible(true);
            Object replacement = constructor.newInstance(target[0], target[1], target[2]);
            goparkyNativeLookTargetOverrides++;
            return replacement;
        } catch (Throwable ignored) {
            goparkyNativeLookTargetFailures++;
            return nativeTarget;
        }
    }

    /** Keep Lunar's own destination arrival/path logic, using the saved taunt radius when active. */
    public static int nativeTauntArrivalDistance(Object actor, int nativeDistance) {
        if (!isLocalGoparkyActor(actor)) return nativeDistance;
        if (isLocalTauntPathOrDanceActive(actor, System.currentTimeMillis())) {
            return boundedArrivalBlocks(GoparkyTauntSettings.get().tauntRadiusBlocks);
        }
        PlayerInteraction interaction = updatePlayerInteraction(actor);
        return interaction == null ? nativeDistance : 1;
    }

    private static double[] nativeMovementTarget(Object actor) {
        if (goparkyTauntTravel != null
                && isLocalTauntPathOrDanceActive(actor, System.currentTimeMillis())) {
            return goparkyTauntTravel.destination();
        }
        PlayerInteraction interaction = updatePlayerInteraction(actor);
        if (interaction == null) return null;
        return interaction.movementTarget(PLAYER_INTERACTION_APPROACH_DISTANCE);
    }

    private static double distanceToTauntDestination(Object actor) {
        GoparkyTauntTravel travel = goparkyTauntTravel;
        double[] position = getActorTransform(actor);
        if (travel == null || position == null) return Double.POSITIVE_INFINITY;
        double[] target = travel.destination();
        return distance(position[0], position[1], position[2], target[0], target[1], target[2]);
    }

    private static double[] nativeLookTarget(Object actor) {
        if (goparkyTauntTravel != null
                && isLocalTauntPathOrDanceActive(actor, System.currentTimeMillis())) {
            double[] destination = goparkyTauntTravel.destination();
            return new double[] {destination[0], destination[1] + 0.65D, destination[2]};
        }
        PlayerInteraction interaction = updatePlayerInteraction(actor);
        if (interaction == null) return null;
        EntityPlayer target = interaction.target;
        if (!interaction.showButt) {
            return new double[] {target.posX, target.posY + target.getEyeHeight(), target.posZ};
        }
        double[] actorPosition = getActorTransform(actor);
        if (actorPosition == null) return null;
        double awayX = actorPosition[0] - target.posX;
        double awayZ = actorPosition[2] - target.posZ;
        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (length < 1.0E-4D) {
            double yaw = Math.toRadians(target.rotationYaw);
            awayX = Math.sin(yaw);
            awayZ = -Math.cos(yaw);
            length = 1.0D;
        }
        return new double[] {actorPosition[0] + awayX / length * 2.0D,
                actorPosition[1] + 0.05D,
                actorPosition[2] + awayZ / length * 2.0D};
    }

    private static synchronized PlayerInteraction updatePlayerInteraction(Object actor) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!isLocalGoparkyActor(actor) || mc == null || mc.thePlayer == null || mc.theWorld == null) return null;
        long now = System.currentTimeMillis();
        GoparkyTauntSettings.Values settings = GoparkyTauntSettings.get();
        if (!settings.randomPlayerInteractions) {
            clearPlayerInteraction(0L);
            return null;
        }
        if (getLocalTauntRemaining(actor) > 0L
                || isLocalTauntPathOrDanceActive(actor, now)) {
            clearPlayerInteraction(now + PLAYER_INTERACTION_RETRY_MS);
            return null;
        }

        PlayerInteraction current = playerInteraction;
        if (current != null && (current.actor != actor || current.world != mc.theWorld
                || current.target == null || current.target.isDead
                || current.target.worldObj != mc.theWorld
                || !mc.theWorld.playerEntities.contains(current.target))) {
            clearPlayerInteraction(now + 8_000L + RANDOM.nextInt(8_001));
            current = null;
        }
        if (current != null) {
            if (current.startedAtMs == 0L) {
                double[] position = getActorTransform(actor);
                if (position != null && distance(position[0], position[1], position[2],
                        current.target.posX, current.target.posY, current.target.posZ) <= 2.0D) {
                    current.begin(now, position);
                } else if (now - current.chosenAtMs > PLAYER_INTERACTION_APPROACH_TIMEOUT_MS) {
                    clearPlayerInteraction(now + 15_000L + RANDOM.nextInt(20_001));
                    return null;
                }
            } else if (now - current.startedAtMs >= current.durationMs) {
                clearPlayerInteraction(now + 15_000L + RANDOM.nextInt(20_001));
                return null;
            }
            return current;
        }

        if (now < nextPlayerInteractionCheckMs) return null;
        nextPlayerInteractionCheckMs = now + PLAYER_INTERACTION_RETRY_MS
                + RANDOM.nextInt(4_001);
        double[] position = getActorTransform(actor);
        if (position == null) return null;
        List<EntityPlayer> candidates = new ArrayList<EntityPlayer>();
        for (Object entry : mc.theWorld.playerEntities) {
            if (!(entry instanceof EntityPlayer)) continue;
            EntityPlayer player = (EntityPlayer) entry;
            if (player == mc.thePlayer || player.isDead || player.isInvisible()) continue;
            double distance = distance(position[0], position[1], position[2],
                    player.posX, player.posY, player.posZ);
            if (distance >= 3.0D && distance <= 12.0D) candidates.add(player);
        }
        if (candidates.isEmpty()) return null;
        EntityPlayer chosen = candidates.get(RANDOM.nextInt(candidates.size()));
        double directionX = position[0] - chosen.posX;
        double directionZ = position[2] - chosen.posZ;
        double directionLength = Math.sqrt(directionX * directionX + directionZ * directionZ);
        if (directionLength < 1.0E-4D) {
            double yaw = Math.toRadians(chosen.rotationYaw);
            directionX = -Math.sin(yaw);
            directionZ = Math.cos(yaw);
            directionLength = 1.0D;
        }
        playerInteraction = new PlayerInteraction(actor, mc.theWorld, chosen,
                directionX / directionLength, directionZ / directionLength,
                RANDOM.nextInt(4) == 0, now, 2_000L + RANDOM.nextInt(2_001));
        return playerInteraction;
    }

    private static void clearPlayerInteraction(long nextCheckAtMs) {
        playerInteraction = null;
        nextPlayerInteractionCheckMs = nextCheckAtMs;
    }

    private static int boundedArrivalBlocks(double radius) {
        return Math.max(1, Math.min(8, (int) Math.round(radius)));
    }

    private static int floorToInt(double value) {
        if (!Double.isFinite(value)) return 0;
        return (int) Math.floor(value);
    }

    private static String formatPosition(double[] position) {
        return position == null || position.length < 3 ? "unavailable"
                : String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f",
                position[0], position[1], position[2]);
    }

    private static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x1 - x2, dy = y1 - y2, dz = z1 - z2;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static long getLocalTauntRemaining(Object actor) {
        Minecraft mc = Minecraft.getMinecraft();
        UUID ownerId = goparkyTauntOwnerId;
        if (mc == null || mc.thePlayer == null || actor == null || ownerId == null
                || mc.theWorld != goparkyTauntWorld || !ownerId.equals(mc.thePlayer.getUniqueID())
                || getGoparkyActor(ownerId) != actor) return 0L;
        updateLocalTauntTravelPhase(actor, System.currentTimeMillis());
        return Math.max(0L, goparkyTauntUntilMs - System.currentTimeMillis());
    }

    public static long getTauntRemaining(Object actor) {
        long local = getLocalTauntRemaining(actor);
        return local > 0L ? local : GomodCompanionPositionSync.tauntRemaining(actor);
    }

    private static String getHitTintedPath(String path) {
        // The Goparky texture path is unique. Lunar binds it after the transform callback has
        // returned, so this texture-only check must not depend on the actor render scope.
        if (!GOPARKY_TEXTURE_PATH.equals(path)) return null;
        long now = System.currentTimeMillis();
        if (goparkyHitFlashUntilMs - now <= 0L) return null;
        Minecraft mc = Minecraft.getMinecraft();
        UUID flashOwner = goparkyHitFlashOwnerId;
        if (mc == null || mc.thePlayer == null || flashOwner == null
                || !flashOwner.equals(mc.thePlayer.getUniqueID())) return null;
        double elapsed = Math.min(1.0D, Math.max(0.0D,
                (now - goparkyHitFlashStartedAtMs) / (double) GOPARKY_HIT_FLASH_DURATION_MS));
        if (elapsed < 0.58D) return GOPARKY_HIT_TEXTURE_PATH;
        if (elapsed < 0.78D) return GOPARKY_HIT_MEDIUM_TEXTURE_PATH;
        if (elapsed < 0.92D) return GOPARKY_HIT_LIGHT_TEXTURE_PATH;
        return GOPARKY_HIT_FAINT_TEXTURE_PATH;
    }

    private static double smoothStep(double value) {
        double t = Math.max(0.0D, Math.min(1.0D, value));
        return t * t * (3.0D - 2.0D * t);
    }

    private static String getNativeResourceString(Object texture, String primary, String fallback)
            throws Exception {
        try {
            return (String) texture.getClass().getMethod(primary).invoke(texture);
        } catch (NoSuchMethodException unavailable) {
            return (String) texture.getClass().getMethod(fallback).invoke(texture);
        }
    }

    private static Object createNativeResource(String domain, String path, Object texture) {
        if (domain == null || path == null) return null;
        try {
            ClassLoader loader = texture.getClass().getClassLoader();
            Class<?> resourceType = Class.forName(NATIVE_RESOURCE_CLASS, true, loader);
            return resourceType.getMethod(NATIVE_RESOURCE_FACTORY_METHOD,
                    String.class, String.class).invoke(null, path, domain);
        } catch (Throwable ignored) {
            try {
                return new ResourceLocation(domain, path);
            } catch (Throwable alsoIgnored) {
                return null;
            }
        }
    }

    private static boolean bindLunarTexture(Object textureManager, Object texture) {
        try {
            Method binder = LUNAR_TEXTURE_BINDERS.get(textureManager.getClass());
            if (binder == null) {
                for (Method candidate : textureManager.getClass().getMethods()) {
                    if (NATIVE_TEXTURE_BIND_METHOD.equals(candidate.getName())
                            && candidate.getParameterTypes().length == 1) {
                        binder = candidate;
                        LUNAR_TEXTURE_BINDERS.put(textureManager.getClass(), binder);
                        break;
                    }
                }
            }
            if (binder == null || !binder.getParameterTypes()[0].isInstance(texture)) return false;
            binder.invoke(textureManager, texture);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isBundledLunarSkinAvailable(String path) {
        if (path == null) return false;
        if (VERIFIED_LUNAR_SKINS.contains(path)) return true;
        try {
            me.napim.gomod.util.LunarGomodCompanionResourcePack.mount();
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.getResourceManager() == null) return false;
            try (InputStream input = mc.getResourceManager()
                    .getResource(new ResourceLocation("lunar", path)).getInputStream()) {
                java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(input);
                if (image == null || image.getWidth() != 64 || image.getHeight() != 64) return false;
            }
            VERIFIED_LUNAR_SKINS.add(path);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void reportLunarTintFailure(String reason) {
        if (goparkyLunarTintFailureReported) return;
        goparkyLunarTintFailureReported = true;
        System.err.println("[gomod] Goparky Lunar texture redirect fallback: " + reason);
    }

    private static double[] getCigarettePosition(Minecraft mc, UUID ownerId) {
        double[] actor = getActorTransform(ownerId);
        // Without Lunar's live actor, a guessed trail point can put smoke beside the face.
        if (actor == null) return null;

        double yaw = Math.toRadians(actor[3]);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        return new double[] {
                // v8 skin's cigarette tip is on the front head overlay near UV (46,14).
                // 0.20 blocks reaches the face plane; the small side offset follows that UV.
                actor[0] + forwardX * 0.20D + rightX * 0.10D,
                actor[1] + Math.max(0.70D, actor[6] * 0.88D),
                actor[2] + forwardZ * 0.20D + rightZ * 0.10D
        };
    }

    /** x, y, z, yaw, unused, width, height for the tracked local or peer actor. */
    private static double[] getActorTransform(UUID ownerId) {
        Object actor = getGoparkyActor(ownerId);
        double[] transform = getActorTransform(actor);
        double[] rendered = GomodCompanionPositionSync.renderedGoparkyPosition(ownerId);
        if (transform != null && rendered != null) {
            transform[0] = rendered[0]; transform[1] = rendered[1]; transform[2] = rendered[2];
            transform[3] = rendered[3] - 180.0D;
            return transform; // The captured frame already contains travel and recoil.
        }
        double[] recoil = getGoparkyRenderRecoilOffset(actor);
        if (transform != null && recoil != null) {
            transform[0] += recoil[0];
            transform[2] += recoil[1];
        }
        return transform;
    }

    private static Object getGoparkyActor(UUID ownerId) {
        if (ownerId == null) return null;
        WeakReference<Object> reference = GOPARKY_ACTORS.get(ownerId);
        Object actor = reference == null ? null : reference.get();
        if (actor == null) {
            if (reference != null) GOPARKY_ACTORS.remove(ownerId, reference);
            return null;
        }
        return actor;
    }

    private static boolean isLocalGoparkyActor(Object actor) {
        Minecraft mc = Minecraft.getMinecraft();
        return actor != null && mc != null && mc.thePlayer != null
                && getGoparkyActor(mc.thePlayer.getUniqueID()) == actor;
    }

    private static double[] getActorTransform(Object actor) {
        if (actor == null) return null;
        try {
            double x = ((Number) actor.getClass().getMethod("bridge$getPosX").invoke(actor)).doubleValue();
            double y = ((Number) actor.getClass().getMethod("bridge$getPosY").invoke(actor)).doubleValue();
            double z = ((Number) actor.getClass().getMethod("bridge$getPosZ").invoke(actor)).doubleValue();
            double yaw = ((Number) actor.getClass().getMethod("getYaw").invoke(actor)).doubleValue();
            double width = ((Number) actor.getClass().getMethod("bridge$getWidth").invoke(actor)).doubleValue();
            double height = ((Number) actor.getClass().getMethod("bridge$getHeight").invoke(actor)).doubleValue();
            return new double[] {x, y, z, yaw, 0.0D, width, height};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void startHitRecoil(Minecraft mc, UUID ownerId, Object actor,
                                       double hitDirectionX, double hitDirectionZ) {
        if (actor == null || mc == null || mc.thePlayer == null) return;
        double[] transform = getActorTransform(actor);
        if (transform == null) return;
        // Follow the eye-to-impact ray so a side hit sends Goparky sideways too.
        double awayX = hitDirectionX;
        double awayZ = hitDirectionZ;
        double length = Math.sqrt(awayX * awayX + awayZ * awayZ);
        if (length < 0.001D) {
            awayX = transform[0] - mc.thePlayer.posX;
            awayZ = transform[2] - mc.thePlayer.posZ;
            length = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (length < 0.001D) return;
        }
        goparkyLastPushDirectionX = awayX / length;
        goparkyLastPushDirectionZ = awayZ / length;
        // The offset exists only while Lunar renders the actor. Its follow AI position and
        // interpolation state remain untouched, so the hit cannot be overwritten mid-tick.
        double[] current = getGoparkyRenderRecoilOffset(actor);
        double fromX = current == null ? 0.0D : current[0];
        double fromZ = current == null ? 0.0D : current[1];
        double toX = fromX + (awayX / length) * GOPARKY_HIT_RECOIL_DISTANCE;
        double toZ = fromZ + (awayZ / length) * GOPARKY_HIT_RECOIL_DISTANCE;
        double displacement = Math.sqrt(toX * toX + toZ * toZ);
        if (displacement > GOPARKY_MAX_HIT_DISPLACEMENT) {
            double scale = GOPARKY_MAX_HIT_DISPLACEMENT / displacement;
            toX *= scale;
            toZ *= scale;
        }
        goparkyLastPushOffsetX = toX;
        goparkyLastPushOffsetZ = toZ;
        hitRecoil = new HitRecoil(ownerId, actor, mc.theWorld, System.nanoTime(), fromX, fromZ, toX, toZ);
    }

    private static void scheduleTaunt(final Minecraft mc, final String victim) {
        if (mc == null) return;
        Runnable start = new Runnable() {
            @Override
            public void run() {
                if (mc.thePlayer == null || mc.theWorld == null
                        || !isGoparkyEquipped(mc.thePlayer.getUniqueID())) return;
                long now = System.currentTimeMillis();
                clearPlayerInteraction(now + PLAYER_INTERACTION_RETRY_MS);
                GoparkyTauntSettings.Values settings = GoparkyTauntSettings.get();
                goparkyTauntTravel = createTauntTravel(mc, victim, now);
                goparkyTauntDestinationState = goparkyTauntTravel == null
                        ? (victim == null || victim.trim().isEmpty() ? "no-target-name" : "position-unavailable")
                        : "death-location";
                goparkyTauntMotion = new GoparkyTauntMotion(settings.speed,
                        settings.minimumGapMs, settings.maximumGapMs, RANDOM.nextLong());
                goparkyTauntOwnerId = mc.thePlayer.getUniqueID();
                goparkyTauntWorld = mc.theWorld;
                goparkyTauntStartedAtMs = now;
                long routeTimeoutMs = goparkyTauntTravel == null ? 0L
                        : Math.min(GOPARKY_TAUNT_PATH_TIMEOUT_MAX_MS,
                        Math.max(GOPARKY_TAUNT_PATH_TIMEOUT_MIN_MS,
                                goparkyTauntTravel.durationMs() * 3L + 2_500L));
                goparkyTauntPathUntilMs = now + routeTimeoutMs;
                // Keep the compass visible through the full native walk and the five-second taunt at arrival.
                goparkyTauntUntilMs = goparkyTauntPathUntilMs + GOPARKY_TAUNT_DURATION_MS;
                goparkyTauntDanceStartedAtMs = 0L;
                goparkyTauntVictim = victim == null ? "" : victim.trim();
                interruptGoparkyEmote(now);
                goparkyTauntStartCount++;
                goparkyLastTauntAtMs = now;
                System.out.println("[gomod] Goparky kill taunt started destination="
                        + goparkyTauntDestinationState + " routeTimeoutMs=" + routeTimeoutMs);
            }
        };
        if (mc.isCallingFromMinecraftThread()) {
            start.run();
        } else {
            try {
                mc.addScheduledTask(start);
            } catch (Throwable ignored) {
                // A world switch can race a kill callback; skipping one local taunt is harmless.
            }
        }
    }

    private static void clearTaunt() {
        goparkyTauntMotion = null;
        goparkyTauntTravel = null;
        goparkyTauntDestinationState = "none";
        goparkyTauntOwnerId = null;
        goparkyTauntWorld = null;
        goparkyTauntStartedAtMs = 0L;
        goparkyTauntUntilMs = 0L;
        goparkyTauntPathUntilMs = 0L;
        goparkyTauntDanceStartedAtMs = 0L;
        goparkyTauntVictim = "";
    }

    private static void updateRandomGoparkyEmote(Minecraft mc, UUID ownerId, long now) {
        Object actor = getGoparkyActor(ownerId);
        if (actor == null || !GomodCompanionPositionSync.hasRenderedGoparky(ownerId)) {
            clearGoparkyEmote();
            goparkyNextRandomEmoteAtMs = 0L;
            return;
        }
        GoparkyLunarEmoteLibrary.requestLoad(mc);
        if (!GoparkyLunarEmoteLibrary.isReady()) {
            if (!GoparkyLunarEmoteLibrary.isLoading()) goparkyNextRandomEmoteAtMs = now + 5_000L;
            return;
        }
        if (goparkyEmoteId > 0 && now >= goparkyEmoteUntilMs) clearGoparkyEmote();
        if (goparkyEmoteId > 0) return;
        if (goparkyNextRandomEmoteAtMs == 0L) {
            goparkyNextRandomEmoteAtMs = now + 20_000L + RANDOM.nextInt(40_001);
            return;
        }
        if (now < goparkyNextRandomEmoteAtMs) return;
        if (!canStartRandomGoparkyEmote(mc, ownerId, actor, now)) {
            goparkyNextRandomEmoteAtMs = now + 5_000L;
            return;
        }
        GoparkyLunarEmoteLibrary.Emote emote = chooseRandomGoparkyEmote();
        if (emote == null) {
            goparkyNextRandomEmoteAtMs = now + 5_000L;
            return;
        }
        startGoparkyEmote(emote, "random", now);
        goparkyNextRandomEmoteAtMs = goparkyEmoteUntilMs
                + GOPARKY_EMOTE_MIN_INTERVAL_MS
                + RANDOM.nextInt((int) (GOPARKY_EMOTE_MAX_INTERVAL_MS - GOPARKY_EMOTE_MIN_INTERVAL_MS + 1L));
    }

    private static boolean canStartRandomGoparkyEmote(Minecraft mc, UUID ownerId, Object actor, long now) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null
                || !ownerId.equals(mc.thePlayer.getUniqueID())
                || goparkyHitFlashUntilMs > now || getLocalTauntRemaining(actor) > 0L
                || isLocalTauntPathOrDanceActive(actor, now)) return false;
        PlayerInteraction interaction = playerInteraction;
        if (interaction == null || interaction.actor != actor) return true;
        return interaction.startedAtMs == 0L
                && now - interaction.chosenAtMs > PLAYER_INTERACTION_APPROACH_TIMEOUT_MS;
    }

    private static void startGoparkyEmote(GoparkyLunarEmoteLibrary.Emote emote, String source, long now) {
        if (emote == null || !emote.playable) return;
        goparkyEmoteId = emote.id;
        goparkyEmoteStartedAtMs = now;
        goparkyEmoteUntilMs = now + GoparkyLunarEmoteLibrary.playbackDurationMs(emote);
        goparkyEmoteSource = source == null ? "unknown" : source;
        goparkyEmoteStartCount++;
        goparkyLastEmoteAtMs = now;
        if ("random".equals(source)) goparkyLastRandomEmoteId = emote.id;
    }

    private static void interruptGoparkyEmote(long now) {
        clearGoparkyEmote();
        goparkyNextRandomEmoteAtMs = now + GOPARKY_EMOTE_MIN_INTERVAL_MS + RANDOM.nextInt(30_001);
    }

    private static void clearGoparkyEmote() {
        goparkyEmoteId = 0;
        goparkyEmoteStartedAtMs = 0L;
        goparkyEmoteUntilMs = 0L;
        goparkyEmoteSource = "none";
    }

    private static GoparkyLunarEmoteLibrary.Emote chooseRandomGoparkyEmote() {
        return GoparkyLunarEmoteLibrary.random(RANDOM, goparkyLastRandomEmoteId);
    }

    private static String goparkyEmoteName(int emoteId) {
        return GoparkyLunarEmoteLibrary.nameForId(emoteId);
    }

    private static GoparkyTauntTravel createTauntTravel(Minecraft mc, String victim, long startedAtMs) {
        if (mc == null || mc.thePlayer == null || mc.theWorld == null
                || victim == null || victim.trim().isEmpty()) return null;
        double[] death = GomodCompanionPositionSync.findRecentPlayerPosition(mc, victim);
        if (death == null) return null;
        UUID owner = mc.thePlayer.getUniqueID();
        double[] actor = getActorTransform(getGoparkyActor(owner));
        double fromX, fromY, fromZ, initialFrameYaw;
        if (actor != null) {
            fromX = actor[0]; fromY = actor[1]; fromZ = actor[2];
            initialFrameYaw = actor[3] + 180D;
        } else {
            double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
            fromX = mc.thePlayer.posX + Math.sin(yaw) * COMPANION_TRAIL_DISTANCE;
            fromY = mc.thePlayer.posY;
            fromZ = mc.thePlayer.posZ - Math.cos(yaw) * COMPANION_TRAIL_DISTANCE;
            initialFrameYaw = mc.thePlayer.rotationYaw + 180D;
        }
        GoparkyTauntSettings.Values settings = GoparkyTauntSettings.get();
        // Give Lunar the actual death position. Its native path task stops within the saved radius.
        double[] destination = {death[0], death[1], death[2]};
        double ownerDx = destination[0] - mc.thePlayer.posX;
        double ownerDy = destination[1] - mc.thePlayer.posY;
        double ownerDz = destination[2] - mc.thePlayer.posZ;
        if (!Double.isFinite(ownerDx) || !Double.isFinite(ownerDy) || !Double.isFinite(ownerDz)
                || Math.abs(ownerDx) > 32D || Math.abs(ownerDy) > 32D || Math.abs(ownerDz) > 32D) return null;
        double distanceX = destination[0] - fromX;
        double distanceY = destination[1] - fromY;
        double distanceZ = destination[2] - fromZ;
        double distance = Math.sqrt(distanceX * distanceX + distanceY * distanceY + distanceZ * distanceZ);
        if (!Double.isFinite(distance) || distance > 36D) return null;
        return new GoparkyTauntTravel(new double[] {fromX, fromY, fromZ}, destination,
                startedAtMs, GoparkyTauntTravel.durationFor(distance, settings.travelBlocksPerSecond),
                initialFrameYaw);
    }

    private static void schedule(final Minecraft mc, final boolean win) {
        if (mc == null) return;
        if (mc.isCallingFromMinecraftThread()) {
            renderReaction(mc, win);
            return;
        }
        try {
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    renderReaction(mc, win);
                }
            });
        } catch (Throwable ignored) {
            // Packet callbacks may race a world switch; a missed cosmetic puff is harmless.
        }
    }

    private static void renderReaction(Minecraft mc, boolean win) {
        if (mc.thePlayer == null || mc.theWorld == null || !ConfigManager.isLunarTweaksEnabled()) return;

        long now = System.currentTimeMillis();
        boolean blitzActive = GameContext.isBlitzGameActive(mc);
        boolean gameOver = GameContext.isBlitzGameOverSidebar(mc);
        boolean deathmatch = GameContext.isBlitzDeathmatchPhase(mc);
        if (win) {
            // Hypixel may move to the lobby before its Win Bonus packet is handled. A recent
            // local kill observed during a Blitz match keeps that final event associated with it.
            boolean recentBlitz = now - lastBlitzActivityMs <= RECENT_BLITZ_ACTIVITY_MS;
            if (!blitzActive && !gameOver && !recentBlitz) {
                if (!winSkipReported) {
                    winSkipReported = true;
                    System.out.println("[gomod] Goparky win reaction skipped: no recent Blitz match state");
                }
                return;
            }
        } else {
            if (!blitzActive && !deathmatch) {
                if (!killSkipReported) {
                    killSkipReported = true;
                    System.out.println("[gomod] Goparky kill reaction skipped: Blitz match state inactive");
                }
                return;
            }
            lastBlitzActivityMs = now;
        }

        if (!isGoparkyEquipped(mc.thePlayer.getUniqueID())) return;

        if (win) {
            if (now - lastWinReactionMs < WIN_COOLDOWN_MS) return;
            lastWinReactionMs = now;
        } else {
            if (now - lastKillReactionMs < KILL_COOLDOWN_MS) return;
            lastKillReactionMs = now;
        }

        // Mini Me keeps a five-block follow distance. Emit the reaction at that trailing spot,
        // beside the cigarette/head height, rather than near the owner's feet.
        double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
        double side = 0.42D;
        double x = mc.thePlayer.posX + Math.sin(yaw) * COMPANION_TRAIL_DISTANCE + Math.cos(yaw) * side;
        double y = mc.thePlayer.posY + (win ? 0.95D : 0.82D);
        double z = mc.thePlayer.posZ - Math.cos(yaw) * COMPANION_TRAIL_DISTANCE - Math.sin(yaw) * side;

        if (win) {
            puff(mc, EnumParticleTypes.SMOKE_NORMAL, x, y, z, 10, 0.22D, 0.055D);
            puff(mc, EnumParticleTypes.HEART, x, y + 0.18D, z, 9, 0.78D, 0.10D);
            puff(mc, EnumParticleTypes.FIREWORKS_SPARK, x, y + 0.10D, z, 18, 0.68D, 0.075D);
            if (!winReactionReported) {
                winReactionReported = true;
                System.out.println("[gomod] Goparky Blitz win reaction fired: hearts and firework sparks");
            }
        } else {
            puff(mc, EnumParticleTypes.SMOKE_NORMAL, x, y, z, 9, 0.18D, 0.065D);
            puff(mc, EnumParticleTypes.CRIT, x, y + 0.06D, z, 14, 0.42D, 0.045D);
            if (!killReactionReported) {
                killReactionReported = true;
                System.out.println("[gomod] Goparky Blitz kill reaction fired: smoke and impact sparks");
            }
        }
    }

    private static void puff(Minecraft mc, EnumParticleTypes type, double x, double y, double z,
                             int count, double spread, double upwardSpeed) {
        for (int i = 0; i < count; i++) {
            double dx = (RANDOM.nextDouble() - 0.5D) * spread;
            double dz = (RANDOM.nextDouble() - 0.5D) * spread;
            double dy = upwardSpeed * (0.65D + RANDOM.nextDouble() * 0.7D);
            mc.theWorld.spawnParticle(type, x + dx, y, z + dz,
                    dx * 0.35D, dy, dz * 0.35D);
        }
    }

    /** The native rendered actor is the equip signal; no static Lunar singleton lookup. */
    private static boolean isGoparkyEquipped(UUID playerId) {
        boolean equipped = GomodCompanionPositionSync.hasRenderedGoparky(playerId);
        reportEquippedState(equipped);
        return equipped;
    }

    private static void reportEquippedState(boolean equipped) {
        int value = equipped ? 1 : 0;
        if (lastReportedEquippedState == value) return;
        lastReportedEquippedState = value;
        System.out.println("[gomod] Goparky Blitz companion equipped=" + equipped);
    }

    private static final class HitRecoil {
        final UUID ownerId;
        final WeakReference<Object> actor;
        final Object world;
        final long startedAtNanos;
        final double fromX;
        final double fromZ;
        final double toX;
        final double toZ;

        HitRecoil(UUID ownerId, Object actor, Object world, long startedAtNanos,
                  double fromX, double fromZ, double toX, double toZ) {
            this.ownerId = ownerId;
            this.actor = new WeakReference<Object>(actor);
            this.world = world;
            this.startedAtNanos = startedAtNanos;
            this.fromX = fromX;
            this.fromZ = fromZ;
            this.toX = toX;
            this.toZ = toZ;
        }
    }

    private static final class PlayerInteraction {
        final Object actor;
        final Object world;
        final EntityPlayer target;
        final double approachDirectionX;
        final double approachDirectionZ;
        final boolean showButt;
        final long chosenAtMs;
        final long durationMs;
        volatile long startedAtMs;
        volatile double[] holdPosition;

        PlayerInteraction(Object actor, Object world, EntityPlayer target,
                          double approachDirectionX, double approachDirectionZ, boolean showButt,
                          long chosenAtMs, long durationMs) {
            this.actor = actor;
            this.world = world;
            this.target = target;
            this.approachDirectionX = approachDirectionX;
            this.approachDirectionZ = approachDirectionZ;
            this.showButt = showButt;
            this.chosenAtMs = chosenAtMs;
            this.durationMs = durationMs;
        }

        void begin(long now, double[] actorPosition) {
            if (startedAtMs > 0L || actorPosition == null || actorPosition.length < 3) return;
            holdPosition = new double[] {actorPosition[0], actorPosition[1], actorPosition[2]};
            startedAtMs = now;
        }

        double[] movementTarget(double approachDistance) {
            if (startedAtMs > 0L) {
                double[] held = holdPosition;
                if (held != null) return held.clone();
            }
            return new double[] {target.posX + approachDirectionX * approachDistance,
                    target.posY, target.posZ + approachDirectionZ * approachDistance};
        }
    }

}
