package me.napim.gomod.features;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.util.ResourceLocation;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Opt-in first-person render trace. Reads model metadata only; never calls GL, JNI or a service. */
public final class LunarArmDebugCapture {
    private static final Object LOCK = new Object();
    private static final int MAX_EVENTS = 2_000;
    private static final long MAX_DURATION_MS = 5 * 60_000L;
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "gomod-lunar-arm-debug-timeout");
        thread.setDaemon(true);
        return thread;
    });
    private static final ThreadLocal<Frame> FRAME = new ThreadLocal<Frame>();
    private static final Set<String> SEEN = new HashSet<String>();
    private static final IdentityHashMap<Object, String> ROLES = new IdentityHashMap<Object, String>();
    private static final IdentityHashMap<Object, ModelSample> MODEL_SAMPLES = new IdentityHashMap<Object, ModelSample>();
    private static volatile boolean active;
    private static BufferedWriter writer;
    private static File output;
    private static ScheduledFuture<?> timeout;
    private static long startedMs;
    private static int phase, events;
    private static long frames, draws, binds, guardCalls;
    private static String lastArmState;
    private static long nextModelSnapshotNanos;

    private LunarArmDebugCapture() { }

    public static File start() throws Exception {
        if (!GomodFixes.isWeaveRuntime() || !GomodInternalFeatures.isEnabled())
            throw new IllegalStateException("Internal Weave capture only");
        synchronized (LOCK) {
            if (active) return output;
            File directory = new File(GomodPaths.getGomod123Dir(), "debugall");
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory())
                throw new IOException("Could not create capture directory");
            output = File.createTempFile("lunar_arm_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
                    .format(new Date()) + "_", ".log", directory);
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(output), StandardCharsets.UTF_8));
            startedMs = System.currentTimeMillis();
            phase = events = 0;
            frames = draws = binds = guardCalls = 0L;
            SEEN.clear(); ROLES.clear(); MODEL_SAMPLES.clear(); lastArmState = null; nextModelSnapshotNanos = 0L;
            active = true;
            appendLocked("capture.start version=1 maxDurationSeconds=300 maxEvents=" + MAX_EVENTS
                    + " namesAndAccountIds=omitted noPayloads=true noNativeQueries=true");
            appendLocked("phase.mark label=start " + state());
            try { writer.flush(); } catch (IOException error) { stopLocked("write-error"); throw error; }
            final File session = output;
            timeout = TIMER.schedule(() -> {
                synchronized (LOCK) { if (active && session.equals(output)) stopLocked("duration-limit"); }
            }, MAX_DURATION_MS, TimeUnit.MILLISECONDS);
            return output;
        }
    }

    public static File stop() {
        synchronized (LOCK) { if (active) stopLocked("command"); return output; }
    }

    public static boolean isActive() { return active; }
    public static File getOutput() { synchronized (LOCK) { return output; } }

    public static boolean mark(String label) {
        if (!Arrays.asList("equipped", "removed", "visible", "hidden", "shown").contains(label)) return false;
        synchronized (LOCK) {
            if (!active) return false;
            appendLocked(summary("phase.summary"));
            if (!active) return false;
            phase++; SEEN.clear(); ROLES.clear(); MODEL_SAMPLES.clear(); lastArmState = null; nextModelSnapshotNanos = 0L;
            appendLocked("phase.mark label=" + label + " " + state());
            try { if (writer != null) writer.flush(); } catch (IOException error) { stopLocked("write-error"); }
            return active;
        }
    }

    /** Whole hand/item pass, including Lunar callbacks that run outside vanilla RenderPlayer's arm scope. */
    public static void beginFirstPersonFrame() {
        if (!active) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.gameSettings == null || mc.gameSettings.thirdPersonView != 0) {
                FRAME.remove(); return;
            }
            Frame frame = new Frame();
            frame.startedNanos = System.nanoTime();
            FRAME.set(frame);
            synchronized (LOCK) { if (active) frames++; }
        } catch (Throwable ignored) { FRAME.remove(); }
    }

    public static void endFirstPersonFrame() { FRAME.remove(); }

    private static Frame frame() {
        if (!active) return null;
        Frame value = FRAME.get();
        // Bound a leaked scope if a renderer threw before RETURN; next HEAD also starts fresh.
        if (value != null && System.nanoTime() - value.startedNanos > TimeUnit.SECONDS.toNanos(1L)) {
            FRAME.remove(); return null;
        }
        return value;
    }

    public static void recordArm(Object player, Object renderer) {
        if (frame() == null) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || player != mc.thePlayer || !(renderer instanceof RenderPlayer)) return;
            Object model = ((RenderPlayer) renderer).getMainModel();
            synchronized (LOCK) {
                if (!active) return;
                String armState = state() + " renderer=" + type(renderer) + " model=" + type(model);
                if (!armState.equals(lastArmState)) {
                    lastArmState = armState;
                    appendLocked("arm.scope " + armState);
                }
                long now = System.nanoTime();
                if (now < nextModelSnapshotNanos) return;
                nextModelSnapshotNanos = now + TimeUnit.MILLISECONDS.toNanos(250L);
                for (Class<?> cls = model.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
                    for (Field field : cls.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || !ModelRenderer.class.isAssignableFrom(field.getType())) continue;
                        field.setAccessible(true);
                        inspectGraphLocked(field.get(model), field.getName(), new IdentityHashMap<Object, Boolean>(), 0);
                    }
                }
            }
        } catch (Throwable error) { recordError("arm-model", error); }
    }

    private static void inspectGraphLocked(Object value, String role, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (!active || !(value instanceof ModelRenderer) || depth > 3 || visited.size() >= 64 || visited.put(value, Boolean.TRUE) != null) return;
        if (ROLES.size() < 512) ROLES.put(value, role);
        ModelRenderer model = (ModelRenderer) value;
        String shape = describe(model);
        uniqueLocked("model-state:" + id(model) + ":" + shape,
                "model.state role=" + role + " model=" + id(model) + " " + shape);
        inspectListLocked(model.childModels, role + "/child", visited, depth);
        Object attachments = fieldValue(model, "lunar$attachedRenderers");
        if (attachments instanceof List<?>) inspectListLocked((List<?>) attachments, role + "/attached", visited, depth);
    }

    private static void inspectListLocked(List<?> list, String role, IdentityHashMap<Object, Boolean> visited, int depth) {
        if (list == null) return;
        for (int i = 0; i < Math.min(16, list.size()); i++) inspectGraphLocked(list.get(i), role + i, visited, depth + 1);
    }

    public static void recordModelDraw(Object value, String method) {
        Frame frame = frame();
        if (frame == null || !(value instanceof ModelRenderer)) return;
        try {
            synchronized (LOCK) {
                if (!active) return;
                draws++;
                ModelRenderer model = (ModelRenderer) value;
                String role = ROLES.get(value);
                String detail = "method=" + method + " model=" + id(value) + " role=" + (role == null ? "unmapped" : role)
                        + " resource=" + frame.texture + " textureId=" + frame.textureId + " " + describe(model);
                traceLocked("draw:" + detail, "model.draw " + detail);
            }
        } catch (Throwable error) { recordError("model-draw", error); }
    }

    public static void recordResourceTexture(Object value) {
        Frame frame = frame();
        if (frame == null || !(value instanceof ResourceLocation)) return;
        try {
            frame.texture = safeResource((ResourceLocation) value);
            synchronized (LOCK) {
                if (!active) return;
                binds++;
                traceLocked("texture:" + frame.texture, "texture.resource value=" + frame.texture);
            }
        } catch (Throwable error) { recordError("texture", error); }
    }

    public static void recordTextureId(int textureId) {
        Frame frame = frame();
        if (frame == null) return;
        frame.textureId = textureId;
        synchronized (LOCK) {
            if (active) traceLocked("texture-id:" + textureId + ":" + frame.texture,
                    "texture.bind id=" + textureId + " lastResource=" + frame.texture);
        }
    }

    public static void recordCosmeticCall(String stage, Object bridge) {
        if (frame() == null) return;
        try {
            boolean local = bridge != null && PlayerStandaloneCape.isLocalPlayerBridge(bridge);
            synchronized (LOCK) {
                if (!active) return;
                guardCalls++;
                String detail = "stage=" + stage + " localBridge=" + local + " " + state();
                traceLocked("cosmetic:" + detail, "cosmetic.pass " + detail);
            }
        } catch (Throwable error) { recordError("cosmetic-pass", error); }
    }

    /** Record only cosmetic product IDs from the local player's profile; no account/outfit UUIDs are written. */
    public static void recordOutfitPush(Object push) {
        if (!active || push == null) return;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null) return;
            Object uuid = invoke(push, "getPlayerUuid");
            UUID playerId = new UUID(((Number) invoke(uuid, "getHigh64")).longValue(), ((Number) invoke(uuid, "getLow64")).longValue());
            if (!playerId.equals(mc.thePlayer.getUniqueID())) return;
            Object cosmetics = invoke(push, "getDefaultCosmeticsList");
            if (!(cosmetics instanceof Iterable<?>)) return;
            List<Integer> ids = new ArrayList<Integer>();
            for (Object cosmetic : (Iterable<?>) cosmetics) {
                if (ids.size() >= 64) break;
                ids.add(((Number) invoke(cosmetic, "getCosmeticId")).intValue());
            }
            synchronized (LOCK) { if (active) uniqueLocked("outfit:" + ids, "outfit.local cosmeticIds=" + ids); }
        } catch (Throwable error) { recordError("outfit-shape", error); }
    }

    private static Object invoke(Object value, String method) throws Exception { return value.getClass().getMethod(method).invoke(value); }

    private static String state() {
        Minecraft mc = Minecraft.getMinecraft();
        return "disguise=" + LocalDisguise.fastEnabledFlag + " hiddenSetting=" + ConfigManager.isDisguiseHideLunarCosmeticsOnWeave()
                + " camera=" + (mc == null || mc.gameSettings == null ? -1 : mc.gameSettings.thirdPersonView);
    }

    private static String describe(ModelRenderer model) {
        long now = System.nanoTime();
        ModelSample cached = MODEL_SAMPLES.get(model);
        if (cached != null && now < cached.expiresNanos) return cached.description;
        Object attached = fieldValue(model, "lunar$attachedRenderers");
        String description = "type=" + type(model) + " shown=" + model.showModel + " hidden=" + model.isHidden
                + " cubes=" + (model.cubeList == null ? 0 : model.cubeList.size())
                + " cubeShape=" + cubeShape(model.cubeList)
                + " children=" + (model.childModels == null ? 0 : model.childModels.size())
                + " attachments=" + (attached instanceof List<?> ? ((List<?>) attached).size() : 0);
        if (MODEL_SAMPLES.size() < 512) MODEL_SAMPLES.put(model, new ModelSample(description, now + TimeUnit.MILLISECONDS.toNanos(250L)));
        return description;
    }

    private static int cubeShape(List<?> cubes) {
        int hash = 1;
        if (cubes == null) return hash;
        for (int i = 0; i < Math.min(128, cubes.size()); i++) {
            Object cube = cubes.get(i);
            for (String axis : new String[]{"posX1", "posY1", "posZ1", "posX2", "posY2", "posZ2"}) {
                Object value = fieldValue(cube, axis);
                if (value instanceof Number) hash = 31 * hash + Float.floatToIntBits(((Number) value).floatValue());
            }
        }
        return hash;
    }

    private static Object fieldValue(Object value, String name) {
        if (value == null) return null;
        for (Class<?> cls = value.getClass(); cls != null; cls = cls.getSuperclass()) {
            try { Field field = cls.getDeclaredField(name); field.setAccessible(true); return field.get(value); }
            catch (NoSuchFieldException next) { } catch (Throwable unavailable) { return null; }
        }
        return null;
    }

    private static String safeResource(ResourceLocation location) {
        String domain = location.getResourceDomain(), path = location.getResourcePath();
        if (domain == null || !domain.matches("[A-Za-z0-9_.-]{1,64}")) return "<unknown-resource>";
        if ("skins".equalsIgnoreCase(domain) || path == null) return domain + ":<skin>";
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("://") || lower.contains("token") || lower.contains("auth") || lower.contains("session")) return domain + ":<redacted>";
        int query = path.indexOf('?'), fragment = path.indexOf('#');
        if (query >= 0) path = path.substring(0, query);
        if (fragment >= 0 && fragment < path.length()) path = path.substring(0, fragment);
        path = path.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<uuid>")
                .replaceAll("[0-9a-fA-F]{32,}", "<hash>");
        if (path.length() > 160) path = path.substring(0, 160);
        return domain + ":" + path.replaceAll("[^A-Za-z0-9_./<>-]", "_");
    }

    private static String type(Object value) { return value == null ? "none" : value.getClass().getName(); }
    private static String id(Object value) { return Integer.toHexString(System.identityHashCode(value)); }

    private static String stack() {
        StringBuilder value = new StringBuilder(); int count = 0;
        for (StackTraceElement entry : new Throwable().getStackTrace()) {
            String cls = entry.getClassName();
            if (cls.equals(LunarArmDebugCapture.class.getName()) || !(cls.startsWith("com.moonsworth.")
                    || cls.startsWith("net.minecraft.client.renderer.") || cls.startsWith("me.napim.gomod."))) continue;
            if (value.length() > 0) value.append('>');
            value.append(cls).append('.').append(entry.getMethodName()).append(':').append(entry.getLineNumber());
            if (++count >= 8) break;
        }
        return value.length() == 0 ? "unavailable" : value.toString();
    }

    private static void uniqueLocked(String key, String event) {
        if (SEEN.size() < MAX_EVENTS && SEEN.add(key)) appendLocked(event);
    }

    private static void traceLocked(String key, String event) {
        if (SEEN.size() < MAX_EVENTS && SEEN.add(key)) appendLocked(event + " stack=" + stack());
    }

    private static void recordError(String stage, Throwable error) {
        synchronized (LOCK) { if (active) uniqueLocked("error:" + stage, "capture.error stage=" + stage + " type=" + error.getClass().getSimpleName()); }
    }

    private static void appendLocked(String line) {
        if (writer == null) return;
        try {
            writer.write("elapsedMs=" + Math.max(0L, System.currentTimeMillis() - startedMs) + " phase=" + phase + " " + line);
            writer.newLine();
            if (++events >= MAX_EVENTS && active) stopLocked("event-limit");
        } catch (IOException error) { stopLocked("write-error"); }
    }

    private static String summary(String event) { return event + " frames=" + frames + " modelDraws=" + draws + " textureBinds=" + binds + " cosmeticCalls=" + guardCalls; }

    private static void stopLocked(String reason) {
        active = false;
        if (timeout != null) { timeout.cancel(false); timeout = null; }
        BufferedWriter closing = writer;
        writer = null;
        if (closing != null) {
            try {
                closing.write(summary("capture.stop reason=" + reason)); closing.newLine();
            } catch (IOException ignored) { }
            finally { try { closing.close(); } catch (IOException ignored) { } }
        }
        SEEN.clear(); ROLES.clear(); MODEL_SAMPLES.clear(); FRAME.remove();
    }

    private static final class Frame {
        long startedNanos;
        String texture = "unobserved";
        int textureId = -1;
    }

    private static final class ModelSample {
        final String description;
        final long expiresNanos;
        ModelSample(String description, long expiresNanos) { this.description = description; this.expiresNanos = expiresNanos; }
    }
}
