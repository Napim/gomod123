package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Stable protobuf hooks: no obfuscated manager names or edits to Lunar's cached files. */
public final class LunarEmoteWheelPersistence {
    private static final ThreadLocal<Boolean> BUILDING_LOCAL_SNAPSHOT = new ThreadLocal<Boolean>();
    private LunarEmoteWheelPersistence() {}

    private static boolean enabled() {
        return GomodFixes.isWeaveRuntime() && ConfigManager.isLunarTweaksEnabled()
                && LunarOnlineStatus.isFullAccessRouteActive();
    }

    private static UUID account() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getSession() == null) return null;
        // Session identity, never the locally disguised entity's UUID/name.
        String raw = mc.getSession().getPlayerID();
        if (raw == null) return null;
        String hex = raw.replace("-", "");
        if (!hex.matches("[0-9a-fA-F]{32}")) return null;
        return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-" + hex.substring(20));
    }

    private static LunarEmoteWheelStore store(boolean sprays) {
        return new LunarEmoteWheelStore(new File(GomodPaths.getGomod123Dir(), sprays ? "lunar-spray-wheels" : "lunar-emote-wheels"));
    }

    private static String protocol(boolean sprays) {
        return "com.lunarclient.websocket." + (sprays ? "spray" : "emote") + ".v1.";
    }

    private static String entriesName(boolean sprays) { return sprays ? "EquippedSprays" : "EquippedEmotes"; }

    private static String event(boolean sprays, String suffix) {
        return (sprays ? "spray_wheel." : "emote_wheel.") + suffix;
    }

    /** Called only for native user-update request builds, not parsed login/default wheels. */
    public static void rememberRequest(Object request) {
        try {
            if (!enabled() || request == null || Boolean.TRUE.equals(BUILDING_LOCAL_SNAPSHOT.get())) return;
            saveRequest(request, "native-user-request", false);
        } catch (Throwable failure) {
            LunarConnectionDiagnostics.record("emote_wheel.save_failed",
                    "exception=" + failure.getClass().getSimpleName());
        }
    }

    /** Runs on explicit local UI commits, before Lunar's Optional connection/send branch. */
    public static void rememberUserWheel(Object manager, List<?> entries) {
        rememberUserEntries(manager, entries, false);
    }

    public static void rememberUserSprayWheel(Object manager, Iterable<?> entries) {
        try {
            if (entries == null) return;
            List<Object> slots = new ArrayList<Object>();
            for (Object entry : entries) {
                if (slots.size() >= 256) return;
                slots.add(entry);
            }
            rememberUserEntries(manager, slots, true);
        } catch (Throwable failure) {
            LunarConnectionDiagnostics.record("spray_wheel.save_failed", "source=local-user-edit exception="
                    + failure.getClass().getSimpleName());
        }
    }

    private static void rememberUserEntries(Object manager, List<?> entries, boolean sprays) {
        if (!GomodFixes.isWeaveRuntime()
                || !ConfigManager.isLunarTweaksEnabled() || !LunarOnlineStatus.isFullAccessPreferenceEnabled()
                || manager == null || entries == null || entries.size() > 256) return;
        try {
            Class<?> request = Class.forName(protocol(sprays) + "Update" + entriesName(sprays) + "Request",
                    true, manager.getClass().getClassLoader());
            Object builder = request.getMethod("newBuilder").invoke(null);
            builder.getClass().getMethod("addAll" + entriesName(sprays), Iterable.class).invoke(builder, entries);
            BUILDING_LOCAL_SNAPSHOT.set(Boolean.TRUE);
            Object snapshot;
            try { snapshot = builder.getClass().getMethod("build").invoke(builder); }
            finally { BUILDING_LOCAL_SNAPSHOT.remove(); }
            saveRequest(snapshot, "local-user-edit", sprays);
        } catch (Throwable failure) {
            LunarConnectionDiagnostics.record(event(sprays, "save_failed"), "source=local-user-edit exception="
                    + failure.getClass().getSimpleName());
        }
    }

    private static void saveRequest(Object request, String source, boolean sprays) throws Exception {
        UUID owner = account();
        if (owner == null) return;
        List<?> entries = (List<?>) request.getClass().getMethod("get" + entriesName(sprays) + "List").invoke(request);
        if (entries.size() > 256) return;
        byte[] bytes = (byte[]) request.getClass().getMethod("toByteArray").invoke(request);
        store(sprays).save(owner, bytes);
        LunarConnectionDiagnostics.record(event(sprays, "saved"), "slots=" + entries.size()
                + " source=" + source + " scope=account-local payload=omitted");
    }

    /** Restore at native RPC delivery as well as parser paths; other service responses remain untouched. */
    public static Object restoreLogin(Object response) {
        boolean sprays = response != null && (protocol(true) + "LoginResponse").equals(response.getClass().getName());
        try {
            if (response == null
                    || !(protocol(sprays) + "LoginResponse").equals(response.getClass().getName())) return response;
            boolean tweaksEnabled = ConfigManager.isLunarTweaksEnabled();
            boolean fullAccessRequested = LunarOnlineStatus.isFullAccessPreferenceEnabled();
            boolean relayRoute = LunarOnlineStatus.isFullAccessRouteActive();
            if (!tweaksEnabled || !fullAccessRequested || !relayRoute) {
                recordRestoreDecision(!tweaksEnabled ? "lunar-tweaks-disabled"
                        : !fullAccessRequested ? "fullaccess-disabled" : "official-route", sprays);
                return response;
            }
            UUID owner = account();
            if (owner == null) {
                recordRestoreDecision("session-account-unavailable", sprays);
                return response;
            }
            byte[] cached = store(sprays).load(owner);
            if (cached == null) {
                recordRestoreDecision("no-account-snapshot", sprays);
                return response;
            }
            Object restored = rewriteLoginResponse(response, cached, sprays);
            int responseSlots = ((Integer) restored.getClass().getMethod("get" + entriesName(sprays) + "Count").invoke(restored)).intValue();
            LunarConnectionDiagnostics.record(event(sprays, "restored"), "scope=account-local snapshotSlots="
                    + responseSlots + " responseSlots=" + responseSlots
                    + " routeActive=" + LunarOnlineStatus.isFullAccessRouteActive() + " payload=omitted");
            return restored;
        } catch (Throwable failure) {
            LunarConnectionDiagnostics.record(event(sprays, "restore_failed"),
                    "exception=" + failure.getClass().getSimpleName());
            return response;
        }
    }

    private static void recordRestoreDecision(String reason, boolean sprays) {
        LunarConnectionDiagnostics.recordOnceAfterStart(event(sprays, "restore_skipped"), "login-response",
                "reason=" + reason + " fullAccessRequested=" + LunarOnlineStatus.isFullAccessPreferenceEnabled()
                        + " fullAccessRouteActive=" + LunarOnlineStatus.isFullAccessRouteActive()
                        + " lunarTweaks=" + ConfigManager.isLunarTweaksEnabled()
                        + " accountAvailable=" + (account() != null));
    }

    /** Rebuild the whole response so list/count/index getters remain consistent; preserve other fields. */
    static Object rewriteLoginResponse(Object response, byte[] cached) throws Exception {
        return rewriteLoginResponse(response, cached, false);
    }

    private static Object rewriteLoginResponse(Object response, byte[] cached, boolean sprays) throws Exception {
        Class<?> requestClass = Class.forName(protocol(sprays) + "Update" + entriesName(sprays) + "Request",
                true, response.getClass().getClassLoader());
        Object request = requestClass.getMethod("parseFrom", byte[].class).invoke(null, (Object) cached);
        List<?> entries = (List<?>) requestClass.getMethod("get" + entriesName(sprays) + "List").invoke(request);
        if (entries.size() > 256) throw new IllegalArgumentException("Wheel too large");
        Object builder = response.getClass().getMethod("toBuilder").invoke(response);
        Class<?> type = builder.getClass();
        type.getMethod("clear" + entriesName(sprays)).invoke(builder);
        type.getMethod("addAll" + entriesName(sprays), Iterable.class).invoke(builder, entries);
        if (!sprays) {
            LinkedHashSet<Integer> ids = new LinkedHashSet<Integer>();
            for (Object entry : entries) ids.add((Integer) entry.getClass().getMethod("getEmoteId").invoke(entry));
            type.getMethod("clearEquippedEmoteIds").invoke(builder);
            type.getMethod("addAllEquippedEmoteIds", Iterable.class).invoke(builder, new ArrayList<Integer>(ids));
        }
        return type.getMethod("build").invoke(builder);
    }
}
