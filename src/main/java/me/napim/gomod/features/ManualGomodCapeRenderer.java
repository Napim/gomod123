package me.napim.gomod.features;

import me.napim.gomod.gui.MountPreviewHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/** Runs gomod's own vanilla-style cape pass because Lunar omits or patches the local vanilla cape layer. */
public final class ManualGomodCapeRenderer {
    private static final ThreadLocal<Boolean> NATIVE_LAYER_PASS = new ThreadLocal<Boolean>();
    private static final ThreadLocal<ResourceLocation> NATIVE_LAYER_CAPE = new ThreadLocal<ResourceLocation>();
    private static final ThreadLocal<Integer> PLAYER_RENDER_DEPTH = new ThreadLocal<Integer>();
    private static final ThreadLocal<Boolean> RENDERED_IN_PLAYER_RENDER = new ThreadLocal<Boolean>();
    private static String lastDiagnostic = "";
    private static boolean reportedNativeCompletion;
    private static boolean reportedTailFallback;

    private ManualGomodCapeRenderer() {
    }

    public static boolean isNativeLayerPass() {
        return Boolean.TRUE.equals(NATIVE_LAYER_PASS.get());
    }

    public static ResourceLocation getNativeLayerCape() {
        return NATIVE_LAYER_CAPE.get();
    }

    public static void beginPlayerRender(AbstractClientPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (player == null || mc == null || player != mc.thePlayer) {
            return;
        }
        Integer depth = PLAYER_RENDER_DEPTH.get();
        if (depth == null || depth <= 0) {
            PLAYER_RENDER_DEPTH.set(1);
            RENDERED_IN_PLAYER_RENDER.set(Boolean.FALSE);
        } else {
            PLAYER_RENDER_DEPTH.set(depth + 1);
        }
    }

    public static boolean hasRenderedInCurrentPlayerRender() {
        return Boolean.TRUE.equals(RENDERED_IN_PLAYER_RENDER.get());
    }

    public static void endPlayerRender(AbstractClientPlayer player) {
        Minecraft mc = Minecraft.getMinecraft();
        if (player == null || mc == null || player != mc.thePlayer) {
            return;
        }
        Integer depth = PLAYER_RENDER_DEPTH.get();
        if (depth == null || depth <= 1) {
            PLAYER_RENDER_DEPTH.remove();
            RENDERED_IN_PLAYER_RENDER.remove();
        } else {
            PLAYER_RENDER_DEPTH.set(depth - 1);
        }
    }

    public static void render(RenderPlayer renderer, AbstractClientPlayer player,
            float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
            float netHeadYaw, float headPitch, float scale) {
        Minecraft mc = Minecraft.getMinecraft();
        if (renderer == null || player == null || mc == null || player.isInvisible()) {
            return;
        }
        boolean localPlayer = player == mc.thePlayer;
        ResourceLocation previewCape = MountPreviewHelper.getForcedCapePreviewCape(player);
        ResourceLocation cape = previewCape != null
                ? previewCape
                : (GomodPeerStandaloneCape.isManualDisguiseProfile(player.getGameProfile())
                        ? GomodPeerStandaloneCape.capeForProfile(player.getGameProfile())
                        : localPlayer
                        ? PlayerStandaloneCape.getLocalGomodCapeForManualRender()
                        : GomodPeerStandaloneCape.capeForProfile(player.getGameProfile()));
        // Remote players without a gomod/peer cape need no manual pass. In particular,
        // do not call the vanilla cape lookup for every remote player on every Forge
        // renderLayers invocation when there is nothing for gomod to draw.
        if (cape == null) {
            return;
        }
        ResourceLocation playerCape = null;
        if (localPlayer) {
            try {
                playerCape = player.getLocationCape();
            } catch (Throwable ignored) {
            }
            String diagnostic = "active=" + PlayerStandaloneCape.isActive()
                    + ", preferGomod=" + ConfigManager.isPlayerStandaloneCapePreferGomodOnWeave()
                    + ", option=" + ConfigManager.getPlayerStandaloneCapeOptionKey()
                    + ", selected=" + cape
                    + ", playerCape=" + playerCape
                    + ", hasInfo=" + player.hasPlayerInfo()
                    + ", capePart=" + player.isWearing(EnumPlayerModelParts.CAPE)
                    + ", invisible=" + player.isInvisible();
            if (!diagnostic.equals(lastDiagnostic)) {
                lastDiagnostic = diagnostic;
                System.out.println("[gomod][cape-trace] " + diagnostic);
            }
        }

        NATIVE_LAYER_PASS.set(Boolean.TRUE);
        NATIVE_LAYER_CAPE.set(cape);
        try {
            if (localPlayer) {
                RENDERED_IN_PLAYER_RENDER.set(Boolean.TRUE);
            }
            renderDirectVanillaCape(renderer, player, cape, partialTicks);
            if (!reportedNativeCompletion) {
                reportedNativeCompletion = true;
                System.out.println("[gomod][cape-trace] gomod direct cape pass completed");
            }
        } finally {
            NATIVE_LAYER_CAPE.remove();
            NATIVE_LAYER_PASS.remove();
        }
    }

