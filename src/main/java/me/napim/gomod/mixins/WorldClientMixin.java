package me.napim.gomod.mixins;

import me.napim.gomod.features.BarrierVisibility;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.EntityNametagLimiter;
import me.napim.gomod.features.FakeBreak;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.BlockPos;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to WorldClient to prevent server block updates from restoring fake-broken blocks
 */
@Mixin(WorldClient.class)
public class WorldClientMixin {
    @Inject(method = {"onEntityAdded", "onEntityRemoved"}, at = @At("RETURN"))
    private void gomod$refreshNametagCandidates(Entity entity, CallbackInfo ci) {
        EntityNametagLimiter.onEntityListChanged((WorldClient) (Object) this, entity);
    }
    
    /**
     * Intercepts block state changes to prevent server from restoring fake-broken blocks
     */
    @Inject(method = "setBlockState", at = @At("HEAD"), cancellable = true)
    private void gomod$preventBlockRestore(BlockPos pos, IBlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
        try {
            // If this block was fake-broken and the server is trying to restore it, cancel the update
            if (FakeBreak.isBrokenBlock(pos)) {
                // Check if the server is trying to restore a non-air block
                if (!state.getBlock().getMaterial().isReplaceable()) {
                    if (FakeBreak.shouldAllowRestoreUpdate(pos)) {
                        return;
                    }
                    // Cancel the block state change to keep it broken
                    cir.setReturnValue(false);
                }
            }
        } catch (Exception e) {
            // Don't interfere if something goes wrong
        }
    }
    
    /**
     * Utility to perform a small client-side explosion that removes nearby blocks via FakeBreak
     */
    private static void gomod$clientExplodeAt(net.minecraft.client.multiplayer.WorldClient world, double x, double y, double z, float radius) {
        try {
            // Render explosion particles client-side
            for (int i = 0; i < 80; i++) {
                double ox = x + (world.rand.nextDouble() - 0.5) * radius * 2.0;
                double oy = y + (world.rand.nextDouble() - 0.5) * radius * 2.0;
                double oz = z + (world.rand.nextDouble() - 0.5) * radius * 2.0;
                world.spawnParticle(EnumParticleTypes.EXPLOSION_NORMAL, ox, oy, oz, 0.0, 0.0, 0.0);
            }

            int ir = (int)Math.ceil(radius);
            BlockPos center = new BlockPos(x, y, z);
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dy = -ir; dy <= ir; dy++) {
                    for (int dz = -ir; dz <= ir; dz++) {
                        BlockPos p = center.add(dx, dy, dz);
                        double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                        if (dist <= radius) {
                            // Mark as broken and force to air; packet hooks will keep it gone
                            if (world.isBlockLoaded(p)) {
                                FakeBreak.addBrokenBlock(p);
                                world.setBlockToAir(p);
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) { System.err.println("[gomod] Error in WorldClientMixin: " + t.getMessage()); }
    }

    /** Matches BarrierVisibility weave mod: skip void-fog particles while barrier blocks are shown as solid. */
    @Inject(method = {"doVoidFogParticles", "func_73029_E"}, at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$barrierVisibilitySkipVoidFogParticles(CallbackInfo ci) {
        try {
            if (BarrierVisibility.isEffectivelyEnabled()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }
}
