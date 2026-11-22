package me.napim.gomod.mixins;

import me.napim.gomod.features.LightningRenderFix;
import me.napim.gomod.features.MountHeadDim;
import me.napim.gomod.features.RoleplayMode;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.Selfie;
import me.napim.gomod.features.VisibleSpecs;
import me.napim.gomod.features.DebugHiddenPlayers;
import me.napim.gomod.features.BlitzPregamePlayerToggle;
import me.napim.gomod.features.RenderProfiler;
import me.napim.gomod.util.ForgeGuiPlayerRenderState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to fix nametag orientation during selfie mode.
 * When selfie mode is active, nametags should orient to the frozen camera rotation,
 * not the player's actual rotation.
 */
@Mixin(RenderManager.class)
public class RenderManagerMixin {
    
    @Shadow
    public float playerViewY;
    
    @Shadow
    public float playerViewX;

    @Shadow
    public Entity livingPlayer;
    
    /**
     * Inject after cacheActiveRenderInfo to override view angles with frozen camera rotation
     * when selfie mode is enabled. This ensures nametags and other billboards face the
     * frozen camera instead of following the player's actual rotation.
     */
    @Inject(method = "cacheActiveRenderInfo", at = @At("RETURN"))
    private void gomod$overrideViewAnglesForSelfie(net.minecraft.world.World worldIn, net.minecraft.client.gui.FontRenderer textRendererIn, Entity livingPlayerIn, Entity pointedEntityIn, net.minecraft.client.settings.GameSettings optionsIn, float partialTicks, CallbackInfo ci) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            // Only apply if selfie mode is enabled
            if (Selfie.isEnabled()) {
                if (mc != null && mc.getRenderViewEntity() != null) {
                    this.playerViewY = Selfie.getCameraYaw();
                    this.playerViewX = Selfie.getCameraPitch();
                }
                return;
            }

            // Roleplay mode: nametag billboards must face the smoothed camera direction
            // (computed by RoleplayRendererMixin) so they don't wobble relative to the view.
            if (RoleplayMode.isEnabled()) {
                this.playerViewY = RoleplayMode.getSmoothCameraYaw();
                this.playerViewX = RoleplayMode.getSmoothCameraPitch();

                // Fix: RenderPlayer.doRender checks `livingPlayer == entity` to decide
                // whether to render a player's body.  Because we swapped renderViewEntity
                // to the target for the whole frame, cacheActiveRenderInfo set livingPlayer
                // to the target — making the check fail for mc.thePlayer, hiding our body.
                // Override livingPlayer back to the real local player so our body renders.
                if (mc != null && mc.thePlayer != null) {
                    this.livingPlayer = mc.thePlayer;
                }
                return;
            }

