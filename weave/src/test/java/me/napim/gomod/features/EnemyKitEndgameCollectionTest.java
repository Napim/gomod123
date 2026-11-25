package me.napim.gomod.features;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Regression tests ensuring both early-game gear scans and live spectator Teleporter reads feed
 * Detailed end-game score kit lookup even when TAB "Show enemy players' kits" is disabled.
 */
public final class EnemyKitEndgameCollectionTest {
    private boolean savedLoaded;
    private boolean savedShowEnemyKits;
    private boolean savedExtendedResults;
    private boolean savedKitDetectionActive;

    @BeforeClass
    public static void bootstrapMinecraftRegistries() throws Exception {
        Class<?> bootstrap = Class.forName("net.minecraft.init.Bootstrap");
        try {
            bootstrap.getMethod("register").invoke(null);
        } catch (NoSuchMethodException e) {
            bootstrap.getMethod("func_151354_b").invoke(null);
        }
    }

    @Before
    public void setUp() throws Exception {
        savedLoaded = getStaticBoolean(ConfigManager.class, "loaded");
        savedShowEnemyKits = getStaticBoolean(ConfigManager.class, "killCounterShowEnemyKits");
        savedExtendedResults = getStaticBoolean(ConfigManager.class, "blitzExtendedResultsEnabled");
        savedKitDetectionActive = getStaticBoolean(KillCounter.class, "kitDetectionActive");

        setStaticBoolean(ConfigManager.class, "loaded", true);
        clearEnemyKits();
        EnemyKitPrestigeValidator.resetForNewMatch();
    }

    @After
    public void tearDown() throws Exception {
        clearEnemyKits();
        EnemyKitPrestigeValidator.resetForNewMatch();
        setStaticBoolean(KillCounter.class, "kitDetectionActive", savedKitDetectionActive);
        setStaticBoolean(ConfigManager.class, "killCounterShowEnemyKits", savedShowEnemyKits);
        setStaticBoolean(ConfigManager.class, "blitzExtendedResultsEnabled", savedExtendedResults);
        setStaticBoolean(ConfigManager.class, "loaded", savedLoaded);
    }

    @Test
    public void collectsBothEarlyGearAndTeleporterKitsForEndgameWhenTabEnemyKitsOff() throws Exception {
        setStaticBoolean(ConfigManager.class, "killCounterShowEnemyKits", false);
        setStaticBoolean(ConfigManager.class, "blitzExtendedResultsEnabled", true);

        assertFalse(KillCounter.isShowEnemyKits());
        assertTrue(KillCounter.shouldCollectEnemyKits());

        // 1. Beginning-of-game gear scan path
        setStaticBoolean(KillCounter.class, "kitDetectionActive", true);
        KillCounter.processPlayerGear("GearPlayer", new String[]{"Guardian's Stone Axe (VI)"});
        assertEquals("Guardian VI", KillCounter.getTrackedKitForResults("GearPlayer"));

        // 2. Spectator Teleporter path
        boolean observed = KillCounter.observeEnemyKitFromTeleporter(
                "TelePlayer",
                new String[]{"§bTelePlayer", "§7Kit: §eSpeleologist IX"});
        assertTrue(observed);
        assertEquals("Speleologist IX", KillCounter.getTrackedKitForResults("TelePlayer"));

        // 3. TAB display gate must remain off when Show enemy players' kits is disabled
        assertEquals("", invokeGetEnemyPlayerKit("GearPlayer"));
        assertEquals("", invokeGetEnemyPlayerKit("TelePlayer"));
    }

    @Test
    public void skipsCollectionWhenBothTabEnemyKitsAndEndgameResultsAreOff() throws Exception {
        setStaticBoolean(ConfigManager.class, "killCounterShowEnemyKits", false);
        setStaticBoolean(ConfigManager.class, "blitzExtendedResultsEnabled", false);

        assertFalse(KillCounter.isShowEnemyKits());
        assertFalse(KillCounter.shouldCollectEnemyKits());

        setStaticBoolean(KillCounter.class, "kitDetectionActive", true);
        KillCounter.processPlayerGear("GearPlayerOff", new String[]{"Guardian's Stone Axe (VI)"});
        assertEquals("", KillCounter.getTrackedKitForResults("GearPlayerOff"));

        boolean observed = KillCounter.observeEnemyKitFromTeleporter(
                "TelePlayerOff",
                new String[]{"§bTelePlayerOff", "§7Kit: §eSpeleologist IX"});
        assertFalse(observed);
        assertEquals("", KillCounter.getTrackedKitForResults("TelePlayerOff"));
    }

    @SuppressWarnings("unchecked")
    private static void clearEnemyKits() throws Exception {
        Field f = KillCounter.class.getDeclaredField("enemyPlayerKits");
        f.setAccessible(true);
        ((Map<String, String>) f.get(null)).clear();
    }

    private static String invokeGetEnemyPlayerKit(String playerName) throws Exception {
        Method m = KillCounter.class.getDeclaredMethod("getEnemyPlayerKit", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, playerName);
    }

    private static boolean getStaticBoolean(Class<?> owner, String fieldName) throws Exception {
        Field f = owner.getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    private static void setStaticBoolean(Class<?> owner, String fieldName, boolean value) throws Exception {
        Field f = owner.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.setBoolean(null, value);
    }
}
