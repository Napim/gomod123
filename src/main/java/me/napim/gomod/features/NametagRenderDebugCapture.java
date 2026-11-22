package me.napim.gomod.features;

import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Opt-in live capture for ordinary player/mob labels and their OpenGL depth/alpha state. */
public final class NametagRenderDebugCapture {
    private static final Object LOCK = new Object();
    private static final int MAX_EVENTS = 8000;
    private static final int MAX_OPEN_NAME_PASSES = 64;
    private static final long SAMPLE_INTERVAL_NANOS = 1_000_000_000L;
    private static final long MAX_CAPTURE_NANOS = 60_000_000_000L;
    private static final String COUNTDOWN_RENDER_CALLER_PREFIX =
            "com.moonsworth.lunar.ORHIICHHOORORCHROCCICIIICCRHHI.RHCIRCIHHHIORORHORCHCOIHCRHRHR.RHCIRCIHHHIORORHORCHCOIHCRHRHR.CIHOHRHCCIIHROIICOIICCHCHRHHOH.";
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<String>();
    private static final ThreadLocal<FloatBuffer> GL_COLOR_READBACK = new ThreadLocal<FloatBuffer>() {
        // LWJGL 2 validates the buffer against the largest glGetFloat result (a 4x4 matrix).
        @Override protected FloatBuffer initialValue() { return BufferUtils.createFloatBuffer(16); }
    };
    private static final ThreadLocal<IntBuffer> GL_TEXTURE_ENV_READBACK = new ThreadLocal<IntBuffer>() {
        @Override protected IntBuffer initialValue() { return BufferUtils.createIntBuffer(16); }
    };
    private static final ThreadLocal<FloatBuffer> GL_TEXTURE_ENV_COLOR_READBACK = new ThreadLocal<FloatBuffer>() {
        @Override protected FloatBuffer initialValue() { return BufferUtils.createFloatBuffer(16); }
    };
    private static final Map<String, Seen> LAST = new HashMap<String, Seen>();
    private static final Map<String, Long> COUNTS = new LinkedHashMap<String, Long>();
    private static final ThreadLocal<ArrayDeque<NamePass>> NAME_PASSES =
            new ThreadLocal<ArrayDeque<NamePass>>() {
                @Override protected ArrayDeque<NamePass> initialValue() { return new ArrayDeque<NamePass>(); }
            };
    private static volatile boolean active;
    private static long startedAtNanos;
    private static long startedAtMillis;
    private static long droppedEvents;
    private static long nextPassId;
    private static File outputFile;

    private NametagRenderDebugCapture() { }

    public static boolean isActive() { return active; }

    public static void recordNativeWorldLabelState(String phase, String state) {
        if (!active) return;
        record("lunar.world_label." + phase, phase, state, state);
    }

    public static String start() {
        NAME_PASSES.get().clear();
        synchronized (LOCK) {
            EVENTS.clear();
            LAST.clear();
            COUNTS.clear();
            droppedEvents = 0L;
            startedAtNanos = System.nanoTime();
            startedAtMillis = System.currentTimeMillis();
            outputFile = null;
            active = true;
        }
        return "Live nametag render capture started (60s max). In F5, aim at player/mob names with your avatar between the camera and target, then turn until a name disappears; run /nametagrenderdebug stop to save.";
    }

