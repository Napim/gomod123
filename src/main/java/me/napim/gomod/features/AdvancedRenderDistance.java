package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

/**
 * Hold the advanced render distance key and scroll to set a half-chunk view distance.
 * Video Settings chunk count is stored as a baseline; the advanced value can shorten or extend view.
 * {@code renderDistanceChunks} is updated for chunk loading/view, with fog/far-plane for sub-chunk precision.
 */
public final class AdvancedRenderDistance {
    public static final int MIN_BLOCKS = 9;
    public static final int MAX_BLOCKS = 512;
    private static final int SCROLL_STEP_BLOCKS = 8;
    private static final int MIN_CHUNKS_LOADED = 2;
    private static final int MAX_CHUNKS_LOADED = (MAX_BLOCKS + 15) / 16;

    /** Video Settings render distance (chunks) when the advanced value was first set. */
    private static int baselineVanillaChunks = -1;
    private static int vanillaChunksWhenCapSet = -1;
    private static int lastAppliedRenderDistanceChunks = -1;
    private static int wheelHandledOnTick = -1;
    private static boolean legacyBlockCapMigrationDone = false;

    private AdvancedRenderDistance() {
    }

    public static void loadSetting() {
        ConfigManager.getPerspectiveAdvancedRenderDistanceKeyCode();
        vanillaChunksWhenCapSet = -1;
        migrateStaleLegacyBlockCapOnce();
        int blocks = getBlocks();
        if (blocks > 0 && blocks < MIN_BLOCKS) {
            applyBlocks(MIN_BLOCKS, true);
        }
        releaseCapIfVanillaTakesOver();
        syncRenderDistanceToCap();
    }

    public static void onClientTick() {
        releaseCapIfVanillaTakesOver();
        syncRenderDistanceToCap();
    }

    public static void pollScrollAtEndOfRunTick() {
        int dWheel = 0;
        int wheel;
        while ((wheel = Mouse.getDWheel()) != 0) {
            dWheel += wheel;
        }
        if (dWheel != 0) {
            handleScroll(dWheel);
        }
    }

