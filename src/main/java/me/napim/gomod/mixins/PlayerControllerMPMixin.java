package me.napim.gomod.mixins;

import me.napim.gomod.features.DisguiseBlitzKitSelectorOverride;
import me.napim.gomod.features.BlitzPregamePlayerToggle;
import me.napim.gomod.features.FakeBreak;
import me.napim.gomod.features.PlayersPlayMode;
import me.napim.gomod.features.RoleplayMode;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin to PlayerControllerMP to create client-side block break effects
 */
@Mixin(PlayerControllerMP.class)
public class PlayerControllerMPMixin {

    /**
     * Vanilla uses a separate right-click branch when the crosshair hits an
     * entity. Consume the pregame dye there before an interaction packet can
     * be sent to that player.
     */
    @Inject(method = {"interactWithEntitySendPacket", "func_78768_b"},
            at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$pregameDyeOverEntity(EntityPlayer playerIn, Entity targetEntity,
            CallbackInfoReturnable<Boolean> cir) {
        if (BlitzPregamePlayerToggle.tryHandleRightClick(Minecraft.getMinecraft())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = {"isPlayerRightClickingOnEntity", "func_178894_a"},
            at = @At("HEAD"), cancellable = true, require = 0)
    private void gomod$pregameDyeOverEntityHitVec(EntityPlayer playerIn, Entity targetEntity,
            MovingObjectPosition hit, CallbackInfoReturnable<Boolean> cir) {
        if (BlitzPregamePlayerToggle.tryHandleRightClick(Minecraft.getMinecraft())) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Intercepts block clicking to create client-side visual effects
     * This is called when you LEFT-CLICK on a block, regardless of whether you can break it
     */
    @Inject(method = "onPlayerDamageBlock", at = @At("HEAD"), cancellable = true)
    private void gomod$onBlockClicked(BlockPos pos, EnumFacing side, CallbackInfoReturnable<Boolean> cir) {
        if (RoleplayMode.isEnabled() || PlayersPlayMode.isEnabled()) {
            cir.setReturnValue(false);
            return;
        }

        if (!FakeBreak.isEnabled()) {
            return;
        }
        
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.theWorld == null || mc.thePlayer == null) {
                return;
            }
            
            WorldClient world = mc.theWorld;
            IBlockState blockState = world.getBlockState(pos);
            Block block = blockState.getBlock();
            
            // Don't process air blocks
            if (block == null || block.getMaterial().isReplaceable()) {
                return;
            }
            
            // Compute hardness increment (vanilla: block.getPlayerRelativeBlockHardness)
            float inc;
            float fixedTime = FakeBreak.getFixedBreakTime();
            if (fixedTime > 0) {
                // Fixed break time: 1 tick = 1/20 second, so increment per tick = 1 / (fixedTime * 20)
                inc = 1.0f / (fixedTime * 20.0f);
            } else {
                try {
                    inc = block.getPlayerRelativeBlockHardness(mc.thePlayer, mc.theWorld, pos);
                } catch (Throwable t) {
                    inc = 0.1f; // fallback
                }
            }
            boolean finished = FakeBreak.incrementBreakProgress(pos, inc);
            // Visual crack stage using break stage
            int prog = FakeBreak.getBreakStage(pos);
            // 0-9 accepted by renderer
            mc.theWorld.sendBlockBreakProgress(mc.thePlayer == null ? 0 : mc.thePlayer.getEntityId(), pos, prog);
            if (finished) {
                // Spawn client-side drop item using getItemDropped/quantityDropped (no fallback to block-as-item)
                try {
                    ItemStack drop = getDroppedItem(block, blockState, world, pos);
                    if (drop != null) {
                        double x = pos.getX() + 0.5;
                        double y = pos.getY() + 0.5;
                        double z = pos.getZ() + 0.5;
                        EntityItem ent = new EntityItem(world, x, y, z, drop);
                        ent.motionX = (Math.random() - 0.5) * 0.1;
                        ent.motionY = 0.2;
                        ent.motionZ = (Math.random() - 0.5) * 0.1;
                        if (ent instanceof me.napim.gomod.mixins.EntityItemAccessor) {
                            ((me.napim.gomod.mixins.EntityItemAccessor) ent).gomod$setDelayBeforeCanPickup(0);
                        }
                        world.spawnEntityInWorld(ent);
                        me.napim.gomod.features.FakeBreak.registerClientDrop(ent);
                    }
                } catch (Throwable t) {
                    System.err.println("[FakeBreak] Drop spawn error: " + t.getMessage());
                }
                try {
                    // Play block break sound
                    mc.theWorld.playSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 
                        block.stepSound.getBreakSound(), 
                        (block.stepSound.getVolume() + 1.0F) / 2.0F, 
                        block.stepSound.getFrequency() * 0.8F, 
                        false);
                    
                    // Mark broken before removing so FakeBreak can restore the original state later.
                    FakeBreak.addBrokenBlock(pos, blockState);
                    world.setBlockToAir(pos);
                } catch (Throwable t) { System.err.println("[gomod] Error in PlayerControllerMPMixin: " + t.getMessage()); }
            }
        } catch (Exception e) {
            // Silently handle any errors to prevent crashes
            System.err.println("[FakeBreak] Error in block break handler: " + e.getMessage());
        }
    }

    @Inject(method = "windowClick", at = @At("HEAD"), cancellable = true)
    private void gomod$beginSelectorWindowClick(int windowId, int slotId, int mouseButtonClicked, int mode,
                                                EntityPlayer playerIn, CallbackInfoReturnable<ItemStack> cir) {
        if (RoleplayMode.isEnabled() || PlayersPlayMode.isEnabled()) {
            cir.setReturnValue(null);
            return;
        }
        if (BlitzPregamePlayerToggle.tryHandleContainerClick(
                Minecraft.getMinecraft(), slotId, mouseButtonClicked, mode)) {
            cir.setReturnValue(null);
            return;
        }
        DisguiseBlitzKitSelectorOverride.beginClientWindowClick(slotId);
    }

    @Inject(method = "windowClick", at = @At("RETURN"))
    private void gomod$endSelectorWindowClick(int windowId, int slotId, int mouseButtonClicked, int mode,
                                              EntityPlayer playerIn, CallbackInfoReturnable<ItemStack> cir) {
        DisguiseBlitzKitSelectorOverride.endClientWindowClick();
    }
    
    /**
     * Gets the item that would be dropped from breaking this block
     */
    private ItemStack getDroppedItem(Block block, IBlockState state, WorldClient world, BlockPos pos) {
        try {
            // Get the block's drop using vanilla logic
            net.minecraft.item.Item item = block.getItemDropped(state, world.rand, 0);
            
            if (item != null) {
                int quantity = block.quantityDropped(world.rand);
                
                // If quantity is 0, block drops nothing (like leaves often do)
                if (quantity <= 0) {
                    return null;
                }
                
                int damageValue = block.damageDropped(state);
                return new ItemStack(item, quantity, damageValue);
            }
            
            // Do not fallback to block-as-item to avoid invalid metas (e.g., leaves 18/4)
        } catch (Exception e) {
            System.err.println("[FakeBreak] Error getting drop for block: " + e.getMessage());
        }
        return null;
    }
}
