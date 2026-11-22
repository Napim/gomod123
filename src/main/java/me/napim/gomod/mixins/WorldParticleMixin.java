package me.napim.gomod.mixins;

import me.napim.gomod.features.BorderIndication;
import me.napim.gomod.features.BlitzPregamePlayerToggle;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(World.class)
public class WorldParticleMixin {

    @Inject(method = "spawnParticle(Lnet/minecraft/util/EnumParticleTypes;DDDDDD[I)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$trackParticles(EnumParticleTypes particleType,
                                      double xCoord, double yCoord, double zCoord,
                                      double xSpeed, double ySpeed, double zSpeed,
                                      int[] parameters, CallbackInfo ci) {
        if (gomod$cancelHiddenPlayerMovementParticle(particleType, xCoord, yCoord, zCoord)) {
            ci.cancel();
            return;
        }
        gomod$trackBorderParticle(particleType, xCoord, yCoord, zCoord);
    }

    @Inject(method = "spawnParticle(Lnet/minecraft/util/EnumParticleTypes;ZDDDDDD[I)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$trackParticlesLongDistance(EnumParticleTypes particleType, boolean longDistance,
                                                  double xCoord, double yCoord, double zCoord,
                                                  double xSpeed, double ySpeed, double zSpeed,
                                                  int[] parameters, CallbackInfo ci) {
        if (gomod$cancelHiddenPlayerMovementParticle(particleType, xCoord, yCoord, zCoord)) {
            ci.cancel();
            return;
        }
        gomod$trackBorderParticle(particleType, xCoord, yCoord, zCoord);
    }

    private static boolean gomod$cancelHiddenPlayerMovementParticle(
            EnumParticleTypes particleType, double x, double y, double z) {
        try {
            if (particleType != EnumParticleTypes.BLOCK_CRACK
                    && particleType != EnumParticleTypes.BLOCK_DUST) {
                return false;
            }
            return BlitzPregamePlayerToggle.isHiddenPlayerNear(x, y, z, 1.75D);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void gomod$trackBorderParticle(EnumParticleTypes particleType, double xCoord, double yCoord, double zCoord) {
        try {
            if (!BorderIndication.isEnabled() || particleType == null) {
                return;
            }

            int particleId = particleType.getParticleID();
            if (particleId == 30 || particleId == 26) {
                BorderIndication.onParticleSpawn(particleId, xCoord, yCoord, zCoord);
            }
        } catch (Throwable ignored) {
        }
    }
}
