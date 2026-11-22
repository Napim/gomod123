package me.napim.gomod.mixins;

import me.napim.gomod.features.FakeBreak;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumParticleTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to make client-spawned TNT explode and break blocks client-side
 */
@Mixin(EntityTNTPrimed.class)
public class EntityTNTPrimedMixin {
    
    @Shadow
    public int fuse;
    
    /**
     * When client-spawned TNT reaches 0 fuse, perform client-side explosion
     */
    @Inject(method = "onUpdate", at = @At("HEAD"))
    private void gomod$clientExplosion(CallbackInfo ci) {
        EntityTNTPrimed self = (EntityTNTPrimed)(Object)this;
        
        // Check if on client side
        if (self.worldObj == null) return;
        if (!self.worldObj.isRemote) return; // Only client-side
        
        try {
            // When fuse reaches 1 (about to explode on next tick)
            if (fuse == 1) {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc == null || mc.theWorld == null) return;
                
                double x = self.posX;
                double y = self.posY;
                double z = self.posZ;
                
                System.out.println("[FakeBreak] TNT exploding at " + x + ", " + y + ", " + z);
                
                // Play explosion sound
                mc.theWorld.playSound(x, y, z, "random.explode", 4.0F, (1.0F + (mc.theWorld.rand.nextFloat() - mc.theWorld.rand.nextFloat()) * 0.2F) * 0.7F, false);
                
                // Push nearby TNT entities (vanilla behavior - approximate formula)
                double pushRadius = 7.0;
                for (net.minecraft.entity.Entity e : (java.util.List<net.minecraft.entity.Entity>) mc.theWorld.loadedEntityList) {
                    if (!(e instanceof EntityTNTPrimed)) continue;
                    if (e == self) continue; // Don't push self
                    
                    double edx = e.posX - x;
                    double edy = e.posY - y;
                    double edz = e.posZ - z;
                    double eDist = Math.sqrt(edx*edx + edy*edy + edz*edz);
                    
                    if (eDist <= pushRadius && eDist > 0.1) {
                        // Vanilla-like knockback: strength decreases with distance squared
                        // Base TNT explosion strength is ~4.0, knockback = (1 - distance/size) * strength
                        double impactPercent = 1.0 - (eDist / pushRadius);
                        double knockback = impactPercent * impactPercent * 0.6; // Vanilla uses squared falloff
                        
                        e.motionX += (edx / eDist) * knockback;
                        e.motionY += (edy / eDist) * knockback;
                        e.motionZ += (edz / eDist) * knockback;
                        
                        // Chain reaction if close
                        if (eDist <= 4.0 && e instanceof EntityTNTPrimed) {
                            EntityTNTPrimed tnt = (EntityTNTPrimed) e;
                            if (tnt.fuse > 10) {
                                tnt.fuse = 10 + mc.theWorld.rand.nextInt(10); // Random delay 0.5-1s
                            }
                        }
                    }
                }
                
                // Spawn lots of explosion particles
                for (int i = 0; i < 100; i++) {
                    double ox = x + (mc.theWorld.rand.nextDouble() - 0.5) * 8.0;
                    double oy = y + (mc.theWorld.rand.nextDouble() - 0.5) * 8.0;
                    double oz = z + (mc.theWorld.rand.nextDouble() - 0.5) * 8.0;
                    mc.theWorld.spawnParticle(EnumParticleTypes.EXPLOSION_LARGE, ox, oy, oz, 0.0D, 0.0D, 0.0D);
                }
                mc.theWorld.spawnParticle(EnumParticleTypes.EXPLOSION_HUGE, x, y, z, 1.0D, 0.0D, 0.0D);
                
                // Break blocks in radius with resistance check and spawn proper vanilla drops
                int radius = 4;
                BlockPos center = new BlockPos(x, y, z);
                int broken = 0;
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                            if (dist <= radius) {
                                BlockPos p = center.add(dx, dy, dz);
                                if (mc.theWorld.isBlockLoaded(p)) {
                                    try {
                                        net.minecraft.block.state.IBlockState state = mc.theWorld.getBlockState(p);
                                        net.minecraft.block.Block block = state.getBlock();
                                        
                                        // Skip air and check resistance
                                        if (block == null || block.getMaterial().isReplaceable()) continue;
                                        
                                        // TNT explosion strength diminishes with distance
                                        float strength = (float)(radius - dist) / (float)radius;
                                        float resistance = block.getExplosionResistance(self);
                                        
                                        // Only break if strength overcomes resistance (simplified TNT logic)
                                        if (strength * 7.0f > resistance) {
                                            // Compute vanilla-like drops via getItemDropped/quantityDropped
                                            net.minecraft.item.Item item = block.getItemDropped(state, mc.theWorld.rand, 0);
                                            java.util.List<net.minecraft.item.ItemStack> drops = null;
                                            if (item != null) {
                                                int qty = block.quantityDropped(mc.theWorld.rand);
                                                if (qty > 0) {
                                                    drops = new java.util.ArrayList<>();
                                                    drops.add(new net.minecraft.item.ItemStack(item, qty, block.damageDropped(state)));
                                                }
                                            }
                                            
                                            // Break the block
                                            FakeBreak.addBrokenBlock(p);
                                            mc.theWorld.setBlockToAir(p);
                                            broken++;
                                            
                                            if (drops != null) {
                                                for (net.minecraft.item.ItemStack drop : drops) {
                                                    if (drop == null || drop.getItem() == null) continue;
                                                    net.minecraft.entity.item.EntityItem ent = new net.minecraft.entity.item.EntityItem(
                                                        mc.theWorld, 
                                                        p.getX() + 0.5, 
                                                        p.getY() + 0.5, 
                                                        p.getZ() + 0.5, 
                                                        drop.copy()
                                                    );
                                                    // Explosion velocity
                                                    double vx = (p.getX() - x) * 0.15;
                                                    double vy = 0.2 + Math.random() * 0.15;
                                                    double vz = (p.getZ() - z) * 0.15;
                                                    ent.motionX = vx;
                                                    ent.motionY = vy;
                                                    ent.motionZ = vz;
                                                    if (ent instanceof me.napim.gomod.mixins.EntityItemAccessor) {
                                                        ((me.napim.gomod.mixins.EntityItemAccessor) ent).gomod$setDelayBeforeCanPickup(10);
                                                    }
                                                    mc.theWorld.spawnEntityInWorld(ent);
                                                    FakeBreak.registerClientDrop(ent);
                                                }
                                            }
                                        }
                                    } catch (Throwable t) { System.err.println("[gomod] Error in EntityTNTPrimedMixin: " + t.getMessage()); }
                                }
                            }
                        }
                    }
                }
                System.out.println("[FakeBreak] TNT broke " + broken + " blocks client-side");
            }
        } catch (Throwable t) {
            System.err.println("[FakeBreak] TNT explosion error: " + t.getMessage());
            t.printStackTrace();
        }
    }
    
    /**
     * Get the item drop from a block (same logic as PlayerControllerMPMixin)
     */
    private net.minecraft.item.ItemStack getBlockDrop(net.minecraft.block.Block block, net.minecraft.block.state.IBlockState state, net.minecraft.world.World world, BlockPos pos) {
        try {
            int metadata = block.getMetaFromState(state);
            net.minecraft.item.Item item = block.getItemDropped(state, world.rand, 0);
            
            if (item != null) {
                int quantity = block.quantityDropped(world.rand);
                int damageValue = block.damageDropped(state);
                
                if (quantity > 0) {
                    return new net.minecraft.item.ItemStack(item, quantity, damageValue);
                }
            }
            
            // If no specific drop, try to get the block as an item
            net.minecraft.item.ItemStack blockItem = new net.minecraft.item.ItemStack(block, 1, metadata);
            if (blockItem.getItem() != null) {
                return blockItem;
            }
        } catch (Exception e) {
            // If we can't determine the drop, return a generic version of the block
            try {
                return new net.minecraft.item.ItemStack(block, 1, 0);
            } catch (Exception ex) {
                // Last resort: return null
            }
        }
        return null;
    }
}


