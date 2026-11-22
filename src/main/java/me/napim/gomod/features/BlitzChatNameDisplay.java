package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import me.napim.gomod.command.KillEffectCommand;
import me.napim.gomod.util.GameContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Opt-in Blitz lifetime kills + rank on actual player labels. All data comes from existing scan/disguise caches. */
public final class BlitzChatNameDisplay {
    private static final Map<UUID, Entry> LABELS = new HashMap<>();
    private static final Map<String, Boolean> FINAL_LABEL_TEXTS = new java.util.concurrent.ConcurrentHashMap<>();
    private static Object contextWorld;
    private static int contextTick = Integer.MIN_VALUE;
    private static boolean contextActive;
    private static long contextDisplayRevision = Long.MIN_VALUE;
    private static String contextFormatMode;

    private static final class Entry {
        int tick = Integer.MIN_VALUE;
        boolean trackedDead, localDisguise, tabFinal, nameFinal;
        String source, visible, line;
        String tabInput, tabOutput, nameInput, nameOutput;
        KillCounter.KillEffectTabDisplayMode tabKillEffectMode;
    }

    private BlitzChatNameDisplay() { }

    public static boolean isActiveContext(Minecraft mc) {
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) return false;
        int tick = mc.thePlayer.ticksExisted;
        if (contextWorld != mc.theWorld) {
            LABELS.clear();
            FINAL_LABEL_TEXTS.clear();
            contextWorld = mc.theWorld;
            contextTick = Integer.MIN_VALUE;
        }
        long revision = LocalDisguise.getDisplayRevision();
        String formatMode = ConfigManager.getKillCounterBetterPlayerFormat();
        if (contextDisplayRevision != revision || !java.util.Objects.equals(contextFormatMode, formatMode)) {
            LABELS.clear();
            FINAL_LABEL_TEXTS.clear();
            contextDisplayRevision = revision;
            contextFormatMode = formatMode;
        }
        if (contextTick != tick) {
            contextTick = tick;
            contextActive = GameContext.isOnHypixel(mc)
                    && !GameContext.isHypixelReplayWatching(mc)
                    && (GameContext.isBlitzPregameLobby(mc) || GameContext.isBlitzCagePhaseSidebar(mc)
                    || GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc)
                    || GameContext.isBlitzGameOverSidebar(mc));
        }
        return contextActive && !TabStatsRuntimeMute.isMuted();
    }

    /** Final semantic labels must remain identical during font measurement and drawing, including Lunar fragments. */
    public static boolean shouldPreserveRenderedPlayerText(String text) {
        if (text == null || text.isEmpty()) return false;
        boolean nativeFinalLabel = LunarDisguiseDisplayCompatibility.isFinalizedNametagFontScope();
        if (!nativeFinalLabel && !FINAL_LABEL_TEXTS.containsKey(text)) return false;
        if (!isActiveContext(Minecraft.getMinecraft())
                || !(ConfigManager.isKillCounterChatStyleTab() || ConfigManager.isKillCounterChatStyleNametag()
                || nativeFinalLabel && KillCounter.getKillEffectTabDisplayMode().colorsNametags())) return false;
        return nativeFinalLabel || FINAL_LABEL_TEXTS.containsKey(text);
    }

    private static String preserveFinalLabel(String output, boolean finalized) {
        if (output != null && finalized) {
            if (FINAL_LABEL_TEXTS.size() >= 512) FINAL_LABEL_TEXTS.clear();
            FINAL_LABEL_TEXTS.put(output, Boolean.TRUE);
        }
        return output;
    }

    private static boolean isLiveMatchContext(Minecraft mc) {
        return !GameContext.isBlitzGameOverSidebar(mc)
                && !GameContext.isBlitzPregameLobby(mc) && !GameContext.isBlitzCagePhaseSidebar(mc)
                && (GameContext.isBlitzGameActive(mc) || GameContext.isBlitzDeathmatchPhase(mc));
    }

    public static String decorateTab(NetworkPlayerInfo row, String original) {
        if (!ConfigManager.isKillCounterChatStyleTab() || original == null || original.isEmpty() || row == null) return original;
        Minecraft mc = Minecraft.getMinecraft();
        if (!isActiveContext(mc)) return original;
        GameProfile source = RemoteDisguiseManager.getSourceProfile(row);
        if (source == null) return original;
        Entry entry = entry(source, mc.thePlayer.getUniqueID().equals(source.getId()), mc);
        if (entry == null || entry.line == null) return original;
        // Dead/spectator facts can outlive the live match. Keep formatting in pregame and after GAME OVER.
        boolean liveMatch = isLiveMatchContext(mc);
        if (liveMatch && (entry.trackedDead
                || row.getGameType() == net.minecraft.world.WorldSettings.GameType.SPECTATOR
                || KillCounter.isTabVictimsDisplayRow(row))) return original;
        KillCounter.KillEffectTabDisplayMode killEffectMode = KillCounter.getKillEffectTabDisplayMode();
        if (entry.tabKillEffectMode != killEffectMode) {
            entry.tabKillEffectMode = killEffectMode;
            entry.tabInput = entry.tabOutput = null;
        }
        if (original.equals(entry.tabOutput)) return preserveFinalLabel(original, entry.tabFinal);
        if (original.equals(entry.tabInput)) return preserveFinalLabel(entry.tabOutput, entry.tabFinal);
        entry.tabInput = original;
        String output = replaceNameSegment(original, entry.source, entry.visible, entry.line);
        // An unmatched secondary label is returned unchanged by identity; successful replacements build a new string.
        entry.tabFinal = output != original;
        if (killEffectMode.colorsTab()) {
            output = carryRenderedNameColor(original, output, entry.source, entry.visible);
        }
        return entry.tabOutput = preserveFinalLabel(output, entry.tabFinal);
    }

    public static String getNametagName(EntityPlayer player) {
        Entry entry = nametagEntry(player);
        return entry == null ? null : entry.line;
    }

    public static String decorateNametag(EntityPlayer player, String original) {
        if (original == null || original.isEmpty()) return original;
        Entry entry = nametagEntry(player);
        if (entry == null || entry.line == null) return original;
        if (original.equals(entry.nameOutput)) return preserveFinalLabel(original, entry.nameFinal);
        if (original.equals(entry.nameInput)) return preserveFinalLabel(entry.nameOutput, entry.nameFinal);
        entry.nameInput = original;
        String output = replaceNameSegment(original, entry.source, entry.visible, entry.line);
        entry.nameFinal = output != original;
        return entry.nameOutput = preserveFinalLabel(output, entry.nameFinal);
    }

    private static Entry nametagEntry(EntityPlayer player) {
        if (!ConfigManager.isKillCounterChatStyleNametag() || player == null) return null;
        Minecraft mc = Minecraft.getMinecraft();
        if (!isActiveContext(mc)
                || isLiveMatchContext(mc) && VisibleSpecs.shouldSuppressHealthNametag(player)) return null;
        NetworkPlayerInfo row = mc.getNetHandler() == null ? null : mc.getNetHandler().getPlayerInfo(player.getUniqueID());
        GameProfile source = row == null ? player.getGameProfile() : RemoteDisguiseManager.getSourceProfile(row);
        return entry(source, player == mc.thePlayer, mc);
    }

    private static Entry entry(GameProfile source, boolean local, Minecraft mc) {
        if (source == null || source.getId() == null || source.getName() == null) return null;
        Entry entry = LABELS.get(source.getId());
        if (entry == null) {
            if (LABELS.size() >= 256) LABELS.clear();
            entry = new Entry();
            LABELS.put(source.getId(), entry);
        }
        if (entry.tick == mc.thePlayer.ticksExisted) return entry;
        entry.tick = mc.thePlayer.ticksExisted;
        entry.trackedDead = KillCounter.isPlayerDead(source.getName());
        String visible = source.getName();
        String ranked;
        Integer kills;
        boolean localDisguise = (local || LocalDisguise.matchesLocalProfileFast(source))
                && LocalDisguise.shouldApplyTabDisplayOverride();
        if (localDisguise) {
            // The tab/chat display value can contain formatting or a custom prefix. Name matching here must use
            // only the disguise IGN, since pregame nametags and TAB intentionally suppress that prefix first.
            visible = LocalDisguise.getTargetName();
            if (visible == null || visible.trim().isEmpty()) {
                visible = LocalDisguise.getTargetTabChatDisplayNamePlain();
            }
            if (visible == null || visible.trim().isEmpty()) visible = source.getName();
            visible = visible.trim();
            kills = LocalDisguise.getDisguiseGuiPreviewKillCount();
            ranked = LocalDisguise.formatDisguiseNameForMatchResults(source.getName());
            if (ranked == null || ranked.isEmpty()) {
                ranked = LocalDisguise.buildDisguiseGuiTooltipRankedName();
            }
        } else {
            LocalDisguise.RemoteDisguiseProfile target = RemoteDisguiseManager.getDisguisedTargetProfile(source);
            if (target != null) {
                visible = target.name;
                kills = target.getBlitzKillCount();
                ranked = target.hypixelChatDisplayName;
                // Never fall back to the source account's rank or stats for an unavailable disguise target.
            } else {
                String lookup = KillCounter.resolveTabLookupName(source.getName());
                TabStatsBlitzMetrics metrics = KillEffectCommand.getCachedBlitzMetrics(lookup);
                if (metrics == null) metrics = KillEffectCommand.getCachedBlitzMetrics(source.getName());
                kills = metrics == null ? null : metrics.kills;
                ranked = HypixelTabStatsRankCache.formatNametagRankLine(lookup, visible);
                if (ranked.isEmpty()) ranked = HypixelTabStatsRankCache.formatNametagRankLine(source.getName(), visible);
                if (ranked.isEmpty()) ranked = metrics == null ? null : EnumChatFormatting.GRAY + visible;
            }
        }
        String line = ranked == null || ranked.isEmpty() ? null : formatName(kills, ranked);
        if (entry.localDisguise != localDisguise || !java.util.Objects.equals(entry.line, line)
                || !java.util.Objects.equals(entry.visible, visible)
                || !java.util.Objects.equals(entry.source, source.getName())) {
            entry.source = source.getName();
            entry.visible = visible;
            entry.line = line;
            entry.localDisguise = localDisguise;
            entry.tabInput = entry.tabOutput = entry.nameInput = entry.nameOutput = null;
            entry.tabFinal = entry.nameFinal = false;
        }
        return entry;
    }

    private static String formatName(Integer kills, String rankedName) {
        return (kills == null ? "" : LocalDisguise.formatBsg2KillBracketForDisplay(kills) + " ")
                + EnumChatFormatting.RESET + rankedName + EnumChatFormatting.RESET;
    }

    /** Replace the leading rank/kill-tag chain and name; preserve DIST/TEAM tags, guild, stats and denicker suffixes. */
    private static String replaceNameSegment(String original, String source, String visibleName, String replacement) {
        StringBuilder plain = new StringBuilder(original.length());
        int[] positions = new int[original.length()];
        for (int i = 0; i < original.length(); i++) {
            if (original.charAt(i) == '\u00a7' && i + 1 < original.length()) { i++; continue; }
            positions[plain.length()] = i;
            plain.append(original.charAt(i));
        }
        String text = plain.toString();
        String matched = visibleName;
        int at = findNameToken(text, matched);
        if (at < 0) { matched = source; at = findNameToken(text, matched); }
        if (at < 0 || matched == null || matched.isEmpty()) return original;
        int end = at + matched.length();
        // Hypixel/AntiShuffle may still supply the exact cage placeholder XXXX<IGN>.
        int bodyStart = at;
        if (bodyStart >= 4 && text.regionMatches(true, bodyStart - 4, "XXXX", 0, 4)) bodyStart -= 4;

        // Teams TAB can order the server tags either as [DIST] [VIP] name or [VIP] [DIST] name.
        // Walk the complete contiguous bracket chain, then rebuild it without stale rank/kill tags while
        // retaining game tags such as [DIST n] and [TEAM]. The previous backward scan stopped at [DIST],
        // leaving a rank behind when DIST appeared between the rank and the IGN.
        int chainStart = bodyStart;
        int cursor = bodyStart;
        while (cursor > 0) {
            int close = cursor - 1;
            while (close >= 0 && Character.isWhitespace(text.charAt(close))) close--;
            if (close < 0 || text.charAt(close) != ']') break;
            int open = text.lastIndexOf('[', close);
            if (open < 0) break;
            chainStart = open;
            cursor = open;
        }

        int rawStart = positions[chainStart];
        while (rawStart >= 2 && original.charAt(rawStart - 2) == '\u00a7') rawStart -= 2;
        int rawEnd = positions[end - 1] + 1;

        StringBuilder preservedTags = new StringBuilder();
        int plainCursor = chainStart;
        int rawCursor = rawStart;
        boolean hasPreservedTag = false;
        while (plainCursor < bodyStart) {
            while (plainCursor < bodyStart && Character.isWhitespace(text.charAt(plainCursor))) plainCursor++;
            if (plainCursor >= bodyStart || text.charAt(plainCursor) != '[') break;
            int close = text.indexOf(']', plainCursor + 1);
            if (close < 0 || close >= bodyStart) break;
            int tokenRawStart = positions[plainCursor];
            int tokenRawEnd = positions[close] + 1;
            String token = text.substring(plainCursor + 1, close);
            if (!isReplaceableBracket(token)) {
                if (hasPreservedTag) preservedTags.append(' ');
                appendFormattingCodes(preservedTags, original, rawCursor, tokenRawStart);
                preservedTags.append(original, tokenRawStart, tokenRawEnd);
                hasPreservedTag = true;
            }
            rawCursor = tokenRawEnd;
            plainCursor = close + 1;
        }
        if (hasPreservedTag) {
            preservedTags.append(' ');
        }

        // The replacement already ends in RESET; consume redundant resets for stable repeated render hooks.
        while (rawEnd + 1 < original.length() && original.charAt(rawEnd) == '\u00a7'
                && Character.toLowerCase(original.charAt(rawEnd + 1)) == 'r') rawEnd += 2;
        return original.substring(0, rawStart) + preservedTags + replacement + original.substring(rawEnd);
    }

    /** Keep only formatting codes from a removed-tag gap; its spacing is rebuilt around preserved tags. */
    private static void appendFormattingCodes(StringBuilder out, String formatted, int start, int end) {
        int from = Math.max(0, start);
        int to = Math.min(formatted.length(), end);
        for (int i = from; i + 1 < to; i++) {
            if (formatted.charAt(i) == '\u00a7') {
                out.append(formatted.charAt(i)).append(formatted.charAt(i + 1));
                i++;
            }
        }
    }

    private static int findNameToken(String text, String name) {
        if (name == null || name.isEmpty()) return -1;
        for (int at = 0; at + name.length() <= text.length(); at++) {
            if (!text.regionMatches(true, at, name, 0, name.length())) continue;
            int end = at + name.length();
            boolean masked = at >= 4 && text.regionMatches(true, at - 4, "XXXX", 0, 4)
                    && (at == 4 || !isNameChar(text.charAt(at - 5)));
            if ((at == 0 || !isNameChar(text.charAt(at - 1)) || masked)
                    && (end == text.length() || !isNameChar(text.charAt(end)))) return at;
        }
        return -1;
    }

    private static boolean isNameChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
    }

    private static boolean isReplaceableBracket(String value) {
        boolean digits = !value.isEmpty();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && c != ',') { digits = false; break; }
        }
        if (digits) return true;
        switch (value.toUpperCase(Locale.ROOT)) {
            case "VIP": case "VIP+": case "MVP": case "MVP+": case "MVP++":
            case "YOUTUBE": case "YOUTUBER": case "ADMIN": case "MOD": case "HELPER":
            case "GM": case "GAME MASTER": case "OWNER": case "STAFF": case "PIG":
            case "PIG+": case "PIG++": case "PIG+++": case "EVENTS": case "INNIT": return true;
            default: return false;
        }
    }

    /** Keep the row's normal/kill-effect IGN color when the rank-formatted line replaces its name. */
    private static String carryRenderedNameColor(String sourceLine, String formattedLine,
                                                  String sourceName, String visibleName) {
        if (sourceLine == null || formattedLine == null || formattedLine.isEmpty()) return formattedLine;

        int[] sourcePositions = new int[sourceLine.length()];
        String sourcePlain = stripFormattingWithPositions(sourceLine, sourcePositions);
        String matchedSource = visibleName;
        int sourceAt = findNameToken(sourcePlain, matchedSource);
        if (sourceAt < 0) {
            matchedSource = sourceName;
            sourceAt = findNameToken(sourcePlain, matchedSource);
        }
        if (sourceAt < 0 || matchedSource == null || matchedSource.isEmpty()) return formattedLine;
        String color = activeColorFormattingBefore(sourceLine, sourcePositions[sourceAt]);

        int[] outputPositions = new int[formattedLine.length()];
        String outputPlain = stripFormattingWithPositions(formattedLine, outputPositions);
        String matchedOutput = visibleName;
        int outputAt = findNameToken(outputPlain, matchedOutput);
        if (outputAt < 0) {
            matchedOutput = sourceName;
            outputAt = findNameToken(outputPlain, matchedOutput);
        }
        if (outputAt < 0 || matchedOutput == null || matchedOutput.isEmpty()) return formattedLine;

        int rawStart = outputPositions[outputAt];
        int rawEnd = outputPositions[outputAt + matchedOutput.length() - 1] + 1;
        return formattedLine.substring(0, rawStart) + color + matchedOutput + EnumChatFormatting.RESET
                + formattedLine.substring(rawEnd);
    }

    private static String stripFormattingWithPositions(String formatted, int[] positions) {
        StringBuilder plain = new StringBuilder(formatted.length());
        for (int i = 0; i < formatted.length(); i++) {
            if (formatted.charAt(i) == '\u00a7' && i + 1 < formatted.length()) {
                i++;
                continue;
            }
            positions[plain.length()] = i;
            plain.append(formatted.charAt(i));
        }
        return plain.toString();
    }

    private static String activeColorFormattingBefore(String formatted, int rawIndex) {
        char activeColor = 'r';
        for (int i = 0; i < rawIndex && i + 1 < formatted.length(); i++) {
            if (formatted.charAt(i) != '\u00a7') continue;
            char code = Character.toLowerCase(formatted.charAt(++i));
            if (code == 'r' || code >= '0' && code <= '9' || code >= 'a' && code <= 'f') {
                activeColor = code;
            }
        }
        return "\u00a7" + activeColor;
    }
}
