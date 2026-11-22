package me.napim.gomod.features;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure policy tests for Inventory QoL chest apple and stick 2+ surplus filters. */
public final class UselessItemPolicyTest {

    @Test
    public void appleChestFilterTriggersAtTwoOrMoreCarriedWhenEnabled() {
        assertFalse(UselessItemPolicy.shouldHighlightExtraChestApple(false, 0));
        assertFalse(UselessItemPolicy.shouldHighlightExtraChestApple(false, 2));
        assertFalse(UselessItemPolicy.shouldHighlightExtraChestApple(false, 5));
        assertFalse(UselessItemPolicy.shouldHighlightExtraChestApple(true, 0));
        assertFalse(UselessItemPolicy.shouldHighlightExtraChestApple(true, 1));
        assertTrue(UselessItemPolicy.shouldHighlightExtraChestApple(true, 2));
        assertTrue(UselessItemPolicy.shouldHighlightExtraChestApple(true, 4));
    }

    @Test
    public void stickChestFilterTriggersAtTwoOrMoreCarriedWhenEnabled() {
        assertFalse(UselessItemPolicy.shouldHighlightExtraChestStick(false, 0));
        assertFalse(UselessItemPolicy.shouldHighlightExtraChestStick(false, 2));
        assertFalse(UselessItemPolicy.shouldHighlightExtraChestStick(false, 5));
        assertFalse(UselessItemPolicy.shouldHighlightExtraChestStick(true, 0));
        assertFalse(UselessItemPolicy.shouldHighlightExtraChestStick(true, 1));
        assertTrue(UselessItemPolicy.shouldHighlightExtraChestStick(true, 2));
        assertTrue(UselessItemPolicy.shouldHighlightExtraChestStick(true, 6));
    }
}
