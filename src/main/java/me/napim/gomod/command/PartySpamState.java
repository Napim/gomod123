package me.napim.gomod.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure-Java state machine and Hypixel chat-matching logic for {@code /pspam}.
 * Kept free of Minecraft types so it can be unit-tested directly.
 */
final class PartySpamState {
    static final long SUPPRESSION_GRACE_MS = 2000L;
    private static final Pattern FORMATTING_CODES = Pattern.compile("\u00A7[0-9a-fk-orA-FK-OR]");
    private static final Pattern PURE_DASH_DIVIDER = Pattern.compile("^-{20,}$");

    private final Object lock = new Object();
    private boolean running = false;
    private boolean invitePhase = true;
    private int targetIndex = 0;
    private int invitesSent = 0;
    private long stoppedAtMs = 0L;
    private String lastInvitedTargetKey = null;
    private String lastInvitedTargetDisplay = null;
    private final Map<String, String> activeSpamTargets = new LinkedHashMap<>();
    private final Set<String> recentTargetKeys = new HashSet<>();

    enum CommandOutcome {
        USAGE,
        NOT_RUNNING,
        RUNNING,
        STOPPED
    }

    static final class CommandResult {
        final CommandOutcome outcome;
        final List<String> targets;
        final int sentCount;
        final boolean shouldStartScheduler;
        final boolean shouldStopScheduler;

        CommandResult(
                CommandOutcome outcome,
                List<String> targets,
                int sentCount,
                boolean shouldStartScheduler,
                boolean shouldStopScheduler) {
            this.outcome = outcome;
            this.targets = targets;
            this.sentCount = sentCount;
            this.shouldStartScheduler = shouldStartScheduler;
            this.shouldStopScheduler = shouldStopScheduler;
        }
    }

    static final class DispatchStep {
        final boolean sendInvite;
        final String target;
        final List<String> snapshotTargets;
        final int snapshotSent;

        DispatchStep(boolean sendInvite, String target, List<String> snapshotTargets, int snapshotSent) {
            this.sendInvite = sendInvite;
            this.target = target;
            this.snapshotTargets = snapshotTargets;
            this.snapshotSent = snapshotSent;
        }
    }

    static final class ChatDecision {
        static final ChatDecision PASS = new ChatDecision(false, false, null, null, null, null, 0);
        static final ChatDecision SUPPRESS_ONLY = new ChatDecision(true, false, null, null, null, null, 0);

        final boolean suppress;
        final boolean shouldStopScheduler;
        final List<String> runningTargets;
        final List<String> stoppedTargets;
        final String stoppedReason;
        final String offlineRemovedTarget;
        final int sentCount;

        ChatDecision(
                boolean suppress,
                boolean shouldStopScheduler,
                List<String> runningTargets,
                List<String> stoppedTargets,
                String stoppedReason,
                String offlineRemovedTarget,
                int sentCount) {
            this.suppress = suppress;
            this.shouldStopScheduler = shouldStopScheduler;
            this.runningTargets = runningTargets;
            this.stoppedTargets = stoppedTargets;
            this.stoppedReason = stoppedReason;
            this.offlineRemovedTarget = offlineRemovedTarget;
            this.sentCount = sentCount;
        }
    }

    CommandResult handleCommandArgs(String[] args, long nowMs) {
        synchronized (lock) {
            if (args == null || args.length == 0) {
                if (activeSpamTargets.isEmpty()) {
                    return new CommandResult(CommandOutcome.USAGE, Collections.emptyList(), invitesSent, false, false);
                }
                List<String> stoppedAll = new ArrayList<>(activeSpamTargets.values());
                int sent = invitesSent;
                activeSpamTargets.clear();
                stopLocked(nowMs);
                return new CommandResult(CommandOutcome.STOPPED, stoppedAll, sent, false, true);
            }

            if (args.length == 1 && "stop".equalsIgnoreCase(args[0] == null ? "" : args[0].trim())) {
                if (activeSpamTargets.isEmpty() && !running) {
                    return new CommandResult(CommandOutcome.NOT_RUNNING, Collections.emptyList(), invitesSent, false, false);
                }
                List<String> stoppedAll = new ArrayList<>(activeSpamTargets.values());
                int sent = invitesSent;
                activeSpamTargets.clear();
                stopLocked(nowMs);
                return new CommandResult(CommandOutcome.STOPPED, stoppedAll, sent, false, true);
            }

            boolean wasRunning = running;
            boolean wasEmpty = activeSpamTargets.isEmpty();
            List<String> started = new ArrayList<>();
            List<String> stopped = new ArrayList<>();

            for (String arg : args) {
                String playerName = arg == null ? "" : arg.trim();
                if (playerName.isEmpty() || "stop".equalsIgnoreCase(playerName)) {
                    continue;
                }
                String key = playerName.toLowerCase(Locale.ROOT);
                if (activeSpamTargets.containsKey(key)) {
                    stopped.add(activeSpamTargets.remove(key));
                    if (targetIndex >= activeSpamTargets.size()) {
                        targetIndex = 0;
                    }
                } else {
                    activeSpamTargets.put(key, playerName);
                    recentTargetKeys.add(key);
                    started.add(playerName);
                }
            }

            if (activeSpamTargets.isEmpty()) {
                int sent = invitesSent;
                stopLocked(nowMs);
                if (!stopped.isEmpty()) {
                    return new CommandResult(CommandOutcome.STOPPED, stopped, sent, false, true);
                }
                return new CommandResult(CommandOutcome.USAGE, Collections.emptyList(), sent, false, wasRunning);
            }

            if (wasEmpty) {
                invitesSent = 0;
                recentTargetKeys.clear();
                recentTargetKeys.addAll(activeSpamTargets.keySet());
            }
            boolean shouldStart = !running;
            running = true;
            stoppedAtMs = 0L;
            if (shouldStart) {
                invitePhase = true;
            }
            return new CommandResult(
                    CommandOutcome.RUNNING,
                    new ArrayList<>(activeSpamTargets.values()),
                    invitesSent,
                    shouldStart,
                    false);
        }
    }

