package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Final display substitutions for opted-in same-server peer disguises. */
public final class GomodPeerDisguiseDisplay {
    private static final Pattern COLON_SPEAKER = Pattern.compile(
            "^\\s*(?:(?:Guild|Party|Officer|Co-op)\\s*>\\s*|(?:From|To)\\s+)?"
                    + "(?:\\[[^\\]\\r\\n]*\\]\\s*)*([A-Za-z0-9_]{1,16})"
                    + "(?:\\s+\\[[^\\]\\r\\n]*\\])*\\s*(?::|\\u00bb)", Pattern.CASE_INSENSITIVE);
    private static final String SOCIAL_OPTIONS_HOVER_PREFIX = "Click to show social options for";
    private static final Pattern ANGLE_SPEAKER = Pattern.compile("^\\s*<([A-Za-z0-9_]{1,16})>");
    private static final Pattern CHAT_SPEAKER_SUFFIX = Pattern.compile(
            "^\\s*(?:\\[[^\\]\\r\\n]*\\]\\s*)*$");
    private static final Pattern SYSTEM_PLAYER_EVENT = Pattern.compile(
            "^\\s*(?:\\[[^\\]\\r\\n]*\\]\\s*)*([A-Za-z0-9_]{1,16})"
                    + "(?=\\s+(?:has\\s+quit!|has\\s+joined\\b|has\\s+left\\b|left\\b|joined\\b"
                    + "|was\\s+kicked\\b|disconnected\\b))", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLITZ_COIN_CHEST_ANNOUNCEMENT = Pattern.compile(
            "^\\s*(?:\\[[^\\]\\r\\n]*\\]\\s*)*([A-Za-z0-9_]{1,16})\\s+found\\s+[\\d,]+\\s+coins\\s+in\\s+a\\s+chest!\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BLITZ_TAUNT_ANNOUNCEMENT = Pattern.compile(
            "^\\s*(?:\\[[^\\]\\r\\n]*\\]\\s*)*([A-Za-z0-9_]{1,16})"
                    + "(?=\\s+performed\\s+the\\s+.+\\bTaunt!\\s*$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLITZ_STAR_FOUND_ANNOUNCEMENT = Pattern.compile(
            "^\\s*(?:\\[[^\\]\\r\\n]*\\]\\s*)*([A-Za-z0-9_]{1,16})"
                    + "\\s+found\\s+the\\s+Blitz\\s+Star!\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLITZ_STAR_USE_ANNOUNCEMENT = Pattern.compile(
            "^\\s*(?:\\[[^\\]\\r\\n]*\\]\\s*)*([A-Za-z0-9_]{1,16})"
                    + "(?:!\\s+BLITZ\\s+STAR!|\\s+BLITZ!)\\s+.+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLITZ_RESULT_PLACEMENT = Pattern.compile(
            "^\\s*(?:Winner|[0-9]+(?:st|nd|rd|th))\\s*[-=]\\s*"
                    + "(?:\\[[^\\]\\r\\n]{1,32}\\]\\s*)*([A-Za-z0-9_]{3,16})(?=\\s*\\().*$",
            Pattern.CASE_INSENSITIVE);
    private static final String[] KILL_FEED_MARKERS = {
            " was killed by ", " were killed by ", " was slain by ", " were slain by ",
            " was shot by ", " were shot by ", " was blown up by ", " were blown up by ",
            " was burned to death by ", " were burned to death by ", " was frozen to death by ",
            " were frozen to death by ", " was electrocuted by ", " were electrocuted by ",
            " was eliminated by "
    };

    private GomodPeerDisguiseDisplay() {}

    /** A name replacement must leave the legacy style codes before and after that name in place. */
    public static String replaceFormattedName(String formatted, String sourceName, String targetName) {
        if (formatted == null || formatted.isEmpty() || sourceName == null || sourceName.isEmpty()
                || targetName == null || targetName.isEmpty()) return formatted;
        VisibleText visible = new VisibleText(formatted);
        int at = indexOfName(visible.text, sourceName, 0);
        if (at < 0) return formatted;
        return replaceRawRange(formatted, visible.rawIndex[at],
                visible.rawIndex[at + sourceName.length() - 1] + 1, targetName);
    }

    /** Applies only configured peer/manual disguises to names in Roulette's semantic lines. */
    public static String rewriteBlitzRouletteNameText(String formatted) {
        if (formatted == null || formatted.isEmpty() || !BlitzRouletteText.isRouletteNameMessage(formatted)
                || (!GomodPeerDisguise.hasPeerDisguises() && !RemoteDisguiseManager.hasActiveManualDisguises())) {
            return formatted;
        }
        VisibleText visible = new VisibleText(formatted);
        List<RouletteNameRange> ranges = rouletteNameRanges(visible);
        if (ranges.isEmpty()) return formatted;
        ranges.sort(Comparator.comparingInt((RouletteNameRange range) -> range.rawStart).reversed());
        String rewritten = formatted;
        for (RouletteNameRange range : ranges) {
            rewritten = replaceRawRange(rewritten, range.rawStart, range.rawEnd, range.replacement);
        }
        return rewritten;
    }

    /** Resolves a lone animated title IGN, while leaving the caller to gate it on Roulette's subtitle. */
    public static String rewriteBlitzRouletteStandaloneTitle(String formatted) {
        if (formatted == null || formatted.isEmpty()
                || (!GomodPeerDisguise.hasPeerDisguises() && !RemoteDisguiseManager.hasActiveManualDisguises())) {
            return formatted;
        }
        VisibleText visible = new VisibleText(formatted);
        String name = visible.text.trim();
        if (!name.matches("(?i)[A-Z0-9_]{3,16}") || LocalDisguise.isLocalDisguiseSourceNameLabel(name)) {
            return formatted;
        }
        String targetName = targetNameForSource(name);
        if (targetName == null || targetName.isEmpty() || name.equalsIgnoreCase(targetName)) return formatted;
        LocalDisguise.RemoteDisguiseProfile profile = RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(name);
        String replacement = profile == null ? targetName : LocalDisguise.formatRemoteDisguiseRouletteName(profile);
        if (replacement == null || replacement.isEmpty()) replacement = targetName;
        int nameStart = visible.text.indexOf(name);
        if (nameStart < 0 || nameStart + name.length() > visible.rawIndex.length) return formatted;
        return replaceRawRange(formatted, visible.rawIndex[nameStart],
                visible.rawIndex[nameStart + name.length() - 1] + 1, replacement);
    }

    /** Replaces one semantic field while retaining the incoming component's colors, bold state and suffix. */
    public static IChatComponent replaceVisibleNameRange(IChatComponent original, int start, int end,
            String replacement) {
        if (original == null || replacement == null || start < 0 || end <= start) return original;
        StringBuilder raw = new StringBuilder();
        if (containsOnlyText(original)) {
            collectText(original, raw);
            VisibleText visible = new VisibleText(raw.toString());
            if (end > visible.text.length()) return original;
            return rewriteTextTreeRange(original, visible.rawIndex[start], visible.rawIndex[end - 1] + 1,
                    replacement, new int[]{0}, new boolean[1], true);
        }
        String formatted = original.getFormattedText();
        VisibleText visible = new VisibleText(formatted);
        if (end > visible.text.length()) return original;
        return new ChatComponentText(replaceRawRange(formatted, visible.rawIndex[start],
                visible.rawIndex[end - 1] + 1, replacement));
    }

    /** Runs after the TAB composer; UUID lookup also works with Lunar's display-only profile copy. */
    public static String rewriteTabName(GameProfile profile, String formatted) {
        if (formatted == null || formatted.isEmpty()) return formatted;
        LocalDisguise.RemoteDisguiseProfile manual = RemoteDisguiseManager.getManualDisguiseTarget(profile);
        GomodPeerDisguise.PeerName peer = GomodPeerDisguise.getPeerName(profile);
        String sourceName = manual == null ? peer == null ? null : peer.sourceName
                : RemoteDisguiseManager.getManualDisguiseSourceName(profile);
        String targetName = manual == null ? peer == null ? null : peer.targetName : manual.name;
        if (sourceName == null || sourceName.isEmpty() || targetName == null || targetName.isEmpty()) return formatted;
        VisibleText visible = new VisibleText(formatted);
        int from = 0;
        while (from < visible.text.length()) {
            int at = indexOfName(visible.text, sourceName, from);
            if (at < 0) return formatted;
            // Bracketed rank/guild labels are not the player's IGN.
            int open = visible.text.lastIndexOf('[', at);
            int close = visible.text.lastIndexOf(']', at);
            if (open <= close) {
                String result = replaceRawRange(formatted, visible.rawIndex[at],
                        visible.rawIndex[at + sourceName.length() - 1] + 1, targetName);
                LunarConnectionDiagnostics.recordOnce("gomod.peer_disguise.tab_display", "rewritten",
                        "changed=true source=" + (manual == null ? "same-server-peer-snapshot" : "local-disguiser"));
                return result;
            }
            from = at + sourceName.length();
        }
        return formatted;
    }

    /** Rewrite once as incoming chat enters the GUI, preserving its component tree and interaction metadata. */
    public static IChatComponent rewriteIncomingChat(IChatComponent original) {
        if (original == null || original instanceof SharedChatDisplay
                || (!GomodPeerDisguise.hasPeerDisguises()
                && !RemoteDisguiseManager.hasActiveManualDisguises())) {
            return original;
        }
        try {
            IChatComponent placement = rewriteDisguiseResultPlacement(original);
            if (placement != original) return placement;
            IChatComponent roulette = rewriteDisguiseRouletteNameMessage(original);
            if (roulette != original) return roulette;
            String teleportConfirmation = ReplayPlayerSelectorDisplay.rewriteTeleportConfirmationText(
                    original.getFormattedText());
            if (teleportConfirmation != null) {
                ChatComponentText rewritten = new ChatComponentText(teleportConfirmation);
                rewritten.setChatStyle(original.getChatStyle().createShallowCopy());
                return rewritten;
            }
            IChatComponent result;
            if (original instanceof ChatComponentTranslation) {
                result = rewriteTranslation((ChatComponentTranslation) original);
            } else if (containsOnlyText(original)) {
                StringBuilder raw = new StringBuilder();
                collectText(original, raw);
                VisibleText visible = new VisibleText(raw.toString());
                IChatComponent blitzStar = rewriteDisguiseBlitzStarAnnouncement(original, visible);
                IChatComponent coinChest = blitzStar == original
                        ? rewriteDisguiseCoinChest(original, visible) : blitzStar;
                IChatComponent taunt = coinChest == original
                        ? rewriteDisguiseTauntAnnouncement(original, visible) : coinChest;
                IChatComponent killFeed = taunt == original
                        ? rewriteDisguiseKillFeed(original, visible) : taunt;
                if (killFeed != original) {
                    result = killFeed;
                } else {
                    Matcher systemEvent = SYSTEM_PLAYER_EVENT.matcher(visible.text);
                    if (systemEvent.find()) {
                        String targetName = targetNameForSource(systemEvent.group(1));
                        if (targetName != null && !systemEvent.group(1).equalsIgnoreCase(targetName)) {
                            int start = visible.rawIndex[systemEvent.start(1)];
                            int end = visible.rawIndex[systemEvent.end(1) - 1] + 1;
                            result = rewriteTextTree(original, start, end, targetName, new int[]{0}, true);
                        } else {
                            return original;
                        }
                    } else {
                        Matcher speaker = speaker(visible.text);
                        if (speaker == null) return original;
                        LocalDisguise.RemoteDisguiseProfile targetProfile =
                                RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(speaker.group(1));
                        String targetName = targetProfile == null ? targetNameForSource(speaker.group(1)) : targetProfile.name;
                        if (targetName == null || speaker.group(1).equalsIgnoreCase(targetName)) return original;
                        if (targetProfile != null && LocalDisguise.isHypixelChatContextActive()) {
                            result = rewriteDisguiseChatSpeaker(original, visible, speaker, targetProfile);
                        } else {
                            int start = visible.rawIndex[speaker.start(1)];
                            int end = visible.rawIndex[speaker.end(1) - 1] + 1;
                            result = rewriteTextTree(original, start, end, targetName, new int[]{0}, true);
                        }
                        if (targetProfile != null) {
                            result = rewriteSocialOptionsHoverForDisguise(result, speaker.group(1), targetProfile);
                        }
                    }
                }
            } else {
                return original;
            }
            if (result != original) {
                LunarConnectionDiagnostics.recordOnce("gomod.peer_disguise.chat_display", "rewritten",
                        "changed=true speakerOrKillFeed=true componentMetadataPreserved=true");
            }
            return result;
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnce("gomod.peer_disguise.chat_display", "unsupported",
                    "changed=false errorType=" + error.getClass().getSimpleName() + " contents=omitted");
            return original;
        }
    }

    /** Rewrites both Blitz Star finder and use announcements while retaining their existing component style. */
    private static IChatComponent rewriteDisguiseBlitzStarAnnouncement(IChatComponent original,
            VisibleText visible) {
        if (original == null || visible == null) return original;
        Matcher announcement = BLITZ_STAR_FOUND_ANNOUNCEMENT.matcher(visible.text);
        if (!announcement.matches()) {
            announcement = BLITZ_STAR_USE_ANNOUNCEMENT.matcher(visible.text);
            if (!announcement.matches()) return original;
        }

        String sourceName = announcement.group(1);
        LocalDisguise.RemoteDisguiseProfile target =
                RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(sourceName);
        String targetName = target == null ? targetNameForSource(sourceName) : target.name;
        if (targetName == null || targetName.isEmpty() || sourceName.equalsIgnoreCase(targetName)) return original;

        int startVisible = findReplaceableSpeakerPrefixStart(visible.text, announcement.start(1));
        int endVisible = announcement.end(1);
        if (startVisible < 0 || endVisible <= startVisible || endVisible > visible.rawIndex.length) return original;
        int start = visible.rawIndex[startVisible];
        int end = visible.rawIndex[endVisible - 1] + 1;
        String replacement = target == null ? targetName : LocalDisguise.formatRemoteDisguiseChatName(target);
        if (replacement == null || replacement.isEmpty()) replacement = targetName;
        return rewriteTextTreeRange(original, start, end, replacement, new int[]{0}, new boolean[1], true);
    }

    private static IChatComponent rewriteDisguiseRouletteNameMessage(IChatComponent original) {
        if (original == null || !containsOnlyText(original)) return original;
        StringBuilder raw = new StringBuilder();
        collectText(original, raw);
        VisibleText visible = new VisibleText(raw.toString());
        List<RouletteNameRange> ranges = rouletteNameRanges(visible);
        if (ranges.isEmpty()) return original;
        ranges.sort(Comparator.comparingInt((RouletteNameRange range) -> range.rawStart).reversed());
        IChatComponent rewritten = original;
        for (RouletteNameRange range : ranges) {
            rewritten = rewriteTextTreeRange(rewritten, range.rawStart, range.rawEnd, range.replacement,
                    new int[]{0}, new boolean[1], true);
        }
        return rewritten;
    }

    private static List<RouletteNameRange> rouletteNameRanges(VisibleText visible) {
        List<RouletteNameRange> ranges = new ArrayList<>();
        if (visible == null || !BlitzRouletteText.isRouletteNameMessage(visible.text)) return ranges;
        for (int[] nameRange : BlitzRouletteText.findPlayerNameRanges(visible.text)) {
            if (nameRange == null || nameRange.length < 2 || nameRange[0] < 0
                    || nameRange[1] <= nameRange[0] || nameRange[1] > visible.text.length()) continue;
            String sourceName = visible.text.substring(nameRange[0], nameRange[1]);
            // Local /disguise owns the local source identity and applies its own target rank color.
            if (LocalDisguise.isLocalDisguiseSourceNameLabel(sourceName)) continue;
            String targetName = targetNameForSource(sourceName);
            if (targetName == null || targetName.isEmpty() || sourceName.equalsIgnoreCase(targetName)) continue;
            LocalDisguise.RemoteDisguiseProfile profile = RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(sourceName);
            String replacement = profile == null ? targetName : LocalDisguise.formatRemoteDisguiseRouletteName(profile);
            if (replacement == null || replacement.isEmpty()) replacement = targetName;
            ranges.add(new RouletteNameRange(visible.rawIndex[nameRange[0]],
                    visible.rawIndex[nameRange[1] - 1] + 1, replacement));
        }
        return ranges;
    }

    private static final class RouletteNameRange {
        final int rawStart;
        final int rawEnd;
        final String replacement;

        RouletteNameRange(int rawStart, int rawEnd, String replacement) {
            this.rawStart = rawStart;
            this.rawEnd = rawEnd;
            this.replacement = replacement;
        }
    }

    /** Rewrites only Hypixel's Winner/placement name, keeping the kit, score and row styling from the server. */
    private static IChatComponent rewriteDisguiseResultPlacement(IChatComponent original) {
        if (original == null || !LocalDisguise.isHypixelChatContextActive() || !containsOnlyText(original)) {
            return original;
        }
        StringBuilder raw = new StringBuilder();
        collectText(original, raw);
        VisibleText visible = new VisibleText(raw.toString());
        Matcher row = BLITZ_RESULT_PLACEMENT.matcher(visible.text);
        if (!row.matches()) return original;
        String sourceName = row.group(1);
        LocalDisguise.RemoteDisguiseProfile target =
                RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(sourceName);
        if (target == null || target.name == null || target.name.isEmpty()
                || sourceName.equalsIgnoreCase(target.name)) return original;

        int startVisible = findReplaceableSpeakerPrefixStart(visible.text, row.start(1));
        int endVisible = row.end(1);
        if (startVisible < 0 || endVisible <= startVisible || endVisible > visible.rawIndex.length) return original;
        int start = visible.rawIndex[startVisible];
        int end = visible.rawIndex[endVisible - 1] + 1;
        String replacement = LocalDisguise.formatRemoteDisguiseChatName(target);
        if (replacement == null || replacement.isEmpty()) replacement = target.name;
        IChatComponent rewritten = rewriteTextTreeRange(original, start, end, replacement,
                new int[]{0}, new boolean[1], true);
        if (rewritten != original && LocalDisguiseDebugCapture.isActive()) {
            LocalDisguiseDebugCapture.record("disguise-result-placement", sourceName,
                    "target=" + target.name + " input=" + original.getFormattedText()
                            + " output=" + rewritten.getFormattedText());
        }
        return rewritten;
    }

    /**
     * Rewrites the speaker portion of a stored message-count hover line using a frozen result name, and keeps
     * the message body white for disguised players. The line may already have been rewritten while live, so
     * accept either the source name or the disguise name in the speaker slot. Message-body mentions are untouched.
     */
    public static String rewriteHistoricalChatSpeaker(String formatted, String sourceName, String targetDisplay) {
        if (formatted == null || formatted.isEmpty() || sourceName == null || sourceName.isEmpty()
                || targetDisplay == null || targetDisplay.isEmpty()) return formatted;
        try {
            VisibleText visible = new VisibleText(formatted);
            Matcher speaker = COLON_SPEAKER.matcher(visible.text);
            if (!speaker.find()) return formatted;

            String targetName = speakerNameFromDisplay(targetDisplay);
            boolean sourceIsSpeaker = sourceName.equalsIgnoreCase(speaker.group(1));
            boolean targetIsSpeaker = targetName != null && targetName.equalsIgnoreCase(speaker.group(1));
            if (!sourceIsSpeaker && !targetIsSpeaker) return formatted;

            String rewritten = formatted;
            if (sourceIsSpeaker) {
                int startVisible = findReplaceableSpeakerPrefixStart(visible.text, speaker.start(1));
                int endVisible = speaker.end(1);
                if (startVisible < 0 || endVisible <= startVisible || endVisible > visible.rawIndex.length) {
                    return formatted;
                }
                int start = visible.rawIndex[startVisible];
                int end = visible.rawIndex[endVisible - 1] + 1;
                IChatComponent renamed = rewriteTextTreeRange(new ChatComponentText(formatted),
                        start, end, targetDisplay, new int[]{0}, new boolean[1], true);
                rewritten = renamed.getFormattedText();
            }

            // Live chat may already have rewritten the speaker before the hover snapshot was captured.
            // Apply the body color after speaker resolution in either case, preserving name rank colors.
            VisibleText rewrittenVisible = new VisibleText(rewritten);
            Matcher rewrittenSpeaker = COLON_SPEAKER.matcher(rewrittenVisible.text);
            if (!rewrittenSpeaker.find()) return rewritten;
            int delimiterVisible = rewrittenSpeaker.end() - 1;
            if (delimiterVisible < 0 || delimiterVisible >= rewrittenVisible.rawIndex.length) return rewritten;
            int bodyStart = rewrittenVisible.rawIndex[delimiterVisible] + 1;
            if (bodyStart < 0 || bodyStart > rewritten.length()) return rewritten;
            return rewritten.substring(0, bodyStart) + EnumChatFormatting.WHITE
                    + forceWhiteLegacyColors(rewritten.substring(bodyStart));
        } catch (Throwable ignored) {
            // A malformed stored hover line should keep its captured original text.
        }
        return formatted;
    }

    private static String speakerNameFromDisplay(String display) {
        if (display == null || display.isEmpty()) return null;
        VisibleText visible = new VisibleText(display);
        Matcher speaker = COLON_SPEAKER.matcher(visible.text + ":");
        return speaker.find() ? speaker.group(1) : null;
    }

    private static IChatComponent rewriteTranslation(ChatComponentTranslation original) {
        IChatComponent coinChest = rewriteTranslatedCoinChest(original);
        if (coinChest != original) return coinChest;
        String key = original.getKey();
        Object[] args = original.getFormatArgs();
        if ("multiplayer.player.left".equals(key) || "multiplayer.player.joined".equals(key)) {
            if (args.length < 1) return original;
            IChatComponent playerName = args[0] instanceof IChatComponent
                    ? (IChatComponent) args[0] : new ChatComponentText(String.valueOf(args[0]));
            if (!containsOnlyText(playerName)) return original;
            StringBuilder raw = new StringBuilder();
            collectText(playerName, raw);
            VisibleText visible = new VisibleText(raw.toString());
            String sourceName = visible.text.trim();
            int visibleStart = visible.text.indexOf(sourceName);
            if (sourceName.isEmpty() || !sourceName.matches("[A-Za-z0-9_]{1,16}") || visibleStart < 0) {
                return original;
            }
            String targetName = targetNameForSource(sourceName);
            if (targetName == null || sourceName.equalsIgnoreCase(targetName)) return original;
            Object[] copied = copyArgs(args);
            int start = visible.rawIndex[visibleStart];
            int end = visible.rawIndex[visibleStart + sourceName.length() - 1] + 1;
            copied[0] = rewriteTextTree(playerName, start, end, targetName, new int[]{0}, true);
            return copyStyleAndSiblings(original, new SharedTranslation(key, copied));
        }
        if (!"chat.type.text".equals(key) && !"chat.type.announcement".equals(key)) return original;
        if (args.length < 2) return original;
        IChatComponent sender = args[0] instanceof IChatComponent
                ? (IChatComponent) args[0] : new ChatComponentText(String.valueOf(args[0]));
        if (!containsOnlyText(sender)) return original;
        StringBuilder raw = new StringBuilder();
        collectText(sender, raw);
        VisibleText visible = new VisibleText(raw.toString());
        Matcher speaker = COLON_SPEAKER.matcher(visible.text + ":");
        if (!speaker.find()) return original;
        LocalDisguise.RemoteDisguiseProfile targetProfile =
                RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(speaker.group(1));
        String targetName = targetProfile == null ? targetNameForSource(speaker.group(1)) : targetProfile.name;
        if (targetName == null || speaker.group(1).equalsIgnoreCase(targetName)) return original;
        Object[] copied = copyArgs(args);
        if (targetProfile != null && LocalDisguise.isHypixelChatContextActive()) {
            int speakerEnd = Math.min(speaker.end(), visible.rawIndex.length);
            int startVisible = findReplaceableSpeakerPrefixStart(visible.text, speaker.start(1));
            if (startVisible >= 0 && speakerEnd > startVisible) {
                String replacement = LocalDisguise.formatRemoteDisguiseChatName(targetProfile);
                if (LocalDisguise.isBlitzSgChatContextActive()
                        && targetProfile.isBlitzChatKillCounterEnabled()
                        && targetProfile.getBlitzKillCount() != null) {
                    replacement = LocalDisguise.formatBsg2KillBracketForDisplay(targetProfile.getBlitzKillCount())
                            + " " + replacement;
                }
                copied[0] = rewriteTextTreeRange(sender, visible.rawIndex[startVisible],
                        visible.rawIndex[speakerEnd - 1] + 1,
                        replacement,
                        new int[]{0}, new boolean[1], true);
            }
            copied[1] = forceWhiteChatBodyArgument(copied[1]);
        } else {
            copied[0] = rewriteTextTree(sender, visible.rawIndex[speaker.start(1)],
                    visible.rawIndex[speaker.end(1) - 1] + 1, targetName, new int[]{0}, false);
        }
        if (targetProfile != null && copied[0] instanceof IChatComponent) {
            copied[0] = rewriteSocialOptionsHoverForDisguise((IChatComponent) copied[0],
                    speaker.group(1), targetProfile);
        }
        return copyStyleAndSiblings(original, new SharedTranslation(key, copied));
    }

    private static IChatComponent rewriteDisguiseChatSpeaker(IChatComponent original, VisibleText visible,
            Matcher speaker, LocalDisguise.RemoteDisguiseProfile target) {
        if (original == null || visible == null || speaker == null || target == null) return original;
        int startVisible = findReplaceableSpeakerPrefixStart(visible.text, speaker.start(1));
        int endVisible = speaker.end(1);
        if (startVisible < 0 || endVisible <= startVisible || endVisible > visible.rawIndex.length) return original;
        int start = visible.rawIndex[startVisible];
        int end = visible.rawIndex[endVisible - 1] + 1;
        String replacement = LocalDisguise.formatRemoteDisguiseChatName(target);
        if (LocalDisguise.isBlitzSgChatContextActive()
                && target.isBlitzChatKillCounterEnabled() && target.getBlitzKillCount() != null) {
            replacement = LocalDisguise.formatBsg2KillBracketForDisplay(target.getBlitzKillCount())
                    + " " + replacement;
        }
        IChatComponent whiteBody = original;
        int delimiterVisible = speaker.end() - 1;
        if (delimiterVisible >= 0 && delimiterVisible < visible.rawIndex.length) {
            int bodyStart = visible.rawIndex[delimiterVisible] + 1;
            whiteBody = forceWhiteFromRawIndex(original, bodyStart, new int[]{0}, true);
        }
        return rewriteTextTreeRange(whiteBody, start, end, replacement, new int[]{0}, new boolean[1], true);
    }

    /** Rewrites the social-options hover on a disguised speaker without changing its yellow prompt or click action. */
    private static IChatComponent rewriteSocialOptionsHoverForDisguise(IChatComponent original, String sourceName,
            LocalDisguise.RemoteDisguiseProfile target) {
        if (original == null || sourceName == null || sourceName.isEmpty() || target == null
                || target.name == null || target.name.isEmpty() || sourceName.equalsIgnoreCase(target.name)) {
            return original;
        }
        String targetChatName = target.hypixelChatName;
        if (targetChatName == null || targetChatName.isEmpty()) {
            targetChatName = EnumChatFormatting.GRAY + target.name + EnumChatFormatting.RESET;
        }
        return rewriteSocialOptionsHoverTree(original, sourceName, targetChatName, true);
    }

    private static IChatComponent rewriteSocialOptionsHoverTree(IChatComponent original, String sourceName,
            String targetChatName, boolean root) {
        if (!(original instanceof ChatComponentText)) return original;
        ChatStyle copiedStyle = original.getChatStyle().createShallowCopy();
        boolean changed = false;
        HoverEvent hover = copiedStyle.getChatHoverEvent();
        if (hover != null && hover.getAction() == HoverEvent.Action.SHOW_TEXT) {
            IChatComponent rewrittenHover = rewriteSocialOptionsHoverValue(hover.getValue(), sourceName,
                    targetChatName);
            if (LocalDisguiseDebugCapture.isActive() && hover.getValue() != null) {
                String hoverInput = hover.getValue().getFormattedText();
                String plainHover = EnumChatFormatting.getTextWithoutFormattingCodes(hoverInput);
                if (plainHover != null && plainHover.toLowerCase(Locale.ROOT)
                        .contains(SOCIAL_OPTIONS_HOVER_PREFIX.toLowerCase(Locale.ROOT))) {
                    LocalDisguiseDebugCapture.record("chat-social-hover", sourceName,
                            "target=" + targetChatName + " rewritten=" + (rewrittenHover != hover.getValue())
                                    + " input=" + hoverInput + " output=" + rewrittenHover.getFormattedText());
                }
            }
            if (rewrittenHover != hover.getValue()) {
                copiedStyle.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, rewrittenHover));
                changed = true;
            }
        }

