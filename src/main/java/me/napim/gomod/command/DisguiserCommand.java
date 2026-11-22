package me.napim.gomod.command;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import me.napim.gomod.Main;
import me.napim.gomod.features.DisguiseDuration;
import me.napim.gomod.features.DisguiserLunarIcon;
import me.napim.gomod.features.DisguiserRelay;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.LunarFullDisguise;
import me.napim.gomod.features.RemoteDisguiseManager;
import me.napim.gomod.mixins.NetworkPlayerInfoAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Local and same-server peer disguises for an arbitrary source player. */
public class DisguiserCommand extends Command {
    private static final Pattern MC_USERNAME = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Object REMOTE_MUTATION_LOCK = new Object();
    private static final AtomicLong REMOTE_GLOBAL_GENERATION = new AtomicLong();
    private static final ConcurrentHashMap<String, AtomicLong> REMOTE_SOURCE_GENERATIONS =
            new ConcurrentHashMap<String, AtomicLong>();
    /** Orders shared set/clear HTTP mutations so an old slow set cannot land after /off. */
    private static final ExecutorService SHARED_RELAY_MUTATIONS = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "gomod-disguiser-relay-mutations");
        thread.setDaemon(true);
        return thread;
    });
    private final boolean shared;
    private final boolean fullLunar;

    public DisguiserCommand() {
        this(false, false);
    }

    protected DisguiserCommand(boolean shared) {
        this(shared, false);
    }

    protected DisguiserCommand(boolean shared, boolean fullLunar) {
        super(commandName(shared, fullLunar), new String[0]);
        this.shared = shared;
        this.fullLunar = fullLunar;
    }

    private static String commandName(boolean shared, boolean fullLunar) {
        if (fullLunar) return shared ? "disguiserfullgomod" : "disguiserfull";
        return shared ? "disguisergomod" : "disguiser";
    }

    private String label() {
        return "/" + commandName(shared, fullLunar);
    }

    private void usage() {
        if (fullLunar) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: "
                    + EnumChatFormatting.WHITE + label() + " <source|all> <target> [time] [lunar|nolunar]"
                    + EnumChatFormatting.GRAY + " | " + EnumChatFormatting.WHITE + label()
                    + " all reset | reset <source> | " + label() + " off");
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: "
                    + EnumChatFormatting.WHITE + label() + " <source> <target> <time> [lunar|nolunar]"
                    + EnumChatFormatting.GRAY + " | " + EnumChatFormatting.WHITE + label()
                    + " all <target> [time] | reset <source> | off");
        }
    }

    @Override
    public void handle(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        String first = clean(args[0]);
        if ("off".equalsIgnoreCase(first) || "clear".equalsIgnoreCase(first)) {
            if (args.length != 1) { usage(); return; }
            if (shared) { clearShared(null); return; }
            invalidateAllRemoteMutations();
            int cleared;
            synchronized (REMOTE_MUTATION_LOCK) {
                cleared = RemoteDisguiseManager.clearAll();
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Disguiser cleared "
                    + EnumChatFormatting.WHITE + cleared + EnumChatFormatting.YELLOW
                    + (cleared == 1 ? " entry." : " entries."));
            return;
        }
        if ("reset".equalsIgnoreCase(first) && args.length == 2) {
            String source = clean(args[1]);
            if (shared) { clearShared(source); return; }
            invalidateRemoteMutation(source);
            boolean removed;
            synchronized (REMOTE_MUTATION_LOCK) {
                removed = RemoteDisguiseManager.clearPlayer(source);
            }
            Main.sendMessage(Main.CHAT_PREFIX + (removed ? EnumChatFormatting.YELLOW
                    + "Disguiser cleared for " + EnumChatFormatting.WHITE + source + EnumChatFormatting.YELLOW + "."
                    : EnumChatFormatting.RED + "No disguiser entry found for " + EnumChatFormatting.WHITE
                    + source + EnumChatFormatting.RED + "."));
            return;
        }

        if (fullLunar && "all".equalsIgnoreCase(first) && args.length == 2
                && "reset".equalsIgnoreCase(clean(args[1]))) {
            if (shared) {
                clearShared(null);
            } else {
                invalidateAllRemoteMutations();
                int cleared;
                synchronized (REMOTE_MUTATION_LOCK) {
                    cleared = RemoteDisguiseManager.clearAllFullLunarEntries();
                }
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Full disguiser cleared for " + EnumChatFormatting.WHITE + cleared
                        + EnumChatFormatting.YELLOW + (cleared == 1 ? " entry." : " entries."));
            }
            return;
        }

        String source = first;
        String target = args.length > 1 ? clean(args[1]) : "";
        boolean all = "all".equalsIgnoreCase(source);
        final long duration;
        final DisguiserLunarIcon lunarIcon;
        if (fullLunar) {
            if (args.length < 2 || args.length > 4) { usage(); return; }
            try {
                duration = args.length >= 3 ? DisguiseDuration.parse(args[2]) : 0L;
                lunarIcon = args.length == 4
                        ? DisguiserLunarIcon.commandOption(args[3]) : DisguiserLunarIcon.KEEP;
            } catch (Exception invalid) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + invalid.getMessage());
                return;
            }
        } else {
            boolean persistentAll = all && args.length == 2;
            if ((!persistentAll && args.length != 3 && args.length != 4)
                    || (persistentAll && args.length != 2)) { usage(); return; }
            try {
                duration = persistentAll ? 0L : DisguiseDuration.parse(args[2]);
                lunarIcon = args.length == 4
                        ? DisguiserLunarIcon.commandOption(args[3]) : DisguiserLunarIcon.KEEP;
            } catch (Exception invalid) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + invalid.getMessage());
                return;
            }
        }

        if ((!all && !MC_USERNAME.matcher(source).matches()) || !MC_USERNAME.matcher(target).matches()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Source and target must be valid Minecraft names (1–16: letters, digits, _)."
                    + EnumChatFormatting.GRAY + " Got: " + EnumChatFormatting.WHITE + "\""
                    + source + "\" \"" + target + "\"");
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Join a world first.");
            return;
        }
        List<GameProfile> sources = all ? loadedWorldProfiles(mc) : new ArrayList<GameProfile>();
        if (!all) {
            try {
                sources.add(shared ? DisguiserRelay.sourceProfile(source) : new GameProfile(null, source));
            } catch (Exception failure) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + failure.getMessage());
                return;
            }
        }
        if (sources.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "There are no other loaded players to disguise.");
            return;
        }

        final UUID targetUuid;
        final GameProfile targetProfile;
        if (fullLunar) {
            if (!all) {
                GameProfile sourceInWorld = findWorldProfile(mc, source);
                if (sourceInWorld == null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Full disguise needs the source player to be loaded in your current world.");
                    return;
                }
                if (sourceInWorld.getId() != null && sourceInWorld.getId().equals(mc.thePlayer.getUniqueID())) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "For yourself, use /disguisefull <player> instead.");
                    return;
                }
                sources.clear();
                sources.add(sourceInWorld);
            }
            GameProfile targetInWorld = findWorldProfile(mc, target);
            if (targetInWorld == null || targetInWorld.getId() == null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                        + "Full disguise needs " + target + " to be in your current world with Lunar loaded.");
                return;
            }
            targetUuid = targetInWorld.getId();
            targetProfile = targetInWorld;
        } else {
            targetUuid = null;
            targetProfile = findWorldProfile(mc, target);
        }

        final String relayServer;
        try {
            relayServer = shared ? DisguiserRelay.context("disguiser_set").get("server").getAsString() : "";
        } catch (Exception failure) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + failure.getMessage());
            return;
        }

        final List<GameProfile> requestedSources = sources;
        final long mutationEpoch = REMOTE_GLOBAL_GENERATION.get();
        final Map<String, Long> sourceMutationTokens = new LinkedHashMap<String, Long>();
        for (GameProfile requestedSource : requestedSources) {
            if (requestedSource != null && requestedSource.getName() != null) {
                String key = mutationKey(requestedSource.getName());
                sourceMutationTokens.put(key, reserveRemoteMutation(key));
            }
        }
        final long requestedDuration = duration;
        final DisguiserLunarIcon requestedIcon = lunarIcon;
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Loading "
                + (fullLunar ? "full Lunar " : "") + "disguiser for "
                + (all ? "all loaded players" : source) + " -> " + EnumChatFormatting.AQUA
                + target + EnumChatFormatting.GRAY + "...");
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                int applied = 0;
                String firstFailure = null;
                try {
                    LocalDisguise.RemoteDisguiseProfile resolvedTarget =
                            LocalDisguise.resolveRemoteProfile(target, targetProfile);
                final String targetLunarProfileBase64;
                if (shared && fullLunar) {
                    String notReady = LunarFullDisguise.awaitReady(targetUuid, 15_000L);
                    if (notReady != null) throw new Exception(notReady);
                    targetLunarProfileBase64 = LunarFullDisguise.getSharedProfileBase64(targetUuid);
                } else {
                    targetLunarProfileBase64 = "";
                }
                    if (fullLunar && (resolvedTarget.profile == null
                            || !targetUuid.equals(resolvedTarget.profile.getId()))) {
                        throw new Exception("The target's live Lunar profile could not be matched. Retry after their cosmetics load.");
                    }
                    long localExpiry = requestedDuration == 0L ? 0L
                            : System.currentTimeMillis() + requestedDuration;
                    for (GameProfile sourceProfile : requestedSources) {
                        if (sourceProfile == null || sourceProfile.getName() == null) continue;
                        if (sourceProfile.getName().equalsIgnoreCase(target)) continue;
                        String mutationKey = mutationKey(sourceProfile.getName());
                        Long sourceMutationToken = sourceMutationTokens.get(mutationKey);
                        if (sourceMutationToken == null || !isRemoteMutationCurrent(
                                mutationKey, mutationEpoch, sourceMutationToken)) continue;
                        try {
                            long expiresAt = localExpiry;
                            if (shared) {
                                if (sourceProfile.getId() == null) {
                                    throw new Exception("Source player has no profile UUID: " + sourceProfile.getName());
                                }
                                JsonObject request = new JsonObject();
                                request.addProperty("type", "disguiser_set");
                                request.addProperty("server", relayServer);
                                request.addProperty("sourceName", sourceProfile.getName());
                                request.addProperty("sourceUuid", sourceProfile.getId().toString());
                                request.addProperty("targetName", resolvedTarget.name);
                                request.addProperty("targetUuid", resolvedTarget.profile.getId().toString());
                                request.addProperty("durationMs", requestedDuration);
                                request.addProperty("lunarIcon", requestedIcon.wire);
                                request.addProperty("fullLunar", fullLunar);
                                if (fullLunar) request.addProperty("targetLunarProfileBase64",
                                        targetLunarProfileBase64);
                                request.add("targetProfile", resolvedTarget.toRelaySnapshot());
                                JsonObject response = requestSharedMutation(request, mutationKey,
                                        mutationEpoch, sourceMutationToken);
                                if (response == null) continue;
                                if (response.has("lunarIcon")
                                        && !requestedIcon.wire.equals(response.get("lunarIcon").getAsString())) {
                                    throw new Exception("Relay returned a different Lunar icon setting.");
                                }
                                if (fullLunar && (!response.has("fullLunar")
                                        || !response.get("fullLunar").getAsBoolean())) {
                                    throw new Exception("The relay needs the full-disguiser update before this can be shared.");
                                }
                                expiresAt = response.get("expiresAt").getAsLong();
                            }
                            synchronized (REMOTE_MUTATION_LOCK) {
                                if (!isRemoteMutationCurrent(mutationKey, mutationEpoch,
                                        sourceMutationToken)) continue;
                                RemoteDisguiseManager.disguiseResolvedProfile(sourceProfile.getName(),
                                        resolvedTarget, expiresAt, requestedIcon, fullLunar);
                                applied++;
                            }
                        } catch (Exception failure) {
                            if (firstFailure == null) firstFailure = sourceProfile.getName() + ": " + failure.getMessage();
                            if (!all) break;
                        }
                    }
                    final int finalApplied = applied;
                    final String finalFailure = firstFailure;
                    Minecraft current = Minecraft.getMinecraft();
                    if (shared && current != null) {
                        current.addScheduledTask(new Runnable() {
                            @Override public void run() {
                                if (Main.getGlobalChatClient() != null) {
                                    Main.getGlobalChatClient().requestImmediatePoll();
                                }
                                sendResult(finalApplied, requestedSources.size(), finalFailure,
                                        target, all, requestedDuration, shared, fullLunar);
                            }
                        });
                    } else {
                        sendResult(finalApplied, requestedSources.size(), finalFailure,
                                target, all, requestedDuration, shared, fullLunar);
                    }
                } catch (Exception failure) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Disguiser failed: " + failure.getMessage());
                }
            }
        }, "gomod-disguiser");
        worker.setDaemon(true);
        worker.start();
    }

    private void sendResult(int applied, int requested, String failure, String target,
                            boolean all, long duration, boolean shared, boolean full) {
        if (applied > 0) {
            String action = full ? "Full disguiser applied" : "Disguiser applied";
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + action
                    + (shared ? " for gomod viewers" : " locally") + ": "
                    + EnumChatFormatting.AQUA + target + EnumChatFormatting.GREEN
                    + " to " + applied + (all ? " loaded player(s)" : " player")
                    + (duration == 0L ? " until reset/off." : " for " + formatDuration(duration) + "."));
        }
        if (failure != null) {
            Main.sendMessage(Main.CHAT_PREFIX + (applied == 0 ? EnumChatFormatting.RED : EnumChatFormatting.YELLOW)
                    + "Some disguises could not be applied: " + failure
                    + (all && applied > 0 && requested > applied ? " (" + applied + "/" + requested + " applied)." : ""));
        }
    }

    private static String formatDuration(long duration) {
        return duration >= 60_000L ? Math.max(1L, duration / 60_000L) + "m" : Math.max(1L, duration / 1000L) + "s";
    }

    private static String clean(String value) {
        return EnumChatFormatting.getTextWithoutFormattingCodes(value == null ? "" : value.trim());
    }

    private static List<GameProfile> loadedWorldProfiles(Minecraft mc) {
        LinkedHashMap<UUID, GameProfile> profilesByUuid = new LinkedHashMap<UUID, GameProfile>();
        if (mc == null) return new ArrayList<GameProfile>();
        UUID localUuid = mc.thePlayer == null ? null : mc.thePlayer.getUniqueID();

        // TAB contains every player known to the current server, including players outside the
        // entity render/load range. Use its original profiles as the primary identity for /all;
        // otherwise the command can affect only nearby spawned entities and leave most TAB rows
        // under their old names. Keep the UUID key so several players can share one disguise name.
        if (mc.getNetHandler() != null) {
            for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
                GameProfile profile = info instanceof NetworkPlayerInfoAccessor
                        ? ((NetworkPlayerInfoAccessor) info).gomod$getGameProfile()
                        : info == null ? null : info.getGameProfile();
                addAllDisguiseSource(profilesByUuid, profile, localUuid);
            }
        }

        // Retain spawned players that are not present in TAB (for singleplayer and unusual
        // server-side player-list behavior), without duplicating UUIDs already collected above.
        if (mc.theWorld != null) {
            for (Object candidate : mc.theWorld.playerEntities) {
                if (!(candidate instanceof EntityPlayer)) continue;
                EntityPlayer player = (EntityPlayer) candidate;
                if (player == mc.thePlayer) continue;
                addAllDisguiseSource(profilesByUuid, player.getGameProfile(), localUuid);
            }
        }
        return new ArrayList<GameProfile>(profilesByUuid.values());
    }

    private static void addAllDisguiseSource(Map<UUID, GameProfile> profilesByUuid,
            GameProfile profile, UUID localUuid) {
        if (profilesByUuid == null || profile == null || profile.getId() == null
                || profile.getName() == null || profile.getName().trim().isEmpty()
                || profile.getId().equals(localUuid)) {
            return;
        }
        profilesByUuid.putIfAbsent(profile.getId(), profile);
    }

    private static GameProfile findWorldProfile(Minecraft mc, String name) {
        if (mc == null || mc.theWorld == null || name == null) return null;
        for (Object candidate : mc.theWorld.playerEntities) {
            if (!(candidate instanceof EntityPlayer)) continue;
            EntityPlayer player = (EntityPlayer) candidate;
            GameProfile profile = player.getGameProfile();
            if (profile != null && profile.getName() != null && name.equalsIgnoreCase(profile.getName())) {
                return profile;
            }
        }
        return null;
    }

    private void clearShared(String source) {
        if (source == null) {
            invalidateAllRemoteMutations();
        } else {
            invalidateRemoteMutation(source);
        }
        synchronized (REMOTE_MUTATION_LOCK) {
            if (source == null) RemoteDisguiseManager.clearAll();
            else RemoteDisguiseManager.clearPlayer(source);
        }
        final JsonObject request;
        try {
            request = DisguiserRelay.context("disguiser_clear");
            if (source != null) request.addProperty("sourceName", source);
        } catch (Exception failure) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + failure.getMessage());
            return;
        }
        final Future<JsonObject> queuedClear = submitSharedRelayMutation(() -> DisguiserRelay.request(request));
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    JsonObject response = queuedClear.get();
                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc != null) mc.addScheduledTask(new Runnable() {
                        @Override public void run() {
                            if (Main.getGlobalChatClient() != null) Main.getGlobalChatClient().requestImmediatePoll();
                            int count = response.has("count") ? response.get("count").getAsInt() : 0;
                            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                                    + "Shared disguiser cleared (" + count + " relay entry/entries).");
                        }
                    });
                } catch (Exception failure) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Shared disguiser failed: " + failure.getMessage());
                }
            }
        }, "gomod-disguiser-clear");
        worker.setDaemon(true);
        worker.start();
    }

    private static String mutationKey(String sourceName) {
        return sourceName == null ? "" : sourceName.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static long reserveRemoteMutation(String key) {
        AtomicLong generation = REMOTE_SOURCE_GENERATIONS.computeIfAbsent(key, ignored -> new AtomicLong());
        return generation.incrementAndGet();
    }

    private static long invalidateRemoteMutation(String sourceName) {
        return reserveRemoteMutation(mutationKey(sourceName));
    }

    private static long invalidateAllRemoteMutations() {
        long epoch = REMOTE_GLOBAL_GENERATION.incrementAndGet();
        for (AtomicLong generation : REMOTE_SOURCE_GENERATIONS.values()) generation.incrementAndGet();
        return epoch;
    }

    private static boolean isRemoteMutationCurrent(String key, long epoch, long token) {
        AtomicLong generation = REMOTE_SOURCE_GENERATIONS.get(key);
        return epoch == REMOTE_GLOBAL_GENERATION.get()
                && generation != null && generation.get() == token;
    }

    private static Future<JsonObject> submitSharedRelayMutation(Callable<JsonObject> request) {
        return SHARED_RELAY_MUTATIONS.submit(request);
    }

    private static JsonObject requestSharedMutation(JsonObject request, String key,
                                                   long epoch, long token) throws Exception {
        Future<JsonObject> pending = SHARED_RELAY_MUTATIONS.submit(() ->
                isRemoteMutationCurrent(key, epoch, token) ? DisguiserRelay.request(request) : null);
        try {
            return pending.get();
        } catch (ExecutionException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new Exception(cause == null ? "Shared relay request failed." : cause.getMessage(), cause);
        }
    }
}
