package me.napim.gomod.mixins;

import me.napim.gomod.gui.MountPreviewHelper;
import me.napim.gomod.features.GomodPeerStandaloneCape;
import me.napim.gomod.features.ManualGomodCapeRenderer;
import me.napim.gomod.features.PlayerStandaloneCape;
import me.napim.gomod.features.LunarCosmeticAppearanceBridge;
import me.napim.gomod.util.OutlineRenderingHelper;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.util.ResourceLocation;

/**
 * The glow outline pass disables {@code GL_TEXTURE_2D} for a flat silhouette; the cape
 * layer only draws meaningful coverage when texturing is on, so without this the cape
 * barely contributes to the outline FBO. Re-enable texturing for the cape draw only and
 * tint with the same glow RGB as the body.
 */
@Mixin(value = LayerCape.class, priority = 2000)
public abstract class LayerCapeMixin {
    @Shadow @Final private RenderPlayer playerRenderer;
    @org.spongepowered.asm.mixin.Unique
    private boolean gomod$noClothCapeAppearanceScope;

    @Inject(
            method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void gomod$capeLayerHead(AbstractClientPlayer entity, float limbSwing, float limbSwingAmount,
            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        gomod$noClothCapeAppearanceScope = false;
        // The dedicated gomod pass deliberately invokes Minecraft's native layer.
        // Never let the normal suppression/preview hooks reject that invocation.
        if (ManualGomodCapeRenderer.isNativeLayerPass()) {
            return;
        }
        if (entity != null && GomodPeerStandaloneCape.isManualDisguiseProfile(entity.getGameProfile())) {
            ci.cancel();
            return;
        }
        // Do not let the normal cape layer redraw the account cape while gomod owns the local source.
        if (entity != null && entity == net.minecraft.client.Minecraft.getMinecraft().thePlayer
                && PlayerStandaloneCape.shouldSuppressLunarCloakForLocalPlayer()) {
            ci.cancel();
            return;
        }
        if (entity != null && entity != net.minecraft.client.Minecraft.getMinecraft().thePlayer
                && entity.getGameProfile() != null
                && (GomodPeerStandaloneCape.capeForProfile(entity.getGameProfile()) != null
                || GomodPeerStandaloneCape.shouldSuppressVanillaCapeForProfile(entity.getGameProfile()))) {
            ci.cancel();
            return;
        }
        if (MountPreviewHelper.shouldSuppressCapeLayer(entity)) {
            ci.cancel();
            return;
        }
        if (MountPreviewHelper.isCapeEditorCapeOnlyPass()
                && MountPreviewHelper.isCapeEditorPreviewEntity(entity)) {
            GlStateManager.enableDepth();
            GlStateManager.depthMask(true);
            GlStateManager.depthFunc(org.lwjgl.opengl.GL11.GL_LEQUAL);
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(org.lwjgl.opengl.GL11.GL_GREATER, 0.003921569F);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            return;
        }
        if (!OutlineRenderingHelper.isInOutlineMode()) {
            gomod$noClothCapeAppearanceScope = LunarCosmeticAppearanceBridge.beginNoClothCapeLayer(entity);
            return;
        }
        int rgb = OutlineRenderingHelper.getGlowColorRgb();
        float cr = ((rgb >> 16) & 0xFF) / 255.0f;
        float cg = ((rgb >> 8) & 0xFF) / 255.0f;
        float cb = (rgb & 0xFF) / 255.0f;
        GlStateManager.enableTexture2D();
        GlStateManager.color(cr, cg, cb, 1.0f);
    }

    /**
     * Hide only the local Mojang cape lookup when Lunar is the selected source.
     * Keeping the layer method alive is important: Lunar injects its FullAccess cloak
     * rendering into this path on some client builds.
     */
    @Redirect(
            method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/AbstractClientPlayer;getLocationCape()Lnet/minecraft/util/ResourceLocation;"
            ),
            require = 0
    )
    private ResourceLocation gomod$suppressMojangCapeLookup(AbstractClientPlayer entity) {
        Minecraft mc = Minecraft.getMinecraft();
        if (entity != null && mc != null && entity == mc.thePlayer
                && !ManualGomodCapeRenderer.isNativeLayerPass()
                && PlayerStandaloneCape.shouldSuppressMinecraftCapeForLocalPlayer()) {
            return null;
        }
        return entity == null ? null : entity.getLocationCape();
    }

    @Inject(
            method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At("RETURN"),
            require = 0
    )
    private void gomod$capeOutlineReturn(AbstractClientPlayer entity, float limbSwing, float limbSwingAmount,
            float partialTicks, float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        if (gomod$noClothCapeAppearanceScope) {
            gomod$noClothCapeAppearanceScope = false;
            LunarCosmeticAppearanceBridge.endNoClothCapeLayer();
        }
        if (MountPreviewHelper.isCapeEditorCapeOnlyPass()
                && MountPreviewHelper.isCapeEditorPreviewEntity(entity)) {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            return;
        }
        if (!OutlineRenderingHelper.isInOutlineMode()) {
            return;
        }
        GlStateManager.disableTexture2D();
    }

    @Inject(
            method = "doRenderLayer(Lnet/minecraft/client/entity/AbstractClientPlayer;FFFFFFF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/ModelPlayer;renderCape(F)V"
            ),
            cancellable = true,
            require = 0
    )
    private void gomod$drawNativeLayerCapeBeforeLunarRedirect(AbstractClientPlayer entity,
            float limbSwing, float limbSwingAmount, float partialTicks, float ageInTicks,
            float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        if (!ManualGomodCapeRenderer.isNativeLayerPass()) {
            return;
        }
        ResourceLocation cape = ManualGomodCapeRenderer.getNativeLayerCape();
        if (cape == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.getTextureManager() != null) {
            mc.getTextureManager().bindTexture(cape);
        }
        ModelPlayer model = this.playerRenderer == null ? null : this.playerRenderer.getMainModel();
        if (model != null) {
            model.renderCape(0.0625F);
        }
        GlStateManager.popMatrix();
        ci.cancel();
    }
}
