package me.napim.gomod.mixins;

import me.napim.gomod.features.ForcePigManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.util.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block mounting / interaction on client-only force pigs (saddle is visual only).
 */
@Mixin(EntityPig.class)
public abstract class EntityPigForcePigMixin {
    private static final java.util.Map<Integer, Float> gomod$targetYaw = new java.util.HashMap<>();
    private static final java.util.Map<Integer, Long> gomod$nextTurnAtMs = new java.util.HashMap<>();

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void gomod$blockForcePigInteract(EntityPlayer player, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (ForcePigManager.isForcePig((EntityPig) (Object) this)) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Drive force pigs with vanilla-style heading/forward controls (no teleport-like dragging).
     */
    @Inject(method = "onLivingUpdate", at = @At("HEAD"))
    private void gomod$forcePigWalk(CallbackInfo ci) {
        try {
            EntityPig self = (EntityPig) (Object) this;
            if (!ForcePigManager.isForcePig(self) || !self.isEntityAlive()) {
                int id = self.getEntityId();
                gomod$targetYaw.remove(id);
                gomod$nextTurnAtMs.remove(id);
                return;
            }
            if (ForcePigManager.isManualMotionEnabled()) {
                return;
            }
            if (self.worldObj == null || !self.worldObj.isRemote) {
                return;
            }
            int id = self.getEntityId();
            long now = System.currentTimeMillis();
            java.util.Random r = self.getRNG();
            Float target = gomod$targetYaw.get(id);
            Long turnAt = gomod$nextTurnAtMs.get(id);
            if (target == null || turnAt == null || now >= turnAt) {
                target = r.nextFloat() * 360.0f;
                gomod$targetYaw.put(id, target);
                gomod$nextTurnAtMs.put(id, now + 170L + r.nextInt(320));
            }
            float diff = MathHelper.wrapAngleTo180_float(target - self.rotationYaw);
            float maxTurn = 12.0f;
            if (diff > maxTurn) diff = maxTurn;
            if (diff < -maxTurn) diff = -maxTurn;
            float yaw = self.rotationYaw + diff;

            self.rotationYaw = yaw;
            self.renderYawOffset = yaw;
            self.rotationYawHead = yaw;
            self.prevRotationYaw = yaw;

            self.moveStrafing = 0.0f;
            self.moveForward = 1.0f;
            float aiSpeed = (float) Math.max(0.18, Math.min(0.65, ForcePigManager.getMoveSpeedBlocksPerSec() / 9.0));
            self.setAIMoveSpeed(aiSpeed);
            if (self.isCollidedHorizontally && self.onGround) {
                self.motionY = 0.34;
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "onLivingUpdate", at = @At("RETURN"))
    private void gomod$forcePigApplyVanillaMove(CallbackInfo ci) {
        try {
            EntityPig self = (EntityPig) (Object) this;
            if (!ForcePigManager.isForcePig(self) || !self.isEntityAlive()) {
                return;
            }
            if (ForcePigManager.isManualMotionEnabled()) {
                return;
            }
            if (self.worldObj == null || !self.worldObj.isRemote) {
                return;
            }
            // Vanilla AI can overwrite moveForward after our HEAD hook; re-apply intent here.
            self.moveStrafing = 0.0f;
            self.moveForward = 1.0f;
            float aiSpeed = (float) Math.max(0.18, Math.min(0.65, ForcePigManager.getMoveSpeedBlocksPerSec() / 9.0));
            self.setAIMoveSpeed(aiSpeed);
            self.moveEntityWithHeading(self.moveStrafing, self.moveForward);
        } catch (Throwable ignored) {
        }
    }
}