    DispatchStep nextDispatchStep(long nowMs) {
        synchronized (lock) {
            if (!running || activeSpamTargets.isEmpty()) {
                stopLocked(nowMs);
                return null;
            }
            if (targetIndex >= activeSpamTargets.size()) {
                targetIndex = 0;
            }
            List<String> targets = new ArrayList<>(activeSpamTargets.values());
            String target = targets.get(targetIndex);
            boolean sendInvite = invitePhase;
            if (invitePhase) {
                invitePhase = false;
                invitesSent++;
                lastInvitedTargetKey = target.toLowerCase(Locale.ROOT);
                lastInvitedTargetDisplay = target;
            } else {
                invitePhase = true;
                targetIndex = (targetIndex + 1) % targets.size();
            }
            return new DispatchStep(sendInvite, target, targets, invitesSent);
        }
    }

    boolean stopOnDisconnect(long nowMs) {
        synchronized (lock) {
            if (!running && activeSpamTargets.isEmpty()) {
                return false;
            }
            activeSpamTargets.clear();
            stopLocked(nowMs);
            return true;
        }
    }

    ChatDecision handleIncomingChat(String rawMessage, long nowMs) {
        if (rawMessage == null) {
            return ChatDecision.PASS;
        }
        String plain = stripFormatting(rawMessage).trim();
        if (plain.isEmpty()) {
            return ChatDecision.PASS;
        }

        synchronized (lock) {
            if (!isSuppressionActiveLocked(nowMs)) {
                return ChatDecision.PASS;
            }

            if (PURE_DASH_DIVIDER.matcher(plain).matches()) {
                return ChatDecision.SUPPRESS_ONLY;
            }

            if (isOfflineOrNotFoundMessage(plain)) {
                return handleOfflineTargetLocked(nowMs);
            }

            if (isNotInPartyMessage(plain)
                    || isDisbandMessage(plain)
                    || isRateLimitMessage(plain)) {
                return ChatDecision.SUPPRESS_ONLY;
            }

            if (isInviteMessageForTrackedTargetLocked(plain)
                    || isAlreadyInvitedMessageForTrackedTargetLocked(plain)) {
                return ChatDecision.SUPPRESS_ONLY;
            }

            return ChatDecision.PASS;
        }
    }

    private ChatDecision handleOfflineTargetLocked(long nowMs) {
        if (!running || activeSpamTargets.isEmpty()) {
            return ChatDecision.SUPPRESS_ONLY;
        }
        String removedDisplay = null;
        if (lastInvitedTargetKey != null && activeSpamTargets.containsKey(lastInvitedTargetKey)) {
            removedDisplay = activeSpamTargets.remove(lastInvitedTargetKey);
        } else if (activeSpamTargets.size() == 1) {
            Map.Entry<String, String> only = activeSpamTargets.entrySet().iterator().next();
            removedDisplay = only.getValue();
            activeSpamTargets.clear();
        } else if (lastInvitedTargetDisplay != null) {
            removedDisplay = lastInvitedTargetDisplay;
        }

        if (targetIndex >= activeSpamTargets.size()) {
            targetIndex = 0;
        }

        if (removedDisplay == null) {
            return ChatDecision.SUPPRESS_ONLY;
        }

        int sent = invitesSent;
        if (activeSpamTargets.isEmpty()) {
            stopLocked(nowMs);
            return new ChatDecision(
                    true,
                    true,
                    null,
                    Collections.singletonList(removedDisplay),
                    "offline",
                    null,
                    sent);
        }

        return new ChatDecision(
                true,
                false,
                new ArrayList<>(activeSpamTargets.values()),
                null,
                null,
                removedDisplay,
                sent);
    }

