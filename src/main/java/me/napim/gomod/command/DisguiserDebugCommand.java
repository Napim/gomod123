package me.napim.gomod.command;

import com.mojang.authlib.GameProfile;
import me.napim.gomod.Main;
import me.napim.gomod.features.PlayerStandaloneCape;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.LunarNametagCompatibility;
import me.napim.gomod.features.LunarDisguiserBadge;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.LocalDisguiseDebugCapture;
import me.napim.gomod.features.LeaderboardHologramDebugCapture;
import me.napim.gomod.features.ScoreboardTeamDisguiseDebugCapture;
import me.napim.gomod.features.GomodPeerStandaloneCape;
import me.napim.gomod.features.RemoteDisguiseManager;
import me.napim.gomod.mixins.NetworkPlayerInfoAccessor;
import me.napim.gomod.util.GomodPaths;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.command.KillEffectCommand;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.IChatComponent;
import net.weavemc.loader.api.command.Command;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Bounded disguise diagnostics for local rendering, scoreboard teams and peer display paths. */
public final class DisguiserDebugCommand extends Command {
    public DisguiserDebugCommand() { super("disguiserdebug", new String[0]); }

    @Override public void handle(String[] args) {
        if (!me.napim.gomod.GomodInternalFeatures.isEnabled()) return;
        if (args.length > 0 && "teams".equalsIgnoreCase(args[0])) {
            handleTeamTrace(args);
            return;
        }
        if (args.length > 0 && "holograms".equalsIgnoreCase(args[0])) {
            handleHologramTrace(args);
            return;
        }
        if (args.length > 0 && "self".equalsIgnoreCase(args[0])) {
            handleLocalTrace(args);
            return;
        }
        if (args.length != 1 || !args[0].matches("[A-Za-z0-9_]{1,16}")) {
            Main.sendMessage(Main.CHAT_PREFIX + "Usage: /disguiserdebug <source-player> | self start|stop|status | teams start|stop|status | holograms start|stop|status");
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getNetHandler() == null || mc.theWorld == null) {
            Main.sendMessage(Main.CHAT_PREFIX + "Join a server first."); return;
        }
        NetworkPlayerInfo found = null;
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (info.getGameProfile() != null && args[0].equalsIgnoreCase(info.getGameProfile().getName())) {
                found = info; break;
            }
        }
        if (found == null) { Main.sendMessage(Main.CHAT_PREFIX + "Source is not in your TAB list."); return; }
        StringBuilder report = new StringBuilder("gomod manual disguise render snapshot\n");
        report.append(RemoteDisguiseManager.describeManualDisguise(found.getGameProfile())).append('\n');
        report.append("sourceProfileName=").append(found.getGameProfile().getName()).append('\n');
        LocalDisguise.RemoteDisguiseProfile anyTarget =
                RemoteDisguiseManager.getDisguisedTargetProfile(found.getGameProfile());
        report.append("anyDisguiseTarget=").append(anyTarget == null ? "none" : anyTarget.name).append('\n');
        report.append("lunarIconOverride: stored=")
                .append(RemoteDisguiseManager.getManualLunarIcon(found.getGameProfile()))
                .append(" policyByUuid=").append(LunarDisguiserBadge.iconPolicy(found.getGameProfile().getId()))
                .append(" hideBadge=").append(LunarDisguiserBadge.shouldHidePlayerBadge(found.getGameProfile().getId()))
                .append(" hideExtraBadge=").append(LunarDisguiserBadge.shouldHideExtraBadge(found.getGameProfile().getId()))
                .append('\n');
        LocalDisguise.RemoteDisguiseProfile target =
                RemoteDisguiseManager.getDisguisedTargetProfile(found.getGameProfile());
        if (target != null) {
            report.append("targetProfile: name=").append(target.name)
                    .append(" networkLevel=").append(target.networkLevelKnown ? target.networkLevel : "unknown")
                    .append(" guildTag=").append(target.guildTag)
                    .append(" hypixelDisplayName=").append(target.hypixelDisplayName).append('\n');
            report.append("tabQol: hypixelLobby=").append(GameContext.isHypixelLobby(mc))
                    .append(" blitzPregame=").append(GameContext.isBlitzPregameLobby(mc))
                    .append(" blitzGameActive=").append(GameContext.isBlitzGameActive(mc))
                    .append(" hypixelPregame=").append(GameContext.isHypixelPregameLobby(mc))
                    .append(" sourceStats=")
                    .append(KillEffectCommand.getStatsSuffixFor(found.getGameProfile().getName()))
                    .append(" targetScannedStats=").append(KillEffectCommand.getStatsSuffixFor(target.name))
                    .append(" targetApiStats=").append(target.getFormattedTabStatsSuffix())
                    .append(" targetGuild=").append(target.guildTag).append('\n');
        }
        report.append("renderedInfoDisplayName=").append(found.getDisplayName() == null
                ? "null" : found.getDisplayName().getFormattedText()).append('\n');
        report.append("rawInfoDisplayName=").append(found instanceof NetworkPlayerInfoAccessor
                ? (((NetworkPlayerInfoAccessor) found).gomod$getDisplayName() == null
                    ? "null" : ((NetworkPlayerInfoAccessor) found).gomod$getDisplayName().getFormattedText())
                : "accessor-unavailable").append('\n');
        report.append("sourceTeamDisplayName=").append(net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(
                found.getPlayerTeam(), found.getGameProfile().getName())).append('\n');
        report.append("gomodRenderedTabLine=").append(KillCounter.getRenderedTabLineForInfo(found)).append('\n');
        report.append("lunarFinalTabRender=")
                .append(RemoteDisguiseManager.describeLastManualDisguiseTabRenderedText()).append('\n');
        report.append("composedRightAlignedStats=")
                .append(KillCounter.getTabStatsSegmentForDebug(found)).append('\n');
        report.append("lunarLevelHead=")
                .append(LunarNametagCompatibility.describeDisguisedLevelForDebug(found.getGameProfile())).append('\n');
        report.append("lunarLevelHeadRuntime=")
                .append(LunarNametagCompatibility.buildLevelHeadDebugSummary()).append('\n');
        report.append("rawCape=").append(found instanceof NetworkPlayerInfoAccessor
                ? ((NetworkPlayerInfoAccessor) found).gomod$getLocationCape() : "accessor-unavailable");
        report.append(" infoCape=").append(found.getLocationCape()).append('\n');
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player instanceof AbstractClientPlayer && player.getUniqueID().equals(found.getGameProfile().getId())) {
                report.append("entityCape=").append(((AbstractClientPlayer) player).getLocationCape()).append('\n');
                String entityDisplay = player.getDisplayName() == null ? "" : player.getDisplayName().getFormattedText();
                report.append("nametagDisguiseDisplay=")
                        .append(RemoteDisguiseManager.getDisguisedDisplayNameFor(player, entityDisplay)).append('\n');
                break;
            }
        }
        report.append("localSelection: ").append(PlayerStandaloneCape.buildLocalDecisionDebug()).append('\n');
        // buildPeerDecisionDebug includes external image URLs, so use only the bounded selection metadata.
        report.append("localPublished: gomod=").append(PlayerStandaloneCape.shouldUseGomodCapeForPeerSharing())
                .append(" key=").append(PlayerStandaloneCape.getActiveOptionKeyForRelay())
                .append(" texture=").append(PlayerStandaloneCape.getCapeLocationForPeerSharing()).append('\n');
        try {
            Path directory = new java.io.File(GomodPaths.getGomod123Dir(), "debugall").toPath();
            Files.createDirectories(directory);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.ROOT).format(new Date());
            Path file = Files.createTempFile(directory, "disguiser_cape_" + stamp + "_", ".log");
            String bounded = report.length() > 8192 ? report.substring(0, 8192) : report.toString();
            Files.write(file, bounded.getBytes(StandardCharsets.UTF_8));
            Main.sendMessage(Main.CHAT_PREFIX + "Disguiser debug saved: " + file.toAbsolutePath());
        } catch (Exception failure) {
            Main.sendMessage(Main.CHAT_PREFIX + "Could not save debug: " + failure.getClass().getSimpleName());
        }
    }

    private static void handleLocalTrace(String[] args) {
        if (args.length != 2) {
            Main.sendMessage(Main.CHAT_PREFIX + "Usage: /disguiserdebug self start|stop|status");
            return;
        }
        String action = args[1].toLowerCase(java.util.Locale.ROOT);
        Minecraft mc = Minecraft.getMinecraft();
        if ("status".equals(action)) {
            Main.sendMessage(Main.CHAT_PREFIX + "Local disguise trace: "
                    + (LocalDisguiseDebugCapture.isActive() ? "recording" : "not recording"));
            return;
        }
        if ("start".equals(action)) {
            if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
                Main.sendMessage(Main.CHAT_PREFIX + "Join a server before starting the local trace.");
                return;
            }
            if (!LocalDisguiseDebugCapture.start()) {
                Main.sendMessage(Main.CHAT_PREFIX + "A local disguise trace is already recording.");
                return;
            }
            recordLocalSnapshot(mc, "capture-start");
            Main.sendMessage(Main.CHAT_PREFIX + "Local disguise trace started (3 minute limit). Reproduce the TAB/name color issue, then run /disguiserdebug self stop.");
            return;
        }
        if ("stop".equals(action)) {
            if (mc != null && mc.thePlayer != null && mc.getNetHandler() != null) {
                recordLocalSnapshot(mc, "capture-stop");
            }
            try {
                File file = LocalDisguiseDebugCapture.stop();
                Main.sendMessage(Main.CHAT_PREFIX + (file == null
                        ? "No local disguise trace was recorded."
                        : "Local disguise trace saved: " + file.getAbsolutePath()));
            } catch (Exception failure) {
                Main.sendMessage(Main.CHAT_PREFIX + "Could not save local trace: "
                        + failure.getClass().getSimpleName());
            }
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + "Usage: /disguiserdebug self start|stop|status");
    }

    private static void handleTeamTrace(String[] args) {
        if (args.length != 2) {
            Main.sendMessage(Main.CHAT_PREFIX + "Usage: /disguiserdebug teams start|stop|status");
            return;
        }
        String action = args[1].toLowerCase(java.util.Locale.ROOT);
        Minecraft mc = Minecraft.getMinecraft();
        if ("status".equals(action)) {
            Main.sendMessage(Main.CHAT_PREFIX + "Scoreboard-team disguise trace: "
                    + (ScoreboardTeamDisguiseDebugCapture.isActive() ? "recording" : "not recording")
                    + " (" + ScoreboardTeamDisguiseDebugCapture.getRowCount() + " rows buffered)");
            return;
        }
        if ("start".equals(action)) {
            if (mc == null || mc.thePlayer == null || mc.getNetHandler() == null) {
                Main.sendMessage(Main.CHAT_PREFIX + "Join Hypixel before starting the scoreboard-team trace.");
                return;
            }
            if (!ScoreboardTeamDisguiseDebugCapture.start(mc)) {
                Main.sendMessage(Main.CHAT_PREFIX + "A scoreboard-team disguise trace is already recording.");
                return;
            }
            Main.sendMessage(Main.CHAT_PREFIX + "Scoreboard-team trace started (15 minute limit). Start before cages, reproduce the name/color change, then run /disguiserdebug teams stop.");
            return;
        }
        if ("stop".equals(action)) {
            if (mc != null && mc.thePlayer != null) {
                ScoreboardTeamDisguiseDebugCapture.recordTick(mc);
            }
            try {
                File file = ScoreboardTeamDisguiseDebugCapture.stop();
                Main.sendMessage(Main.CHAT_PREFIX + (file == null
                        ? "No scoreboard-team trace was recorded."
                        : "Scoreboard-team trace saved: " + file.getAbsolutePath()));
            } catch (Exception failure) {
                Main.sendMessage(Main.CHAT_PREFIX + "Could not save scoreboard-team trace: "
                        + failure.getClass().getSimpleName());
            }
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + "Usage: /disguiserdebug teams start|stop|status");
    }

    private static void handleHologramTrace(String[] args) {
        if (args.length != 2) {
            Main.sendMessage(Main.CHAT_PREFIX + "Usage: /disguiserdebug holograms start|stop|status");
            return;
        }
        String action = args[1].toLowerCase(java.util.Locale.ROOT);
        Minecraft mc = Minecraft.getMinecraft();
        if ("status".equals(action)) {
            Main.sendMessage(Main.CHAT_PREFIX + "Leaderboard hologram trace: "
                    + (LeaderboardHologramDebugCapture.isActive() ? "recording" : "not recording")
                    + " (render-label hooks=" + LeaderboardHologramDebugCapture.getRenderLabelHookCalls()
                    + ", armor-stand rewrites=" + LeaderboardHologramDebugCapture.getArmorStandHookCalls()
                    + ", Lunar text-hologram calls=" + LeaderboardHologramDebugCapture.getLunarTextHologramCalls()
                    + ")");
            return;
        }
        if ("start".equals(action)) {
            if (mc == null || mc.thePlayer == null || mc.theWorld == null || mc.getNetHandler() == null) {
                Main.sendMessage(Main.CHAT_PREFIX + "Join a world before starting the hologram trace.");
                return;
            }
            if (!LeaderboardHologramDebugCapture.start()) {
                Main.sendMessage(Main.CHAT_PREFIX + "A leaderboard hologram trace is already recording.");
                return;
            }
            Main.sendMessage(Main.CHAT_PREFIX + "Leaderboard hologram trace started (3 minute limit). Look at the broken leaderboard hologram, then run /disguiserdebug holograms stop.");
            return;
        }
        if ("stop".equals(action)) {
            try {
                File file = LeaderboardHologramDebugCapture.stop();
                Main.sendMessage(Main.CHAT_PREFIX + (file == null
                        ? "No leaderboard hologram trace was recorded."
                        : "Leaderboard hologram trace saved: " + file.getAbsolutePath()));
            } catch (Exception failure) {
                Main.sendMessage(Main.CHAT_PREFIX + "Could not save hologram trace: "
                        + failure.getClass().getSimpleName());
            }
            return;
        }
        Main.sendMessage(Main.CHAT_PREFIX + "Usage: /disguiserdebug holograms start|stop|status");
    }

    private static void recordLocalSnapshot(Minecraft mc, String reason) {
        if (mc == null || mc.thePlayer == null) return;
        EntityPlayer player = mc.thePlayer;
        String originalName = player.getDisplayName() == null
                ? player.getName() : player.getDisplayName().getFormattedText();
        String nametag = LocalDisguise.buildNametagDisplayName(originalName, player.getName());
        LocalDisguiseDebugCapture.record("snapshot", "context",
                "reason=" + reason + " disguiseEnabled=" + LocalDisguise.isEnabled()
                        + " tabOverride=" + LocalDisguise.shouldApplyTabDisplayOverride()
                        + " real=" + LocalDisguise.getRealName() + " target=" + LocalDisguise.getTargetName()
                        + " game=" + GameContext.explainDetection(mc));
        LocalDisguiseDebugCapture.record("snapshot", "spectator-target-packet",
                me.napim.gomod.features.ReplayPlayerSelectorDisplay.describeLastSpectatorTargetPacket());
        LocalDisguiseDebugCapture.record("snapshot", "spectator-target-actionbar",
                me.napim.gomod.features.ReplayPlayerSelectorDisplay.describeLastSpectatorTargetActionBar());
        LocalDisguiseDebugCapture.record("snapshot", "nametag",
                "input=" + originalName + " output=" + nametag);

        NetworkPlayerInfo localInfo = null;
        String rawProfileName = "<not-in-tab>";
        for (NetworkPlayerInfo info : mc.getNetHandler().getPlayerInfoMap()) {
            if (!(info instanceof NetworkPlayerInfoAccessor)) continue;
            GameProfile rawProfile = ((NetworkPlayerInfoAccessor) info).gomod$getGameProfile();
            if (rawProfile == null || rawProfile.getId() == null
                    || !rawProfile.getId().equals(player.getUniqueID())) continue;
            localInfo = info;
            rawProfileName = rawProfile.getName();
            break;
        }
        if (localInfo == null) {
            LocalDisguiseDebugCapture.record("snapshot", "tab", "localPlayerInfo=<not-in-tab>");
        } else {
            NetworkPlayerInfoAccessor accessor = (NetworkPlayerInfoAccessor) localInfo;
            IChatComponent rawDisplay = accessor.gomod$getDisplayName();
            String rawLine = rawDisplay == null ? rawProfileName : rawDisplay.getFormattedText();
            String profileView = localInfo.getGameProfile() == null
                    ? "<null>" : localInfo.getGameProfile().getName();
            LocalDisguiseDebugCapture.record("snapshot", "tab",
                    "rawProfile=" + rawProfileName + " profileView=" + profileView
                            + " nativeDisplay=" + rawLine
                            + " gomodRendered=" + KillCounter.getRenderedTabLineForInfo(localInfo)
                            + " disguiseOutput=" + (GameContext.isHypixelReplayWatching(mc)
                            ? LocalDisguise.rewriteReplayTabRowForRender(rawLine, accessor.gomod$getGameProfile())
                            : LocalDisguise.buildTabDisplayName(rawLine, rawProfileName)));
            LocalDisguiseDebugCapture.record("snapshot", "tab-cape",
                    "rawCape=" + accessor.gomod$getLocationCape() + " getterCape=" + localInfo.getLocationCape());
        }
        GameProfile target = LocalDisguise.getTargetProfile();
        if (target != null) {
            LocalDisguiseDebugCapture.record("snapshot", "cape",
                    GomodPeerStandaloneCape.describeLocalDisguiseTargetCape(target,
                            LocalDisguise.getFallbackCapeLocationForDebug(),
                            LocalDisguise.isLocalDisguiseCapeSelectionExplicit()));
        }
    }
}
