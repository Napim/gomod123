package me.napim.gomod.features;

import com.google.gson.*;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;

/** Private, account-local timers. Only source/target names and fixed deadlines are saved. */
public final class TimedDisguiserStore {
    private static String account = "";
    private static final Map<String, JsonObject> entries = new LinkedHashMap<String, JsonObject>();
    private TimedDisguiserStore() { }
    private static File file() { return new File(new File(GomodPaths.getGomod123Dir(), "timed-disguiser"), account + ".json"); }

    public static synchronized void tick() {
        if (!GomodInternalFeatures.isEnabled()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.getNetHandler() == null) return;
        String current = GomodSocialIdentity.profileUuidUndashed(mc);
        if (!current.matches("[a-fA-F0-9]{32}") || current.equals(account)) return;
        RemoteDisguiseManager.clearForAccountSwitch();
        account = current; entries.clear();
        try {
            Path path = file().toPath();
            if (!Files.exists(path) || Files.size(path) > 32768L) return;
            JsonArray saved = new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement element : saved) {
                if (entries.size() >= 32 || !element.isJsonObject()) break;
                JsonObject entry = element.getAsJsonObject();
                String source = entry.get("source").getAsString(), target = entry.get("target").getAsString();
                long expiresAt = entry.get("expiresAt").getAsLong();
                if (!source.matches("[A-Za-z0-9_]{1,16}") || !target.matches("[A-Za-z0-9_]{1,16}")
                        || expiresAt <= System.currentTimeMillis() || expiresAt > System.currentTimeMillis() + DisguiseDuration.MAX_MS) continue;
                entries.put(source.toLowerCase(java.util.Locale.ROOT), entry);
                DisguiserLunarIcon lunarIcon = DisguiserLunarIcon.fromWire(entry.has("lunarIcon")
                        ? entry.get("lunarIcon").getAsString() : "keep");
                boolean fullLunar = entry.has("fullLunar") && entry.get("fullLunar").getAsBoolean();
                Thread worker = new Thread(() -> {
                    try { RemoteDisguiseManager.disguisePlayer(source, target, expiresAt, lunarIcon, fullLunar); }
                    catch (Exception failure) { LunarConnectionDiagnostics.record("disguiser.timer_restore", "result=failed errorType=" + failure.getClass().getSimpleName()); }
                }, "gomod-disguiser-restore");
                worker.setDaemon(true); worker.start();
            }
        } catch (Exception failure) { LunarConnectionDiagnostics.record("disguiser.timer_store", "result=read_failed"); }
    }

    public static synchronized void remember(String source, String target, long expiresAt) {
        remember(source, target, expiresAt, DisguiserLunarIcon.KEEP);
    }
    public static synchronized void remember(String source, String target, long expiresAt, DisguiserLunarIcon lunarIcon) {
        remember(source, target, expiresAt, lunarIcon, false);
    }
    public static synchronized void remember(String source, String target, long expiresAt,
                                               DisguiserLunarIcon lunarIcon, boolean fullLunar) {
        if (account.isEmpty() || expiresAt <= System.currentTimeMillis()) return;
        JsonObject entry = new JsonObject(); entry.addProperty("source", source); entry.addProperty("target", target); entry.addProperty("expiresAt", expiresAt);
        entry.addProperty("lunarIcon", lunarIcon.wire);
        entry.addProperty("fullLunar", fullLunar);
        entries.put(source.toLowerCase(java.util.Locale.ROOT), entry); save();
    }
    public static synchronized void remove(String source) { entries.remove(source.toLowerCase(java.util.Locale.ROOT)); save(); }
    public static synchronized void clear() { entries.clear(); save(); }
    private static void save() {
        if (account.isEmpty()) return;
        try {
            entries.values().removeIf(entry -> entry.get("expiresAt").getAsLong() <= System.currentTimeMillis());
            JsonArray saved = new JsonArray(); for (JsonObject entry : entries.values()) saved.add(entry);
            Path path = file().toPath(), temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.createDirectories(path.getParent()); Files.write(temporary, saved.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { LunarConnectionDiagnostics.record("disguiser.timer_store", "result=write_failed"); }
    }
}
