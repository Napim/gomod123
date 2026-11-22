package me.napim.gomod.mixins;

import me.napim.gomod.features.FakeBreak;
import me.napim.gomod.features.PlayersPlayMode;
import me.napim.gomod.features.RoleplayMode;
import me.napim.gomod.features.TntPlacementPreview;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Client-side placement hook: when holding TNT from /givtnt, place it visually even if server denies.
 */
@Mixin(PlayerControllerMP.class)
public class PlayerControllerPlaceBlockMixin {

    @Inject(method = "onPlayerRightClick", at = @At("HEAD"), cancellable = true)
    private void gomod$clientPlaceOverride(net.minecraft.client.entity.EntityPlayerSP player, net.minecraft.client.multiplayer.WorldClient world, ItemStack held, BlockPos pos, EnumFacing side, net.minecraft.util.Vec3 vec, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (RoleplayMode.isEnabled() || PlayersPlayMode.isEnabled()) {
                cir.setReturnValue(false);
                return;
            }
            if (player == null || world == null) return;
            if (held == null) return;
            if (held.getItem() == null) return;
            if (pos == null || side == null) return;
            BlockPos placePos = pos.offset(side);

            if (FakeBreak.tryRequestRestoreOnRightClick(pos, held)
                || FakeBreak.tryRequestRestoreOnRightClick(placePos, held)) {
                return;
            }

            // Hypixel-style TNT debug (/tntplacementdebug) — any TNT stack, client-only primed entity
            if (TntPlacementPreview.tryDebugPlace(player, world, held, pos, side)) {
                cir.setReturnValue(true);
                return;
            }

            // 1) TNT: spawn primed entity and consume item (only for /gogive-tagged stacks).
            // While /tntplacementdebug is on, skip client-priming so you can tune the ghost without explosions.
            if (net.minecraft.item.Item.getIdFromItem(held.getItem()) == net.minecraft.item.Item.getIdFromItem(net.minecraft.item.Item.getItemFromBlock(Blocks.tnt))
                && TntPlacementPreview.isGogiveClientStack(held)
                && !TntPlacementPreview.isDebugMode()) {
                try {
                    double[] center = TntPlacementPreview.gogivePrimedTntCenter(player, pos, placePos);
                    double spawnX = center != null ? center[0] : placePos.getX() + 0.5;
                    double spawnY = center != null ? center[1] : placePos.getY() + 0.5;
                    double spawnZ = center != null ? center[2] : placePos.getZ() + 0.5;
                    net.minecraft.entity.item.EntityTNTPrimed primed = new net.minecraft.entity.item.EntityTNTPrimed(world, spawnX, spawnY, spawnZ, player);
                    primed.fuse = 80; // 4 seconds like real TNT
                    world.spawnEntityInWorld(primed);
                    FakeBreak.registerClientEntity(primed);
                    player.playSound("game.tnt.primed", 1.0F, 1.0F);
                    if (!player.capabilities.isCreativeMode) {
                        held.stackSize--;
                        if (held.stackSize <= 0) {
                            player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                        }
                    }
                } catch (Throwable t) { System.err.println("[gomod] Error in PlayerControllerPlaceBlockMixin: " + t.getMessage()); }
                FakeBreak.removeBrokenBlock(placePos);
                cir.setReturnValue(true);
                return;
            }

            // 2) Water/Lava buckets: place fluid and swap to empty bucket
            if (held.getItem() == net.minecraft.init.Items.water_bucket && held.hasTagCompound() && held.getTagCompound().getBoolean("gomod_client")) {
                // Place flowing water and schedule a client update to avoid static look
                net.minecraft.block.state.IBlockState waterState = net.minecraft.init.Blocks.flowing_water.getDefaultState();
                world.setBlockState(placePos, waterState, 2);
                me.napim.gomod.features.FakeBreak.addPlacedBlock(placePos, waterState);
                try { world.scheduleUpdate(placePos, net.minecraft.init.Blocks.flowing_water, 1); } catch (Throwable t) { System.err.println("[gomod] Error in PlayerControllerPlaceBlockMixin: " + t.getMessage()); }
                world.markBlockRangeForRenderUpdate(placePos, placePos);
                player.playSound("item.bucket.empty", 1.0F, 1.0F);
                if (!player.capabilities.isCreativeMode) {
                    held.stackSize--;
                    if (held.stackSize <= 0) {
                        player.inventory.setInventorySlotContents(player.inventory.currentItem, new net.minecraft.item.ItemStack(net.minecraft.init.Items.bucket, 1));
                    } else {
                        player.inventory.addItemStackToInventory(new net.minecraft.item.ItemStack(net.minecraft.init.Items.bucket, 1));
                    }
                }
                cir.setReturnValue(true);
                return;
            }
            if (held.getItem() == net.minecraft.init.Items.lava_bucket && held.hasTagCompound() && held.getTagCompound().getBoolean("gomod_client")) {
                // Place flowing lava and schedule a client update to avoid static look
                net.minecraft.block.state.IBlockState lavaState = net.minecraft.init.Blocks.flowing_lava.getDefaultState();
                world.setBlockState(placePos, lavaState, 2);
                me.napim.gomod.features.FakeBreak.addPlacedBlock(placePos, lavaState);
                try { world.scheduleUpdate(placePos, net.minecraft.init.Blocks.flowing_lava, 1); } catch (Throwable t) { System.err.println("[gomod] Error in PlayerControllerPlaceBlockMixin: " + t.getMessage()); }
                world.markBlockRangeForRenderUpdate(placePos, placePos);
                player.playSound("item.bucket.empty_lava", 1.0F, 1.0F);
                if (!player.capabilities.isCreativeMode) {
                    held.stackSize--;
                    if (held.stackSize <= 0) {
                        player.inventory.setInventorySlotContents(player.inventory.currentItem, new net.minecraft.item.ItemStack(net.minecraft.init.Items.bucket, 1));
                    } else {
                        player.inventory.addItemStackToInventory(new net.minecraft.item.ItemStack(net.minecraft.init.Items.bucket, 1));
                    }
                }
                cir.setReturnValue(true);
                return;
            }

            // 3) General Block items: place block client-side (e.g., bed)
            if (held.getItem() instanceof net.minecraft.item.ItemBlock && held.hasTagCompound() && held.getTagCompound().getBoolean("gomod_client")) {
                net.minecraft.item.ItemBlock ib = (net.minecraft.item.ItemBlock) held.getItem();
                net.minecraft.block.Block block = ib.getBlock();
                if (block != null) {
                    try {
                        IBlockState state = block.getDefaultState();
                        // Use metadata if present
                        state = block.getStateFromMeta(held.getMetadata());

                        // Special-case bed: place both parts oriented by player facing
                        if (block instanceof net.minecraft.block.BlockBed) {
                            net.minecraft.block.BlockBed bed = (net.minecraft.block.BlockBed) block;
                            EnumFacing facing = player.getHorizontalFacing();
                            IBlockState foot = state
                                .withProperty(net.minecraft.block.BlockBed.FACING, facing)
                                .withProperty(net.minecraft.block.BlockBed.PART, net.minecraft.block.BlockBed.EnumPartType.FOOT);
                            IBlockState head = state
                                .withProperty(net.minecraft.block.BlockBed.FACING, facing)
                                .withProperty(net.minecraft.block.BlockBed.PART, net.minecraft.block.BlockBed.EnumPartType.HEAD);
                            BlockPos headPos = placePos.offset(facing);
                            // Use flag 2 (no neighbor notifications) to avoid immediate break checks
                            world.setBlockState(placePos, foot, 2);
                            world.setBlockState(headPos, head, 2);
                            me.napim.gomod.features.FakeBreak.addPlacedBlock(placePos, foot);
                            me.napim.gomod.features.FakeBreak.addPlacedBlock(headPos, head);
                            world.markBlockRangeForRenderUpdate(placePos, headPos);
                        } else {
                            world.setBlockState(placePos, state, 2);
                            me.napim.gomod.features.FakeBreak.addPlacedBlock(placePos, state);
                            world.markBlockRangeForRenderUpdate(placePos, placePos);
                        }
                        if (!player.capabilities.isCreativeMode) {
                            held.stackSize--;
                            if (held.stackSize <= 0) {
                                player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                            }
                        }
                        cir.setReturnValue(true);
                        return;
                    } catch (Throwable t) { System.err.println("[gomod] Error in PlayerControllerPlaceBlockMixin: " + t.getMessage()); }
                }
            }
        } catch (Throwable t) { System.err.println("[gomod] Error in PlayerControllerPlaceBlockMixin: " + t.getMessage()); }
    }
}
