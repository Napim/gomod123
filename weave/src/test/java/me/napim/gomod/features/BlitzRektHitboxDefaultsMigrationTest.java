package me.napim.gomod.features;

import com.google.gson.JsonObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BlitzRektHitboxDefaultsMigrationTest {

    @Test
    public void migratesMissingKeysToCalibratedDefaults() {
        JsonObject config = new JsonObject();
        assertTrue(BlitzRektHitboxDefaultsMigration.migrate(config));
        assertEquals(BlitzRektHitboxDefaultsMigration.CURRENT_VERSION,
                BlitzRektHitboxDefaultsMigration.readVersion(config));
        assertEquals(-0.2D, config.get("blitzRektHitboxPreviewLeft").getAsDouble(), 1.0E-9D);
        assertEquals(-0.2D, config.get("blitzRektHitboxPreviewRight").getAsDouble(), 1.0E-9D);
        assertEquals(-0.175D, config.get("blitzRektHitboxPreviewUp").getAsDouble(), 1.0E-9D);
        assertEquals(-0.21D, config.get("blitzRektHitboxPreviewDown").getAsDouble(), 1.0E-9D);
    }

    @Test
    public void migratesLegacyZeroDefaultsToCalibratedDefaults() {
        JsonObject config = new JsonObject();
        config.addProperty("blitzRektHitboxPreviewLeft", 0.0D);
        config.addProperty("blitzRektHitboxPreviewRight", 0.0D);
        config.addProperty("blitzRektHitboxPreviewUp", 0.0D);
        config.addProperty("blitzRektHitboxPreviewDown", 0.0D);

        assertTrue(BlitzRektHitboxDefaultsMigration.migrate(config));
        assertEquals(-0.2D, config.get("blitzRektHitboxPreviewLeft").getAsDouble(), 1.0E-9D);
        assertEquals(-0.2D, config.get("blitzRektHitboxPreviewRight").getAsDouble(), 1.0E-9D);
        assertEquals(-0.175D, config.get("blitzRektHitboxPreviewUp").getAsDouble(), 1.0E-9D);
        assertEquals(-0.21D, config.get("blitzRektHitboxPreviewDown").getAsDouble(), 1.0E-9D);
        assertFalse(BlitzRektHitboxDefaultsMigration.migrate(config));
    }

    @Test
    public void preservesCustomNonZeroValues() {
        JsonObject config = new JsonObject();
        config.addProperty("blitzRektHitboxPreviewLeft", -0.15D);
        config.addProperty("blitzRektHitboxPreviewRight", -0.15D);
        config.addProperty("blitzRektHitboxPreviewUp", -0.10D);
        config.addProperty("blitzRektHitboxPreviewDown", -0.25D);

        assertTrue(BlitzRektHitboxDefaultsMigration.migrate(config));
        assertEquals(-0.15D, config.get("blitzRektHitboxPreviewLeft").getAsDouble(), 1.0E-9D);
        assertEquals(-0.15D, config.get("blitzRektHitboxPreviewRight").getAsDouble(), 1.0E-9D);
        assertEquals(-0.10D, config.get("blitzRektHitboxPreviewUp").getAsDouble(), 1.0E-9D);
        assertEquals(-0.25D, config.get("blitzRektHitboxPreviewDown").getAsDouble(), 1.0E-9D);
    }

    @Test
    public void doesNotOverwriteExplicitZeroAfterMigration() {
        JsonObject config = new JsonObject();
        config.addProperty("blitzRektHitboxPreviewDefaultsVersion", BlitzRektHitboxDefaultsMigration.CURRENT_VERSION);
        config.addProperty("blitzRektHitboxPreviewLeft", 0.0D);
        config.addProperty("blitzRektHitboxPreviewRight", 0.0D);
        config.addProperty("blitzRektHitboxPreviewUp", 0.0D);
        config.addProperty("blitzRektHitboxPreviewDown", 0.0D);

        assertFalse(BlitzRektHitboxDefaultsMigration.migrate(config));
        assertEquals(0.0D, config.get("blitzRektHitboxPreviewLeft").getAsDouble(), 1.0E-9D);
        assertEquals(0.0D, config.get("blitzRektHitboxPreviewDown").getAsDouble(), 1.0E-9D);
    }
}
