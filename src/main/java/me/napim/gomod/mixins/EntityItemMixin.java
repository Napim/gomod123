package me.napim.gomod.mixins;

import me.napim.gomod.features.FakeBreak;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin to EntityItem to handle client-side item pickup with animation
 */
@Mixin(EntityItem.class)
public class EntityItemMixin {
    
    @Shadow
    public int delayBeforeCanPickup;
    
    @Shadow
    public int age;
    
    private boolean gomod$pickingUp = false;
    private int gomod$pickupAnimTicks = 0;
    
    /**
     * Handle client-side item pickup for fake-broken block drops with animation
     */
    @Inject(method = "onUpdate", at = @At("HEAD"))
    private void gomod$handleClientPickup(CallbackInfo ci) {
        EntityItem self = (EntityItem)(Object)this;
        
        // Only process client-only items we spawned
        if (!FakeBreak.isClientItem(self.getEntityId())) return;
        
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.thePlayer == null || mc.theWorld == null) return;
            if (self.worldObj == null || !self.worldObj.isRemote) return;
            
            EntityPlayer player = mc.thePlayer;
            
            // If already picking up, continue animation
            if (gomod$pickingUp) {
                gomod$pickupAnimTicks++;
                
                // Move towards player center/waist (vanilla behavior)
                double dx = player.posX - self.posX;
                double dy = player.posY + 0.5 - self.posY; // Waist height, not head
                double dz = player.posZ - self.posZ;
                double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                
                if (dist < 0.25 || gomod$pickupAnimTicks > 4) {
                    // Animation complete - add to inventory and remove
                    ItemStack stack = self.getEntityItem();
                    if (stack != null) {
                        FakeBreak.recordCollected(stack);
                        System.out.println("[FakeBreak] Client-side pickup complete: " + stack.getDisplayName() + " x" + stack.stackSize);
                    }
                    self.setDead();
                    FakeBreak.unregisterClientItem(self.getEntityId());
                    return;
                }
                
                // Fast acceleration towards player (vanilla-like)
                double speed = Math.min(0.8, dist);
                self.motionX = dx / dist * speed;
                self.motionY = dy / dist * speed;
                self.motionZ = dz / dist * speed;
                
                // Disable gravity during pickup
                self.motionY += 0.04; // Counter vanilla gravity
                return;
            }
            
            // Check pickup delay
            if (delayBeforeCanPickup > 0) return;
            
            // Check distance to start pickup
            double dx = self.posX - player.posX;
            double dy = self.posY - player.posY - player.getEyeHeight() / 2.0;
            double dz = self.posZ - player.posZ;
            double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
            
            if (dist <= 1.5D) {
                ItemStack stack = self.getEntityItem();
                if (stack != null && stack.stackSize > 0) {
                    // Try to add to inventory
                    ItemStack copy = stack.copy();
                    boolean added = player.inventory.addItemStackToInventory(copy);
                    
                    if (added) {
                        // Start pickup animation
                        gomod$pickingUp = true;
                        gomod$pickupAnimTicks = 0;
                        
                        // Play pickup sound immediately
                        mc.theWorld.playSound(self.posX, self.posY, self.posZ, "random.pop", 0.2F, 
                            ((player.getRNG().nextFloat() - player.getRNG().nextFloat()) * 0.7F + 1.0F) * 2.0F, false);
                        
                        System.out.println("[FakeBreak] Starting client-side pickup animation: " + stack.getDisplayName() + " x" + stack.stackSize);
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[FakeBreak] EntityItem pickup error: " + t.getMessage());
        }
    }
}


