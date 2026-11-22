package me.napim.gomod.features;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Local extensions to native Fits. Identity always comes from the verified native Fits snapshot. */
public final class LunarFitColors {
    private static final int LIMIT = 128;
    private static final Map<UUID, Map<UUID, Colors>> ACCOUNTS = new LinkedHashMap<>();
    private static final Set<UUID> UNREADABLE = new HashSet<>();
    private static final Map<Long, Selection> LOGO_WRITES = new LinkedHashMap<>();
    private static final Set<Long> RESTORE_WRITES = new HashSet<>();
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gomod-fit-colors"); t.setDaemon(true); return t;
    });
    private static Selection active;
    private static Set<UUID> known = Collections.emptySet();
    private static boolean restorePending, wasConnected;
    private static long selectionGeneration;
    private static final ThreadLocal<Boolean> APPLYING = new ThreadLocal<>();

    static {
        IO.scheduleWithFixedDelay(LunarFitColors::maintain, 250, 250, TimeUnit.MILLISECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            IO.shutdown();
            try { IO.awaitTermination(2, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, "gomod-fit-colors-save"));
    }
    private LunarFitColors() { }

    private static final class Selection {
        final UUID owner, fit;
        Selection(UUID owner, UUID fit) { this.owner = owner; this.fit = fit; }
    }
    private static final class Colors {
        final Integer plus, logo;
        Colors(Integer plus, Integer logo) { this.plus = plus; this.logo = logo; }
    }
    private static UUID uuid(String raw) {
        if (raw == null) return null;
        String h = raw.replace("-", "");
        if (!h.matches("(?i)[a-f0-9]{32}")) return null;
        return UUID.fromString(h.substring(0,8)+"-"+h.substring(8,12)+"-"+h.substring(12,16)+"-"+h.substring(16,20)+"-"+h.substring(20));
    }
    private static UUID account() {
        try { Minecraft mc = Minecraft.getMinecraft(); return mc == null || mc.getSession() == null ? null : uuid(mc.getSession().getPlayerID()); }
        catch (Throwable unavailable) { return null; }
    }
    private static Path file(UUID owner) {
        return new File(ConfigManager.getGomodDataDirectory(), "lunar-fit-colors/"+owner+".json").toPath();
    }
    private static Map<UUID, Colors> colors(UUID owner) {
        Map<UUID, Colors> result = ACCOUNTS.get(owner);
        if (result != null) return result;
        result = new LinkedHashMap<>();
        try {
            Path path = file(owner);
            if (Files.exists(path)) {
                if (Files.size(path) > 32768) throw new IllegalArgumentException("size");
                JsonObject root = new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
                if (root.get("version").getAsInt()!=1 || !owner.toString().equals(root.get("account").getAsString())) throw new IllegalArgumentException("account/version");
                JsonObject fits = root.getAsJsonObject("fits");
                if (fits.entrySet().size() > LIMIT) throw new IllegalArgumentException("count");
                for (Map.Entry<String,JsonElement> entry : fits.entrySet()) {
                    UUID id = uuid(entry.getKey()); JsonObject value = entry.getValue().getAsJsonObject();
                    Integer plus = number(value, "plus", -1, 0xFFFFFF), logo = number(value, "logo", 0, GomodLogoColorSync.DEFAULT_SENTINEL);
                    if (id == null || plus == null && logo == null) throw new IllegalArgumentException("entry");
                    result.put(id, new Colors(plus, logo));
                }
            }
        } catch (Exception invalid) {
            result.clear(); UNREADABLE.add(owner);
            System.err.println("[gomod] Fit colors could not be read; preserving the existing file: "+invalid.getClass().getSimpleName());
        }
        while (ACCOUNTS.size() >= 8) {
            UUID oldest = ACCOUNTS.keySet().iterator().next(); ACCOUNTS.remove(oldest); UNREADABLE.remove(oldest);
        }
        ACCOUNTS.put(owner, result); return result;
    }
    private static Integer number(JsonObject object, String key, int min, int max) {
        if (!object.has(key)) return null;
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber() || !element.getAsString().matches("-?[0-9]{1,8}")) throw new IllegalArgumentException("color");
        int value = element.getAsInt();
        if (value < min || value > max) throw new IllegalArgumentException("range");
        return value;
    }
    private static JsonObject json(Colors value) {
        JsonObject object = new JsonObject();
        if (value.plus != null) object.addProperty("plus", value.plus);
        if (value.logo != null) object.addProperty("logo", value.logo);
        return object;
    }
    private static void put(Selection selection, Integer plus, Integer logo) {
        Map<UUID, Colors> values = colors(selection.owner);
        if (UNREADABLE.contains(selection.owner) || !values.containsKey(selection.fit) && values.size() >= LIMIT) return;
        Colors old = values.get(selection.fit);
        Colors next = new Colors(plus != null ? plus : old == null ? null : old.plus,
                logo != null ? logo : old == null ? null : old.logo);
        if (next.plus == null && next.logo == null || old != null && Objects.equals(old.plus,next.plus) && Objects.equals(old.logo,next.logo)) return;
        values.put(selection.fit, next);
        persist(selection.owner,values);
    }
    private static void persist(UUID owner, Map<UUID,Colors> values) {
        JsonObject root = new JsonObject(), fits = new JsonObject();
        root.addProperty("version",1); root.addProperty("account",owner.toString());
        for (Map.Entry<UUID,Colors> entry : values.entrySet()) fits.add(entry.getKey().toString(),json(entry.getValue()));
        root.add("fits",fits);
        byte[] bytes = (root.toString()+"\n").getBytes(StandardCharsets.UTF_8);
        IO.execute(() -> write(owner,bytes));
    }
    private static void write(UUID owner, byte[] bytes) {
        Path temporary = null;
        try {
            Path target = file(owner); Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(),owner.toString(),".tmp"); Files.write(temporary,bytes);
            try { Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING); }
        } catch (Exception error) { System.err.println("[gomod] Could not save Fit colors: "+error.getClass().getSimpleName()); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (Exception ignored) { } }
    }

    /** Only LunarOutfitPersistence's source-confirmed, validated snapshots may call this. */
    public static synchronized void nativeSnapshot(UUID owner, Object snapshot, boolean login) throws Exception {
        if (!owner.equals(account())) return;
        Set<UUID> ids = new HashSet<>();
        for (Object outfit : (List<?>)call(snapshot,"getOutfitsList")) ids.add(protoId(call(outfit,"getId")));
        UUID selected = protoId(call(call(snapshot,"getOutfitTree"),"getDefaultOutfitId"));
        if (!ids.contains(selected) || ids.size() > LIMIT) return;
        boolean sameAccount = active != null && active.owner.equals(owner);
        Set<UUID> previous = known;
        boolean changed = !sameAccount || !active.fit.equals(selected);
        known = ids;
        if (changed || login) {
            active = new Selection(owner,selected); ++selectionGeneration;
            restorePending = colors(owner).containsKey(selected);
        }
        if (sameAccount && !login) {
            Map<UUID,Colors> values = colors(owner);
            if (!UNREADABLE.contains(owner) && values.keySet().retainAll(ids)) persist(owner,values);
            for (UUID id : ids) if (!previous.contains(id) && !values.containsKey(id)) capture(new Selection(owner,id));
        }
    }
    private static Object call(Object value,String method) throws Exception { return value.getClass().getMethod(method).invoke(value); }
    private static UUID protoId(Object value) throws Exception {
        return new UUID(((Number)call(value,"getHigh64")).longValue(),((Number)call(value,"getLow64")).longValue());
    }
    private static boolean usable() { return active != null && active.owner.equals(account()) && GomodLogoColorSync.isFullAccessColorAvailable(); }
    private static void capture(Selection selection) {
        if (!usable()) return;
        put(selection,GomodLogoColorSync.getLunarPlusColorForFit(),GomodLogoColorSync.getSettledLogoColorForFit());
    }
    public static synchronized void rememberPlus(int rgb) {
        if (usable()) {
            put(active,rgb,null);
        }
    }
    public static synchronized void logoWriteStarted(long generation) {
        if (!usable()) return;
        while (LOGO_WRITES.size() >= 32) {
            Long oldest = LOGO_WRITES.keySet().iterator().next(); LOGO_WRITES.remove(oldest); RESTORE_WRITES.remove(oldest);
        }
        LOGO_WRITES.put(generation,active);
        if (Boolean.TRUE.equals(APPLYING.get())) RESTORE_WRITES.add(generation);
    }
    public static synchronized void logoConfirmed(long generation,int rgb) {
        Selection target = LOGO_WRITES.remove(generation);
        RESTORE_WRITES.remove(generation);
        if (target != null && active != null && target.owner.equals(account()) && target.owner.equals(active.owner)
                && known.contains(target.fit)) put(target,null,rgb);
    }
    public static synchronized void logoRejected(long generation) {
        Selection target = LOGO_WRITES.remove(generation);
        boolean restoring = RESTORE_WRITES.remove(generation);
        if (!restoring && target != null && active != null && target.owner.equals(active.owner)
                && target.fit.equals(active.fit)) restorePending = colors(active.owner).containsKey(active.fit);
    }
    private static void maintain() {
        try {
            final Selection target; final Colors value; final long generation;
            synchronized (LunarFitColors.class) {
                boolean connected = usable();
                if (!connected) { wasConnected = false; return; }
                if (!wasConnected) restorePending = colors(active.owner).containsKey(active.fit);
                wasConnected = true;
                Colors current = colors(active.owner).get(active.fit);
                if (!restorePending) {
                    // A Plus selection or newly created Fit may precede the startup logo response.
                    // Older Fits gain their first record when equipped and the actual logo is ready.
                    if (current == null ? GomodLogoColorSync.getSettledLogoColorForFit() != null
                            : current.logo == null || current.plus == null) capture(active);
                    return;
                }
                restorePending = false; target = active; value = current; generation = selectionGeneration;
            }
            if (value == null) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) mc.addScheduledTask(() -> {
                synchronized (LunarFitColors.class) {
                    if (!usable() || generation != selectionGeneration || active != target) return;
                    Colors latest = colors(target.owner).get(target.fit);
                    if (latest == null) return;
                    Selection writing = LOGO_WRITES.get(GomodLogoColorSync.getPendingLogoWriteForFit());
                    boolean userWritingHere = writing != null && writing.owner.equals(target.owner) && writing.fit.equals(target.fit);
                    APPLYING.set(Boolean.TRUE);
                    try {
                        if (latest.plus != null) GomodLogoColorSync.applyLunarPlusColorFromFit(latest.plus);
                        if (latest.logo != null && !userWritingHere) {
                            if (latest.logo == GomodLogoColorSync.DEFAULT_SENTINEL) GomodLogoColorSync.resetLogoColor();
                            else GomodLogoColorSync.setLogoColor(latest.logo);
                        }
                    } finally { APPLYING.remove(); }
                }
            });
        } catch (Throwable error) { /* Native state may disappear during account changes or shutdown. */ }
    }
    public static synchronized String uiSnapshot() {
        JsonObject result = new JsonObject(), fits = new JsonObject();
        UUID owner = account(); result.addProperty("account",owner == null ? "" : owner.toString());
        if (active != null && active.owner.equals(owner) && ConfigManager.isLunarTweaksEnabled()) for (UUID id : known) {
            Colors value = colors(owner).get(id);
            if (value != null) fits.add(id.toString(),json(value));
        }
        result.addProperty("available",usable());
        result.addProperty("canSave",usable() && !restorePending && GomodLogoColorSync.getSettledLogoColorForFit()!=null);
        Integer currentLogo = usable() ? GomodLogoColorSync.getSettledLogoColorForFit() : null;
        if (currentLogo != null) result.addProperty("currentLogo",currentLogo);
        result.add("fits",fits);
        try { result.add("sharing",LunarFitSharing.uiSnapshot()); } catch (Throwable unavailable) { }
        return result.toString();
    }
    public static synchronized JsonObject exportShared(UUID owner,String id) {
        UUID fit=uuid(id);
        if (owner == null || !owner.equals(account()) || fit == null) return new JsonObject();
        Colors value=colors(owner).get(fit);return value == null ? new JsonObject() : json(value);
    }
    public static synchronized void importShared(UUID owner,UUID fit,JsonObject value) {
        if (owner == null || !owner.equals(account()) || fit == null || value == null) return;
        Integer plus=number(value,"plus",-1,0xFFFFFF),logo=number(value,"logo",0,GomodLogoColorSync.DEFAULT_SENTINEL);
        if (plus != null || logo != null) put(new Selection(owner,fit),plus,logo);
    }
    /** Explicit migration for pre-existing Fits; no invented historical colors. */
    public static synchronized boolean saveFromUi(String encoded) {
        try {
            if (encoded == null || encoded.length()>256) return false;
            JsonObject request = new JsonParser().parse(encoded).getAsJsonObject();
            UUID owner = uuid(request.get("account").getAsString());
            return owner != null && owner.equals(account()) && saveCurrent(request.get("fit").getAsString());
        } catch (Exception invalid) { return false; }
    }
    public static synchronized boolean saveCurrent(String id) {
        UUID fit = uuid(id);
        if (!usable() || restorePending || fit == null || !known.contains(fit) || GomodLogoColorSync.getSettledLogoColorForFit()==null) return false;
        Selection target = new Selection(active.owner,fit); capture(target);
        return !UNREADABLE.contains(target.owner) && colors(target.owner).containsKey(fit);
    }
}