            // Leave vanilla third-person-front billboard pitch untouched.
            // Forcing an X inversion here can make nametag quads look unstable/skewed
            // while moving in front view (F5 twice).

        } catch (Throwable ignored) {
            // Ignore errors to prevent crashes
        }
    }

    /**
     * When roleplay mode is active, force the local player to be considered
     * renderable. Vanilla's shouldRender skips the entity that matches its
     * cached "livingPlayer" reference (always mc.thePlayer) when thirdPersonView==0.
     * Since we swapped renderViewEntity to the target for the whole frame, the
     * local player is no longer the view entity, but shouldRender still hides it.
     * Returning true here fixes the invisible-local-player bug.
     */
    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$forceRenderLocalPlayerInRoleplay(Entity entity, ICamera camera, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!RoleplayMode.isEnabled()) return;
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;
            if (entity == mc.thePlayer) {
                cir.setReturnValue(true);
            }
        } catch (Throwable t) { System.err.println("[gomod] Error in RenderManagerMixin: " + t.getMessage()); }
    }

    /**
     * Mount dim strategy 8+: tint at {@code RenderManager} level so Lunar/world paths that
     * only call {@code renderEntitySimple} still pick up alpha (inner {@code RendererLivingEntity}
     * hooks may not run).
     * <p>
     * Depth mode 6: handled here with {@code cancellable} — two inner {@code renderEntitySimple} calls
     * then cancel the outer invocation (single HEAD injector avoids ordering issues with cancel).
     * <p>
     * Vanilla 1.8.9: {@code boolean renderEntitySimple(Entity, float)} — mixin must use
     * {@link CallbackInfoReturnable}&lt;Boolean&gt;, not {@link CallbackInfo} (Lunar fails otherwise).
     */
    @Inject(method = "renderEntitySimple", at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$mountDimEntitySimpleHead(Entity entityIn, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        boolean player = entityIn instanceof EntityPlayer;
        boolean bypass = player && RenderProfiler.shouldBypassPlayerHooks();
        String deepSection = player
                ? "DeepPlayer.RenderManager.head." + (bypass ? "bypassed" : "active") : null;
        long deepProfile = player ? RenderProfiler.beginDeep(deepSection) : 0L;
        try {
            if (bypass) return;
            if (gomod$isOrdinaryRemotePlayer(entityIn)) return;
            if (MountHeadDim.shouldCancelAndScheduleNormalMountRender(entityIn, partialTicks)) {
                cir.setReturnValue(false);
                cir.cancel();
                return;
            }
            Boolean hybrid = MountHeadDim.runHybridMountDrawIfNeeded((RenderManager) (Object) this, entityIn, partialTicks);
            if (hybrid != null) {
                cir.setReturnValue(hybrid.booleanValue());
                cir.cancel();
                return;
            }
            Boolean selfOpacity = SelfOpacity.runWeaveDirectTwoPassIfNeeded(
                    (RenderManager) (Object) this, entityIn, partialTicks);
            if (selfOpacity != null) {
                cir.setReturnValue(selfOpacity.booleanValue());
                cir.cancel();
                return;
            }
            Boolean visibleSpecsOpacity = VisibleSpecs.runWeaveDirectTwoPassIfNeeded(
                    (RenderManager) (Object) this, entityIn, partialTicks);
            if (visibleSpecsOpacity != null) {
                cir.setReturnValue(visibleSpecsOpacity.booleanValue());
                cir.cancel();
                return;
            }
            MountHeadDim.onRenderEntitySimpleHead(entityIn);
            SelfOpacity.onRenderEntitySimpleHead(entityIn);
            VisibleSpecs.onRenderEntitySimpleHead(entityIn);
        } catch (Throwable t) {
            System.err.println("[gomod] Error in RenderManagerMixin.mountDim HEAD: " + t.getMessage());
        } finally {
            RenderProfiler.endDeep(deepSection, deepProfile);
        }
    }

    @Inject(method = "renderEntitySimple", at = @At("RETURN"), require = 0)
    private void gomod$mountDimEntitySimpleTail(Entity entityIn, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        boolean player = entityIn instanceof EntityPlayer;
        boolean bypass = player && RenderProfiler.shouldBypassPlayerHooks();
        String deepSection = player
                ? "DeepPlayer.RenderManager.tail." + (bypass ? "bypassed" : "active") : null;
        long deepProfile = player ? RenderProfiler.beginDeep(deepSection) : 0L;
        try {
            if (bypass) return;
            if (gomod$isOrdinaryRemotePlayer(entityIn)) return;
            MountHeadDim.onRenderEntitySimpleTail(entityIn);
            SelfOpacity.onRenderEntitySimpleTail(entityIn);
            VisibleSpecs.onRenderEntitySimpleTail(entityIn);
        } catch (Throwable t) {
            System.err.println("[gomod] Error in RenderManagerMixin.mountDim TAIL: " + t.getMessage());
        } finally {
            RenderProfiler.endDeep(deepSection, deepProfile);
        }
    }

    /**
     * Mount opacity and self opacity only target the mount/local player. Visible Specs remote players only need
     * this RenderManager compatibility stack when that specific player is an opacity target. Previously every
     * remote player went through the stack whenever spectator mode was active, which was a large Forge cost.
     */
    private static boolean gomod$isOrdinaryRemotePlayer(Entity entity) {
        if (!(entity instanceof EntityPlayer)) {
            return false;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || entity == mc.thePlayer) {
            return false;
        }
        // Mount dim/self-opacity are local-player features. When VisibleSpecs is not
        // active, a remote player is always ordinary; avoid the per-render entity-id
        // cache/map probe for every Forge player.
        if (!VisibleSpecs.isRuntimeActiveForRender()) {
            return true;
        }
        return !VisibleSpecs.shouldApplyToEntityForRender(entity);
    }

    /**
     * Lowest common RenderManager boundary used by vanilla, Forge and Replay Mod.
     * Cancelling here suppresses the model, armor/layers, held items, nametag,
     * shadow and fire without mutating the recorded entity.
     */
    @Inject(method = "doRenderEntity", at = @At("HEAD"), cancellable = true, require = 1)
    private void gomod$hideDebugPlayerEverywhere(Entity entityIn, double x, double y, double z,
            float entityYaw, float partialTicks, boolean hideDebugBox,
            CallbackInfoReturnable<Boolean> cir) {
        if (entityIn instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        if (DebugHiddenPlayers.shouldHide(entityIn) || BlitzPregamePlayerToggle.shouldHide(entityIn)) {
            cir.setReturnValue(false);
        }
    }

    /**
     * Vanilla 1.8.9 returns {@code boolean} from {@code renderEntityWithPosYaw}.
     * Mixin therefore requires {@link CallbackInfoReturnable} even though these
     * observers do not read or replace the return value.
     */
    @Inject(method = "renderEntityWithPosYaw", at = @At("HEAD"), require = 0)
    private void gomod$selfOpacityInventoryPreviewHead(Entity entityIn, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        if (entityIn instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        try {
            ForgeGuiPlayerRenderState.begin(entityIn, x, y, z, partialTicks);
            VisibleSpecs.beginGuiInventoryEntityRender(entityIn);
            SelfOpacity.beginGuiInventoryEntityRender(entityIn);
        } catch (Throwable t) {
            System.err.println("[gomod] self-opacity inventory preview HEAD: " + t.getMessage());
        }
    }

    @Inject(method = "renderEntityWithPosYaw", at = @At("RETURN"), require = 0)
    private void gomod$selfOpacityInventoryPreviewTail(Entity entityIn, double x, double y, double z,
            float entityYaw, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        if (entityIn instanceof EntityPlayer && RenderProfiler.shouldBypassPlayerHooks()) return;
        try {
            SelfOpacity.endGuiInventoryEntityRender(entityIn);
            VisibleSpecs.endGuiInventoryEntityRender(entityIn);
        } catch (Throwable t) {
            System.err.println("[gomod] self-opacity inventory preview TAIL: " + t.getMessage());
        } finally {
            ForgeGuiPlayerRenderState.end();
        }
    }

    @Inject(method = "renderEntitySimple", at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$deferLightningRenderEntitySimple(Entity entityIn, float partialTicks, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (LightningRenderFix.shouldSkipAndDefer(entityIn, partialTicks)) {
                cir.setReturnValue(false);
                cir.cancel();
            }
        } catch (Throwable t) {
            System.err.println("[gomod] deferLightning renderEntitySimple: " + t.getMessage());
        }
    }

    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$deferLightningRenderEntity(Entity entityIn, float partialTicks, CallbackInfo ci) {
        try {
            if (LightningRenderFix.shouldSkipAndDefer(entityIn, partialTicks)) {
                ci.cancel();
            }
        } catch (Throwable t) {
            System.err.println("[gomod] deferLightning renderEntity: " + t.getMessage());
        }
    }

    /**
     * While drawing the dimmed mount, {@code renderEntity} is called for the rider (local player).
     * Suspend mount GL overrides for that subtree so the player body stays opaque in third person.
     * <p>
     * 1.8.9: {@code void renderEntity(Entity, float, float, boolean)} — {@code require = 0} if mappings differ.
     */
    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$mountDimPassengerHead(Entity entityIn, float yaw, float partialTicks, boolean hideLiving, CallbackInfo ci) {
        try {
            MountHeadDim.onRenderEntityPassengerHead(entityIn);
        } catch (Throwable t) {
            System.err.println("[gomod] Error in RenderManagerMixin.mountPassenger HEAD: " + t.getMessage());
        }
    }

    @Inject(method = "renderEntity", at = @At("RETURN"), require = 0)
    private void gomod$mountDimPassengerTail(Entity entityIn, float yaw, float partialTicks, boolean hideLiving, CallbackInfo ci) {
        try {
            MountHeadDim.onRenderEntityPassengerTail(entityIn);
        } catch (Throwable t) {
            System.err.println("[gomod] Error in RenderManagerMixin.mountPassenger TAIL: " + t.getMessage());
        }
    }
}