        IChatComponent[] siblings = original.getSiblings().toArray(new IChatComponent[0]);
        for (int i = 0; i < siblings.length; i++) {
            IChatComponent rewritten = rewriteSocialOptionsHoverTree(siblings[i], sourceName, targetChatName, false);
            if (rewritten != siblings[i]) {
                siblings[i] = rewritten;
                changed = true;
            }
        }
        if (!changed) return original;

        String value = ((ChatComponentText) original).getChatComponentText_TextValue();
        ChatComponentText copy = root ? new SharedText(value) : new ChatComponentText(value);
        copy.setChatStyle(copiedStyle);
        for (IChatComponent sibling : siblings) copy.appendSibling(sibling);
        return copy;
    }

    private static IChatComponent rewriteSocialOptionsHoverValue(IChatComponent hoverText, String sourceName,
            String targetChatName) {
        if (hoverText == null) return null;
        try {
            boolean textTree = containsOnlyText(hoverText);
            String raw;
            if (textTree) {
                StringBuilder collected = new StringBuilder();
                collectText(hoverText, collected);
                raw = collected.toString();
            } else {
                raw = hoverText.getFormattedText();
            }
            VisibleText visible = new VisibleText(raw);
            int phraseStart = indexOfIgnoreCase(visible.text, SOCIAL_OPTIONS_HOVER_PREFIX, 0);
            if (phraseStart < 0 || !visible.text.substring(0, phraseStart).trim().isEmpty()) return hoverText;

            int nameStart = phraseStart + SOCIAL_OPTIONS_HOVER_PREFIX.length();
            while (nameStart < visible.text.length() && Character.isWhitespace(visible.text.charAt(nameStart))) {
                nameStart++;
            }
            int nameEnd = nameStart + sourceName.length();
            if (nameEnd > visible.text.length()
                    || !visible.text.regionMatches(true, nameStart, sourceName, 0, sourceName.length())
                    || (nameStart > 0 && usernameChar(visible.text.charAt(nameStart - 1)))
                    || (nameEnd < visible.text.length() && usernameChar(visible.text.charAt(nameEnd)))
                    || !visible.text.substring(nameEnd).trim().isEmpty()) {
                return hoverText;
            }

            int rawStart = visible.rawIndex[nameStart];
            int rawEnd = visible.rawIndex[nameEnd - 1] + 1;
            if (textTree) {
                return rewriteTextTreeRange(hoverText, rawStart, rawEnd, targetChatName,
                        new int[]{0}, new boolean[1], true);
            }
            ChatComponentText rewritten = new ChatComponentText(
                    replaceRawRange(raw, rawStart, rawEnd, targetChatName));
            rewritten.setChatStyle(hoverText.getChatStyle().createShallowCopy());
            return rewritten;
        } catch (Throwable ignored) {
            // Keep the original tooltip intact if an unusual component can't be safely parsed.
            return hoverText;
        }
    }

    private static Object forceWhiteChatBodyArgument(Object argument) {
        if (argument == null) return null;
        IChatComponent body;
        if (argument instanceof IChatComponent) {
            body = (IChatComponent) argument;
            if (!containsOnlyText(body)) return argument;
        } else {
            body = new ChatComponentText(String.valueOf(argument));
        }
        IChatComponent white = forceWhiteFromRawIndex(body, 0, new int[]{0}, true);
        return white;
    }

    private static int findReplaceableSpeakerPrefixStart(String visible, int nameStart) {
        if (visible == null || nameStart < 0 || nameStart > visible.length()) return nameStart;
        int start = nameStart;
        int cursor = nameStart;
        while (cursor > 0) {
            while (cursor > 0 && Character.isWhitespace(visible.charAt(cursor - 1))) cursor--;
            if (cursor == 0 || visible.charAt(cursor - 1) != ']') break;
            int open = visible.lastIndexOf('[', cursor - 1);
            if (open < 0) break;
            String token = visible.substring(open + 1, cursor - 1).trim();
            if (!isHypixelRankOrKillBracket(token)) break;
            start = open;
            cursor = open;
        }
        return start;
    }

    private static boolean isHypixelRankOrKillBracket(String token) {
        if (token == null || token.isEmpty()) return false;
        // Blitz chat kill counters can be five digits or more (for example [21033]).
        // The old three-digit limit left the source player's counter in place when their
        // killer name was rewritten, even when the disguise target has the counter disabled.
        if (token.matches("[0-9]+(?:,[0-9]{3})*")) return true;
        switch (token.toUpperCase(Locale.ROOT)) {
            case "VIP": case "VIP+": case "MVP": case "MVP+": case "MVP++":
            case "YOUTUBE": case "YOUTUBER": case "OWNER": case "ADMIN": case "MOD":
            case "HELPER": case "GM": case "GAME MASTER": case "MOJANG": case "EVENTS":
            case "PIG+++": case "INNIT": case "STAFF":
                return true;
            default:
                return false;
        }
    }

    private static IChatComponent rewriteDisguiseKillFeed(IChatComponent original, VisibleText visible) {
        if (original == null || visible == null || !LocalDisguise.isHypixelChatContextActive()) return original;
        int markerStart = -1;
        String marker = null;
        for (String candidate : KILL_FEED_MARKERS) {
            int at = indexOfIgnoreCase(visible.text, candidate, 0);
            if (at >= 0 && (markerStart < 0 || at < markerStart)) {
                markerStart = at;
                marker = candidate;
            }
        }
        if (markerStart < 0 || marker == null) return original;
        int markerEnd = markerStart + marker.length();
        boolean personalDeath = visible.text.trim().toLowerCase(Locale.ROOT).startsWith("you were ");
        IChatComponent rewritten = original;

        // The source on the left of the marker is the victim. Resolve it independently from
        // the killer after the marker so /disguiser can rename either side of the kill feed.
        String victimSource = markerStart == 0 ? null
                : RemoteDisguiseManager.findDisguiseSourceNameInText(visible.text.substring(0, markerStart), 0);
        String killerSource = RemoteDisguiseManager.findDisguiseSourceNameInText(visible.text, markerEnd);
        if (killerSource != null) {
            LocalDisguise.RemoteDisguiseProfile killerTarget =
                    RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(killerSource);
            int killerAt = indexOfName(visible.text, killerSource, markerEnd);
            if (killerTarget != null && killerAt >= 0) {
                int startVisible = findReplaceableSpeakerPrefixStart(visible.text, killerAt);
                int endVisible = killerAt + killerSource.length();
                if (startVisible >= 0 && endVisible <= visible.rawIndex.length) {
                    int start = visible.rawIndex[startVisible];
                    int end = visible.rawIndex[endVisible - 1] + 1;
                    String replacement = killerTarget.hypixelChatName == null
                            ? killerTarget.name : killerTarget.hypixelChatName;
                    if (personalDeath && LocalDisguise.isBlitzSgChatContextActive()
                            && killerTarget.isBlitzChatKillCounterEnabled()
                            && killerTarget.getBlitzKillCount() != null) {
                        replacement = LocalDisguise.formatBsg2KillBracketForDisplay(
                                killerTarget.getBlitzKillCount()) + " " + replacement;
                    }
                    rewritten = rewriteTextTreeRange(rewritten, start, end,
                            replacement, new int[]{0}, new boolean[1], true);
                    if (!personalDeath) {
                        rewritten = rewriteDisguiseTournamentCounter(rewritten, killerSource, killerTarget);
                    }
                }
            }
        }

        // Rewrite the victim last. Both offsets came from the original line, so changing the
        // right-hand killer first keeps the victim's original raw-text indices valid.
        if (victimSource != null) {
            LocalDisguise.RemoteDisguiseProfile victimTarget =
                    RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(victimSource);
            int victimAt = indexOfName(visible.text, victimSource, 0);
            if (victimTarget != null && victimAt >= 0 && victimAt < markerStart) {
                int startVisible = findReplaceableSpeakerPrefixStart(visible.text, victimAt);
                int endVisible = victimAt + victimSource.length();
                if (startVisible >= 0 && endVisible <= visible.rawIndex.length) {
                    int start = visible.rawIndex[startVisible];
                    int end = visible.rawIndex[endVisible - 1] + 1;
                    String replacement = victimTarget.hypixelChatName == null
                            ? victimTarget.name : victimTarget.hypixelChatName;
                    rewritten = rewriteTextTreeRange(rewritten, start, end,
                            replacement, new int[]{0}, new boolean[1], true);
                }
            }
        }
        return rewritten;
    }

    /** Replaces the rank/name in Hypixel's literal "performed the ... Taunt!" announcement. */
    private static IChatComponent rewriteDisguiseTauntAnnouncement(IChatComponent original, VisibleText visible) {
        if (original == null || visible == null || !LocalDisguise.isBlitzSgChatContextActive()) return original;
        Matcher announcement = BLITZ_TAUNT_ANNOUNCEMENT.matcher(visible.text);
        if (!announcement.find()) return original;

        String sourceName = announcement.group(1);
        LocalDisguise.RemoteDisguiseProfile target =
                RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(sourceName);
        String targetName = target == null ? targetNameForSource(sourceName) : target.name;
        if (targetName == null || targetName.isEmpty() || sourceName.equalsIgnoreCase(targetName)) return original;

        int startVisible = findReplaceableSpeakerPrefixStart(visible.text, announcement.start(1));
        int endVisible = announcement.end(1);
        if (startVisible < 0 || endVisible <= startVisible || endVisible > visible.rawIndex.length) return original;
        int start = visible.rawIndex[startVisible];
        int end = visible.rawIndex[endVisible - 1] + 1;
        String replacement = target == null ? targetName : LocalDisguise.formatRemoteDisguiseChatName(target);
        if (replacement == null || replacement.isEmpty()) replacement = targetName;
        return rewriteTextTreeRange(original, start, end, replacement, new int[]{0}, new boolean[1], true);
    }

    /** Replaces a peer's source rank/name in Blitz's special coin-chest announcement. */
    private static IChatComponent rewriteDisguiseCoinChest(IChatComponent original, VisibleText visible) {
        if (original == null || visible == null || !LocalDisguise.isBlitzSgChatContextActive()) return original;
        Matcher announcement = BLITZ_COIN_CHEST_ANNOUNCEMENT.matcher(visible.text);
        if (!announcement.matches()) return original;

        String sourceName = announcement.group(1);
        LocalDisguise.RemoteDisguiseProfile target =
                RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(sourceName);
        String targetName = target == null ? targetNameForSource(sourceName) : target.name;
        if (targetName == null || targetName.isEmpty() || sourceName.equalsIgnoreCase(targetName)) return original;

        int startVisible = findReplaceableSpeakerPrefixStart(visible.text, announcement.start(1));
        int endVisible = announcement.end(1);
        if (startVisible < 0 || endVisible <= startVisible || endVisible > visible.rawIndex.length) return original;
        int start = visible.rawIndex[startVisible];
        int end = visible.rawIndex[endVisible - 1] + 1;
        String replacement = target == null ? targetName : LocalDisguise.formatRemoteDisguiseChatName(target);
        if (replacement == null || replacement.isEmpty()) replacement = targetName;
        return rewriteTextTreeRange(original, start, end, replacement, new int[]{0}, new boolean[1], true);
    }

    private static IChatComponent rewriteTranslatedCoinChest(ChatComponentTranslation original) {
        if (original == null || !LocalDisguise.isBlitzSgChatContextActive()) return original;
        String plain = original.getUnformattedText();
        if (plain == null) return original;
        Matcher announcement = BLITZ_COIN_CHEST_ANNOUNCEMENT.matcher(plain);
        if (!announcement.matches()) return original;
        String sourceName = announcement.group(1);
        LocalDisguise.RemoteDisguiseProfile target =
                RemoteDisguiseManager.getDisguiseTargetProfileForSourceName(sourceName);
        String targetName = target == null ? targetNameForSource(sourceName) : target.name;
        if (targetName == null || targetName.isEmpty() || sourceName.equalsIgnoreCase(targetName)) return original;
        String replacement = target == null ? targetName : LocalDisguise.formatRemoteDisguiseChatName(target);
        if (replacement == null || replacement.isEmpty()) replacement = targetName;
        String formatted = original.getFormattedText();
        String rewritten = LunarDisguiseDisplayCompatibility.replaceHypixelRankedName(
                formatted, sourceName, replacement);
        if (rewritten.equals(formatted)) return original;
        SharedText copy = new SharedText(rewritten);
        copy.setChatStyle(original.getChatStyle().createShallowCopy());
        return copy;
    }

    private static IChatComponent rewriteDisguiseTournamentCounter(IChatComponent original, String source,
            LocalDisguise.RemoteDisguiseProfile target) {
        StringBuilder raw = new StringBuilder();
        collectText(original, raw);
        VisibleText visible = new VisibleText(raw.toString());
        int markerStart = -1;
        String marker = null;
        for (String candidate : KILL_FEED_MARKERS) {
            int at = indexOfIgnoreCase(visible.text, candidate, 0);
            if (at >= 0 && (markerStart < 0 || at < markerStart)) {
                markerStart = at;
                marker = candidate;
            }
        }
        if (markerStart < 0 || marker == null) return original;
        int targetAt = indexOfName(visible.text, target.name, markerStart + marker.length());
        if (targetAt < 0) return original;
        int suffixStart = indexOfIgnoreCase(visible.text, "(Kill #", targetAt + target.name.length());
        int suffixEnd = suffixStart < 0 ? -1 : visible.text.indexOf(')', suffixStart);
        boolean show = LocalDisguise.isBlitzSgChatContextActive()
                && target.isBlitzTournamentKillCounterEnabled();
        Integer killCount = show ? RemoteDisguiseManager.getDisguiseTournamentKillCount(source) : null;
        show = show && killCount != null;
        if (show) {
            String counter = EnumChatFormatting.GOLD + "(Kill #" + formatNumberWithGrouping(killCount) + ")";
            if (suffixStart >= 0 && suffixEnd >= suffixStart) {
                int start = visible.rawIndex[suffixStart];
                int end = visible.rawIndex[suffixEnd] + 1;
                int punctuation = findKillMessagePunctuation(visible.text, suffixEnd + 1);
                boolean terminalBang = punctuation > suffixEnd
                        && visible.text.charAt(punctuation) == '!'
                        && visible.text.substring(suffixEnd + 1, punctuation).trim().isEmpty();
                String trailingText = visible.text.substring(suffixEnd + 1);
                boolean noOtherTextAfterCounter = trailingText.trim().isEmpty()
                        && trailingText.indexOf('\n') < 0 && trailingText.indexOf('\r') < 0;
                String replacement = counter + EnumChatFormatting.RESET;
                if (terminalBang) {
                    // Replacing just the counter and ending it with RESET also reset Hypixel's following ! to
                    // white. Replace the adjacent punctuation with its intended yellow style explicitly.
                    end = visible.rawIndex[punctuation] + 1;
                    replacement = counter + EnumChatFormatting.YELLOW
                            + visible.text.substring(suffixEnd + 1, punctuation) + "!" + EnumChatFormatting.RESET;
                } else if (noOtherTextAfterCounter) {
                    // Kill-counter announcements normally end with a bang; restore it in yellow if the incoming
                    // component has no terminal punctuation, so the counter rewrite cannot leave it missing.
                    if (!trailingText.isEmpty()) {
                        end = visible.rawIndex[visible.text.length() - 1] + 1;
                    }
                    replacement = counter + EnumChatFormatting.YELLOW + trailingText + "!" + EnumChatFormatting.RESET;
                }
                return rewriteTextTreeRange(original, start, end, replacement,
                        new int[]{0}, new boolean[1], true);
            }
            int punct = findKillMessagePunctuation(visible.text, targetAt + target.name.length());
            if (punct >= 0 && visible.text.charAt(punct) == '!') {
                int start = visible.rawIndex[punct];
                int end = start + 1;
                return rewriteTextTreeRange(original, start, end,
                        " " + counter + EnumChatFormatting.YELLOW + "!" + EnumChatFormatting.RESET,
                        new int[]{0}, new boolean[1], true);
            }
            int insertionVisible = punct >= 0 ? punct : visible.text.length();
            int insertionRaw = insertionVisible < visible.text.length()
                    ? visible.rawIndex[insertionVisible] : raw.length();
            return rewriteTextTreeRange(original, insertionRaw, insertionRaw,
                    " " + counter + EnumChatFormatting.RESET, new int[]{0}, new boolean[1], true);
        }
        if (suffixStart >= 0 && suffixEnd >= suffixStart) {
            int startVisible = suffixStart > 0 && visible.text.charAt(suffixStart - 1) == ' '
                    ? suffixStart - 1 : suffixStart;
            return rewriteTextTreeRange(original, visible.rawIndex[startVisible],
                    visible.rawIndex[suffixEnd] + 1, "", new int[]{0}, new boolean[1], true);
        }
        return original;
    }

    private static int findKillMessagePunctuation(String visible, int from) {
        if (visible == null) return -1;
        for (int i = Math.max(0, from); i < visible.length(); i++) {
            char c = visible.charAt(i);
            if (c == '!' || c == '?' || c == '.') return i;
        }
        return -1;
    }

    private static String formatNumberWithGrouping(int number) {
        return NumberFormat.getIntegerInstance(Locale.US).format(Math.max(0, number));
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        if (text == null || needle == null || needle.isEmpty()) return -1;
        for (int i = Math.max(0, from); i <= text.length() - needle.length(); i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) return i;
        }
        return -1;
    }

    private static String targetNameForSource(String sourceName) {
        String manualTarget = RemoteDisguiseManager.getDisguiseTargetNameForSourceName(sourceName);
        if (manualTarget != null && !manualTarget.isEmpty()) return manualTarget;
        GomodPeerDisguise.PeerName peer = GomodPeerDisguise.getPeerName(sourceName);
        return peer == null || peer.sourceName.equalsIgnoreCase(peer.targetName) ? null : peer.targetName;
    }

    private static Matcher speaker(String visible) {
        Matcher angle = ANGLE_SPEAKER.matcher(visible);
        if (angle.find()) return angle;
        Matcher colon = COLON_SPEAKER.matcher(visible);
        return colon.find() ? colon : null;
    }

    private static boolean containsOnlyText(IChatComponent node) {
        if (!(node instanceof ChatComponentText)) return false;
        for (IChatComponent child : node.getSiblings()) if (!containsOnlyText(child)) return false;
        return true;
    }

    private static void collectText(IChatComponent node, StringBuilder text) {
        text.append(((ChatComponentText) node).getChatComponentText_TextValue());
        for (IChatComponent child : node.getSiblings()) collectText(child, text);
    }

    /** Replaces a range across literal-text components, or inserts at a raw-text boundary. */
    private static IChatComponent rewriteTextTreeRange(IChatComponent node, int start, int end, String replacement,
            int[] cursor, boolean[] inserted, boolean root) {
        String raw = ((ChatComponentText) node).getChatComponentText_TextValue();
        int offset = cursor[0];
        int nodeEnd = offset + raw.length();
        cursor[0] = nodeEnd;

        String rewritten = raw;
        if (start == end) {
            if (!inserted[0] && start >= offset && start <= nodeEnd) {
                int at = Math.max(0, Math.min(raw.length(), start - offset));
                rewritten = replaceRawRange(raw, at, at, replacement);
                inserted[0] = true;
            }
        } else {
            int from = Math.max(0, start - offset);
            int to = Math.min(raw.length(), end - offset);
            if (from < to) {
                boolean ownsStart = start >= offset && start < nodeEnd;
                rewritten = replaceRawRange(raw, from, to, ownsStart ? replacement : "");
            }
        }

        IChatComponent copy = root ? new SharedText(rewritten) : new ChatComponentText(rewritten);
        copy.setChatStyle(node.getChatStyle().createShallowCopy());
        for (IChatComponent child : node.getSiblings()) {
            copy.appendSibling(rewriteTextTreeRange(child, start, end, replacement, cursor, inserted, false));
        }
        return copy;
    }

    private static IChatComponent rewriteTextTree(IChatComponent node, int start, int end, String target,
                                                   int[] cursor, boolean root) {
        String raw = ((ChatComponentText) node).getChatComponentText_TextValue();
        int offset = cursor[0];
        cursor[0] += raw.length();
        int from = Math.max(0, start - offset), to = Math.min(raw.length(), end - offset);
        String rewritten = from < to ? replaceRawRange(raw, from, to,
                start >= offset && start < offset + raw.length() ? target : "") : raw;
        IChatComponent copy = root ? new SharedText(rewritten) : new ChatComponentText(rewritten);
        copy.setChatStyle(node.getChatStyle().createShallowCopy());
        for (IChatComponent child : node.getSiblings()) {
            copy.appendSibling(rewriteTextTree(child, start, end, target, cursor, false));
        }
        return copy;
    }

    /** Applies white to the message body while preserving the speaker's style and component metadata. */
    private static IChatComponent forceWhiteFromRawIndex(IChatComponent node, int boundary, int[] cursor,
            boolean root) {
        String raw = ((ChatComponentText) node).getChatComponentText_TextValue();
        int offset = cursor[0];
        int nodeEnd = offset + raw.length();
        cursor[0] = nodeEnd;
        int split = boundary <= offset ? 0 : boundary >= nodeEnd ? raw.length() : boundary - offset;

        String prefix = raw.substring(0, split);
        String body = forceWhiteLegacyColors(raw.substring(split));
        boolean bodyStartsHere = split < raw.length() || boundary <= offset;
        String initialText = bodyStartsHere && split == 0 ? body : prefix;
        IChatComponent copy = root ? new SharedText(initialText) : new ChatComponentText(initialText);
        copy.setChatStyle(node.getChatStyle().createShallowCopy());
        if (bodyStartsHere && split == 0) copy.getChatStyle().setColor(EnumChatFormatting.WHITE);
        if (split > 0 && split < raw.length()) {
            ChatComponentText whiteSuffix = new ChatComponentText(body);
            whiteSuffix.setChatStyle(node.getChatStyle().createShallowCopy());
            whiteSuffix.getChatStyle().setColor(EnumChatFormatting.WHITE);
            copy.appendSibling(whiteSuffix);
        }
        for (IChatComponent child : node.getSiblings()) {
            copy.appendSibling(forceWhiteFromRawIndex(child, boundary, cursor, false));
        }
        return copy;
    }

    /** Embedded legacy color changes must not turn a disguised speaker's chat body gray again. */
    private static String forceWhiteLegacyColors(String text) {
        if (text == null || text.indexOf('\u00a7') < 0) return text;
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '\u00a7' && i + 1 < text.length()) {
                char code = text.charAt(i + 1);
                char lower = Character.toLowerCase(code);
                if ((lower >= '0' && lower <= '9') || (lower >= 'a' && lower <= 'f')) {
                    result.append('\u00a7').append('f');
                    i++;
                    continue;
                }
            }
            result.append(current);
        }
        return result.toString();
    }

    private static String replaceRawRange(String raw, int from, int to, String target) {
        StringBuilder formats = new StringBuilder();
        // Restore formatting changes that originally occurred inside the name for the following text.
        for (int i = from; i + 1 < to; i++) {
            if (raw.charAt(i) == '\u00A7') formats.append(raw.charAt(i)).append(raw.charAt(++i));
        }
        return raw.substring(0, from) + target + formats + raw.substring(to);
    }

    private static int indexOfName(String visible, String name, int from) {
        for (int i = from; i <= visible.length() - name.length(); i++) {
            if (visible.regionMatches(true, i, name, 0, name.length())
                    && (i == 0 || !usernameChar(visible.charAt(i - 1)))
                    && (i + name.length() == visible.length() || !usernameChar(visible.charAt(i + name.length())))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean usernameChar(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_';
    }

    private static <T extends IChatComponent> T copyStyleAndSiblings(IChatComponent original, T copy) {
        copy.setChatStyle(original.getChatStyle().createShallowCopy());
        for (IChatComponent child : original.getSiblings()) copy.appendSibling(child.createCopy());
        return copy;
    }

    private static Object[] copyArgs(Object[] args) {
        Object[] result = args.clone();
        for (int i = 0; i < result.length; i++) {
            if (result[i] instanceof IChatComponent) result[i] = ((IChatComponent) result[i]).createCopy();
        }
        return result;
    }

    /** Marker survives delegation/copies so aliases that match another peer's real name cannot cascade. */
    private interface SharedChatDisplay {}

    private static final class SharedText extends ChatComponentText implements SharedChatDisplay {
        SharedText(String text) { super(text); }
        @Override public ChatComponentText createCopy() {
            return copyStyleAndSiblings(this, new SharedText(getChatComponentText_TextValue()));
        }
    }

    private static final class SharedTranslation extends ChatComponentTranslation implements SharedChatDisplay {
        SharedTranslation(String key, Object[] args) { super(key, args); }
        @Override public ChatComponentTranslation createCopy() {
            return copyStyleAndSiblings(this, new SharedTranslation(getKey(), copyArgs(getFormatArgs())));
        }
    }

    private static final class VisibleText {
        final String text;
        final int[] rawIndex;

        VisibleText(String raw) {
            StringBuilder visible = new StringBuilder();
            rawIndex = new int[raw.length()];
            for (int i = 0; i < raw.length(); i++) {
                if (raw.charAt(i) == '\u00A7' && i + 1 < raw.length()) { i++; continue; }
                rawIndex[visible.length()] = i;
                visible.append(raw.charAt(i));
            }
            text = visible.toString();
        }
    }
}
