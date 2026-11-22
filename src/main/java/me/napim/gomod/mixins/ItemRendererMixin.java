package me.napim.gomod.mixins;

import me.napim.gomod.features.GomodBlockOverlay;
import me.napim.gomod.features.LocalMobDisguise;
import me.napim.gomod.features.PlayersPlayMode;
import me.napim.gomod.features.RoleplayMode;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.WaterClarity;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.gui.MapItemRenderer;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.opengl.GL11;
import net.minecraft.item.ItemStack;
import net.minecraft.world.storage.MapData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/**
 * Temporarily sets renderViewEntity to the roleplay target during first-person
 * arm/item rendering so the arm skin, held item, and animations all come from
 * the target player instead of the local player.
 */
@Mixin(ItemRenderer.class)
public class ItemRendererMixin {
    @Shadow private ItemStack itemToRender;
    @Shadow private int equippedItemSlot;
    @Shadow private float equippedProgress;
    @Shadow private float prevEquippedProgress;

    private Entity gomod$savedViewEntity = null;
    private EntityPlayerSP gomod$savedLocalPlayer = null;
    private float gomod$savedYaw = 0.0f;
    private float gomod$savedPrevYaw = 0.0f;
    private float gomod$savedPitch = 0.0f;
    private float gomod$savedPrevPitch = 0.0f;
    private float gomod$savedRenderArmYaw = 0.0f;
    private float gomod$savedPrevRenderArmYaw = 0.0f;
    private float gomod$savedRenderArmPitch = 0.0f;
    private float gomod$savedPrevRenderArmPitch = 0.0f;
    private float gomod$savedYawHead = 0.0f;
    private float gomod$savedPrevYawHead = 0.0f;
    private float gomod$savedSwingProgress = 0.0f;
    private float gomod$savedPrevSwingProgress = 0.0f;
    private boolean gomod$savedIsSwingInProgress = false;
    private int gomod$savedSwingProgressInt = 0;
    private ItemStack gomod$savedItemInUse = null;
    private int gomod$savedItemInUseCount = 0;
    private ItemStack gomod$savedItemToRender = null;
    private int gomod$savedEquippedItemSlot = -1;
    private float gomod$savedEquippedProgress = 0.0f;
    private float gomod$savedPrevEquippedProgress = 0.0f;
    private boolean gomod$specialArmOverrideActive = false;
    private boolean gomod$selfOpacityArmActive = false;
    private boolean gomod$visibleSpecsArmActive = false;
    private boolean gomod$opacityBlendActive = false;
    private int gomod$firstPersonItemOpacityScopes = 0;

