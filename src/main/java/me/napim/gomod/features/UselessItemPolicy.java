package me.napim.gomod.features;

/** Small, pure item-priority rules shared by the Inventory QoL overlay and its focused checks. */
final class UselessItemPolicy {
    private static final double DAMAGE_EPSILON = 0.0001D;
    private static final int HEALTHY_BOW_PRIORITY_BONUS = 1_000_000;
    static final int EXTRA_CHEST_APPLE_THRESHOLD = 2;
    static final int EXTRA_CHEST_STICK_THRESHOLD = 2;

    private UselessItemPolicy() {
    }

    static boolean shouldHighlightWheat(boolean wheatOptionEnabled, boolean anotherFoodAvailable,
            boolean hasHorseOrDonkeyKit, boolean hasUsableBlock) {
        return hasUsableBlock || wheatOptionEnabled && (!anotherFoodAvailable || !hasHorseOrDonkeyKit);
    }

    /** Keep the first nine wheat in the carried inventory for a hay bale; only chest extras are surplus. */
    static boolean shouldHighlightExtraChestWheat(boolean wheatOptionEnabled, int carriedWheatCount) {
        return wheatOptionEnabled && carriedWheatCount >= 9;
    }

    /** Keep up to one carried apple; when enabled and 2+ are already carried, extra chest apples are surplus. */
    static boolean shouldHighlightExtraChestApple(boolean appleOptionEnabled, int carriedAppleCount) {
        return appleOptionEnabled && carriedAppleCount >= EXTRA_CHEST_APPLE_THRESHOLD;
    }

    /** Keep up to one carried unenchanted stick; when enabled and 2+ are already carried, extra chest sticks are surplus. */
    static boolean shouldHighlightExtraChestStick(boolean stickOptionEnabled, int carriedUnenchantedStickCount) {
        return stickOptionEnabled && carriedUnenchantedStickCount >= EXTRA_CHEST_STICK_THRESHOLD;
    }

    static boolean shouldHighlightSharpnessFish(boolean fishOptionEnabled, boolean anotherFoodAvailable) {
        return fishOptionEnabled && anotherFoodAvailable;
    }

    static boolean shouldHighlightAxeAsWorse(double axeDamage, double otherWeaponDamage, boolean otherWeaponIsSword) {
        if (otherWeaponDamage > axeDamage + DAMAGE_EPSILON) {
            return true;
        }
        return otherWeaponIsSword && Math.abs(otherWeaponDamage - axeDamage) <= DAMAGE_EPSILON;
    }

    /**
     * A melee option is dominated only when the other option does at least as much damage and preserves every
     * utility the candidate has. Fire Aspect and Knockback remain independent priorities, so a stronger weapon
     * without one of those utilities cannot make the candidate useless. Swords win equal-damage ties against axes
     * because they can block. Same-shape options with identical damage and utility levels use remaining durability
     * as the final tie-break; exact ties remain neutral.
     */
    static boolean shouldHighlightMeleeWeaponAsDominated(double candidateDamage, int candidateFire,
            int candidateKnockback, boolean candidateIsAxe, int candidateRemainingUses,
            double otherDamage, int otherFire, int otherKnockback, boolean otherIsSword,
            int otherRemainingUses) {
        if (!Double.isFinite(candidateDamage) || !Double.isFinite(otherDamage)
                || otherDamage + DAMAGE_EPSILON < candidateDamage
                || otherFire < candidateFire || otherKnockback < candidateKnockback) {
            return false;
        }
        if (candidateIsAxe && otherIsSword) {
            return true;
        }
        return otherDamage > candidateDamage + DAMAGE_EPSILON
                || otherFire > candidateFire || otherKnockback > candidateKnockback
                || Math.abs(otherDamage - candidateDamage) <= DAMAGE_EPSILON
                && otherFire == candidateFire && otherKnockback == candidateKnockback
                && otherRemainingUses > candidateRemainingUses;
    }

    /** A chest melee copy never wins an equal-priority tie against an item already in the inventory. */
    static boolean shouldHighlightChestMeleeDuplicate(boolean sameShapeAndTier, int candidateUnbreaking,
            int ownedUnbreaking, boolean hasCombatEnchants) {
        if (!sameShapeAndTier) {
            return false;
        }
        if (candidateUnbreaking == ownedUnbreaking) {
            // Existing inventory keeps exact ties, regardless of the chest simulation's arbitrary empty slot.
            return true;
        }
        // For clean weapons preserve an unenchanted copy for enchanting; otherwise durability is useful.
        return hasCombatEnchants ? ownedUnbreaking > candidateUnbreaking : ownedUnbreaking < candidateUnbreaking;
    }

    /** Bows with at most 10% durability left do not satisfy the player's need for a reusable bow. */
    static int bowUsefulnessScore(int combatEnchantScore, int remainingUses, int maxUses) {
        boolean nearlyBroken = maxUses > 0 && remainingUses <= maxUses / 10;
        return combatEnchantScore + (nearlyBroken ? 0 : HEALTHY_BOW_PRIORITY_BONUS);
    }

    /** A chest bow is surplus when a carried bow has equal or better usable priority. */
    static boolean shouldHighlightChestBow(int candidatePriority, int ownedPriority) {
        return ownedPriority >= candidatePriority;
    }
}
