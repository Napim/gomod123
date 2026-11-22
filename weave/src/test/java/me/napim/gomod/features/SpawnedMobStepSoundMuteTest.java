package me.napim.gomod.features;

import me.napim.gomod.util.GameContext;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link SpawnedMobStepSoundMute} step-sound filtering, strict Blitz context
 * gating, and positional entity tie-breaking.
 */
public final class SpawnedMobStepSoundMuteTest {

    @Test
    public void matchesOnlySteppingSoundsAndLowStepVolume() {
        assertTrue(SpawnedMobStepSoundMute.isStepSoundPath("step.grass"));
        assertTrue(SpawnedMobStepSoundMute.isStepSoundPath("step.stone"));
        assertTrue(SpawnedMobStepSoundMute.isStepSoundPath("step.wood"));
        assertTrue(SpawnedMobStepSoundMute.isStepSoundPath("step.cloth"));
        assertTrue(SpawnedMobStepSoundMute.isStepSoundPath("step.ladder"));
        assertTrue(SpawnedMobStepSoundMute.isStepSoundPath("minecraft:step.sand"));
        assertTrue(SpawnedMobStepSoundMute.isStepSoundPath("STEP.SNOW"));

        // Non-step sounds on Ocelots, Witches, and blocks must never be matched.
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("mob.cat.meow"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("mob.cat.purr"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("mob.cat.hitt"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("mob.witch.ambient"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("mob.witch.hurt"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("mob.witch.death"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("mob.witch.drink"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("mob.witch.throw"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("gomod:witch.ambient1"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("random.drink"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("random.bow"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("game.potion.smash"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("game.neutral.swim"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("dig.grass"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath("mob.wolf.step"));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath(""));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundPath(null));

        // Entity step volumes are <= 0.15F; block placement (e.g. wool/ladder) uses 0.8F.
        assertTrue(SpawnedMobStepSoundMute.isStepSoundVolume(0.15F));
        assertTrue(SpawnedMobStepSoundMute.isStepSoundVolume(0.075F));
        assertTrue(SpawnedMobStepSoundMute.isStepSoundVolume(0.045F));
        assertTrue(SpawnedMobStepSoundMute.isStepSoundVolume(0.50F));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundVolume(0.8F));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundVolume(1.0F));
        assertFalse(SpawnedMobStepSoundMute.isStepSoundVolume(-0.1F));
    }

    @Test
    public void strictBlitzContextAcceptsBlitzAndRejectsSkyWarsLobbiesAndReplays() {
        assertTrue(GameContext.isStrictBlitzMatchFromSidebar(
                "blitz sg", "blitz",
                Arrays.asList("next event:", "kills: 1", "players: 10", "kit: troll"),
                true, false, false, true, false, false));

        assertTrue(GameContext.isStrictBlitzMatchFromSidebar(
                "blitz sg", "blitz",
                Arrays.asList("game over!", "kills: 4", "kit: knight"),
                true, false, false, false, true, false));

        // Even if title lacked Blitz, Blitz-only rows like Taunt/Kit qualify.
        assertTrue(GameContext.isStrictBlitzMatchFromSidebar(
                "game", "board",
                Arrays.asList("taunt: unused", "kills: 0", "kit: meatmaster"),
                true, false, false, true, false, false));

        // Reject SkyWars even when loose fingerprint might flag blitzGameActive=true.
        assertFalse(GameContext.isStrictBlitzMatchFromSidebar(
                "skywars", "skywars",
                Arrays.asList("kills: 2", "players left: 4"),
                true, false, false, true, false, false));

        // Live Blitz match with Blitz Star on sidebar (even if pregame flag fired) still counts as Blitz.
        assertTrue(GameContext.isStrictBlitzMatchFromSidebar(
                "blitz sg", "blitz",
                Arrays.asList("blitz star: 02:30", "kills: 1", "players: 8", "kit: troll x"),
                true, false, true, true, false, false));

        // Reject lobbies, pregames (not active), replays, and singleplayer/non-Hypixel.
        assertFalse(GameContext.isStrictBlitzMatchFromSidebar(
                "blitz survival games", "lobby",
                Collections.singletonList("lobby #1"),
                true, true, false, true, false, false));
        assertFalse(GameContext.isStrictBlitzMatchFromSidebar(
                "blitz sg", "blitz",
                Arrays.asList("starting in 00:05", "players: 8/12"),
                true, false, true, false, false, false));
        assertFalse(GameContext.isStrictBlitzMatchFromSidebar(
                "replay", "replay",
                Arrays.asList("replay from xyz", "kills: 2", "kit: troll"),
                true, false, false, true, false, true));
        assertFalse(GameContext.isStrictBlitzMatchFromSidebar(
                "blitz sg", "blitz",
                Arrays.asList("taunt: unused", "kit: troll"),
                false, false, false, true, false, false));
    }

    @Test
    public void positionalDecisionMutesSpawnedMobWithoutHidingCloserEnemySteps() {
        // Alone near sound: mute.
        assertTrue(SpawnedMobStepSoundMute.selectMuteDecision(0.04D, Double.MAX_VALUE));
        // Spawned mob much closer to sound than enemy player: mute.
        assertTrue(SpawnedMobStepSoundMute.selectMuteDecision(0.05D, 1.50D));
        // Enemy player closer to sound than spawned mob: preserve enemy footstep.
        assertFalse(SpawnedMobStepSoundMute.selectMuteDecision(1.40D, 0.04D));
        // Exact tie at same spot: err on preserving potential enemy footstep.
        assertFalse(SpawnedMobStepSoundMute.selectMuteDecision(0.10D, 0.10D));
        // Beyond match radius: never mute.
        assertFalse(SpawnedMobStepSoundMute.selectMuteDecision(
                SpawnedMobStepSoundMute.MAX_MATCH_RADIUS_SQ + 0.01D, Double.MAX_VALUE));
    }
}