    private void gomod$resetArmOpacityGlIfActive() {
        if (!gomod$selfOpacityArmActive && !gomod$visibleSpecsArmActive && !gomod$opacityBlendActive) {
            return;
        }
        if (gomod$selfOpacityArmActive) {
            gomod$selfOpacityArmActive = false;
            SelfOpacity.endFirstPersonArm();
        }
        if (gomod$visibleSpecsArmActive) {
            gomod$visibleSpecsArmActive = false;
            VisibleSpecs.endFirstPersonArm();
        }
        gomod$opacityBlendActive = false;
        GL11.glColor4f(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.disableBlend();
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
    }

    @Inject(method = "renderItemInFirstPerson", at = @At("HEAD"))
    private void gomod$blockOverlayHandGl(float partialTicks, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.gameSettings != null && mc.gameSettings.thirdPersonView == 0
                    && GomodBlockOverlay.drewThisFrame()) {
                GomodBlockOverlay.prepareFirstPersonHandGl();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "renderItemInFirstPerson", at = @At("HEAD"))
    private void gomod$selfOpacityArmHead(float partialTicks, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.gameSettings.thirdPersonView != 0) return;
            if (SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_FRAME_ENTRY)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_FRAME_ENTRY,
                        "ItemRenderer.renderItemInFirstPerson:HEAD", itemToRender,
                        "handAlpha=" + SelfOpacity.getFirstPersonHandAlpha()
                                + " view=" + mc.gameSettings.thirdPersonView);
            }
            if (LocalMobDisguise.isActive()) return;
            float selfAlpha = (SelfOpacity.isActive() ? SelfOpacity.getFirstPersonHandAlpha() : 1.0f);
            float visibleSpecsAlpha = (VisibleSpecs.shouldApplyToLocalFirstPersonArm() ? VisibleSpecs.getRenderOpacity() : 1.0f);
            float effectiveAlpha = Math.min(selfAlpha, visibleSpecsAlpha);
            if (effectiveAlpha >= 0.99f) return;
            gomod$opacityBlendActive = true;
            if (selfAlpha < 0.99f) {
                gomod$selfOpacityArmActive = true;
                SelfOpacity.beginFirstPersonArm();
            }
            if (visibleSpecsAlpha < 0.99f) {
                gomod$visibleSpecsArmActive = true;
                VisibleSpecs.beginFirstPersonArm();
            }
            // Keep alpha-test enabled so enchanted item glint doesn't render as a square.
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        } catch (Throwable t) { System.err.println("[gomod] ItemRendererMixin selfOpacityArmHead: " + t.getMessage()); }
    }

    @Inject(method = "renderItemInFirstPerson", at = @At("RETURN"))
    private void gomod$selfOpacityArmReturn(float partialTicks, CallbackInfo ci) {
        if (SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_FRAME_RETURN)) {
            SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_FRAME_RETURN,
                    "ItemRenderer.renderItemInFirstPerson:RETURN", itemToRender,
                    "handAlpha=" + SelfOpacity.getFirstPersonHandAlpha());
        }
        gomod$resetArmOpacityGlIfActive();
    }

    private void gomod$ensureFirstPersonHeldItemBlend() {
        if (!SelfOpacity.isFirstPersonHeldItemOpacityActive()
                && !SelfOpacity.isFirstPersonArmActive()) {
            return;
        }
        if (SelfOpacity.getRenderAlpha() >= 0.99F) {
            return;
        }
        // The hand path can turn blending off before the held item is drawn. Reassert it at
        // the item draw boundary even when renderItemInFirstPerson already opened the alpha scope.
        gomod$opacityBlendActive = true;
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.003921569F);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /**
     * Bracket the actual native item-render entry as well as the vanilla call site below. Lunar can
     * bypass that call-site redirect; SelfOpacity's outer arm scope is the blend fallback for that route.
     * The item-only scope still handles native entries outside the arm's own render method.
     */
    @Inject(
            method = "renderItem(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;)V",
            at = @At("HEAD"),
            require = 0
    )
    private void gomod$firstPersonItemBlendHead(EntityLivingBase entity, ItemStack stack,
                                                 ItemCameraTransforms.TransformType transform,
                                                 CallbackInfo ci) {
        if (transform == ItemCameraTransforms.TransformType.FIRST_PERSON) {
            boolean scopeOpened = SelfOpacity.beginFirstPersonHeldItemOpacity();
            if (scopeOpened) {
                gomod$firstPersonItemOpacityScopes++;
            }
            gomod$ensureFirstPersonHeldItemBlend();
            if (SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_RENDER_ENTRY)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_RENDER_ENTRY,
                        "ItemRenderer.renderItem:HEAD", stack,
                        "scopeOpened=" + scopeOpened + " transform=" + transform);
            }
        }
    }

    @Inject(
            method = "renderItem(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;)V",
            at = @At("RETURN"),
            require = 0
    )
    private void gomod$firstPersonItemOpacityReturn(EntityLivingBase entity, ItemStack stack,
                                                     ItemCameraTransforms.TransformType transform,
                                                     CallbackInfo ci) {
        if (transform == ItemCameraTransforms.TransformType.FIRST_PERSON
                && gomod$firstPersonItemOpacityScopes > 0) {
            if (SelfOpacity.shouldLogFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_RENDER_RETURN)) {
                SelfOpacity.logFirstPersonItemDebug(SelfOpacity.FP_ITEM_DEBUG_RENDER_RETURN,
                        "ItemRenderer.renderItem:RETURN", stack,
                        "scopeActiveBeforeClose=" + SelfOpacity.isFirstPersonHeldItemOpacityActive());
            }
            gomod$firstPersonItemOpacityScopes--;
            SelfOpacity.endFirstPersonHeldItemOpacity();
        }
    }

    @Redirect(
            method = "renderItemInFirstPerson",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemRenderer;renderItem(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;)V"),
            require = 0
    )
    private void gomod$renderFirstPersonHeldItemWithOpacity(ItemRenderer renderer, EntityLivingBase entity,
                                                              ItemStack stack,
                                                              ItemCameraTransforms.TransformType transform) {
        boolean opacityScope = SelfOpacity.beginFirstPersonHeldItemOpacity();
        try {
            gomod$ensureFirstPersonHeldItemBlend();
            renderer.renderItem(entity, stack, transform);
        } finally {
            if (opacityScope) SelfOpacity.endFirstPersonHeldItemOpacity();
        }
    }

    @Redirect(
            method = "renderItemMap",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/Tessellator;draw()V"),
            require = 0
    )
    private void gomod$renderFirstPersonMapBackgroundWithOpacity(Tessellator tessellator) {
        boolean opacityScope = SelfOpacity.beginFirstPersonHeldItemOpacity();
        gomod$ensureFirstPersonHeldItemBlend();
        if (opacityScope) GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            tessellator.draw();
        } finally {
            if (opacityScope) {
                SelfOpacity.endFirstPersonHeldItemOpacity();
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            }
        }
    }

    @Redirect(
            method = "renderItemMap",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/MapItemRenderer;renderMap(Lnet/minecraft/world/storage/MapData;Z)V"),
            require = 0
    )
    private void gomod$renderFirstPersonMapDataWithOpacity(MapItemRenderer renderer, MapData data,
                                                            boolean noOverlay) {
        boolean opacityScope = SelfOpacity.beginFirstPersonHeldItemOpacity();
        gomod$ensureFirstPersonHeldItemBlend();
        if (opacityScope) GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        try {
            renderer.renderMap(data, noOverlay);
        } finally {
            if (opacityScope) {
                SelfOpacity.endFirstPersonHeldItemOpacity();
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            }
        }
    }

    @Inject(method = "renderItemInFirstPerson", at = @At("HEAD"), cancellable = true)
    private void gomod$roleplayArmHead(float partialTicks, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.gameSettings.thirdPersonView != 0) {
                gomod$sanitizeNormalFirstPerson(mc);
                return;
            }
            if (LocalMobDisguise.isActive()) {
                gomod$sanitizeNormalFirstPerson(mc);
                // If another HEAD inject enabled arm opacity and we cancel, ensure GL is restored
                // (RETURN injection won't run on cancellation).
                gomod$resetArmOpacityGlIfActive();
                ci.cancel();
                return;
            }

            boolean roleplayArm = RoleplayMode.isEnabled();
            boolean playersPlayArm = !roleplayArm
                    && PlayersPlayMode.isEnabled() && PlayersPlayMode.getCurrentRenderTarget() != null;
            if (!roleplayArm && !playersPlayArm) {
                gomod$sanitizeNormalFirstPerson(mc);
                return;
            }

            EntityPlayer target = roleplayArm
                    ? RoleplayMode.getTargetPlayer()
                    : PlayersPlayMode.getCurrentRenderTarget();
            if (target == null) {
                gomod$sanitizeNormalFirstPerson(mc);
                return;
            }
            gomod$savedViewEntity = mc.getRenderViewEntity();
            mc.setRenderViewEntity(target);

            if (mc.thePlayer != null) {
                gomod$savedLocalPlayer = mc.thePlayer;
                gomod$savedYaw = mc.thePlayer.rotationYaw;
                gomod$savedPrevYaw = mc.thePlayer.prevRotationYaw;
                gomod$savedPitch = mc.thePlayer.rotationPitch;
                gomod$savedPrevPitch = mc.thePlayer.prevRotationPitch;
                gomod$savedRenderArmYaw = mc.thePlayer.renderArmYaw;
                gomod$savedPrevRenderArmYaw = mc.thePlayer.prevRenderArmYaw;
                gomod$savedRenderArmPitch = mc.thePlayer.renderArmPitch;
                gomod$savedPrevRenderArmPitch = mc.thePlayer.prevRenderArmPitch;
                gomod$savedYawHead = mc.thePlayer.rotationYawHead;
                gomod$savedPrevYawHead = mc.thePlayer.prevRotationYawHead;
                gomod$savedSwingProgress = mc.thePlayer.swingProgress;
                gomod$savedPrevSwingProgress = mc.thePlayer.prevSwingProgress;
                gomod$savedIsSwingInProgress = mc.thePlayer.isSwingInProgress;
                gomod$savedSwingProgressInt = mc.thePlayer.swingProgressInt;
                gomod$savedItemInUse = mc.thePlayer.getItemInUse();
                gomod$savedItemInUseCount = mc.thePlayer.getItemInUseCount();

                float armYaw = roleplayArm ? RoleplayMode.getSmoothCameraYaw() : target.rotationYawHead;
                float prevArmYaw = roleplayArm ? RoleplayMode.getSmoothCameraYaw() : target.prevRotationYawHead;
                float armPitch = roleplayArm ? RoleplayMode.getSmoothCameraPitch() : target.rotationPitch;
                float prevArmPitch = roleplayArm ? RoleplayMode.getSmoothCameraPitch() : target.prevRotationPitch;
                if (armYaw == 0.0f && armPitch == 0.0f) {
                    armYaw = target.rotationYawHead;
                    prevArmYaw = target.prevRotationYawHead;
                    armPitch = target.rotationPitch;
                    prevArmPitch = target.prevRotationPitch;
                }

                mc.thePlayer.rotationYaw = armYaw;
                mc.thePlayer.prevRotationYaw = prevArmYaw;
                mc.thePlayer.rotationPitch = armPitch;
                mc.thePlayer.prevRotationPitch = prevArmPitch;
                mc.thePlayer.rotationYawHead = armYaw;
                mc.thePlayer.prevRotationYawHead = prevArmYaw;
                mc.thePlayer.renderArmYaw = armYaw;
                mc.thePlayer.prevRenderArmYaw = prevArmYaw;
                mc.thePlayer.renderArmPitch = armPitch;
                mc.thePlayer.prevRenderArmPitch = prevArmPitch;
                mc.thePlayer.swingProgress = target.swingProgress;
                mc.thePlayer.prevSwingProgress = target.prevSwingProgress;
                mc.thePlayer.isSwingInProgress = target.isSwingInProgress;
                mc.thePlayer.swingProgressInt = target.swingProgressInt;

                ItemStack targetItemInUse = target.getItemInUse();
                int targetItemInUseCount = target.getItemInUseCount();
                if (targetItemInUse != null && targetItemInUseCount > 0) {
                    mc.thePlayer.setItemInUse(targetItemInUse, targetItemInUseCount);
                } else {
                    mc.thePlayer.clearItemInUse();
                }
            }

            gomod$savedItemToRender = this.itemToRender;
            gomod$savedEquippedItemSlot = this.equippedItemSlot;
            gomod$savedEquippedProgress = this.equippedProgress;
            gomod$savedPrevEquippedProgress = this.prevEquippedProgress;
            ItemStack targetHeldItem = target.getHeldItem();
            this.itemToRender = targetHeldItem;
            this.equippedItemSlot = target.inventory.currentItem;
            if (ItemStack.areItemStacksEqual(gomod$savedItemToRender, targetHeldItem)
                    && gomod$savedEquippedItemSlot == target.inventory.currentItem) {
                this.equippedProgress = gomod$savedEquippedProgress;
                this.prevEquippedProgress = gomod$savedPrevEquippedProgress;
            } else {
                this.equippedProgress = 1.0f;
                this.prevEquippedProgress = 1.0f;
            }
            gomod$specialArmOverrideActive = true;

            // Signal skin/item overrides on mc.thePlayer during arm rendering.
            if (roleplayArm) {
                RoleplayMode.setRenderingFirstPersonArm(true);
            } else if (playersPlayArm) {
                PlayersPlayMode.setRenderingFirstPersonArm(true);
            }
        } catch (Throwable t) { System.err.println("[gomod] Error in ItemRendererMixin: " + t.getMessage()); }
    }

    @Inject(method = "renderItemInFirstPerson", at = @At("RETURN"))
    private void gomod$roleplayArmReturn(float partialTicks, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            gomod$restoreAfterSpecialArmRender(mc);
        } catch (Throwable t) {
            gomod$restoreAfterSpecialArmRender(Minecraft.getMinecraft());
            System.err.println("[gomod] Error in ItemRendererMixin: " + t.getMessage());
        }
    }

    @Redirect(
            method = "renderItemInFirstPerson",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/AbstractClientPlayer;getSwingProgress(F)F"
            )
    )
    private float gomod$redirectSwingProgress(AbstractClientPlayer player, float partialTicks) {
        EntityPlayer target = gomod$getSpecialArmTarget();
        return target != null ? target.getSwingProgress(partialTicks) : player.getSwingProgress(partialTicks);
    }

    @Redirect(
            method = "renderItemInFirstPerson",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/EntityPlayerSP;getItemInUseCount()I"
            )
    )
    private int gomod$redirectItemInUseCount(EntityPlayerSP player) {
        EntityPlayer target = gomod$getSpecialArmTarget();
        return target != null ? target.getItemInUseCount() : player.getItemInUseCount();
    }

    @Redirect(
            method = "renderItemInFirstPerson",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/EntityPlayerSP;isInvisible()Z"
            )
    )
    private boolean gomod$redirectIsInvisible(EntityPlayerSP player) {
        EntityPlayer target = gomod$getSpecialArmTarget();
        return target != null ? target.isInvisible() : player.isInvisible();
    }

    @Redirect(
            method = "renderOverlays",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemRenderer;renderWaterOverlayTexture(F)V"
            ),
            require = 0
    )
    private void gomod$maybeSkipWaterOverlay(ItemRenderer renderer, float partialTicks) {
        gomod$invokeOverlayMethod(renderer, partialTicks, "renderWaterOverlayTexture", "func_78448_c");
    }

    private void gomod$restoreAfterSpecialArmRender(Minecraft mc) {
        if (!gomod$specialArmOverrideActive) {
            return;
        }

        RoleplayMode.setRenderingFirstPersonArm(false);
        PlayersPlayMode.setRenderingFirstPersonArm(false);
        this.itemToRender = gomod$savedItemToRender;
        if (gomod$savedEquippedItemSlot >= 0) {
            this.equippedItemSlot = gomod$savedEquippedItemSlot;
        }
        this.equippedProgress = gomod$savedEquippedProgress;
        this.prevEquippedProgress = gomod$savedPrevEquippedProgress;
        gomod$restoreSavedLocalRotation();
        if (mc != null && gomod$savedViewEntity != null) {
            mc.setRenderViewEntity(gomod$savedViewEntity);
        }
        gomod$clearSavedArmState();
    }

    private void gomod$sanitizeNormalFirstPerson(Minecraft mc) {
        boolean hadSavedState = gomod$savedViewEntity != null
                || gomod$savedLocalPlayer != null
                || gomod$savedItemToRender != null
                || gomod$savedEquippedItemSlot >= 0;
        if (!hadSavedState) {
            return;
        }

        RoleplayMode.setRenderingFirstPersonArm(false);
        PlayersPlayMode.setRenderingFirstPersonArm(false);
        gomod$restoreSavedLocalRotation();
        if (mc != null && mc.thePlayer != null) {
            // While roleplay/share keep the frame's camera entity on the remote target; vanilla
            // third-person would otherwise leave renderViewEntity on the local player here and
            // desync the rest of the frame (RoleplayRendererMixin still expects the target).
            if (RoleplayMode.isEnabled()) {
                EntityPlayer t = RoleplayMode.getTargetPlayer();
                if (t != null) {
                    mc.setRenderViewEntity(t);
                }
            } else {
                mc.setRenderViewEntity(mc.thePlayer);
            }
        }
        gomod$clearSavedArmState();
        gomod$resetEquippedProgressReflective();
    }

    private void gomod$restoreSavedLocalRotation() {
        if (gomod$savedLocalPlayer == null) {
            return;
        }

        gomod$savedLocalPlayer.rotationYaw = gomod$savedYaw;
        gomod$savedLocalPlayer.prevRotationYaw = gomod$savedPrevYaw;
        gomod$savedLocalPlayer.rotationPitch = gomod$savedPitch;
        gomod$savedLocalPlayer.prevRotationPitch = gomod$savedPrevPitch;
        gomod$savedLocalPlayer.renderArmYaw = gomod$savedRenderArmYaw;
        gomod$savedLocalPlayer.prevRenderArmYaw = gomod$savedPrevRenderArmYaw;
        gomod$savedLocalPlayer.renderArmPitch = gomod$savedRenderArmPitch;
        gomod$savedLocalPlayer.prevRenderArmPitch = gomod$savedPrevRenderArmPitch;
        gomod$savedLocalPlayer.rotationYawHead = gomod$savedYawHead;
        gomod$savedLocalPlayer.prevRotationYawHead = gomod$savedPrevYawHead;
        gomod$savedLocalPlayer.swingProgress = gomod$savedSwingProgress;
        gomod$savedLocalPlayer.prevSwingProgress = gomod$savedPrevSwingProgress;
        gomod$savedLocalPlayer.isSwingInProgress = gomod$savedIsSwingInProgress;
        gomod$savedLocalPlayer.swingProgressInt = gomod$savedSwingProgressInt;
        if (gomod$savedItemInUse != null && gomod$savedItemInUseCount > 0) {
            gomod$savedLocalPlayer.setItemInUse(gomod$savedItemInUse, gomod$savedItemInUseCount);
        } else {
            gomod$savedLocalPlayer.clearItemInUse();
        }
    }

    private void gomod$clearSavedArmState() {
        gomod$savedViewEntity = null;
        gomod$savedLocalPlayer = null;
        gomod$savedItemInUse = null;
        gomod$savedItemToRender = null;
        gomod$savedEquippedItemSlot = -1;
        gomod$savedItemInUseCount = 0;
        gomod$savedEquippedProgress = 0.0f;
        gomod$savedPrevEquippedProgress = 0.0f;
        gomod$specialArmOverrideActive = false;
    }

    private void gomod$resetEquippedProgressReflective() {
        gomod$invokeIfPresent("resetEquippedProgress");
        gomod$invokeIfPresent("resetEquippedProgress2");
        gomod$invokeIfPresent("func_78444_b");
        gomod$invokeIfPresent("func_78445_c");
    }

    private void gomod$invokeIfPresent(String methodName) {
        try {
            Method method = ItemRenderer.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(this);
        } catch (Throwable ignored) {}
    }

    private static void gomod$invokeOverlayMethod(ItemRenderer renderer, float partialTicks, String methodName, String obfName) {
        try {
            Method method;
            try {
                method = ItemRenderer.class.getDeclaredMethod(methodName, float.class);
            } catch (NoSuchMethodException ignored) {
                method = ItemRenderer.class.getDeclaredMethod(obfName, float.class);
            }
            method.setAccessible(true);
            method.invoke(renderer, partialTicks);
        } catch (Throwable t) {
            System.err.println("[gomod] Water overlay invoke failed: " + t.getMessage());
        }
    }

    private EntityPlayer gomod$getSpecialArmTarget() {
        if (!gomod$specialArmOverrideActive) {
            return null;
        }
        if (RoleplayMode.isRenderingFirstPersonArm()) {
            return RoleplayMode.getTargetPlayer();
        }
        if (PlayersPlayMode.isRenderingFirstPersonArm()) {
            return PlayersPlayMode.getCurrentRenderTarget();
        }
        return null;
    }
}
