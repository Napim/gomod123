/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.client.Minecraft
 *  net.minecraft.client.gui.GuiScreen
 *  net.minecraft.util.EnumChatFormatting
 *  net.weavemc.loader.api.command.Command
 */
package me.napim.gomod.command;

import com.mojang.authlib.GameProfile;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CancellationException;
import java.util.UUID;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.ApiKeyManager;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.DisguiseDuration;
import me.napim.gomod.features.DisguiseKiller;
import me.napim.gomod.features.DisguiserLunarIcon;
import me.napim.gomod.features.DisguiserRelay;
import me.napim.gomod.features.GomodFixes;
import me.napim.gomod.features.GomodPeerDisguise;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.LunarFullDisguise;
import me.napim.gomod.gui.RealPlayerNickConflictGUI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.command.Command;

public class DisguiseCommand
extends Command {
    private static final AtomicInteger ACTIVE_LOADS = new AtomicInteger(0);
    private static final AtomicLong LOCAL_DISGUISE_EXPIRY_TOKEN = new AtomicLong();
    private static final Object LOCAL_DISGUISE_EXPIRY_LOCK = new Object();
    private static final ScheduledExecutorService LOCAL_DISGUISE_EXPIRY_EXECUTOR =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "gomod-local-disguise-expiry");
                thread.setDaemon(true);
                return thread;
            });
    private static volatile ScheduledFuture<?> localDisguiseExpiry;
    private final boolean shareWithGomodPeers;

    public DisguiseCommand() {
        this("disguise", false);
    }

    protected DisguiseCommand(String commandName, boolean shareWithGomodPeers) {
        super(commandName, new String[0]);
        this.shareWithGomodPeers = shareWithGomodPeers;
    }

    public static boolean isLoading() {
        return ACTIVE_LOADS.get() > 0;
    }

    /** Invalidates a pending local disguise expiry when another local disguise starts or is cleared. */
    public static void cancelTimedLocalDisguiseExpiry() {
        synchronized (LOCAL_DISGUISE_EXPIRY_LOCK) {
            LOCAL_DISGUISE_EXPIRY_TOKEN.incrementAndGet();
            if (localDisguiseExpiry != null) {
                localDisguiseExpiry.cancel(false);
                localDisguiseExpiry = null;
            }
        }
    }

    private static void configureTimedLocalDisguise(String target, long durationMs,
                                                     DisguiserLunarIcon lunarIcon) {
        final long token;
        synchronized (LOCAL_DISGUISE_EXPIRY_LOCK) {
            token = LOCAL_DISGUISE_EXPIRY_TOKEN.incrementAndGet();
            if (localDisguiseExpiry != null) {
                localDisguiseExpiry.cancel(false);
                localDisguiseExpiry = null;
            }
        }
        LocalDisguise.setLocalDisguiseLunarIconOverride(lunarIcon);
        if (durationMs <= 0L) return;

        // A finite disguise must not be restored indefinitely by the separate reconnect option.
        ConfigManager.clearDisguiseReconnectSnapshot();

        ScheduledFuture<?> scheduled = LOCAL_DISGUISE_EXPIRY_EXECUTOR.schedule(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null) {
                mc.addScheduledTask(() -> expireTimedLocalDisguise(token, target, durationMs));
            }
        }, durationMs, TimeUnit.MILLISECONDS);
        synchronized (LOCAL_DISGUISE_EXPIRY_LOCK) {
            if (token == LOCAL_DISGUISE_EXPIRY_TOKEN.get()) {
                localDisguiseExpiry = scheduled;
            } else {
                scheduled.cancel(false);
            }
        }
    }

    private static void expireTimedLocalDisguise(long token, String target, long durationMs) {
        synchronized (LocalDisguise.class) {
            if (token != LOCAL_DISGUISE_EXPIRY_TOKEN.get()
                    || !LocalDisguise.isEnabled()
                    || target == null
                    || !target.equalsIgnoreCase(LocalDisguise.getTargetName())) {
                return;
            }
            synchronized (LOCAL_DISGUISE_EXPIRY_LOCK) {
                if (token != LOCAL_DISGUISE_EXPIRY_TOKEN.get()) return;
                localDisguiseExpiry = null;
            }
            ConfigManager.clearDisguiseReconnectSnapshot();
            LocalDisguise.disable();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Timed disguise as "
                    + EnumChatFormatting.WHITE + target + EnumChatFormatting.YELLOW + " expired after "
                    + formatDuration(durationMs) + ".");
        }
    }

    private static String formatDuration(long durationMs) {
        if (durationMs % 604800000L == 0L) return durationMs / 604800000L + "w";
        if (durationMs % 86400000L == 0L) return durationMs / 86400000L + "d";
        if (durationMs % 3600000L == 0L) return durationMs / 3600000L + "h";
        if (durationMs % 60000L == 0L) return durationMs / 60000L + "m";
        return Math.max(1L, durationMs / 1000L) + "s";
    }

    public static void disableActiveDisguiseWithMessage() {
        disableActiveDisguiseWithMessage(false);
    }

    private static void disableActiveDisguiseWithMessage(boolean stopDisguiseKiller) {
        boolean stoppedDisguiseKiller = stopDisguiseKiller && DisguiseKiller.isEnabled();
        if (stopDisguiseKiller) DisguiseKiller.setEnabled(false);
        // Invalidate any profile/texture lookup still running for an earlier enable command.
        LocalDisguise.cancelPendingEnableRequests();
        // Explicitly disabling a disguise is also an explicit choice not to restore
        // the previous disguise after the next game restart. Keep the restore toggle
        // enabled, but clear only the saved snapshot.
        ConfigManager.clearDisguiseReconnectSnapshot();
        // The full Lunar appearance is a separate override from the normal local
        // disguise flag. Restore it even if that flag was cleared independently.
        boolean stoppedFullLunarAppearance = LunarFullDisguise.isActive();
        LunarFullDisguise.deactivate();
        boolean stoppedPeerShare = GomodPeerDisguise.clearLocalShare();
        String automaticModeSuffix = stoppedDisguiseKiller ? " Disguise killer is OFF." : "";
        if (LocalDisguise.isEnabled()) {
            String target = LocalDisguise.getTargetName();
            LocalDisguise.disable();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Disguise disabled" + (String)(target != null ? " (" + target + ")" : "") + "." + automaticModeSuffix);
            if (stoppedPeerShare) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "gomod peer disguise sharing stopped.");
            }
        } else if (stoppedFullLunarAppearance) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Disguise disabled (Lunar cosmetics restored)." + automaticModeSuffix);
            if (stoppedPeerShare) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "gomod peer disguise sharing stopped.");
            }
        } else if (stoppedPeerShare) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "gomod peer disguise sharing stopped." + automaticModeSuffix);
        } else if (stoppedDisguiseKiller) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "Disguise killer disabled. No disguise was active.");
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "No disguise is active.");
        }
    }

    public static void enableDisguiseAsync(String requestedPlayer) {
        DisguiseCommand.enableDisguiseAsync(requestedPlayer, EnableMode.AUTO, 0L);
    }

    /** Returns the request token so the automatic mode can cancel only its own pending work. */
    public static long enableKilledPlayerDisguiseAsync(String victim, GameProfile knownProfile) {
        final long requestGeneration = LocalDisguise.beginEnableRequest();
        ACTIVE_LOADS.incrementAndGet();
        Thread worker = new Thread(() -> {
            try {
                if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                LocalDisguise.EnableResult result = LocalDisguise.enableKilledPlayer(
                        victim, knownProfile, requestGeneration);
                synchronized (LocalDisguise.class) {
                    if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                    GomodPeerDisguise.clearLocalShare();
                    configureTimedLocalDisguise(result.targetName, 0L, DisguiserLunarIcon.KEEP);
                }
            } catch (CancellationException ignored) {
                // A later kill, manual disguise, mode disable, or world change owns the selection now.
            } catch (Exception error) {
                if (LocalDisguise.isEnableRequestCurrent(requestGeneration)) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Could not copy " + victim + "'s disguise: " + error.getMessage());
                }
            } finally {
                ACTIVE_LOADS.updateAndGet(value -> Math.max(0, value - 1));
            }
        }, "gomod-disguise-killer");
        worker.setDaemon(true);
        worker.start();
        return requestGeneration;
    }

    public static void enableTimedDisguiseAsync(String requestedPlayer, long durationMs,
                                                DisguiserLunarIcon lunarIcon) {
        DisguiseCommand.enableDisguiseAsync(requestedPlayer, EnableMode.AUTO, 0L, null, null,
                PeerShareMode.LOCAL_ONLY, durationMs,
                lunarIcon == null ? DisguiserLunarIcon.KEEP : lunarIcon);
    }

    /** Starts a real-player disguise with a developer FPS-isolation component mask. */
    public static void enableFpsDebugDisguiseAsync(String requestedPlayer, LocalDisguise.FpsDebugMode debugMode) {
        DisguiseCommand.enableDisguiseAsync(requestedPlayer, EnableMode.AUTO, 0L,
                debugMode == null ? LocalDisguise.FpsDebugMode.FULL : debugMode);
    }

    public static void enableNickDisguiseAsync(String nick, LocalDisguise.NickSkinMode skinMode, String skinRef, GuiScreen nickSetupParent) {
        final long requestGeneration = LocalDisguise.beginEnableRequest();
        final String pendingNickRaw = nick == null ? "" : nick;
        final LocalDisguise.NickSkinMode pendingMode = skinMode;
        final String pendingRef = skinRef == null ? "" : skinRef;
        ACTIVE_LOADS.incrementAndGet();
        new Thread(() -> {
            try {
                LocalDisguise.EnableResult result = LocalDisguise.enableNickDisguise(
                        nick, skinMode, skinRef, requestGeneration);
                if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                boolean hasHypixelApiKey = ApiKeyManager.hasApiKey("hypixel");
                StringBuilder msg = new StringBuilder();
                msg.append(Main.CHAT_PREFIX).append(EnumChatFormatting.GREEN).append("Nick disguise enabled as ")
                        .append(EnumChatFormatting.YELLOW).append(result.targetName).append(EnumChatFormatting.GREEN).append(".");
                msg.append(" ").append(EnumChatFormatting.GRAY).append("skin=").append(result.skinLoaded ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no");
                Main.sendMessage(msg.toString());
                if (!hasHypixelApiKey) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Hypixel stats for a fake nick stay default unless you use a real player's skin (copied textures only).");
                }
            } catch (LocalDisguise.NickIsMojangAccountException conflict) {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc != null && LocalDisguise.isEnableRequestCurrent(requestGeneration)) {
                    final GuiScreen disguiseMenuParent = nickSetupParent != null ? nickSetupParent : mc.currentScreen;
                    final String mojangName = conflict.getMojangName();
                    final String uuid = conflict.getUuidUndashed();
                    mc.addScheduledTask(() -> mc.displayGuiScreen(new RealPlayerNickConflictGUI(
                            disguiseMenuParent, pendingNickRaw, mojangName, uuid, pendingMode, pendingRef)));
                }
            } catch (CancellationException ignored) {
                // /off or a newer disguise command superseded this lookup.
            } catch (Exception e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Nick disguise failed: " + e.getMessage());
            } finally {
                ACTIVE_LOADS.updateAndGet(value -> Math.max(0, value - 1));
            }
        }, "gomod-disguise-nick").start();
    }

    public static void enableNickDisguiseAllowingTakenAsync(String nick, LocalDisguise.NickSkinMode skinMode, String skinRef) {
        final long requestGeneration = LocalDisguise.beginEnableRequest();
        ACTIVE_LOADS.incrementAndGet();
        new Thread(() -> {
            try {
                LocalDisguise.EnableResult result = LocalDisguise.enableNickDisguiseAllowingTakenName(
                        nick, skinMode, skinRef, requestGeneration);
                if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                StringBuilder msg = new StringBuilder();
                msg.append(Main.CHAT_PREFIX).append(EnumChatFormatting.GREEN).append("Nick disguise enabled as ")
                        .append(EnumChatFormatting.YELLOW).append(result.targetName).append(EnumChatFormatting.GREEN).append(".");
                msg.append(" ").append(EnumChatFormatting.GRAY).append("skin=").append(result.skinLoaded ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no");
                Main.sendMessage(msg.toString());
            } catch (CancellationException ignored) {
                // A newer disguise command superseded the nick lookup.
            } catch (Exception e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Nick disguise failed: " + e.getMessage());
            } finally {
                ACTIVE_LOADS.updateAndGet(value -> Math.max(0, value - 1));
            }
        }, "gomod-disguise-nick-offline").start();
    }

    /** Re-apply last saved disguise when entering a world (if config allows). */
    public static void restorePersistedDisguiseAsync() {
        if (!GomodInternalFeatures.isEnabled()) {
            return;
        }
        if (!ConfigManager.isDisguiseRestoreOnReconnect()) {
            return;
        }
        String kind = ConfigManager.getDisguiseReconnectKind();
        if ("OFF".equals(kind)) {
            return;
        }
        final long requestGeneration = LocalDisguise.beginEnableRequest();
        final String restoreShareMode = ConfigManager.getDisguiseReconnectShareMode();
        final String restoreFullUuid = ConfigManager.getDisguiseReconnectFullLunarUuid();
        ACTIVE_LOADS.incrementAndGet();
        new Thread(() -> {
            try {
                if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                if ("PLAYER".equals(kind)) {
                    String n = ConfigManager.getDisguiseReconnectPlayerName();
                    if (n != null && !n.trim().isEmpty()) {
                        boolean restoreFull = "FULL_LOCAL".equals(restoreShareMode)
                                || "GOMOD_FULL_LUNAR".equals(restoreShareMode);
                        UUID fullUuid = null;
                        if (restoreFull) {
                            try {
                                String value = restoreFullUuid == null ? "" : restoreFullUuid.replace("-", "");
                                if (value.length() != 32) throw new IllegalArgumentException("invalid UUID");
                                fullUuid = UUID.fromString(value.substring(0, 8) + "-" + value.substring(8, 12)
                                        + "-" + value.substring(12, 16) + "-" + value.substring(16, 20)
                                        + "-" + value.substring(20));
                            }
                            catch (Exception invalid) {
                                throw new IllegalStateException("The saved full Lunar disguise target UUID is missing.");
                            }
                            String notReady = LunarFullDisguise.awaitReady(fullUuid, 15_000L,
                                    () -> LocalDisguise.isEnableRequestCurrent(requestGeneration));
                            if (notReady != null) throw new IllegalStateException(notReady);
                            if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                        }
                        LocalDisguise.EnableResult result = fullUuid == null
                                ? LocalDisguise.enable(n.trim(), requestGeneration)
                                : LocalDisguise.enable(new GameProfile(fullUuid, n.trim()), requestGeneration);
                        if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                        if (restoreFull) {
                            LunarFullDisguise.activate(fullUuid, result.targetName,
                                    () -> LocalDisguise.isEnableRequestCurrent(requestGeneration));
                        }
                        boolean restoredPeerShare = false;
                        synchronized (LocalDisguise.class) {
                            if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                            if ("GOMOD_NAME".equals(restoreShareMode)
                                    || "GOMOD_FULL_LUNAR".equals(restoreShareMode)) {
                                restoredPeerShare = GomodPeerDisguise.shareLocalDisguise(result.targetName,
                                        restoreFullUuid, "GOMOD_FULL_LUNAR".equals(restoreShareMode));
                            }
                            ConfigManager.saveDisguiseReconnectSnapshotPlayer(result.targetName,
                                    restoreShareMode, restoreFull ? restoreFullUuid : "");
                        }
                        if (restoredPeerShare && "GOMOD_FULL_LUNAR".equals(restoreShareMode)) {
                            try {
                                DisguiserRelay.publishFullLunarProfile(fullUuid,
                                        LunarFullDisguise.getSharedProfileBase64(fullUuid));
                            } catch (Exception unavailable) {
                                if (LocalDisguise.isEnableRequestCurrent(requestGeneration)) {
                                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                                            + "Restored the disguise, but gomod viewers are still waiting for its Lunar profile: "
                                            + unavailable.getMessage());
                                }
                            }
                        }
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Restored player disguise: "
                                + EnumChatFormatting.YELLOW + n.trim());
                    }
                } else if ("NICK".equals(kind)) {
                    String nick = ConfigManager.getDisguiseReconnectNick();
                    if (nick == null || nick.trim().isEmpty()) {
                        return;
                    }
                    String sm = ConfigManager.getDisguiseReconnectNickSkinMode();
                    String ref = ConfigManager.getDisguiseReconnectNickSkinRef();
                    LocalDisguise.NickSkinMode mode = LocalDisguise.NickSkinMode.STEVE;
                    if ("PLAYER_SKIN".equalsIgnoreCase(sm)) {
                        mode = LocalDisguise.NickSkinMode.PLAYER_SKIN;
                    } else if ("SKIN_FILE".equalsIgnoreCase(sm)) {
                        mode = LocalDisguise.NickSkinMode.SKIN_FILE;
                    }
                    String nickInput = ConfigManager.getDisguiseReconnectNickInputForRestore();
                    LocalDisguise.enableNickDisguise(
                            nickInput != null && !nickInput.isEmpty() ? nickInput : nick.trim(),
                            mode,
                            ref == null ? "" : ref,
                            requestGeneration);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Restored nick disguise: "
                            + EnumChatFormatting.YELLOW + nick.trim());
                }
            } catch (CancellationException ignored) {
                // A later command (especially /off) owns the current state now.
            } catch (Exception e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Could not restore disguise: "
                        + EnumChatFormatting.RED + e.getMessage());
            } finally {
                ACTIVE_LOADS.updateAndGet(value -> Math.max(0, value - 1));
            }
        }, "gomod-disguise-restore").start();
    }

    public static void enableDisguiseAsync(String requestedPlayer, EnableMode mode, long waitMillis) {
        DisguiseCommand.enableDisguiseAsync(requestedPlayer, mode, waitMillis, null);
    }

    public static void enableFullDisguiseAsync(String requestedPlayer, UUID sourcePlayerId) {
        if (requestedPlayer == null || sourcePlayerId == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Could not identify that in-world Lunar player.");
            return;
        }
        DisguiseCommand.enableDisguiseAsync(requestedPlayer, EnableMode.AUTO, 0L, null, sourcePlayerId);
    }

    public static void enableGomodDisguiseAsync(String requestedPlayer) {
        DisguiseCommand.enableDisguiseAsync(requestedPlayer, EnableMode.AUTO, 0L,
                null, null, PeerShareMode.GOMOD_NAME);
    }

    public static void enableFullGomodDisguiseAsync(String requestedPlayer, UUID sourcePlayerId) {
        if (requestedPlayer == null || sourcePlayerId == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Could not identify that in-world Lunar player.");
            return;
        }
        DisguiseCommand.enableDisguiseAsync(requestedPlayer, EnableMode.AUTO, 0L,
                null, sourcePlayerId, PeerShareMode.GOMOD_FULL_LUNAR);
    }

    private static void enableDisguiseAsync(String requestedPlayer, EnableMode mode, long waitMillis,
                                            LocalDisguise.FpsDebugMode fpsDebugMode) {
        DisguiseCommand.enableDisguiseAsync(requestedPlayer, mode, waitMillis, fpsDebugMode, null);
    }

    private static void enableDisguiseAsync(String requestedPlayer, EnableMode mode, long waitMillis,
                                            LocalDisguise.FpsDebugMode fpsDebugMode,
                                            UUID fullLunarSourcePlayerId) {
        DisguiseCommand.enableDisguiseAsync(requestedPlayer, mode, waitMillis, fpsDebugMode,
                fullLunarSourcePlayerId, PeerShareMode.LOCAL_ONLY);
    }

    private static void enableDisguiseAsync(String requestedPlayer, EnableMode mode, long waitMillis,
                                            LocalDisguise.FpsDebugMode fpsDebugMode,
                                            UUID fullLunarSourcePlayerId, PeerShareMode peerShareMode) {
        enableDisguiseAsync(requestedPlayer, mode, waitMillis, fpsDebugMode,
                fullLunarSourcePlayerId, peerShareMode, 0L, DisguiserLunarIcon.KEEP);
    }

    private static void enableDisguiseAsync(String requestedPlayer, EnableMode mode, long waitMillis,
                                            LocalDisguise.FpsDebugMode fpsDebugMode,
                                            UUID fullLunarSourcePlayerId, PeerShareMode peerShareMode,
                                            long localDurationMs, DisguiserLunarIcon localLunarIcon) {
        final long requestGeneration = LocalDisguise.beginEnableRequest();
        ACTIVE_LOADS.incrementAndGet();
        DisguiseCommand.sendLoadingMessage(requestedPlayer, mode, waitMillis);
        if (fullLunarSourcePlayerId != null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Waiting for Lunar's live appearance profile...");
        }
        GuiScreen parentScreen = Minecraft.getMinecraft() == null ? null : Minecraft.getMinecraft().currentScreen;
        new Thread(() -> {
            try {
                if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                if (fullLunarSourcePlayerId != null) {
                    String notReady = LunarFullDisguise.awaitReady(fullLunarSourcePlayerId, 15_000L,
                            () -> LocalDisguise.isEnableRequestCurrent(requestGeneration));
                    if (notReady != null) {
                        throw new IllegalStateException(notReady);
                    }
                }
                LocalDisguise.EnableResult result;
                if (waitMillis > 0L) {
                    Thread.sleep(waitMillis);
                }
                if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                if (fpsDebugMode != null) {
                    result = LocalDisguise.enableForFpsDebug(requestedPlayer, fpsDebugMode,
                            requestGeneration);
                } else switch (mode) {
                    case USE_CACHE: {
                        result = LocalDisguise.enableUsingCachedHypixelData(requestedPlayer,
                                requestGeneration);
                        break;
                    }
                    case FRESH: {
                        result = LocalDisguise.enableFreshHypixelData(requestedPlayer,
                                requestGeneration);
                        break;
                    }
                    default: {
                        result = fullLunarSourcePlayerId == null
                                ? LocalDisguise.enable(requestedPlayer, requestGeneration)
                                : LocalDisguise.enable(new GameProfile(
                                        fullLunarSourcePlayerId, requestedPlayer), requestGeneration);
                    }
                }
                if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                synchronized (LocalDisguise.class) {
                    if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                    // The previous opt-in describes the old active disguise. Once a new local
                    // disguise has succeeded, clear it before publishing the new selection.
                    GomodPeerDisguise.clearLocalShare();
                    if (peerShareMode == PeerShareMode.LOCAL_ONLY && fullLunarSourcePlayerId == null) {
                        configureTimedLocalDisguise(result.targetName, localDurationMs, localLunarIcon);
                    }
                }
                boolean hasHypixelApiKey = ApiKeyManager.hasApiKey("hypixel");
                StringBuilder msg = new StringBuilder();
                msg.append(Main.CHAT_PREFIX).append(EnumChatFormatting.GREEN).append("Disguise enabled as ").append(EnumChatFormatting.YELLOW).append(result.targetName).append(EnumChatFormatting.GREEN).append(".");
                if (localDurationMs > 0L && peerShareMode == PeerShareMode.LOCAL_ONLY
                        && fullLunarSourcePlayerId == null) {
                    msg.append(" ").append(EnumChatFormatting.GRAY)
                            .append("(expires in ").append(formatDuration(localDurationMs)).append(")");
                }
                if (localLunarIcon != DisguiserLunarIcon.KEEP
                        && peerShareMode == PeerShareMode.LOCAL_ONLY
                        && fullLunarSourcePlayerId == null) {
                    msg.append(" ").append(EnumChatFormatting.GRAY).append("(Lunar icon: ")
                            .append(localLunarIcon == DisguiserLunarIcon.LUNAR ? "on" : "off").append(")");
                }
                if (mode == EnableMode.USE_CACHE) {
                    msg.append(" ").append(EnumChatFormatting.GRAY).append("(using cached Hypixel data)");
                }
                if (fpsDebugMode != null) {
                    msg.append(" ").append(EnumChatFormatting.AQUA).append("[FPS mode: ")
                            .append(fpsDebugMode.commandName).append("]");
                }
                msg.append(" ").append(EnumChatFormatting.GRAY).append("skin=")
                        .append(result.skinLoaded ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no")
                        .append(EnumChatFormatting.GRAY).append(" cape=")
                        .append(result.capeLoaded ? EnumChatFormatting.GREEN + "yes" : EnumChatFormatting.RED + "no")
                        .append(EnumChatFormatting.GRAY).append(" kc=")
                        .append(result.killCounterEnabled ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "off")
                        .append(EnumChatFormatting.GRAY).append(" altkc=")
                        .append(result.alternativeKillMessageEnabled
                                ? EnumChatFormatting.GREEN + "on" : EnumChatFormatting.RED + "off");
                if (GomodFixes.isWeaveRuntime()) {
                    msg.append(EnumChatFormatting.GRAY).append(" lunarcosmetics=")
                            .append(ConfigManager.isDisguiseHideLunarCosmeticsOnWeave()
                                    ? EnumChatFormatting.RED + "hiding" : EnumChatFormatting.GREEN + "showing");
                }
                Main.sendMessage(msg.toString());
                boolean fullLunarApplied = fullLunarSourcePlayerId == null;
                if (fullLunarSourcePlayerId != null) {
                    try {
                        boolean cachedForLater = LunarFullDisguise.activate(
                                fullLunarSourcePlayerId, requestedPlayer,
                                () -> LocalDisguise.isEnableRequestCurrent(requestGeneration));
                        fullLunarApplied = true;
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                                + "Copied that player's Lunar outfit, Lunar+ color, logo color, and badge."
                                + (cachedForLater ? " Saved locally for use when they are offline."
                                        : " Their Lunar appearance could not be saved for offline reuse."));
                    } catch (Exception profileError) {
                        if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                                + "The name/skin disguise is active, but the Lunar appearance copy failed: "
                                + profileError.getMessage());
                    }
                }
                boolean peerShareEnabled = false;
                synchronized (LocalDisguise.class) {
                    if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                    if (peerShareMode == PeerShareMode.GOMOD_NAME) {
                        peerShareEnabled = GomodPeerDisguise.shareLocalDisguise(result.targetName, "", false);
                        Main.sendMessage(Main.CHAT_PREFIX + (peerShareEnabled ? EnumChatFormatting.GREEN : EnumChatFormatting.RED)
                                + (peerShareEnabled
                                ? (GomodPeerDisguise.isRelaySharingSupported()
                                    ? "Disguise sharing enabled for updated gomod players on this server. "
                                    : "Local disguise active; gomod sharing is waiting for the relay update. ")
                                : "The local disguise worked, but gomod sharing could not be enabled.")
                                + (peerShareEnabled ? EnumChatFormatting.GRAY
                                    + "The Minecraft server and non-gomod players still see your real account." : ""));
                    } else if (peerShareMode == PeerShareMode.GOMOD_FULL_LUNAR) {
                        peerShareEnabled = fullLunarApplied && GomodPeerDisguise.shareLocalDisguise(result.targetName,
                                fullLunarSourcePlayerId.toString(), true);
                        Main.sendMessage(Main.CHAT_PREFIX + (peerShareEnabled ? EnumChatFormatting.GREEN : EnumChatFormatting.RED)
                                + (peerShareEnabled
                                ? (GomodPeerDisguise.isRelaySharingSupported()
                                    ? "Full disguise sharing enabled for updated gomod players on this server. "
                                    : "Local full disguise active; gomod sharing is waiting for the relay update. ")
                                : "The disguise is local only; gomod sharing needs the full Lunar appearance to apply first.")
                                + (peerShareEnabled ? EnumChatFormatting.GRAY
                                    + "The Minecraft server and non-gomod players still see your real account." : ""));
                    }
                    if (fullLunarSourcePlayerId != null && fullLunarApplied) {
                        String reconnectMode = peerShareMode == PeerShareMode.GOMOD_FULL_LUNAR
                                && peerShareEnabled ? "GOMOD_FULL_LUNAR" : "FULL_LOCAL";
                        ConfigManager.saveDisguiseReconnectSnapshotPlayer(result.targetName,
                                reconnectMode, fullLunarSourcePlayerId.toString());
                    } else if (peerShareMode == PeerShareMode.GOMOD_NAME && peerShareEnabled) {
                        ConfigManager.saveDisguiseReconnectSnapshotPlayer(result.targetName,
                                "GOMOD_NAME", "");
                    }
                }
                if (peerShareMode == PeerShareMode.GOMOD_FULL_LUNAR && peerShareEnabled
                        && fullLunarSourcePlayerId != null
                        && LocalDisguise.isEnableRequestCurrent(requestGeneration)) {
                    try {
                        String sharedProfile = LunarFullDisguise.getSharedProfileBase64(fullLunarSourcePlayerId);
                        DisguiserRelay.publishFullLunarProfile(fullLunarSourcePlayerId, sharedProfile);
                    } catch (Exception shareError) {
                        if (LocalDisguise.isEnableRequestCurrent(requestGeneration)) {
                            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                                    + "gomod name sharing is active, but the full Lunar outfit could not be uploaded yet: "
                                    + shareError.getMessage());
                        }
                    }
                }
                if (!hasHypixelApiKey) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Set a Hypixel API key with " + EnumChatFormatting.WHITE + "/gomod api hypixel <key>" + EnumChatFormatting.YELLOW + " to use the disguised player's rank, kill counter, and alternative kill counter.");
                }
            }
            catch (LocalDisguise.HypixelDataDecisionRequiredException e) {
                DisguiseCommand.showDecisionDialog(parentScreen, e.getDecision(), peerShareMode,
                        fullLunarSourcePlayerId, localDurationMs, localLunarIcon, requestGeneration);
            }
            catch (CancellationException ignored) {
                // The pending command was deliberately invalidated.
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Disguise request cancelled.");
            }
            catch (Exception e) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Failed to enable disguise: " + e.getMessage());
            }
            finally {
                ACTIVE_LOADS.updateAndGet(value -> Math.max(0, value - 1));
            }
        }, "gomod-disguise").start();
    }

    private static void sendLoadingMessage(String requestedPlayer, EnableMode mode, long waitMillis) {
        if (mode == EnableMode.USE_CACHE) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Loading disguise for " + EnumChatFormatting.YELLOW + requestedPlayer + EnumChatFormatting.GRAY + " from cache...");
            return;
        }
        if (mode == EnableMode.FRESH && waitMillis > 0L) {
            long waitSeconds = Math.max(1L, (waitMillis + 999L) / 1000L);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Waiting " + EnumChatFormatting.YELLOW + waitSeconds + "s" + EnumChatFormatting.GRAY + " before loading disguise for " + EnumChatFormatting.YELLOW + requestedPlayer + EnumChatFormatting.GRAY + "...");
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Loading disguise for " + EnumChatFormatting.YELLOW + requestedPlayer + EnumChatFormatting.GRAY + "...");
    }

    private static void showDecisionDialog(GuiScreen parentScreen,
                                           LocalDisguise.HypixelDataDecision decision,
                                           final PeerShareMode peerShareMode,
                                           final UUID fullLunarSourcePlayerId,
                                           final long localDurationMs,
                                           final DisguiserLunarIcon localLunarIcon,
                                           final long requestGeneration) {
        LocalDisguise.showHypixelDataDecisionDialog(parentScreen, decision,
                new LocalDisguise.HypixelDataDecisionHandler() {
                    @Override
                    public void onUseCache(LocalDisguise.HypixelDataDecision selected) {
                        if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                        DisguiseCommand.enableDisguiseAsync(selected.targetName,
                                EnableMode.USE_CACHE, 0L, null, fullLunarSourcePlayerId, peerShareMode,
                                localDurationMs, localLunarIcon);
                    }

                    @Override
                    public void onWait(LocalDisguise.HypixelDataDecision selected) {
                        if (!LocalDisguise.isEnableRequestCurrent(requestGeneration)) return;
                        DisguiseCommand.enableDisguiseAsync(selected.targetName,
                                EnableMode.FRESH, selected.waitMillis, null,
                                fullLunarSourcePlayerId, peerShareMode, localDurationMs, localLunarIcon);
                    }

                    @Override
                    public void onCancel() {
                        // Keep the previous disguise and its sharing state intact.
                    }
                });
    }

    public void handle(String[] args) {
        if (args.length == 0) {
            if (LocalDisguise.isEnabled() || LunarFullDisguise.isActive()) {
                DisguiseCommand.disableActiveDisguiseWithMessage(!shareWithGomodPeers);
            } else {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Usage: "
                        + EnumChatFormatting.WHITE + "/" + (shareWithGomodPeers ? "disguisegomod" : "disguise")
                        + (shareWithGomodPeers ? " <player>" : " <player> [time] [lunar|nolunar]")
                        + EnumChatFormatting.YELLOW + " or " + EnumChatFormatting.WHITE
                        + "/" + (shareWithGomodPeers ? "disguisegomod" : "disguise") + " off");
            }
            return;
        }
        String mode = args[0];
        if ("help".equalsIgnoreCase(mode)) {
            showHelp();
            return;
        }
        if ("off".equalsIgnoreCase(mode) || "disable".equalsIgnoreCase(mode) || "reset".equalsIgnoreCase(mode)) {
            DisguiseCommand.disableActiveDisguiseWithMessage(!shareWithGomodPeers);
            return;
        }
        if (shareWithGomodPeers && args.length > 1) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "/"
                    + (shareWithGomodPeers ? "disguisegomod" : "disguise") + " only takes one player."
                    + EnumChatFormatting.GRAY + " For another player's disguise use "
                    + EnumChatFormatting.WHITE + "/disguiser <source> <target>" + EnumChatFormatting.GRAY + ".");
            return;
        }
        if (shareWithGomodPeers) {
            DisguiseCommand.enableGomodDisguiseAsync(mode);
        } else {
            if (args.length > 3) {
                sendLocalUsage();
                return;
            }
            long durationMs = 0L;
            boolean durationSeen = false;
            DisguiserLunarIcon lunarIcon = DisguiserLunarIcon.KEEP;
            boolean iconSeen = false;
            try {
                for (int i = 1; i < args.length; i++) {
                    String option = args[i].trim();
                    if ("lunar".equalsIgnoreCase(option) || "nolunar".equalsIgnoreCase(option)) {
                        if (iconSeen) throw new IllegalArgumentException("Choose only one of lunar or nolunar.");
                        lunarIcon = DisguiserLunarIcon.commandOption(option);
                        iconSeen = true;
                    } else {
                        if (durationSeen) throw new IllegalArgumentException("Provide only one duration.");
                        durationMs = DisguiseDuration.parse(option);
                        durationSeen = true;
                    }
                }
            } catch (IllegalArgumentException invalid) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + invalid.getMessage());
                sendLocalUsage();
                return;
            }
            DisguiseCommand.enableTimedDisguiseAsync(mode, durationMs, lunarIcon);
        }
    }

    private static void sendLocalUsage() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                + "Usage: /disguise <player> [time] [lunar|nolunar] or /disguise off"
                + EnumChatFormatting.GRAY + " (time example: 10s, 1h30m; icon and time may be in either order)");
    }

    private static void showHelp() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.AQUA + "Disguise commands:");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/disguise <player> [time] [lunar|nolunar] | /disguise off"
                + EnumChatFormatting.GRAY + " — local disguise; time and icon are optional.");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/disguisekiller [on|off|status]"
                + EnumChatFormatting.GRAY + " — automatically disguise as each player you kill in Blitz.");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/disguisegomod <player> | /disguisegomod off"
                + EnumChatFormatting.GRAY + " — share your name disguise with gomod peers.");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/disguisefull <player> | /disguisefull off"
                + EnumChatFormatting.GRAY + " — copy one in-world player's Lunar appearance onto you.");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/disguisefullgomod <player> | /disguisefullgomod off"
                + EnumChatFormatting.GRAY + " — share your full disguise with gomod peers.");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/disguiser <source> <target> <time> [lunar|nolunar]"
                + EnumChatFormatting.GRAY + " — local timed disguise.");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/disguiser all <target> [time] [lunar|nolunar]"
                + EnumChatFormatting.GRAY + " — disguise all loaded players locally.");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/disguisergomod <source> <target> <time> [lunar|nolunar]"
                + EnumChatFormatting.GRAY + " — timed disguise shared with gomod peers.");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/disguisergomod all <target> [time] [lunar|nolunar]"
                + EnumChatFormatting.GRAY + " — share disguises for all loaded players.");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/disguiserfull <source|all> <target> [time] [lunar|nolunar]"
                + EnumChatFormatting.GRAY + " — local full Lunar disguise; stays until reset/off if time is omitted.");
        Main.sendMessage(EnumChatFormatting.YELLOW + "/disguiserfullgomod <source|all> <target> [time] [lunar|nolunar]"
                + EnumChatFormatting.GRAY + " — full appearance disguise shared with gomod peers.");
        Main.sendMessage(EnumChatFormatting.GRAY + "Time units: s, m, h, d, w; examples 10s, 30m, 1h30m (1s–365d). /disguise accepts time and lunar/nolunar in either order.");
        Main.sendMessage(EnumChatFormatting.GRAY + "lunar forces the basic Lunar logo on; nolunar hides it; omit the option to keep the target's setting.");
        Main.sendMessage(EnumChatFormatting.GRAY + "Use /disguiser reset <source> or /disguiser off to restore local entries; "
                + "use /disguiserfull all reset to clear local full disguises. Shared commands also accept reset/off.");
    }

    private enum PeerShareMode {
        LOCAL_ONLY,
        GOMOD_NAME,
        GOMOD_FULL_LUNAR
    }

    public static enum EnableMode {
        AUTO,
        USE_CACHE,
        FRESH;

    }
}
