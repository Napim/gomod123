package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import me.napim.gomod.mixins.RenderAccessor;
import me.napim.gomod.render.NametagLayerBridge;
import me.napim.gomod.render.PlayerNametagOverlayStack;
import me.napim.gomod.render.VanillaPlayerNametagLayout;
import me.napim.gomod.render.VanillaEntityLabelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import org.lwjgl.opengl.GL11;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Second nametag line above the vanilla plate: resolved real IGN for nicked players
 * ({@link TabDuplicateNickResolver}), when enabled via {@code /nametagnick}.
 * Default: gold plain name; optional {@code /nametagnick rank} adds Hypixel {@code [MVP+]} + rank colors from tabstats.
 * <p>
 * <b>Standing / not sneaking:</b> hooks {@link NametagLayerBridge} after
 * {@link net.minecraft.client.renderer.entity.RenderPlayer#renderOffsetLivingLabel} so the extra line uses the same
 * foot-{@code y} step vanilla uses between tab-health and the main name (no guessed “health row” offsets).
 * <p>
 * <b>Sneaking:</b> vanilla draws the name in {@link net.minecraft.client.renderer.entity.RendererLivingEntity#passSpecialRender}
 * with a dedicated GL path (not {@link net.minecraft.client.renderer.entity.Render#renderLivingLabel}), including
 * semi-transparent text (ARGB {@code 553648127}) and a dim nameplate background. Extra lines
 * must use the same path or they stay fully opaque.
 */
public final class NickRealNametagOverlay {

    /** Same ARGB as vanilla sneaking {@code FontRenderer.drawString(..., 553648127)} in {@code passSpecialRender}. */
    private static final int VANILLA_SNEAKING_NAMETAG_TEXT_COLOR = 553648127;
    /** Deduplicates the normal RenderPlayer bridge and Lunar's doRender-tail fallback. */
    private static final Set<Integer> renderedStandingEntityIdsThisFrame = new HashSet<Integer>();
    /** Players whose renderer tail ran; the world fallback must not scan/resolve them a second time. */
    private static final Set<Integer> processedStandingEntityIdsThisFrame = new HashSet<Integer>();
    // Only cache the parsed display name. The resolver and privacy policy still use live data on every draw.
    private static final Map<EntityPlayer, String> visibleNickThisTick = new IdentityHashMap<>();

    static {
        NametagLayerBridge.register(NickRealNametagOverlay::onAfterVanillaPlayerNameplate);
    }

    private NickRealNametagOverlay() {}

    /** Ensures {@link NametagLayerBridge} registration runs even if this class was not touched by other mixins yet. */
    public static void ensureHooksLoaded() {
    }

    public static void beginWorldNametagFrame() {
        renderedStandingEntityIdsThisFrame.clear();
        processedStandingEntityIdsThisFrame.clear();
        MobTargetDisplay.beginWorldNametagFrame();
        LunarNametagCompatibility.beginWorldNametagFrame();
    }

    static void clearVisibleNicknameCache() {
        visibleNickThisTick.clear();
    }

    /**
     * True when {@code /nametagnick} would draw the extra real-IGN line for this player (denick or manual map).
     * Other features can stack above that row instead of overlapping it.
     */
    public static boolean wouldShowRealIgnNametagLine(EntityPlayer player) {
        if (!ConfigManager.isDenickerEnabled() || !ConfigManager.isNickRealNametagOverlayEnabled() || player == null) {
            return false;
        }
        return buildRealIgnOverlayLabel(player) != null;
    }

    private static void onAfterVanillaPlayerNameplate(AbstractClientPlayer player, Render render,
            double x, double footYOfMainNameLine, double z, float labelScale, double distanceSq,
            String renderedLabel) {
        try {
            if (!ConfigManager.isDenickerEnabled() || !ConfigManager.isNickRealNametagOverlayEnabled()) {
                return;
            }
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || player == null || player == mc.thePlayer) {
                return;
            }
            if (ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack(player)) {
                return;
            }
            if (LunarNametagCompatibility.isHandledByLunarNametagStack(player)) {
                return;
            }
            String label = buildRealIgnOverlayLabel(player);
            if (label == null) {
                return;
            }
            renderedStandingEntityIdsThisFrame.add(Integer.valueOf(player.getEntityId()));
            double footYAboveName = PlayerNametagOverlayStack.footYForRowAboveMainIgn(
                    render, footYOfMainNameLine, labelScale, player, distanceSq,
                    LunarNametagCompatibility.countNativeRowsAboveMainName() + 1);
            final RenderAccessor accessor = (RenderAccessor) render;
            final AbstractClientPlayer target = player;
            final String text = label;
            final double drawX = x;
            final double drawY = footYAboveName;
            final double drawZ = z;
            GomodPlayerNametagLabels.renderCustomLabel(new Runnable() {
                @Override
                public void run() {
                    accessor.gomod$invokeRenderLivingLabel(target, text, drawX, drawY, drawZ, 64);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * Lunar can render a standing player without invoking RenderPlayer#renderOffsetLivingLabel, so the bridge above
     * never fires. RendererLivingEntity#doRender is stable on that runtime; draw the missing row from its tail.
     */
    public static void tryRenderStandingNametagFallback(EntityPlayer player, Render render,
            double x, double y, double z) {
        if (!ConfigManager.isDenickerEnabled()
                || !ConfigManager.isNickRealNametagOverlayEnabled()
                || !(player instanceof AbstractClientPlayer)
                || player.isSneaking()) {
            return;
        }
        processedStandingEntityIdsThisFrame.add(Integer.valueOf(player.getEntityId()));
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || player == mc.thePlayer || render == null) {
            return;
        }
        if (ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack(player)) {
            return;
        }
        if (LunarNametagCompatibility.isHandledByLunarNametagStack(player)) {
            return;
        }
        if (VisibleSpecs.isDeferredOpacityPrepassActive()
                || VisibleSpecs.shouldSuppressNametag(player)) {
            return;
        }
        if (renderedStandingEntityIdsThisFrame.contains(Integer.valueOf(player.getEntityId()))) {
            return;
        }
        try {
            Entity view = mc.getRenderManager() == null ? null : mc.getRenderManager().livingPlayer;
            if (view == null) {
                view = mc.thePlayer;
            }
            double distanceSq = player.getDistanceSqToEntity(view);
            if (distanceSq > 4096.0D) {
                return;
            }
            String label = buildRealIgnOverlayLabel(player);
            if (label == null) {
                return;
            }
            AbstractClientPlayer clientPlayer = (AbstractClientPlayer) player;
            float scale = VanillaPlayerNametagLayout.VANILLA_WORLD_LABEL_SCALE;
            double mainFootY = VanillaPlayerNametagLayout.resolveMainIgnFootY(
                    render, clientPlayer, y, scale, distanceSq);
            double overlayFootY = VanillaPlayerNametagLayout.footYAfterRowsAboveMainName(
                    render, mainFootY, scale,
                    LunarNametagCompatibility.countNativeRowsAboveMainName() + 1);
            renderedStandingEntityIdsThisFrame.add(Integer.valueOf(player.getEntityId()));
            GomodPlayerNametagLabels.renderCustomLabel(new Runnable() {
                @Override
                public void run() {
                    ((RenderAccessor) render).gomod$invokeRenderLivingLabel(
                            player, label, x, overlayFootY, z, 64);
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * Final Lunar fallback independent of RenderPlayer/RendererLivingEntity callbacks. Some Lunar builds draw the
     * vanilla player nameplate in transformed code that bypasses both, while RenderGlobal's world tail remains stable.
     */
    public static void renderMissingWorldNametags(float partialTicks) {
        if (!ConfigManager.isDenickerEnabled() || !ConfigManager.isNickRealNametagOverlayEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.getRenderManager() == null
                || mc.currentScreen != null) {
            return;
        }
        RenderManager rm = mc.getRenderManager();
        Entity view = mc.getRenderViewEntity();
        if (view == null) {
            return;
        }
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (!(player instanceof AbstractClientPlayer)
                    || player == mc.thePlayer
                    || player.isDead
                    || player.isSneaking()
                    || LunarNametagCompatibility.isHandledByLunarNametagStack(player)
                    || ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack(player)
                    || processedStandingEntityIdsThisFrame.contains(Integer.valueOf(player.getEntityId()))
                    || renderedStandingEntityIdsThisFrame.contains(Integer.valueOf(player.getEntityId()))
                    || VisibleSpecs.shouldSuppressNametag(player)) {
                continue;
            }
            try {
                double distanceSq = player.getDistanceSqToEntity(view);
                if (distanceSq > 4096.0D) {
                    continue;
                }
                String label = buildRealIgnOverlayLabel(player);
                if (label == null) {
                    continue;
                }
                Render render = rm.getEntityRenderObject(player);
                if (render == null) {
                    continue;
                }
                AbstractClientPlayer clientPlayer = (AbstractClientPlayer) player;
                double baseX = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks - rm.viewerPosX;
                double baseY = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks - rm.viewerPosY;
                double baseZ = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks - rm.viewerPosZ;
                float scale = VanillaPlayerNametagLayout.VANILLA_WORLD_LABEL_SCALE;
                double mainFootY = VanillaPlayerNametagLayout.resolveMainIgnFootY(
                        render, clientPlayer, baseY, scale, distanceSq);
                double overlayFootY = VanillaPlayerNametagLayout.footYAfterRowsAboveMainName(
                        render, mainFootY, scale,
                        LunarNametagCompatibility.countNativeRowsAboveMainName() + 1);
                double footExtra = overlayFootY - baseY;
                renderedStandingEntityIdsThisFrame.add(Integer.valueOf(player.getEntityId()));
                VanillaEntityLabelRenderer.render(player, label, baseX, baseY, baseZ,
                        64, footExtra, 0xFFFFFF, 1.0F, 1.0F, false, true);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Sneaking players: vanilla never runs {@code RenderPlayer.renderOffsetLivingLabel}; approximate placement from
     * {@code renderName} foot {@code y}.
     */
    public static void tryRenderSneakingNametagFallback(EntityPlayer player, Render render, double x, double y, double z) {
        if (!ConfigManager.isDenickerEnabled() || !ConfigManager.isNickRealNametagOverlayEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || player == null || player == mc.thePlayer) {
            return;
        }
        if (ReplayPlayerHealthTagFix.shouldRenderCompleteReplayStack(player)) {
            return;
        }
        if (!player.isSneaking()) {
            return;
        }
        if (LunarNametagCompatibility.isHandledByLunarNametagStack(player)) {
            return;
        }
        String label = buildRealIgnOverlayLabel(player);
        if (label == null) {
            return;
        }
        double overlayFootY = PlayerNametagOverlayStack.sneakingFootYForRowAboveMain(render, y, player,
                LunarNametagCompatibility.countNativeRowsAboveMainName() + 1);
        renderSneakingStyleNametagPlate(player, render, x, overlayFootY, z, label);
    }

    /**
     * Matches {@code RendererLivingEntity.passSpecialRender} sneaking branch: same scale, background alpha, and
     * text color as the vanilla player nametag (not {@link Render#renderLivingLabel}, which is unused for sneaking).
     *
     * @param feetY vanilla-relative feet {@code y} (same as passed to {@link Render#renderLivingLabel} as {@code y})
     */
    public static void renderSneakingStyleNametagPlate(EntityPlayer player, Render render,
            double x, double feetY, double z, String text) {
        if (text == null || text.isEmpty() || player == null || render == null) {
            return;
        }
        FontRenderer font = render.getFontRendererFromRenderManager();
        RenderManager rm = render.getRenderManager();
        if (font == null || rm == null) {
            return;
        }
        float childLift = player.isChild() ? player.height / 2.0F : 0.0F;

        GlStateManager.pushMatrix();
        try {
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            GlStateManager.translate((float) x, (float) feetY + player.height + 0.5F - childLift, (float) z);
            GL11.glNormal3f(0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(-rm.playerViewY, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(rm.playerViewX, 1.0F, 0.0F, 0.0F);
            GlStateManager.scale(-0.02666667F, -0.02666667F, 0.02666667F);
            GlStateManager.translate(0.0F, 9.374999F, 0.0F);
            GlStateManager.disableLighting();
            GlStateManager.depthMask(false);
            GlStateManager.enableBlend();
            GlStateManager.disableTexture2D();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

            int halfW = font.getStringWidth(text) / 2;
            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();
            wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
            wr.pos(-halfW - 1, -1, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
            wr.pos(-halfW - 1, 8, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
            wr.pos(halfW + 1, 8, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
            wr.pos(halfW + 1, -1, 0.0D).color(0.0F, 0.0F, 0.0F, 0.25F).endVertex();
            tess.draw();

            GlStateManager.enableTexture2D();
            GlStateManager.depthMask(true);
            font.drawString(text, -halfW, 0, VANILLA_SNEAKING_NAMETAG_TEXT_COLOR);
            GlStateManager.enableLighting();
            GlStateManager.disableBlend();
        } finally {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    public static String buildRealIgnOverlayLabel(EntityPlayer player) {
        // A relay spectator is rendered to an alive Gomod peer using the server-visible nick. Do not add a
        // denicked real-IGN row above it; that would expose the resolved identity to the other spectators.
        if (player == null || SpectatorRelay.isAliveViewer() && SpectatorRelay.isConfirmedSpectator(player)) {
            return null;
        }
        GameProfile profile = player.getGameProfile();
        if (profile == null || profile.getName() == null) {
            return null;
        }
        String nick = profile.getName().trim();
        if (nick.isEmpty()) {
            return null;
        }
        // Lunar's world entity can retain an internal profile name that differs from the visible nick used by TAB.
        // Resolve by the shared session UUID first (the same source that successfully denicks TAB), then try the
        // entity's formatted visible nametag before falling back to the profile name.
        String real = TabDuplicateNickResolver.resolveRealNameForDenicker(profile.getId(), nick);
        String displayNick = nick;
        if (real == null) {
            String visible = visibleNickname(player);
            if (visible != null) {
                displayNick = visible;
                real = TabDuplicateNickResolver.getKnownRealName(displayNick);
            }
            if (real == null && !nick.equals(visible)) {
                real = TabDuplicateNickResolver.getKnownRealName(nick);
            }
        }
        if (real == null) {
            return null;
        }
        String trimmedReal = real.trim();
        // AntiShuffle removes Hypixel's XXXX mask from the name at render time. Compare against that final visible
        // name as well as the raw profile alias; otherwise a nick such as XXXXmrunc renders as "mrunc" and this
        // overlay redundantly adds "(mrunc)" above the very same player.
        String antiShuffleVisibleNick = AntiShuffleCompat.cleanTabTextIfExternalAntiShuffleEnabled(displayNick);
        String plainVisibleNick = EnumChatFormatting.getTextWithoutFormattingCodes(antiShuffleVisibleNick);
        if (trimmedReal.isEmpty() || trimmedReal.equalsIgnoreCase(displayNick)
                || plainVisibleNick != null && trimmedReal.equalsIgnoreCase(plainVisibleNick.trim())) {
            return null;
        }
        String knownNick = TabDuplicateNickResolver.getKnownDuplicateNick(trimmedReal);
        String formatKey = knownNick == null || knownNick.trim().isEmpty() ? displayNick : knownNick.trim();
        return Denicker.formatResolvedTag(formatKey, trimmedReal);
    }

    private static String visibleNickname(EntityPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        boolean inWorld = mc != null && mc.theWorld != null && player.worldObj == mc.theWorld;
        if (inWorld && visibleNickThisTick.containsKey(player)) {
            return visibleNickThisTick.get(player);
        }
        try {
            IChatComponent display = player.getDisplayName();
            String visible = TabDuplicateNickResolver.extractVisibleUsernamePublic(
                    display == null ? null : display.getFormattedText());
            if (inWorld) visibleNickThisTick.put(player, visible);
            return visible;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
