package me.napim.gomod.util;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import org.lwjgl.opengl.GL11;

/**
 * Restores GL state to what vanilla 2D HUD widgets and {@link net.minecraft.client.gui.FontRenderer}
 * expect after other mods or gomod features change lighting, blending, masks, or texturing.
 * <p>
 * When lighting stays enabled or texturing is wrong, font atlases draw as flat gray rectangles
 * (often reported as "coords HUD" or tab list looking gray next to other mods).
 */
public final class HudGlState {

    private HudGlState() {}

    public static void restoreForOverlayText() {
        // Item rendering (especially enchanted/custom held items) can leave the lightmap texture
        // unit active. Matrix push/pop does not restore texture units, so subsequent HUD textures
        // and font atlases can otherwise be multiplied into a flat gray image.
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableCull();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(516, 0.1F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.depthMask(true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Flat slot/hotbar underlay baseline: draw translucent quads in screen space without inheriting world colour,
     * lightmap, depth, cull, or stencil state. Call {@link #restoreAfterInventoryUnderlay()} before item icons draw.
     */
    public static void restoreForInventoryUnderlay() {
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableCull();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(516, 0.1F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDepthMask(false);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Throwable ignored) {
        }
    }

    /** Restore the vanilla-ish GUI item baseline after drawing flat Inventory-QoL underlays. */
    public static void restoreAfterInventoryUnderlay() {
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.disableCull();
        GlStateManager.disableRescaleNormal();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(516, 0.1F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(515);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glDisable(GL11.GL_FOG);
            GL11.glDepthMask(true);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Before untextured world overlays (block highlight lines) when nametag passes may have left lighting on.
     */
    public static void restoreBeforeWorldGeometryOverlay() {
        GlStateManager.disableLighting();
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(515);
        try {
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_STENCIL_TEST);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Before in-world billboards/nameplates at late world-event timing. Lunar/Weave can enter this stage with the
     * lightmap texture unit still active, which makes font atlases render as solid gray rectangles in first person.
     */
    public static void restoreBeforeWorldText() {
        GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.disableTexture2D();
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.disableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(515);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Throwable ignored) {
        }
    }

    /**
     * After {@link me.napim.gomod.features.GomodBlockOverlay} geometry. Uses only {@link GlStateManager} so the
     * client tracker stays aligned with GL (never {@code pushAttrib}/{@code popAttrib} — those skip the tracker and
     * break self-opacity alpha test and water clarity when combined with block overlay).
     *
     * @param thirdPerson {@code true} when drawn at {@code DrawBlockHighlight} (terrain/translucent passes follow);
     *                    {@code false} when drawn at {@code RenderWorldLast} before the first-person hand
     */
    public static void restoreAfterBlockOverlay(boolean thirdPerson) {
        GlStateManager.enableCull();
        GlStateManager.disableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(516, 0.1F);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(515);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        if (thirdPerson) {
            GlStateManager.disableBlend();
        } else {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        }
        try {
            GL11.glDepthMask(true);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        } catch (Throwable ignored) {
        }
    }

    /** After block overlay at RenderWorldLast, before {@code ItemRenderer#renderItemInFirstPerson}. */
    public static void restoreForFirstPersonHand() {
        // Other renderers may leave the lightmap texture unit selected. The first-person
        // item/arm pass expects its ordinary texture on the default unit.
        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(516, 0.1F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableLighting();
        // Some world renderers call raw glDisable(GL_LIGHTING) without updating
        // GlStateManager's cached BooleanState. Its enableLighting() can then be a
        // no-op even though the real GL capability is off; force the final hand-pass state.
        GL11.glEnable(GL11.GL_LIGHTING);
        GlStateManager.disableFog();
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.depthFunc(515);
        GlStateManager.colorMask(true, true, true, true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            float light = readLastBrightness("lastBrightnessX", "field_77476_b");
            float lightY = readLastBrightness("lastBrightnessY", "field_77477_c");
            net.minecraft.client.renderer.OpenGlHelper.setLightmapTextureCoords(
                    net.minecraft.client.renderer.OpenGlHelper.lightmapTexUnit, light, lightY);
        } catch (Throwable ignored) {
        }
    }

    private static float readLastBrightness(String... names) {
        try {
            java.lang.reflect.Field field = MinecraftPrivateAccess.findField(
                    net.minecraft.client.renderer.OpenGlHelper.class, names);
            return field.getFloat(null);
        } catch (Throwable ignored) {
            return 240.0F;
        }
    }
}
