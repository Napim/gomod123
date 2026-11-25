package me.napim.gomod.command;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class PartySpamCommandTest {
    private PartySpamState state;

    @Before
    public void setUp() {
        state = new PartySpamState();
    }

    @Test
    public void suppressesHypixelPartySpamMessagesWhileActive() {
        state.seedActiveStateForTest(Collections.singletonList("ILiveInAWall"), "ILiveInAWall", 5);
        long now = 10_000L;

        assertTrue(state.handleIncomingChat("-----------------------------------------------------", now).suppress);
        assertTrue(state.handleIncomingChat(
                "\u00A79[VIP] OnSeb \u00A7einvited \u00A7b[MVP+] ILiveInAWall \u00A7eto the party! They have 60 seconds to accept.",
                now).suppress);
        assertTrue(state.handleIncomingChat("[VIP] OnSeb has disbanded the party!", now).suppress);
        assertTrue(state.handleIncomingChat("You are not in a party right now.", now).suppress);
        assertTrue(state.handleIncomingChat("Woah slow down, you're doing that too fast!", now).suppress);
        assertTrue(state.handleIncomingChat("ILiveInAWall has already been invited to the party.", now).suppress);
        assertTrue(state.handleIncomingChat("[MVP+] ILiveInAWall has already been invited to the party.", now).suppress);
        assertFalse(state.handleIncomingChat("OtherPlayer has already been invited to the party.", now).suppress);
    }

    @Test
    public void keepsNonSpamMessagesAndDoesNotStopOnJoin() {
        state.seedActiveStateForTest(Collections.singletonList("bitez1"), "bitez1", 10);
        long now = 20_000L;

        PartySpamState.ChatDecision joinResult = state.handleIncomingChat("bitez1 joined the party.", now);
        assertFalse(joinResult.suppress);
        assertFalse(joinResult.shouldStopScheduler);
        assertTrue(state.isRunning());
        assertEquals(Collections.singletonList("bitez1"), state.getActiveTargets());

        assertFalse(state.handleIncomingChat("--------------  Guild: Message Of The Day  --------------", now).suppress);
        assertFalse(state.handleIncomingChat("[VIP] OnSeb: hello", now).suppress);
        assertFalse(state.handleIncomingChat(
                "[MVP+] SomeoneElse invited [VIP] RandomPlayer to the party! They have 60 seconds to accept.",
                now).suppress);
    }

    @Test
    public void stopsSingleTargetWhenOffline() {
        state.seedActiveStateForTest(Collections.singletonList("ILiveInAWall"), "ILiveInAWall", 12);
        long now = 30_000L;

        PartySpamState.ChatDecision decision = state.handleIncomingChat(
                "You cannot invite that player since they're not online.", now);
        assertTrue(decision.suppress);
        assertTrue(decision.shouldStopScheduler);
        assertFalse(state.isRunning());
        assertTrue(state.getActiveTargets().isEmpty());
        assertEquals(Collections.singletonList("ILiveInAWall"), decision.stoppedTargets);
        assertEquals("offline", decision.stoppedReason);
        assertEquals(12, decision.sentCount);
        assertNull(decision.offlineRemovedTarget);
    }

    @Test
    public void removesOnlyOfflineTargetWhenMultipleTargetsActive() {
        state.seedActiveStateForTest(Arrays.asList("ILiveInAWall", "bitez1"), "ILiveInAWall", 18);
        long now = 40_000L;

        PartySpamState.ChatDecision decision = state.handleIncomingChat(
                "You cannot invite that player since they're not online.", now);
        assertTrue(decision.suppress);
        assertFalse(decision.shouldStopScheduler);
        assertTrue(state.isRunning());
        assertEquals(Collections.singletonList("bitez1"), state.getActiveTargets());
        assertEquals("ILiveInAWall", decision.offlineRemovedTarget);
        assertEquals(Collections.singletonList("bitez1"), decision.runningTargets);
        assertNull(decision.stoppedTargets);
    }

    @Test
    public void graceWindowSuppressesTrailingLinesThenExpires() {
        state.seedActiveStateForTest(Collections.singletonList("ILiveInAWall"), "ILiveInAWall", 3);
        long stopTime = 50_000L;

        assertTrue(state.stopOnDisconnect(stopTime));
        assertFalse(state.isRunning());

        assertTrue(state.handleIncomingChat("[VIP] OnSeb has disbanded the party!", stopTime + 500L).suppress);
        assertTrue(state.handleIncomingChat("-----------------------------------------------------", stopTime + 1500L).suppress);
        assertFalse(state.handleIncomingChat(
                "[VIP] OnSeb has disbanded the party!", stopTime + PartySpamState.SUPPRESSION_GRACE_MS + 1L).suppress);
    }

    @Test
    public void stopCommandAndEmptyArgsStopAllWithoutAddingStopTarget() {
        state.seedActiveStateForTest(Arrays.asList("ILiveInAWall", "bitez1"), "bitez1", 7);

        PartySpamState.CommandResult stopResult = state.handleCommandArgs(new String[]{"stop"}, 60_000L);
        assertEquals(PartySpamState.CommandOutcome.STOPPED, stopResult.outcome);
        assertTrue(stopResult.shouldStopScheduler);
        assertEquals(Arrays.asList("ILiveInAWall", "bitez1"), stopResult.targets);
        assertFalse(state.isRunning());
        assertTrue(state.getActiveTargets().isEmpty());

        // Calling /pspam stop when already stopped must not add "stop" as a target.
        PartySpamState.CommandResult alreadyStopped = state.handleCommandArgs(new String[]{"stop"}, 61_000L);
        assertEquals(PartySpamState.CommandOutcome.NOT_RUNNING, alreadyStopped.outcome);
        assertFalse(state.isRunning());
        assertTrue(state.getActiveTargets().isEmpty());

        // Calling /pspam with no args while running stops all active targets.
        state.seedActiveStateForTest(Collections.singletonList("ILiveInAWall"), "ILiveInAWall", 9);
        PartySpamState.CommandResult emptyStop = state.handleCommandArgs(new String[0], 62_000L);
        assertEquals(PartySpamState.CommandOutcome.STOPPED, emptyStop.outcome);
        assertTrue(emptyStop.shouldStopScheduler);
        assertFalse(state.isRunning());
        assertTrue(state.getActiveTargets().isEmpty());

        // Calling /pspam with no args while idle prints usage.
        PartySpamState.CommandResult usage = state.handleCommandArgs(new String[0], 63_000L);
        assertEquals(PartySpamState.CommandOutcome.USAGE, usage.outcome);
    }

    @Test
    public void dispatchStepAlternatesInviteAndDisbandAndTracksCounts() {
        PartySpamState.CommandResult started = state.handleCommandArgs(new String[]{"ILiveInAWall", "bitez1"}, 70_000L);
        assertEquals(PartySpamState.CommandOutcome.RUNNING, started.outcome);
        assertTrue(started.shouldStartScheduler);

        PartySpamState.DispatchStep s1 = state.nextDispatchStep(70_100L);
        assertNotNull(s1);
        assertTrue(s1.sendInvite);
        assertEquals("ILiveInAWall", s1.target);
        assertEquals(1, s1.snapshotSent);

        PartySpamState.DispatchStep s2 = state.nextDispatchStep(70_310L);
        assertNotNull(s2);
        assertFalse(s2.sendInvite);
        assertEquals("ILiveInAWall", s2.target);
        assertEquals(1, s2.snapshotSent);

        PartySpamState.DispatchStep s3 = state.nextDispatchStep(70_520L);
        assertNotNull(s3);
        assertTrue(s3.sendInvite);
        assertEquals("bitez1", s3.target);
        assertEquals(2, s3.snapshotSent);
    }
}