    public static String status() {
        synchronized (LOCK) {
            if (startedAtNanos == 0L) return "No nametag render capture has been started.";
            long elapsed = Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000_000L);
            String state = active ? "capturing" : "stopped; run stop to save";
            return "Nametag render capture " + state + " (" + elapsed + "s, " + EVENTS.size()
                    + " logged events, " + droppedEvents + " overwritten).";
        }
    }

    public static String stopAndSave() throws IOException {
        final String report;
        synchronized (LOCK) {
            if (startedAtNanos == 0L) return "No nametag render capture to save.";
            active = false;
            report = buildReportLocked();
        }
        File dir = new File(GomodPaths.getGomod123Dir(), "debugall");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not create " + dir.getAbsolutePath());
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
        File file = new File(dir, "nametag_render_state_" + stamp + ".log");
        Files.write(file.toPath(), report.getBytes(StandardCharsets.UTF_8));
        synchronized (LOCK) { outputFile = file; }
        return "Nametag render capture saved: " + file.getAbsolutePath();
    }

    /** Called at RendererLivingEntity.renderName HEAD and RETURN. */
    public static void recordNamePass(String phase, EntityLivingBase entity, double x, double y, double z) {
        if (entity == null) return;
        ArrayDeque<NamePass> stack = NAME_PASSES.get();
        NamePass pass;
        if ("begin".equals(phase)) {
            if (!active || !isRelevantNamePass(entity)) return;
            removePassesForEntity(stack, entity.getEntityId());
            synchronized (LOCK) { pass = new NamePass(++nextPassId, entity.getEntityId()); }
            stack.push(pass);
            while (stack.size() > MAX_OPEN_NAME_PASSES) stack.removeLast();
        } else {
            pass = popPass(stack, entity.getEntityId());
            if (!active || !isRelevantNamePass(entity)) return;
        }
        String gl = readGlState();
        Minecraft mc = Minecraft.getMinecraft();
        int view = mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
        String detail = "pass=" + (pass == null ? -1L : pass.id) + " phase=" + phase + " entity="
                + entitySummary(entity) + " thirdPersonView=" + view + " pos=" + vector(x, y, z)
                + (pass == null ? "" : " labels=" + pass.labelCalls) + " " + gl;
        String signature = "view=" + view + " " + gl;
        record("renderName." + phase, entity.getEntityId(), signature, detail);
    }

    /** Records the living entity render even when Lunar bypasses renderName and its label bridge. */
    public static void recordEntityRender(String phase, EntityLivingBase entity,
            double x, double y, double z) {
        if (!active || entity == null || !isRelevantNamePass(entity)) return;
        String gl = readGlState();
        Minecraft mc = Minecraft.getMinecraft();
        int view = mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
        String detail = "phase=" + phase + " entity=" + entitySummary(entity)
                + " thirdPersonView=" + view + " pos=" + vector(x, y, z) + " " + gl;
        record("entityRender." + phase, entity.getEntityId(), "view=" + view + " " + gl, detail);
    }

    /** Captures actual FontRenderer text submitted while a living entity is being rendered. */
    public static void recordEntityFontDraw(EntityLivingBase entity, String text,
            float x, float y, int color, boolean dropShadow) {
        if (!active || entity == null || !isRelevantNamePass(entity)) return;
        String gl = readGlState();
        Minecraft mc = Minecraft.getMinecraft();
        int view = mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
        String detail = "entity=" + entitySummary(entity) + " thirdPersonView=" + view
                + " x=" + String.format(Locale.ROOT, "%.2f", x)
                + " y=" + String.format(Locale.ROOT, "%.2f", y)
                + " color=0x" + Integer.toHexString(color) + " dropShadow=" + dropShadow
                + " label={" + labelSummary(text) + "} " + gl;
        String key = entity.getEntityId() + ":" + labelHash(text) + ":"
                + Math.round(x * 2.0f) + ":" + Math.round(y * 2.0f);
        record("fontRenderer.entity_text", key, "view=" + view + " " + gl, detail);
    }

    /** Captures short, countdown-like text even when it is not drawn inside a living-entity label pass. */
    public static void recordGlobalFontDraw(String text, float x, float y, int color, boolean dropShadow) {
        // The preceding capture isolated the Countdown's centered world-space draw at (-10, 0).
        // Reject HUD/item text before doing stack inspection or OpenGL queries to keep capture overhead low.
        if (!active || dropShadow || x < -16.0F || x > -4.0F || y < -3.0F || y > 3.0F
                || text == null || text.length() > 12 || !isShortCountdownCandidate(text)) return;
        String caller = fontDrawCaller();
        String gl = readGlState();
        String detail = "caller=" + caller + " x=" + String.format(Locale.ROOT, "%.2f", x)
                + " y=" + String.format(Locale.ROOT, "%.2f", y)
                + " color=0x" + Integer.toHexString(color) + " dropShadow=" + dropShadow
                + " formats=" + formatCodeSequence(text) + " label={" + labelSummary(text) + "} " + gl;
        String key = labelHash(text) + ":" + caller + ":" + Math.round(x * 2.0f) + ":" + Math.round(y * 2.0f);
        record("fontRenderer.global_countdown_candidate", key, "color=0x" + Integer.toHexString(color)
                + ",shadow=" + dropShadow + " " + gl, detail);
    }

    /** Records the actual color applied by GlStateManager during Lunar's known countdown font draw. */
    public static void recordAppliedCountdownGlyphColor(float requestedRed, float requestedGreen,
            float requestedBlue, float requestedAlpha) {
        if (!active) return;
        if (!isFontRendererOnStack()) return;
        String caller = fontDrawCaller();
        if (!caller.startsWith(COUNTDOWN_RENDER_CALLER_PREFIX)) return;
        String actualColor = readCurrentGlColor();
        String textureEnv = readCountdownTextureEnv();
        String requested = String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f",
                requestedRed, requestedGreen, requestedBlue, requestedAlpha);
        String detail = "caller=" + caller + " requested=" + requested + " actualGL=" + actualColor
                + " " + textureEnv + " source=GlStateManager.color during FontRenderer call";
        record("fontRenderer.countdown_glyph_color", caller + ":" + actualColor + ":" + textureEnv,
                actualColor + ":" + textureEnv, detail);
    }

    private static boolean isFontRendererOnStack() {
        try {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String className = frame.getClassName();
                if (className.equals("net.minecraft.client.gui.FontRenderer")
                        || className.endsWith(".FontRenderer") || className.equals("avn")) return true;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    /** Called at Render.renderLivingLabel HEAD/RETURN, covering player, mob, and health rows. */
    public static void recordLivingLabel(String phase, Entity entity, String label,
            double x, double y, double z, int maxDistance) {
        if (!active || entity == null) return;
        ArrayDeque<NamePass> stack = NAME_PASSES.get();
        NamePass pass = findPass(stack, entity.getEntityId());
        if (pass != null && pass.entityId == entity.getEntityId() && "enter".equals(phase)) pass.labelCalls++;
        String labelInfo = labelSummary(label);
        String gl = readGlState();
        Minecraft mc = Minecraft.getMinecraft();
        int view = mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
        String detail = "phase=" + phase + " pass=" + (pass == null ? -1L : pass.id) + " entity="
                + entitySummary(entity) + " thirdPersonView=" + view + " label={" + labelInfo + "} maxDistance="
                + maxDistance + " pos=" + vector(x, y, z) + " " + gl;
        String signature = "view=" + view + " " + gl;
        record("renderLivingLabel." + phase, entity.getEntityId() + ":" + labelHash(label), signature, detail);
    }

    /** Records a Gomod billboard draw at the same boundary where its GL state is repaired/restored. */
    public static void recordBillboard(String phase, Entity entity, String text, String source,
            boolean fadeThroughWalls, boolean prioritizeOverEntityBody) {
        if (!active || entity == null) return;
        String gl = readGlState();
        Minecraft mc = Minecraft.getMinecraft();
        int view = mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
        String detail = "phase=" + phase + " source=" + source + " entity=" + entitySummary(entity)
                + " thirdPersonView=" + view + " fadeThroughWalls=" + fadeThroughWalls
                + " prioritizeOverEntityBody=" + prioritizeOverEntityBody
                + " label={" + labelSummary(text) + "} " + gl;
        record("billboard." + phase, entity.getEntityId() + ":" + source + ":" + labelHash(text),
                "view=" + view + ",fade=" + fadeThroughWalls + ",body=" + prioritizeOverEntityBody + " " + gl,
                detail);
    }

    /** Records why the camera-ray test did or did not queue a label over the local F5 avatar. */
    public static void recordBodyOcclusionCheck(Entity entity, String label, String decision,
            int bodyRays, int blockedRays, int waterRays, Vec3 camera, Vec3 center) {
        if (!active || entity == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        int view = mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
        String detail = "decision=" + decision + " entity=" + entitySummary(entity)
                + " thirdPersonView=" + view + " label={" + labelSummary(label) + "}"
                + " bodyRays=" + bodyRays + " blockedRays=" + blockedRays + " waterRays=" + waterRays
                + " camera=" + (camera == null ? "unavailable" : vector(camera.xCoord,camera.yCoord,camera.zCoord))
                + " labelCenter=" + (center == null ? "unavailable" : vector(center.xCoord,center.yCoord,center.zCoord));
        String signature = decision + ":" + bodyRays + ":" + blockedRays + ":" + waterRays + ":view=" + view;
        record("f5BodyOcclusion.check", entity.getEntityId() + ":" + labelHash(label), signature, detail);
    }

    /** Records when Lunar supplied a camera offset that had to be rebased to the viewer's world origin. */
    public static void recordCameraPositionRebased(Vec3 raw, Vec3 viewerOrigin, Vec3 worldPosition) {
        if (!active || raw == null || viewerOrigin == null || worldPosition == null) return;
        String detail = "source=ActiveRenderInfo-relative-to-viewer raw="
                + vector(raw.xCoord, raw.yCoord, raw.zCoord) + " viewerOrigin="
                + vector(viewerOrigin.xCoord, viewerOrigin.yCoord, viewerOrigin.zCoord) + " world="
                + vector(worldPosition.xCoord, worldPosition.yCoord, worldPosition.zCoord);
        record("f5Camera.position_rebased", "third-person", "active-info-relative", detail);
    }

    /** Records state immediately before an actual Gomod label quad/text pass is submitted. */
    public static void recordBillboardStage(String stage, Entity entity, String text, String source,
            boolean fadeThroughWalls, boolean prioritizeOverEntityBody) {
        if (!active || entity == null) return;
        String gl = readGlState();
        Minecraft mc = Minecraft.getMinecraft();
        int view = mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
        String detail = "stage=" + stage + " source=" + source + " entity=" + entitySummary(entity)
                + " thirdPersonView=" + view + " fadeThroughWalls=" + fadeThroughWalls
                + " prioritizeOverEntityBody=" + prioritizeOverEntityBody
                + " label={" + labelSummary(text) + "} " + gl;
        record("billboard.draw." + stage, entity.getEntityId() + ":" + source + ":" + labelHash(text),
                "view=" + view + ",fade=" + fadeThroughWalls + ",body=" + prioritizeOverEntityBody + " " + gl,
                detail);
    }

    /** Captures the shared player/mob row transform before it dispatches into the native font draw. */
    public static void recordOffsetLabel(String phase, Entity entity, String text, double x, double y, double z,
            float labelScale, double distanceSq) {
        if (!active || entity == null) return;
        String gl = readGlState();
        Minecraft mc = Minecraft.getMinecraft();
        int view = mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView;
        String detail = "phase=" + phase + " entity=" + entitySummary(entity) + " thirdPersonView=" + view
                + " label={" + labelSummary(text) + "} labelScale="
                + String.format(Locale.ROOT, "%.4f", labelScale) + " distanceSq="
                + String.format(Locale.ROOT, "%.2f", distanceSq) + " pos=" + vector(x, y, z) + " " + gl;
        record("offsetLabel." + phase, entity.getEntityId() + ":" + labelHash(text), "view=" + view + " " + gl,
                detail);
    }

    /** Records why the existing render hook cancelled a particular row. */
    public static void recordLabelCancelled(Entity entity, String label, String reason) {
        if (!active || entity == null) return;
        String gl = readGlState();
        String detail = "reason=" + reason + " entity=" + entitySummary(entity) + " label={"
                + labelSummary(label) + "} " + gl;
        record("label.cancel", entity.getEntityId() + ":" + labelHash(label) + ":" + reason,
                gl, detail);
    }

    private static void record(String event, int entityId, String signature, String detail) {
        record(event, String.valueOf(entityId), signature, detail);
    }

    private static void record(String event, String entityKey, String signature, String detail) {
        long now = System.nanoTime();
        synchronized (LOCK) {
            if (!active) return;
            if (now - startedAtNanos >= MAX_CAPTURE_NANOS) {
                active = false;
                return;
            }
            Long count = COUNTS.get(event);
            COUNTS.put(event, count == null ? 1L : count + 1L);
            String key = event + ':' + entityKey;
            Seen seen = LAST.get(key);
            if (seen != null && seen.signature.equals(signature)
                    && now - seen.loggedAtNanos < SAMPLE_INTERVAL_NANOS) return;
            LAST.put(key, new Seen(signature, now));
            while (EVENTS.size() >= MAX_EVENTS) {
                EVENTS.removeFirst();
                droppedEvents++;
            }
            double seconds = (now - startedAtNanos) / 1_000_000_000.0D;
            EVENTS.addLast(String.format(Locale.ROOT, "+%.3fs %s %s", seconds, event, detail));
        }
    }

    private static boolean isRelevantNamePass(EntityLivingBase entity) {
        if (entity instanceof EntityPlayer) return true;
        try { return entity.hasCustomName(); } catch (Throwable ignored) { return false; }
    }

    private static NamePass popPass(ArrayDeque<NamePass> stack, int entityId) {
        if (stack.isEmpty()) return null;
        if (stack.peek().entityId == entityId) return stack.pop();
        ArrayDeque<NamePass> deferred = new ArrayDeque<NamePass>();
        NamePass found = null;
        while (!stack.isEmpty()) {
            NamePass pass = stack.pop();
            if (pass.entityId == entityId) { found = pass; break; }
            deferred.push(pass);
        }
        while (!deferred.isEmpty()) stack.push(deferred.pop());
        return found;
    }

    private static NamePass findPass(ArrayDeque<NamePass> stack, int entityId) {
        for (NamePass pass : stack) if (pass.entityId == entityId) return pass;
        return null;
    }

    private static void removePassesForEntity(ArrayDeque<NamePass> stack, int entityId) {
        ArrayDeque<NamePass> retained = new ArrayDeque<NamePass>();
        while (!stack.isEmpty()) {
            NamePass pass = stack.pop();
            if (pass.entityId != entityId) retained.push(pass);
        }
        while (!retained.isEmpty()) stack.push(retained.pop());
    }

    private static String entitySummary(Entity entity) {
        String kind = entity instanceof EntityPlayer ? "player" : entity instanceof EntityLivingBase ? "mob" : "entity";
        boolean customName = false;
        boolean invisible = false;
        try { customName = entity.hasCustomName(); } catch (Throwable ignored) { }
        try { invisible = entity.isInvisible(); } catch (Throwable ignored) { }
        Minecraft mc = Minecraft.getMinecraft();
        Entity viewer = mc == null ? null : mc.getRenderViewEntity();
        String distance = "n/a";
        try {
            if (viewer != null) distance = String.format(Locale.ROOT, "%.2f",
                    Math.sqrt(entity.getDistanceSqToEntity(viewer)));
        } catch (Throwable ignored) { }
        return kind + "#" + entity.getEntityId() + " class=" + entity.getClass().getName()
                + " customName=" + customName + " invisible=" + invisible + " distance=" + distance;
    }

    private static String labelSummary(String label) {
        if (label == null) return "null";
        int sectionSigns = 0;
        for (int i = 0; i < label.length(); i++) if (label.charAt(i) == '\u00a7') sectionSigns++;
        String kind = label.indexOf('\n') >= 0 ? "multi-line" : "single-line";
        return "kind=" + kind + ",length=" + label.length() + ",formatCodes=" + sectionSigns
                + ",hash=" + labelHash(label);
    }

    private static int labelHash(String label) { return label == null ? 0 : label.hashCode(); }

    private static boolean isShortCountdownCandidate(String text) {
        if (text == null || text.isEmpty() || text.length() > 48) return false;
        if (text.indexOf('\u00a7') >= 0) return true;
        for (int i = 0; i < text.length(); i++) if (Character.isDigit(text.charAt(i))) return true;
        return text.toLowerCase(Locale.ROOT).contains("tnt");
    }

    private static String formatCodeSequence(String text) {
        StringBuilder codes = new StringBuilder();
        for (int i = 0; i + 1 < text.length(); i++) {
            if (text.charAt(i) == '\u00a7') codes.append(Character.toLowerCase(text.charAt(++i)));
        }
        return codes.length() == 0 ? "none" : codes.toString();
    }

    private static String fontDrawCaller() {
        try {
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                String className = frame.getClassName();
                if (className.startsWith("java.") || className.startsWith("sun.")
                        || className.startsWith("org.spongepowered.")
                        || className.equals(NametagRenderDebugCapture.class.getName())
                        || className.equals("me.napim.gomod.mixins.FontRendererMixin")
                        || className.equals("me.napim.gomod.mixins.GlStateManagerMixin")
                        || className.equals("net.minecraft.client.renderer.GlStateManager")
                        || className.equals("bfl")
                        || className.equals("net.minecraft.client.gui.FontRenderer")) continue;
                return className + "." + frame.getMethodName();
            }
        } catch (Throwable ignored) { }
        return "unavailable";
    }

    private static String vector(double x, double y, double z) {
        return String.format(Locale.ROOT, "(%.2f,%.2f,%.2f)", x, y, z);
    }

    private static String readGlState() {
        try {
            return "gl{depth=" + GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                    + ",depthFunc=0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_DEPTH_FUNC))
                    + ",depthWrite=" + GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)
                    + ",alpha=" + GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
                    + ",alphaFunc=0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC))
                    + ",alphaRef=" + String.format(Locale.ROOT, "%.4f", GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF))
                    + ",blend=" + GL11.glIsEnabled(GL11.GL_BLEND)
                    + ",stencil=" + GL11.glIsEnabled(GL11.GL_STENCIL_TEST)
                    + ",stencilBits=" + GL11.glGetInteger(GL11.GL_STENCIL_BITS)
                    + ",stencilFunc=0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_STENCIL_FUNC))
                    + ",stencilRef=" + GL11.glGetInteger(GL11.GL_STENCIL_REF)
                    + ",fog=" + GL11.glIsEnabled(GL11.GL_FOG)
                    + ",fogMode=0x" + Integer.toHexString(GL11.glGetInteger(GL11.GL_FOG_MODE))
                    + ",fogStart=" + String.format(Locale.ROOT, "%.2f", GL11.glGetFloat(GL11.GL_FOG_START))
                    + ",fogEnd=" + String.format(Locale.ROOT, "%.2f", GL11.glGetFloat(GL11.GL_FOG_END))
                    + ",texture2d=" + GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
                    + ",textureBinding=" + GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
                    + ",currentColor=" + readCurrentGlColor() + '}';
        } catch (Throwable unavailable) {
            return "gl=unavailable:" + unavailable.getClass().getSimpleName();
        }
    }

    private static String readCurrentGlColor() {
        try {
            FloatBuffer color = GL_COLOR_READBACK.get();
            color.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
            return String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f",
                    color.get(0), color.get(1), color.get(2), color.get(3));
        } catch (Throwable unavailable) {
            return "unavailable:" + unavailable.getClass().getSimpleName();
        }
    }

    private static String readCountdownTextureEnv() {
        try {
            int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            IntBuffer ints = GL_TEXTURE_ENV_READBACK.get();
            ints.clear();
            GL11.glGetTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE, ints);
            int mode = ints.get(0);
            StringBuilder result = new StringBuilder("textureEnv{unit=0x")
                    .append(Integer.toHexString(activeTexture)).append(",mode=0x")
                    .append(Integer.toHexString(mode));
            if (mode == GL13.GL_COMBINE) {
                appendTextureEnvInteger(result, "combineRgb", GL13.GL_COMBINE_RGB);
                appendTextureEnvInteger(result, "src0Rgb", GL13.GL_SOURCE0_RGB);
                appendTextureEnvInteger(result, "op0Rgb", GL13.GL_OPERAND0_RGB);
                appendTextureEnvInteger(result, "src1Rgb", GL13.GL_SOURCE1_RGB);
                appendTextureEnvInteger(result, "op1Rgb", GL13.GL_OPERAND1_RGB);
                appendTextureEnvInteger(result, "src2Rgb", GL13.GL_SOURCE2_RGB);
                appendTextureEnvInteger(result, "op2Rgb", GL13.GL_OPERAND2_RGB);
                FloatBuffer color = GL_TEXTURE_ENV_COLOR_READBACK.get();
                color.clear();
                GL11.glGetTexEnv(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_COLOR, color);
                result.append(",constant=").append(String.format(Locale.ROOT, "%.3f,%.3f,%.3f,%.3f",
                        color.get(0), color.get(1), color.get(2), color.get(3)));
            }
            return result.append('}').toString();
        } catch (Throwable unavailable) {
            return "textureEnv=unavailable:" + unavailable.getClass().getSimpleName();
        }
    }

    private static void appendTextureEnvInteger(StringBuilder out, String label, int parameter) {
        IntBuffer values = GL_TEXTURE_ENV_READBACK.get();
        values.clear();
        GL11.glGetTexEnv(GL11.GL_TEXTURE_ENV, parameter, values);
        out.append(',').append(label).append("=0x").append(Integer.toHexString(values.get(0)));
    }

    private static String buildReportLocked() {
        StringBuilder out = new StringBuilder(32_768);
        out.append("gomod123 live nametag render-state capture\n")
                .append("generated=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
                        .format(new Date())).append('\n')
                .append("started=").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT)
                        .format(new Date(startedAtMillis))).append('\n')
                .append("durationSeconds=").append(Math.max(0L,
                        (System.nanoTime() - startedAtNanos) / 1_000_000_000L)).append('\n')
                .append("activeAtSave=").append(active).append('\n')
                .append("eventRowsKept=").append(EVENTS.size()).append(" overwrittenRows=")
                .append(droppedEvents).append('\n')
                .append("privacy=labels are represented by length/format-code count/hash; no player names or label text are written\n")
                .append("glFields=depth test/function/write mask, alpha test/function/cutoff, blend, stencil enable/function/ref/buffer-bits, fog enable/mode/start/end, texture enable/binding, current GL color\n")
                .append("note=fontRenderer candidates are hashed numeric/formatted draws through Lunar's shared text renderer; lunar.world_label events observe the native SERVER_HOLOGRAMS row used by TNT Countdown; probe modes are opt-in and recorded with their before/after state\n\n")
                .append("# Invocation counts (including repeated frames)\n");
        for (Map.Entry<String, Long> entry : COUNTS.entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        out.append("\n# State changes and sampled calls, chronological\n");
        for (String event : new ArrayList<String>(EVENTS)) out.append(event).append('\n');
        return out.toString();
    }

    private static final class Seen {
        final String signature;
        final long loggedAtNanos;
        Seen(String signature, long loggedAtNanos) { this.signature = signature; this.loggedAtNanos = loggedAtNanos; }
    }

    private static final class NamePass {
        final long id;
        final int entityId;
        int labelCalls;
        NamePass(long id, int entityId) { this.id = id; this.entityId = entityId; }
    }

}
