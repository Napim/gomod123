package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.GomodVersion;
import me.napim.gomod.render.PlayerNametagOverlayStack;
import me.napim.gomod.render.VanillaPlayerNametagLayout;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.MovingObjectPosition;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Builds a read-only, shareable snapshot of every input used by player nametag/healthtag overlays. */
public final class PlayerTagDebug {
    private PlayerTagDebug() {
    }

    public static Result capture(String requestedPlayer) throws IOException {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            throw new IllegalStateException("Join a world before running /tagdebug.");
        }

        List<EntityPlayer> targets = selectTargets(mc, requestedPlayer);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("No matching player found. Look at a player or use /tagdebug <name|all>.");
        }

        String report = buildReport(mc, targets, requestedPlayer);
        File dir = new File(GomodPaths.getGomod123Dir(), "tagdebug");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create " + dir.getAbsolutePath());
        }
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        File output = new File(dir, "tagdebug_" + stamp + ".txt");
        File latest = new File(dir, "tagdebug_latest.txt");
        byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
        Files.write(output.toPath(), bytes);
        Files.write(latest.toPath(), bytes);

        boolean copied = false;
        try {
            GuiScreen.setClipboardString(report);
            copied = true;
        } catch (Throwable ignored) {
        }
        return new Result(output, targets.size(), copied);
    }

    private static List<EntityPlayer> selectTargets(Minecraft mc, String requested) {
        List<EntityPlayer> out = new ArrayList<EntityPlayer>();
        String query = requested == null ? "" : requested.trim();
        if ("all".equalsIgnoreCase(query)) {
            out.addAll(mc.theWorld.playerEntities);
            return out;
        }
        if (!query.isEmpty()) {
            for (EntityPlayer player : mc.theWorld.playerEntities) {
                if (matches(player, query)) {
                    out.add(player);
                    return out;
                }
            }
            return out;
        }
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit != null && hit.entityHit instanceof EntityPlayer) {
            out.add((EntityPlayer) hit.entityHit);
            return out;
        }

        EntityPlayer nearest = null;
        double nearestSq = Double.MAX_VALUE;
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer) continue;
            double distanceSq = mc.thePlayer.getDistanceSqToEntity(player);
            if (distanceSq < nearestSq) {
                nearestSq = distanceSq;
                nearest = player;
            }
        }
        if (nearest != null) out.add(nearest);
        else out.add(mc.thePlayer);
        return out;
    }

    private static boolean matches(EntityPlayer player, String query) {
        if (player == null) return false;
        if (player.getName() != null && player.getName().equalsIgnoreCase(query)) return true;
        if (player.getGameProfile() != null && player.getGameProfile().getName() != null
                && player.getGameProfile().getName().equalsIgnoreCase(query)) return true;
        String visible = TabDuplicateNickResolver.extractVisibleUsernamePublic(
                player.getDisplayName() == null ? null : player.getDisplayName().getFormattedText());
        return visible != null && visible.equalsIgnoreCase(query);
    }

    private static String buildReport(Minecraft mc, List<EntityPlayer> targets, String request) {
        StringBuilder sb = new StringBuilder(8192);
        line(sb, "gomod123 /tagdebug player tag snapshot");
        kv(sb, "generated", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date()));
        kv(sb, "build", (GomodReleaseBuild.PUBLIC_RELEASE ? "public" : "internal/dev") + " " + GomodVersion.get());
        kv(sb, "runtime.weave", GomodFixes.isWeaveRuntime());
        kv(sb, "request", request == null || request.trim().isEmpty() ? "looked-at/nearest" : request.trim());
        kv(sb, "camera_player", mc.thePlayer.getName());
        kv(sb, "lunar_hook.patched_returns", readLunarHookPatchedReturns());
        kv(sb, "lunar_hook.runtime", LunarNametagCompatibility.buildDebugSummary());
        kv(sb, "lunar.native_rows_above_main", LunarNametagCompatibility.countNativeRowsAboveMainName());
        kv(sb, "lunar.network_level_settings", LunarNametagCompatibility.getLunarSettingsDebugSummary());
        kv(sb, "config.denicker_enabled", ConfigManager.isDenickerEnabled());
        kv(sb, "config.denick_world_row_enabled", ConfigManager.isNickRealNametagOverlayEnabled());
        kv(sb, "config.denick_rank_mode", ConfigManager.isDenickerRankTagMode());
        kv(sb, "config.nametag_through_blocks", ConfigManager.isNametagThroughBlocksEnabled());
        kv(sb, "config.force_replay_healthtags", ConfigManager.isForceReplayPlayerHealthTagsEnabled());
        kv(sb, "visible_specs.replay_context", VisibleSpecs.isReplayContext());
        kv(sb, "visible_specs.nametag_mode", VisibleSpecs.getNametagMode());

        for (EntityPlayer player : targets) {
            line(sb, "");
            line(sb, "=== PLAYER " + safe(player.getName()) + " ===");
            appendPlayer(sb, mc, player);
        }
        return sb.toString();
    }

    private static void appendPlayer(StringBuilder sb, Minecraft mc, EntityPlayer player) {
        double distanceSq = mc.getRenderViewEntity() == null ? -1.0D
                : player.getDistanceSqToEntity(mc.getRenderViewEntity());
        kv(sb, "entity.class", player.getClass().getName());
        kv(sb, "entity.id", player.getEntityId());
        kv(sb, "entity.uuid", player.getUniqueID());
        kv(sb, "entity.profile_name", player.getGameProfile() == null ? null : player.getGameProfile().getName());
        kv(sb, "entity.name", player.getName());
        kv(sb, "entity.display_formatted", formattingVisible(player.getDisplayName() == null
                ? null : player.getDisplayName().getFormattedText()));
        kv(sb, "entity.custom_name", formattingVisible(player.getCustomNameTag()));
        kv(sb, "entity.flags", "dead=" + player.isDead + " invisible=" + player.isInvisible()
                + " invisibleToCamera=" + player.isInvisibleToPlayer(mc.thePlayer)
                + " sneaking=" + player.isSneaking() + " child=" + player.isChild());
        kv(sb, "entity.distance_sq", distanceSq);
        kv(sb, "entity.within_name_64", distanceSq >= 0.0D && distanceSq <= 4096.0D);
        if (GomodFixes.isWeaveRuntime()) {
            kv(sb, "lunar.fullaccess_state", LunarOnlineStatus.getFullAccessState());
            kv(sb, "lunar.fullaccess_route_active", LunarOnlineStatus.isFullAccessRouteActive());
            kv(sb, "lunar.logo.native_profile", GomodLogoColorSync.describeNativeTabLogoLookup(player.getUniqueID()));
            kv(sb, "lunar.logo.native_tab_width", GomodLogoColorSync.getNativeTabLogoWidth(player.getUniqueID()));
            kv(sb, "lunar.logo.hide_badge_policy", LunarDisguiserBadge.shouldHidePlayerBadge(player.getUniqueID()));
            kv(sb, "lunar.logo.hide_plus_policy", LunarDisguiserBadge.shouldHideExtraBadge(player.getUniqueID()));
            kv(sb, "lunar.logo.manual_icon_policy", LunarDisguiserBadge.iconPolicy(player.getUniqueID()));
        }

        NetworkPlayerInfo info = mc.getNetHandler() == null ? null
                : mc.getNetHandler().getPlayerInfo(player.getUniqueID());
        kv(sb, "tab.present_by_uuid", info != null);
        if (info != null) {
            kv(sb, "tab.profile_name", info.getGameProfile() == null ? null : info.getGameProfile().getName());
            kv(sb, "tab.display_name", formattingVisible(info.getDisplayName() == null
                    ? null : info.getDisplayName().getFormattedText()));
            kv(sb, "tab.ping", info.getResponseTime());
            kv(sb, "tab.game_type", info.getGameType());
            kv(sb, "tab.duplicate_hide_reason", TabDuplicateNickResolver.getDuplicateTabHideExplain(info));
        }

        appendTeamAndHealth(sb, player, distanceSq);

        String profileName = player.getGameProfile() == null ? player.getName() : player.getGameProfile().getName();
        String real = TabDuplicateNickResolver.resolveRealNameForDenicker(player.getUniqueID(), profileName);
        kv(sb, "denick.resolved_real", real);
        kv(sb, "denick.known_real_by_profile", TabDuplicateNickResolver.getKnownRealName(profileName));
        kv(sb, "denick.overlay_label", formattingVisible(NickRealNametagOverlay.buildRealIgnOverlayLabel(player)));
        kv(sb, "denick.would_show_row", NickRealNametagOverlay.wouldShowRealIgnNametagLine(player));
        kv(sb, "denick.handled_by_lunar_this_frame", LunarNametagCompatibility.isHandledByLunarNametagStack(player));

        kv(sb, "stack.overlay_rows_above_main", PlayerNametagOverlayStack.countOverlayRowsAboveMainIgn(player));
        kv(sb, "stack.mob_target_row", MobTargetDisplay.wouldShowAggroNametagRow(player));
        kv(sb, "stack.killer_row", SpectatorKillerNametagOverlay.shouldShowFor(player));
        kv(sb, "stack.killer_label", formattingVisible(SpectatorKillerNametagOverlay.buildLabel(player)));
        kv(sb, "visible_specs.suppress_nametag", VisibleSpecs.shouldSuppressNametag(player));
        kv(sb, "visible_specs.replay_target", VisibleSpecs.isReplaySpectatorSettingsTarget(player));
        kv(sb, "visible_specs.force_health_row", VisibleSpecs.shouldShowReplayHealthNametag(player));
    }

    private static void appendTeamAndHealth(StringBuilder sb, EntityPlayer player, double distanceSq) {
        Scoreboard board = player.worldObj == null ? null : player.worldObj.getScoreboard();
        if (board == null) {
            kv(sb, "scoreboard", "unavailable");
            return;
        }
        ScorePlayerTeam team = board.getPlayersTeam(player.getName());
        kv(sb, "team.name", team == null ? null : team.getRegisteredName());
        kv(sb, "team.prefix", team == null ? null : formattingVisible(team.getColorPrefix()));
        kv(sb, "team.suffix", team == null ? null : formattingVisible(team.getColorSuffix()));
        kv(sb, "team.visibility", team == null ? null : team.getNameTagVisibility());

        ScoreObjective objective = board.getObjectiveInDisplaySlot(2);
        kv(sb, "healthtag.objective_present", objective != null);
        if (objective != null) {
            kv(sb, "healthtag.objective_name", objective.getName());
            kv(sb, "healthtag.display_name", formattingVisible(objective.getDisplayName()));
            kv(sb, "healthtag.criteria", objective.getCriteria() == null ? null : objective.getCriteria().getName());
            boolean hasScore = board.entityHasObjective(player.getName(), objective);
            kv(sb, "healthtag.score_present_for_entity_name", hasScore);
            if (hasScore) {
                Score score = board.getValueFromObjective(player.getName(), objective);
                kv(sb, "healthtag.score", score == null ? null : score.getScorePoints());
            }
        }
        kv(sb, "healthtag.vanilla_would_render", player instanceof net.minecraft.client.entity.AbstractClientPlayer
                && VanillaPlayerNametagLayout.wouldRenderTabHealthNametagRow(
                (net.minecraft.client.entity.AbstractClientPlayer) player, distanceSq));
    }

    private static String readLunarHookPatchedReturns() {
        try {
            Class<?> hook = Class.forName("me.napim.gomod.weave.hooks.LunarHypixelNametagWeaveHook", false,
                    PlayerTagDebug.class.getClassLoader());
            Field field = hook.getField("lastPatchedReturns");
            return String.valueOf(field.get(null));
        } catch (Throwable t) {
            return "unavailable (" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String formattingVisible(String value) {
        return value == null ? "<null>" : value.replace('\u00a7', '&').replace('\r', ' ').replace('\n', ' ');
    }

    private static String safe(Object value) {
        return value == null ? "<null>" : String.valueOf(value).replace('\r', ' ').replace('\n', ' ');
    }

    private static void kv(StringBuilder sb, String key, Object value) {
        line(sb, key + "=" + safe(value));
    }

    private static void line(StringBuilder sb, String value) {
        sb.append(value == null ? "<null>" : value).append("\r\n");
    }

    public static final class Result {
        public final File file;
        public final int playerCount;
        public final boolean copiedToClipboard;

        private Result(File file, int playerCount, boolean copiedToClipboard) {
            this.file = file;
            this.playerCount = playerCount;
            this.copiedToClipboard = copiedToClipboard;
        }
    }
}