    public static void onVanillaRenderDistanceChanged() {
        int blocks = getBlocks();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.gameSettings != null && blocks > 0) {
            baselineVanillaChunks = mc.gameSettings.renderDistanceChunks;
            vanillaChunksWhenCapSet = baselineVanillaChunks;
        }
        releaseCapIfVanillaTakesOver();
        syncRenderDistanceToCap();
    }

    public static int getKeyCode() {
        return ConfigManager.getPerspectiveAdvancedRenderDistanceKeyCode();
    }

    public static void setKeyCode(int keyCode) {
        ConfigManager.setPerspectiveAdvancedRenderDistanceKeyCode(keyCode);
    }

    public static int getBlocks() {
        return ConfigManager.getPerspectiveAdvancedRenderDistanceBlocks();
    }

    public static int getBaselineVanillaBlocks() {
        if (baselineVanillaChunks > 0) {
            return baselineVanillaChunks * 16;
        }
        return getVanillaRenderDistanceBlocks();
    }

    public static int getVanillaRenderDistanceBlocks() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return 0;
        }
        return mc.gameSettings.renderDistanceChunks * 16;
    }

    private static boolean isStaleLegacyBlockCap() {
        int blocks = getBlocks();
        if (blocks <= 0 || blocks > 16) {
            return false;
        }
        int vanillaBlocks = getBaselineVanillaBlocks();
        int vanillaChunks = vanillaBlocks / 16;
        return vanillaChunks >= 4 && vanillaBlocks > blocks * 2;
    }

    public static boolean isActive() {
        int blocks = getBlocks();
        return blocks > 0;
    }

    public static int getEffectiveFogBlocks() {
        if (!isActive() || isViewEntityBlind()) {
            return 0;
        }
        return clamp(getBlocks());
    }

    public static float getEffectiveFarPlaneDistance() {
        int blocks = getEffectiveFogBlocks();
        return blocks > 0 ? (float) blocks : 0.0F;
    }

    public static boolean isViewEntityBlind() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return false;
            }
            Entity view = mc.getRenderViewEntity();
            return view instanceof EntityLivingBase
                    && ((EntityLivingBase) view).isPotionActive(Potion.blindness);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean shouldOverrideTerrainFog() {
        return getEffectiveFogBlocks() > 0;
    }

    public static float adjustFogEnd(float vanillaFogEnd) {
        float cap = getEffectiveFarPlaneDistance();
        if (cap <= 0.0F) {
            return vanillaFogEnd;
        }
        return Math.min(cap, vanillaFogEnd);
    }

    public static float adjustFogStart(float vanillaFogStart, float vanillaFogEnd) {
        float end = adjustFogEnd(vanillaFogEnd);
        if (end == vanillaFogEnd || vanillaFogEnd <= 0.0F) {
            return vanillaFogStart;
        }
        return end * (vanillaFogStart / vanillaFogEnd);
    }

    public static void applyForcedTerrainFog(int startCoords) {
        if (startCoords != 0 || !shouldOverrideTerrainFog()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        float chunkEnd = mc.gameSettings.renderDistanceChunks * 16.0F;
        float vanillaStart = Math.max(0.0F, chunkEnd * 0.75F);
        float fogEnd = adjustFogEnd(chunkEnd);
        float fogStart = adjustFogStart(vanillaStart, chunkEnd);

        GlStateManager.enableFog();
        GlStateManager.setFog(GL11.GL_LINEAR);
        GlStateManager.setFogStart(fogStart);
        GlStateManager.setFogEnd(fogEnd);
    }

    public static boolean wouldHandleScroll() {
        if (getScrollModifierKeyCode() <= 0) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.gameSettings == null || mc.currentScreen != null) {
            return false;
        }
        return isScrollModifierHeld(mc);
    }

    public static boolean handleScroll(int dWheel) {
        if (dWheel == 0 || !wouldHandleScroll()) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        int tick = mc.thePlayer.ticksExisted;
        if (tick == wheelHandledOnTick) {
            return true;
        }
        int notches = (int) Math.signum(dWheel) * Math.max(1, Math.abs(dWheel) / 120);
        int current = getBlocks();
        if (current <= 0) {
            current = getBaselineVanillaBlocks();
            if (current <= 0) {
                current = getVanillaRenderDistanceBlocks();
            }
        }
        int next = clampScrollTarget(current, (long) notches * SCROLL_STEP_BLOCKS);
        if (next == current) {
            wheelHandledOnTick = tick;
            return true;
        }
        applyBlocks(next, true);
        wheelHandledOnTick = tick;
        syncRenderDistanceToCap();
        notifyScrollApplied(getBlocks());
        return true;
    }

    private static int getScrollModifierKeyCode() {
        KeyBinding bind = GomodKeybinds.getAdvancedRenderDistanceKeyBinding();
        if (bind != null && bind.getKeyCode() > 0) {
            return bind.getKeyCode();
        }
        return getKeyCode();
    }

    private static boolean isScrollModifierHeld(Minecraft mc) {
        return GomodKeybinds.isAdvancedRenderDistanceModifierHeld(mc);
    }

    private static void notifyScrollApplied(int blocks) {
        try {
            int baseline = getBaselineVanillaBlocks();
            String msg = Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Render distance: "
                    + EnumChatFormatting.WHITE + blocks + EnumChatFormatting.GRAY + " blocks";
            if (isActive()) {
                int chunks = Minecraft.getMinecraft().gameSettings.renderDistanceChunks;
                msg += EnumChatFormatting.GREEN + " (" + chunks + " chunks loaded)";
                if (baseline > 0 && blocks != baseline) {
                    msg += EnumChatFormatting.GRAY + " video settings=" + (baseline / 16) + " chunks";
                }
            }
            Main.sendMessage(msg);
        } catch (Throwable ignored) {
        }
    }

    public static void applyBlocks(int blocks, boolean saveConfig) {
        blocks = clamp(blocks);
        if (saveConfig) {
            ConfigManager.setPerspectiveAdvancedRenderDistanceBlocks(blocks);
        } else {
            ConfigManager.setPerspectiveAdvancedRenderDistanceBlocksSilent(blocks);
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.gameSettings != null) {
            if (baselineVanillaChunks < 0) {
                baselineVanillaChunks = mc.gameSettings.renderDistanceChunks;
            }
            vanillaChunksWhenCapSet = baselineVanillaChunks;
        }
    }

    /**
     * Applies {@code renderDistanceChunks} from the block cap so view distance actually changes on all clients.
     * Sub-chunk values still use fog / far-plane mixins within the loaded chunk radius.
     */
    public static void syncRenderDistanceToCap() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        int blocks = getBlocks();
        if (blocks <= 0) {
            restoreBaselineRenderDistance(mc);
            return;
        }
        ensureBaselineCaptured(mc);
        int targetChunks = blocksToChunks(blocks);
        applyRenderDistanceChunks(mc, targetChunks);
    }

    private static void ensureBaselineCaptured(Minecraft mc) {
        if (baselineVanillaChunks < 0) {
            baselineVanillaChunks = mc.gameSettings.renderDistanceChunks;
            vanillaChunksWhenCapSet = baselineVanillaChunks;
        }
    }

    private static int blocksToChunks(int blocks) {
        int clampedBlocks = clamp(blocks);
        return Math.max(MIN_CHUNKS_LOADED, Math.min(MAX_CHUNKS_LOADED, (clampedBlocks + 15) / 16));
    }

    private static void applyRenderDistanceChunks(Minecraft mc, int chunks) {
        if (mc.gameSettings.renderDistanceChunks == chunks && lastAppliedRenderDistanceChunks == chunks) {
            return;
        }
        mc.gameSettings.renderDistanceChunks = chunks;
        lastAppliedRenderDistanceChunks = chunks;
        if (mc.renderGlobal != null) {
            try {
                mc.renderGlobal.loadRenderers();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void restoreBaselineRenderDistance(Minecraft mc) {
        if (baselineVanillaChunks < 0) {
            lastAppliedRenderDistanceChunks = -1;
            return;
        }
        if (mc.gameSettings.renderDistanceChunks != baselineVanillaChunks
                || lastAppliedRenderDistanceChunks != baselineVanillaChunks) {
            mc.gameSettings.renderDistanceChunks = baselineVanillaChunks;
            lastAppliedRenderDistanceChunks = baselineVanillaChunks;
            if (mc.renderGlobal != null) {
                try {
                    mc.renderGlobal.loadRenderers();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void resetBaseline() {
        baselineVanillaChunks = -1;
        vanillaChunksWhenCapSet = -1;
        lastAppliedRenderDistanceChunks = -1;
    }

    private static void releaseCapIfVanillaTakesOver() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.gameSettings == null) {
            return;
        }
        int blocks = getBlocks();
        if (blocks <= 0) {
            return;
        }
        int vanillaChunks = mc.gameSettings.renderDistanceChunks;
        if (lastAppliedRenderDistanceChunks >= 0 && vanillaChunks != lastAppliedRenderDistanceChunks) {
            baselineVanillaChunks = vanillaChunks;
            vanillaChunksWhenCapSet = vanillaChunks;
            lastAppliedRenderDistanceChunks = -1;
        }
        if (vanillaChunksWhenCapSet >= 0 && vanillaChunks > vanillaChunksWhenCapSet
                && lastAppliedRenderDistanceChunks < 0) {
            baselineVanillaChunks = vanillaChunks;
            vanillaChunksWhenCapSet = vanillaChunks;
        }
    }

    /** One-time migration for old configs that stored a mistaken sub-chunk cap. */
    private static void migrateStaleLegacyBlockCapOnce() {
        if (legacyBlockCapMigrationDone) {
            return;
        }
        legacyBlockCapMigrationDone = true;
        if (!isStaleLegacyBlockCap()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        ConfigManager.setPerspectiveAdvancedRenderDistanceBlocksSilent(0);
        if (mc != null && mc.gameSettings != null) {
            restoreBaselineRenderDistance(mc);
        }
        resetBaseline();
    }

    private static int clamp(int blocks) {
        return Math.max(MIN_BLOCKS, Math.min(MAX_BLOCKS, blocks));
    }

    private static int clampScrollTarget(int current, long deltaBlocks) {
        int maxBlocks = MAX_BLOCKS;
        if (deltaBlocks < 0 && current <= MIN_BLOCKS) {
            return MIN_BLOCKS;
        }
        if (deltaBlocks > 0 && current >= maxBlocks) {
            return maxBlocks;
        }
        long next = (long) current + deltaBlocks;
        if (next <= MIN_BLOCKS) {
            return MIN_BLOCKS;
        }
        if (next >= maxBlocks) {
            return maxBlocks;
        }
        return (int) next;
    }
}
