package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tracks Blitz Star usages that apply timed (or match-long) "effect" rows in the Effects HUD.
 * Fed from the same chat patterns as {@link BlitzStarTabTracker} / Better BLITZ texts.
 * <p>
 * Vampire is special: BLITZ chat fires when the star is played, but Hypixel starts the real timer only after
 * you right-click the activator redstone (no chat). The HUD follows that redstone use; see
 * {@link BlitzVampireEffectActivation}.
 */
public final class BlitzStarEffectsHudTracker {

    /** First color-settings slot after vanilla potion ids 1–23. */
    public static final int COLOR_SLOT_BASE = 24;
    public static final int COLOR_SLOT_COUNT = 10;

    /** Ticks passed to duration formatters for infinite / match-long stars. */
    public static final int INFINITE_DURATION_TICKS = 643000;

    /** Who must use the star for the HUD row to appear. */
    public enum StarAudience {
        /** Row when the local player used the star (Vampire, Wither Warrior, …). */
        LOCAL_ONLY,
        /** Row when someone else used the star (Acid Rain, Ragnarok). */
        OTHER_ONLY,
        /** Row on every player's HUD when anyone uses the star (Apocalypse). */
        ALL_PLAYERS
    }

    public enum Effect {
        VAMPIRE(COLOR_SLOT_BASE, "Vampire", 30, StarAudience.LOCAL_ONLY,
                new ItemStack(Items.redstone),
                new String[] { "vampire" },
                0xCC3333),
        WITHER_WARRIOR(COLOR_SLOT_BASE + 1, "Wither Warrior", 30, StarAudience.LOCAL_ONLY,
                new ItemStack(Item.getItemFromBlock(Blocks.end_stone)),
                new String[] { "witherwarrior", "wither_warrior", "wither warrior" },
                0xCC3333),
        ROBINHOOD(COLOR_SLOT_BASE + 2, "Robinhood", 20, StarAudience.LOCAL_ONLY,
                new ItemStack(Items.bow),
                new String[] { "robinhood" },
                0x79522E),
        RAGNAROK(COLOR_SLOT_BASE + 3, "Ragnarok", -1, StarAudience.OTHER_ONLY,
                new ItemStack(Items.skull),
                new String[] { "ragnarok" },
                0x818181),
        PICKPOCKET(COLOR_SLOT_BASE + 4, "Pickpocket", -1, StarAudience.LOCAL_ONLY,
                new ItemStack(Items.blaze_rod),
                new String[] { "pickpocket" },
                0x9B2E2E),
        INFECTION(COLOR_SLOT_BASE + 5, "Infection", -1, StarAudience.LOCAL_ONLY,
                new ItemStack(Blocks.brewing_stand),
                new String[] { "infection" },
                0x41B7A3),
        ACID_RAIN(COLOR_SLOT_BASE + 6, "Acid Rain", 30, StarAudience.OTHER_ONLY,
                new ItemStack(Items.melon_seeds),
                new String[] { "acid_rain", "acid rain", "acidrain" },
                0x8400C6),
        /** 2m 32s = 152 seconds. */
        LUCKY_CHARM(COLOR_SLOT_BASE + 7, "Lucky Charm", 152, StarAudience.LOCAL_ONLY,
                new ItemStack(Items.experience_bottle),
                new String[] { "lucky_charm", "lucky charm", "luckycharm" },
                0x41B7A3),
        LOCKDOWN(COLOR_SLOT_BASE + 8, "Lockdown", -1, StarAudience.OTHER_ONLY,
                new ItemStack(Blocks.barrier),
                new String[] { "lockdown" },
                0x7E510D),
        APOCALYPSE(COLOR_SLOT_BASE + 9, "Apocalypse", 30, StarAudience.ALL_PLAYERS,
                new ItemStack(Item.getItemFromBlock(Blocks.tnt)),
                new String[] { "apocalypse", "apocalypse star" },
                0xFF7900);

        final int colorSlot;
        final String displayName;
        /** Seconds; {@code -1} = until game end / clear. */
        final int durationSeconds;
        final StarAudience audience;
        final ItemStack iconStack;
        final String[] matchKeys;
        final int defaultNameRgb;

        Effect(int colorSlot, String displayName, int durationSeconds, StarAudience audience, ItemStack iconStack,
               String[] matchKeys, int defaultNameRgb) {
            this.colorSlot = colorSlot;
            this.displayName = displayName;
            this.durationSeconds = durationSeconds;
            this.audience = audience;
            this.iconStack = iconStack;
            this.matchKeys = matchKeys;
            this.defaultNameRgb = defaultNameRgb;
        }

        public int durationTicks() {
            if (durationSeconds < 0) {
                return INFINITE_DURATION_TICKS;
            }
            return durationSeconds * 20;
        }

        public ItemStack getIconStack() {
            return iconStack;
        }
    }

    private static final Map<Effect, Long> activeUntilMs = new EnumMap<Effect, Long>(Effect.class);

    private BlitzStarEffectsHudTracker() {
    }