    private void stopLocked(long nowMs) {
        if (running) {
            running = false;
            stoppedAtMs = nowMs;
        }
        invitePhase = true;
        targetIndex = 0;
        lastInvitedTargetKey = null;
        lastInvitedTargetDisplay = null;
    }

    private boolean isSuppressionActiveLocked(long nowMs) {
        if (running) {
            return true;
        }
        return stoppedAtMs > 0L && (nowMs - stoppedAtMs) <= SUPPRESSION_GRACE_MS;
    }

    private static boolean isOfflineOrNotFoundMessage(String plain) {
        return plain.equals("You cannot invite that player since they're not online.")
                || plain.equals("Couldn't find a player by that name!")
                || plain.equals("That player is not online!");
    }

    private static boolean isNotInPartyMessage(String plain) {
        return plain.equals("You are not in a party right now.");
    }

    private static boolean isDisbandMessage(String plain) {
        return plain.endsWith(" has disbanded the party!")
                || plain.equals("The party was disbanded because all invites expired and the party was empty.");
    }

    private static boolean isRateLimitMessage(String plain) {
        return plain.equals("Woah slow down, you're doing that too fast!");
    }

    private boolean isInviteMessageForTrackedTargetLocked(String plain) {
        int invitedIdx = plain.indexOf(" invited ");
        int toPartyIdx = plain.indexOf(" to the party!");
        if (invitedIdx < 0 || toPartyIdx <= invitedIdx) {
            return false;
        }
        String targetSegment = plain.substring(invitedIdx + " invited ".length(), toPartyIdx).toLowerCase(Locale.ROOT);
        return matchesTrackedTargetLocked(targetSegment);
    }

    private boolean isAlreadyInvitedMessageForTrackedTargetLocked(String plain) {
        String suffix = " has already been invited to the party.";
        if (!plain.endsWith(suffix)) {
            return false;
        }
        String targetSegment = plain.substring(0, plain.length() - suffix.length()).toLowerCase(Locale.ROOT);
        return matchesTrackedTargetLocked(targetSegment);
    }

    private boolean matchesTrackedTargetLocked(String targetSegmentLower) {
        for (String key : activeSpamTargets.keySet()) {
            if (containsWholeWord(targetSegmentLower, key)) {
                return true;
            }
        }
        for (String key : recentTargetKeys) {
            if (containsWholeWord(targetSegmentLower, key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsWholeWord(String haystackLower, String needleLower) {
        if (needleLower == null || needleLower.isEmpty()) {
            return false;
        }
        int from = 0;
        while (true) {
            int idx = haystackLower.indexOf(needleLower, from);
            if (idx < 0) {
                return false;
            }
            boolean leftOk = idx == 0 || !isUsernameChar(haystackLower.charAt(idx - 1));
            int end = idx + needleLower.length();
            boolean rightOk = end == haystackLower.length() || !isUsernameChar(haystackLower.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            from = idx + 1;
        }
    }

    private static boolean isUsernameChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    static String stripFormatting(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return FORMATTING_CODES.matcher(text).replaceAll("");
    }

    boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }

    List<String> getActiveTargets() {
        synchronized (lock) {
            return new ArrayList<>(activeSpamTargets.values());
        }
    }

    void seedActiveStateForTest(List<String> targets, String lastInvited, int sentCount) {
        synchronized (lock) {
            activeSpamTargets.clear();
            recentTargetKeys.clear();
            for (String target : targets) {
                String key = target.toLowerCase(Locale.ROOT);
                activeSpamTargets.put(key, target);
                recentTargetKeys.add(key);
            }
            running = !activeSpamTargets.isEmpty();
            stoppedAtMs = 0L;
            invitePhase = true;
            targetIndex = 0;
            invitesSent = sentCount;
            lastInvitedTargetDisplay = lastInvited;
            lastInvitedTargetKey = lastInvited == null ? null : lastInvited.toLowerCase(Locale.ROOT);
        }
    }

    void resetForTest() {
        synchronized (lock) {
            activeSpamTargets.clear();
            recentTargetKeys.clear();
            running = false;
            invitePhase = true;
            targetIndex = 0;
            invitesSent = 0;
            stoppedAtMs = 0L;
            lastInvitedTargetKey = null;
            lastInvitedTargetDisplay = null;
        }
    }
}
