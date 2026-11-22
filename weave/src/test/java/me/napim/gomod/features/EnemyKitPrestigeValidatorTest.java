package me.napim.gomod.features;

import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.*;

/** Regression tests for Prestige I -> Prestige II enemy kit validation against Hypixel API data. */
public final class EnemyKitPrestigeValidatorTest {
    private static final String P1 = EnemyKitPrestigeValidator.PRESTIGE_ONE_STAR;
    private static final String P2 = EnemyKitPrestigeValidator.PRESTIGE_TWO_STARS;

    @Before
    public void resetMatch() {
        EnemyKitPrestigeValidator.resetForNewMatch();
    }

    @Test
    public void extractsAndUpgradesOnlyPrestigeOneKits() {
        assertEquals("Pigman", EnemyKitPrestigeValidator.extractPrestigeOneKitName("Pigman " + P1));
        assertEquals("Shadow Knight", EnemyKitPrestigeValidator.extractPrestigeOneKitName("Shadow Knight " + P1));
        assertEquals("Hype Train", EnemyKitPrestigeValidator.extractPrestigeOneKitName("Hype Train " + P1));
        assertNull(EnemyKitPrestigeValidator.extractPrestigeOneKitName("Pigman " + P2));
        assertNull(EnemyKitPrestigeValidator.extractPrestigeOneKitName("Pigman X"));
        assertNull(EnemyKitPrestigeValidator.extractPrestigeOneKitName("Knight IX"));
        assertNull(EnemyKitPrestigeValidator.extractPrestigeOneKitName("Pigman"));
        assertNull(EnemyKitPrestigeValidator.extractPrestigeOneKitName(null));

        assertEquals("Pigman " + P2, EnemyKitPrestigeValidator.upgradePrestigeOneToTwo("Pigman " + P1));
        assertEquals("Shadow Knight " + P2, EnemyKitPrestigeValidator.upgradePrestigeOneToTwo("Shadow Knight " + P1));
        assertEquals("Pigman " + P2, EnemyKitPrestigeValidator.upgradePrestigeOneToTwo("Pigman " + P2));
        assertEquals("Pigman X", EnemyKitPrestigeValidator.upgradePrestigeOneToTwo("Pigman X"));
    }

    @Test
    public void readsBlitzKitPrestigeFromHypixelPlayerJson() {
        JsonObject player = playerWithKitPrestige(UUID.randomUUID(), "PigmanPlayer", "ppigman", 2);
        assertEquals(2, EnemyKitPrestigeValidator.readBlitzKitPrestige(player, "Pigman"));
        assertEquals(0, EnemyKitPrestigeValidator.readBlitzKitPrestige(player, "Knight"));

        JsonObject spacedKitPlayer = playerWithKitPrestige(UUID.randomUUID(), "ShadowUser", "pshadow knight", 2);
        assertEquals(2, EnemyKitPrestigeValidator.readBlitzKitPrestige(spacedKitPlayer, "Shadow Knight"));

        JsonObject hypePlayer = playerWithKitPrestige(UUID.randomUUID(), "HypeUser", "phype train", 2);
        assertEquals(2, EnemyKitPrestigeValidator.readBlitzKitPrestige(hypePlayer, "Hype Train"));

        JsonObject emptyPlayer = new JsonObject();
        assertEquals(-1, EnemyKitPrestigeValidator.readBlitzKitPrestige(emptyPlayer, "Pigman"));
        assertEquals(-1, EnemyKitPrestigeValidator.readBlitzKitPrestige(null, "Pigman"));
    }

    @Test
    public void cacheDecisionReusesPermanentPrestigeTwoAndFreshPrestigeOne() {
        JsonObject p2 = playerWithKitPrestige(UUID.randomUUID(), "P2Guy", "ppigman", 2);
        JsonObject p1 = playerWithKitPrestige(UUID.randomUUID(), "P1Guy", "ppigman", 1);

        // Even an older cached profile with P2 immediately upgrades (prestige is permanent).
        assertEquals(EnemyKitPrestigeValidator.CacheDecision.UPGRADE_TO_P2,
                EnemyKitPrestigeValidator.evaluateCachedProfiles("Pigman",
                        Collections.singletonList(p2), Collections.<JsonObject>emptyList()));

        // Fresh cached profile with P1 confirms they really are P1 (avoid redundant API call).
        assertEquals(EnemyKitPrestigeValidator.CacheDecision.CONFIRMED_BELOW_P2,
                EnemyKitPrestigeValidator.evaluateCachedProfiles("Pigman",
                        Collections.singletonList(p1), Collections.singletonList(p1)));

        // Stale cached profile with P1 (not in fresh list) triggers a re-fetch in case they upgraded.
        assertEquals(EnemyKitPrestigeValidator.CacheDecision.NEEDS_API_FETCH,
                EnemyKitPrestigeValidator.evaluateCachedProfiles("Pigman",
                        Collections.singletonList(p1), Collections.<JsonObject>emptyList()));

        // No cache at all triggers an API fetch.
        assertEquals(EnemyKitPrestigeValidator.CacheDecision.NEEDS_API_FETCH,
                EnemyKitPrestigeValidator.evaluateCachedProfiles("Pigman",
                        Collections.<JsonObject>emptyList(), Collections.<JsonObject>emptyList()));
    }

    @Test
    public void synchronouslyUpgradesPrestigeOneFromHypixelPlayerDataCache() {
        UUID p2Uuid = UUID.randomUUID();
        String p2Name = "CachedPigmanP2_" + p2Uuid.toString().substring(0, 6);
        HypixelPlayerDataCache.ingest(p2Name, playerWithKitPrestige(p2Uuid, p2Name, "ppigman", 2));

        // Cache hit by name upgrades Pigman ✫ -> Pigman ✫✫ immediately.
        String upgradedByName = EnemyKitPrestigeValidator.validateAndUpgradeOrSchedule(
                p2Name, p2Name, Collections.singletonList(p2Name), null, "Pigman " + P1, null);
        assertEquals("Pigman " + P2, upgradedByName);

        // Cache hit by UUID (e.g. different nick/alias) also upgrades immediately.
        String upgradedByUuid = EnemyKitPrestigeValidator.validateAndUpgradeOrSchedule(
                "SomeNick123", "SomeNick123", Collections.singletonList("SomeNick123"), p2Uuid, "Pigman " + P1, null);
        assertEquals("Pigman " + P2, upgradedByUuid);

        // Fresh cache confirming P1 leaves Pigman ✫ unchanged.
        UUID p1Uuid = UUID.randomUUID();
        String p1Name = "CachedPigmanP1_" + p1Uuid.toString().substring(0, 6);
        HypixelPlayerDataCache.ingest(p1Name, playerWithKitPrestige(p1Uuid, p1Name, "ppigman", 1));
        String staysP1 = EnemyKitPrestigeValidator.validateAndUpgradeOrSchedule(
                p1Name, p1Name, Collections.singletonList(p1Name), p1Uuid, "Pigman " + P1, null);
        assertEquals("Pigman " + P1, staysP1);
    }

    private static JsonObject playerWithKitPrestige(UUID uuid, String displayName, String prestigeKey, int prestige) {
        JsonObject hg = new JsonObject();
        hg.addProperty(prestigeKey, prestige);
        JsonObject stats = new JsonObject();
        stats.add("HungerGames", hg);
        JsonObject player = new JsonObject();
        player.addProperty("uuid", uuid.toString().replace("-", ""));
        player.addProperty("displayname", displayName);
        player.add("stats", stats);
        return player;
    }
}