    /**
     * Called when chat reports a Blitz Star use.
     *
     * @param starDisplayName star name from the message
     * @param userIgn         player who used the star (from chat prefix), or null if unknown
     */
    public static void onStarUsed(String starDisplayName, String userIgn) {
        if (!ConfigManager.isMoreHudPotionsEnabled()) {
            return;
        }
        if (shouldHideHudWhileEliminated()) {
            return;
        }
        Effect effect = resolve(starDisplayName);
        if (effect == null || !matchesAudience(effect, userIgn)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (effect.durationSeconds < 0) {
            activeUntilMs.put(effect, -1L);
        } else {
            activeUntilMs.put(effect, now + effect.durationSeconds * 1000L);
        }
    }

    private static boolean matchesAudience(Effect effect, String userIgn) {
        if (effect.audience == StarAudience.ALL_PLAYERS) {
            return true;
        }
        if (userIgn == null || userIgn.trim().isEmpty()) {
            return false;
        }
        boolean isLocal = KillCounter.isLocalClientIgn(userIgn);
        if (effect.audience == StarAudience.LOCAL_ONLY) {
            return isLocal;
        }
        return !isLocal;
    }

    public static Effect resolve(String starDisplayName) {
        if (starDisplayName == null || starDisplayName.isEmpty()) {
            return null;
        }
        String k = starDisplayName.toLowerCase(Locale.ROOT).trim();
        String underscored = k.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "").replaceAll("_+", "_");
        String compact = k.replaceAll("[^a-z0-9]", "");
        for (Effect e : Effect.values()) {
            for (String key : e.matchKeys) {
                if (key.equals(k) || key.equals(underscored) || key.equals(compact)
                        || key.replace(' ', '_').equals(underscored)
                        || key.replace("_", "").equals(compact)) {
                    return e;
                }
            }
        }
        return null;
    }

    public static void clearAll() {
        activeUntilMs.clear();
        BlitzVampireEffectActivation.reset();
    }

    /**
     * Starts the Vampire Effects HUD row when the local player consumes the Hypixel Vampire redstone activator
     * (right-click). Chat-only star use does not call this.
     */
    public static void onVampireActivatedFromRedstoneUse() {
        if (!ConfigManager.isMoreHudPotionsEnabled()) {
            return;
        }
        if (shouldHideHudWhileEliminated()) {
            return;
        }
        Effect effect = Effect.VAMPIRE;
        long now = System.currentTimeMillis();
        activeUntilMs.put(effect, now + effect.durationSeconds * 1000L);
    }

    /**
     * True when the local player is dead / spectating — active rows are cleared and not drawn.
     */
    public static boolean shouldHideHudWhileEliminated() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) {
            return true;
        }
        if (mc.thePlayer.isDead) {
            return true;
        }
        if (mc.thePlayer.getHealth() <= 0.0F) {
            return true;
        }
        return KillCounter.isLocalBlitzEliminatedOrSpectatingTabSignals(mc);
    }

    private static void clearIfEliminated() {
        if (shouldHideHudWhileEliminated()) {
            clearAll();
        }
    }

    /** Drops expired timed entries; infinite entries stay until {@link #clearAll()}. */
    public static void tick() {
        clearIfEliminated();
        if (activeUntilMs.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Effect> expired = null;
        for (Map.Entry<Effect, Long> e : activeUntilMs.entrySet()) {
            Long end = e.getValue();
            if (end != null && end > 0L && now >= end) {
                if (expired == null) {
                    expired = new ArrayList<Effect>();
                }
                expired.add(e.getKey());
            }
        }
        if (expired != null) {
            for (Effect eff : expired) {
                activeUntilMs.remove(eff);
            }
        }
    }

    public static boolean hasActiveEffects() {
        tick();
        return !activeUntilMs.isEmpty();
    }

    public static List<ActiveRow> getActiveRows() {
        tick();
        if (activeUntilMs.isEmpty()) {
            return Collections.emptyList();
        }
        List<ActiveRow> rows = new ArrayList<ActiveRow>();
        long now = System.currentTimeMillis();
        for (Effect e : Effect.values()) {
            Long end = activeUntilMs.get(e);
            if (end == null) {
                continue;
            }
            int ticks;
            if (end < 0L) {
                ticks = INFINITE_DURATION_TICKS;
            } else {
                long remMs = end - now;
                if (remMs <= 0L) {
                    continue;
                }
                ticks = (int) Math.min(Integer.MAX_VALUE, (remMs * 20L + 999L) / 1000L);
            }
            rows.add(new ActiveRow(e, ticks));
        }
        return rows;
    }

    public static int getDefaultColor(int colorSlot) {
        for (Effect e : Effect.values()) {
            if (e.colorSlot == colorSlot) {
                return e.defaultNameRgb;
            }
        }
        return ConfigManager.getMoreHudPotionsColor() & 0xFFFFFF;
    }

    public static Effect effectForColorSlot(int colorSlot) {
        for (Effect e : Effect.values()) {
            if (e.colorSlot == colorSlot) {
                return e;
            }
        }
        return null;
    }

    public static final class ActiveRow {
        public final Effect effect;
        public final int remainingTicks;

        ActiveRow(Effect effect, int remainingTicks) {
            this.effect = effect;
            this.remainingTicks = remainingTicks;
        }
    }
}