    private static void renderDirectVanillaCape(RenderPlayer renderer, AbstractClientPlayer player,
            ResourceLocation cape, float partialTicks) {
        if (renderer == null || player == null || cape == null || !player.hasPlayerInfo()
                || player.isInvisible() || !player.isWearing(EnumPlayerModelParts.CAPE)) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.getTextureManager() == null) {
            return;
        }
        ModelPlayer model = renderer.getMainModel();
        if (model == null) {
            return;
        }

        mc.getTextureManager().bindTexture(cape);
        GlStateManager.pushMatrix();
        boolean selfOpacityCapePass = SelfOpacity.isActive() && SelfOpacity.getAlpha() < 0.99F;
        boolean savedCull = false;
        boolean savedDepthMask = true;
        try {
            if (selfOpacityCapePass) {
                savedCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
                savedDepthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
                GlStateManager.enableCull();
                GlStateManager.depthMask(true);
            }
            GlStateManager.translate(0.0F, 0.0F, 0.125F);
            if (player.isSneaking()) {
                // Lunar's player model/body is already crouched when layers render, but our direct
                // gomod cape is drawn from the standing cape anchor. Move the whole cape down with
                // the crouched body before applying the vanilla cape swing rotations.
                GlStateManager.translate(0.0F, 0.20F, 0.0F);
            }
            double dx = player.prevChasingPosX + (player.chasingPosX - player.prevChasingPosX) * partialTicks
                    - (player.prevPosX + (player.posX - player.prevPosX) * partialTicks);
            double dy = player.prevChasingPosY + (player.chasingPosY - player.prevChasingPosY) * partialTicks
                    - (player.prevPosY + (player.posY - player.prevPosY) * partialTicks);
            double dz = player.prevChasingPosZ + (player.chasingPosZ - player.prevChasingPosZ) * partialTicks
                    - (player.prevPosZ + (player.posZ - player.prevPosZ) * partialTicks);
            float bodyYaw = player.prevRenderYawOffset
                    + (player.renderYawOffset - player.prevRenderYawOffset) * partialTicks;
            double sin = MathHelper.sin(bodyYaw * (float) Math.PI / 180.0F);
            double negCos = -MathHelper.cos(bodyYaw * (float) Math.PI / 180.0F);
            float lift = (float) dy * 10.0F;
            lift = MathHelper.clamp_float(lift, -6.0F, 32.0F);
            float swingBack = (float) (dx * sin + dz * negCos) * 100.0F;
            swingBack = MathHelper.clamp_float(swingBack, 0.0F, 150.0F);
            float sideSwing = (float) (dx * negCos - dz * sin) * 100.0F;
            sideSwing = MathHelper.clamp_float(sideSwing, -20.0F, 20.0F);
            float camera = player.prevCameraYaw + (player.cameraYaw - player.prevCameraYaw) * partialTicks;
            lift += MathHelper.sin((player.prevDistanceWalkedModified
                    + (player.distanceWalkedModified - player.prevDistanceWalkedModified) * partialTicks) * 6.0F)
                    * 32.0F * camera;
            if (player.isSneaking()) {
                lift += 25.0F;
            }

            GlStateManager.rotate(6.0F + swingBack / 2.0F + lift, 1.0F, 0.0F, 0.0F);
            GlStateManager.rotate(sideSwing / 2.0F, 0.0F, 0.0F, 1.0F);
            GlStateManager.rotate(-sideSwing / 2.0F, 0.0F, 1.0F, 0.0F);
            GlStateManager.rotate(180.0F, 0.0F, 1.0F, 0.0F);
            model.renderCape(0.0625F);
        } finally {
            if (selfOpacityCapePass) {
                GlStateManager.depthMask(savedDepthMask);
                if (savedCull) {
                    GlStateManager.enableCull();
                } else {
                    GlStateManager.disableCull();
                }
            }
            GlStateManager.popMatrix();
        }
    }

    public static void renderTailFallback(RenderPlayer renderer, AbstractClientPlayer player, float partialTicks) {
        if (hasRenderedInCurrentPlayerRender()) {
            return;
        }
        if (!reportedTailFallback) {
            reportedTailFallback = true;
            System.out.println("[gomod][cape-trace] RenderPlayer.doRender tail fallback active");
        }
        float limbSwing = 0.0F;
        float limbSwingAmount = 0.0F;
        float ageInTicks = player == null ? 0.0F : player.ticksExisted + partialTicks;
        float netHeadYaw = player == null ? 0.0F : player.rotationYawHead - player.renderYawOffset;
        float headPitch = player == null ? 0.0F : player.rotationPitch;
        render(renderer, player, limbSwing, limbSwingAmount, partialTicks, ageInTicks, netHeadYaw, headPitch, 0.0625F);
    }
}
