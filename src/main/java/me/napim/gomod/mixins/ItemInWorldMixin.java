package me.napim.gomod.mixins;

import me.napim.gomod.features.FakeBreak;
import me.napim.gomod.features.PlayersPlayMode;
import me.napim.gomod.features.RoleplayMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.item.EntityTNTPrimed;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemFlintAndSteel;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import net.minecraft.client.multiplayer.PlayerControllerMP;

/**
 * Intercept right-click use to light TNT client-side and perform a local explosion.
 */
@Mixin(PlayerControllerMP.class)
public class ItemInWorldMixin {

    /**
     * Server spawn egg in air uses {@code processRightClick}, not {@code processRightClickBlock}.
     * Record pending here too so {@link me.napim.gomod.features.SpawnedMobTracker} can match the entity.
     */
    @Inject(method = "processRightClick", at = @At("HEAD"))
    private void gomod$trackServerSpawnEggAir(net.minecraft.client.entity.EntityPlayerSP player, WorldClient world, ItemStack held, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (player == null || world == null) {
                return;
            }
            if (held != null && held.getItem() == net.minecraft.init.Items.spawn_egg) {
                if (!held.hasTagCompound() || !held.getTagCompound().getBoolean("gomod_client")) {
                    me.napim.gomod.features.SpawnedMobTracker.onSpawnEggUsed(
                            held.getMetadata(), player.posX, player.posY, player.posZ);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // Handle right-click in air (use item) - 1.8.9 signature
    @Inject(method = "processRightClick", at = @At("HEAD"), cancellable = true)
    private void gomod$clientRightClickProjectiles(net.minecraft.client.entity.EntityPlayerSP player, WorldClient world, ItemStack held, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (RoleplayMode.isEnabled() || PlayersPlayMode.isEnabled()) {
                cir.setReturnValue(false);
                return;
            }
            if (player == null || world == null) return;
            if (held == null || held.getItem() == null) return;

            // Client-throw splash potion (only for client-given stacks)
            if (held.getItem() == net.minecraft.init.Items.potionitem && (held.getMetadata() & 16384) != 0 && held.hasTagCompound() && held.getTagCompound().getBoolean("gomod_client")) {
                net.minecraft.entity.projectile.EntityPotion potion = new net.minecraft.entity.projectile.EntityPotion(world, player, held.copy());
                // Compute throw vector similar to vanilla
                float yaw = player.rotationYaw;
                float pitch = player.rotationPitch;
                float f = -net.minecraft.util.MathHelper.sin(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                float f1 = -net.minecraft.util.MathHelper.sin(pitch * 0.017453292F);
                float f2 =  net.minecraft.util.MathHelper.cos(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                double vel = 0.5D;
                potion.motionX = f * vel;
                potion.motionY = f1 * vel + 0.1D;
                potion.motionZ = f2 * vel;
                world.spawnEntityInWorld(potion);
                me.napim.gomod.features.FakeBreak.registerClientEntity(potion);
                player.playSound("random.bow", 0.5F, 0.4F / (world.rand.nextFloat() * 0.4F + 0.8F));
                if (!player.capabilities.isCreativeMode) {
                    held.stackSize--;
                    if (held.stackSize <= 0) {
                        player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                    }
                }
                cir.setReturnValue(true);
                return;
            }

            // Client-throw experience bottle
            if (held.getItem() == net.minecraft.init.Items.experience_bottle && held.hasTagCompound() && held.getTagCompound().getBoolean("gomod_client")) {
                net.minecraft.entity.item.EntityExpBottle xp = new net.minecraft.entity.item.EntityExpBottle(world, player);
                float yaw = player.rotationYaw;
                float pitch = player.rotationPitch;
                float f = -net.minecraft.util.MathHelper.sin(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                float f1 = -net.minecraft.util.MathHelper.sin(pitch * 0.017453292F);
                float f2 =  net.minecraft.util.MathHelper.cos(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                double vel = 0.7D;
                xp.motionX = f * vel;
                xp.motionY = f1 * vel + 0.1D;
                xp.motionZ = f2 * vel;
                world.spawnEntityInWorld(xp);
                me.napim.gomod.features.FakeBreak.registerClientEntity(xp);
                player.playSound("random.bow", 0.5F, 0.4F / (world.rand.nextFloat() * 0.4F + 0.8F));
                if (!player.capabilities.isCreativeMode) {
                    held.stackSize--;
                    if (held.stackSize <= 0) {
                        player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                    }
                }
                cir.setReturnValue(true);
                return;
            }

            // Spawn eggs: create entity client-side and consume egg
            if (held.getItem() == net.minecraft.init.Items.spawn_egg && held.hasTagCompound() && held.getTagCompound().getBoolean("gomod_client")) {
                int id = held.getMetadata();
                net.minecraft.entity.Entity ent = net.minecraft.entity.EntityList.createEntityByID(id, world);
                if (ent != null) {
                    // Spawn ~2 blocks ahead of player
                    float yaw = player.rotationYaw;
                    float pitch = player.rotationPitch;
                    float f = -net.minecraft.util.MathHelper.sin(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                    float f1 = -net.minecraft.util.MathHelper.sin(pitch * 0.017453292F);
                    float f2 =  net.minecraft.util.MathHelper.cos(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                    double dist = 2.0D;
                    double x = player.posX + f * dist;
                    double y = player.posY + player.getEyeHeight() + f1 * dist;
                    double z = player.posZ + f2 * dist;
                    ent.setLocationAndAngles(x, y, z, player.rotationYaw, 0.0F);
                    world.spawnEntityInWorld(ent);
                    me.napim.gomod.features.FakeBreak.registerClientEntity(ent);
                    me.napim.gomod.features.SpawnedMobTracker.onClientEntitySpawned(ent);
                    player.playSound("mob.chicken.plop", 0.4F, 1.0F);
                    if (!player.capabilities.isCreativeMode) {
                        held.stackSize--;
                        if (held.stackSize <= 0) {
                            player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                        }
                    }
                    me.napim.gomod.features.FakeBreak.addPlacedBlock(new net.minecraft.util.BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z)), world.getBlockState(new net.minecraft.util.BlockPos((int)Math.floor(x), (int)Math.floor(y), (int)Math.floor(z))));
                    // Return success
                    cir.setReturnValue(true);
                    return;
                }
            }

            // Ender Pearl: throw client-side and spawn ghost on predicted landing
            if (held.getItem() == net.minecraft.init.Items.ender_pearl && held.hasTagCompound() && held.getTagCompound().getBoolean("gomod_client")) {
                net.minecraft.entity.item.EntityEnderPearl pearl = new net.minecraft.entity.item.EntityEnderPearl(world, player);
                float yaw = player.rotationYaw;
                float pitch = player.rotationPitch;
                float f = -net.minecraft.util.MathHelper.sin(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                float f1 = -net.minecraft.util.MathHelper.sin(pitch * 0.017453292F);
                float f2 =  net.minecraft.util.MathHelper.cos(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                double vel = 1.3D;
                pearl.motionX = f * vel;
                pearl.motionY = f1 * vel + 0.2D;
                pearl.motionZ = f2 * vel;
                world.spawnEntityInWorld(pearl);
                me.napim.gomod.features.FakeBreak.registerClientEntity(pearl);
                try { me.napim.gomod.features.FakeBreak.class.getMethod("watchPearl", int.class).invoke(null, pearl.getEntityId()); } catch (Throwable t) { System.err.println("[gomod] Error in ItemInWorldMixin: " + t.getMessage()); }
                player.playSound("random.bow", 0.5F, 0.4F / (world.rand.nextFloat() * 0.4F + 0.8F));
                if (!player.capabilities.isCreativeMode) {
                    held.stackSize--;
                    if (held.stackSize <= 0) {
                        player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                    }
                }
                cir.setReturnValue(true);
                return;
            }
        } catch (Throwable t) { System.err.println("[gomod] Error in ItemInWorldMixin: " + t.getMessage()); }
    }

    // Detect server-side spawn egg use so SpawnedMobTracker can correlate the arriving entity.
    @Inject(method = "onPlayerRightClick", at = @At("HEAD"), require = 0)
    private void gomod$trackServerSpawnEgg18(net.minecraft.client.entity.EntityPlayerSP player, net.minecraft.client.multiplayer.WorldClient worldIn, ItemStack stack, net.minecraft.util.BlockPos pos, EnumFacing direction, net.minecraft.util.Vec3 hitVec, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        try {
            if (player != null && stack != null && stack.getItem() == net.minecraft.init.Items.spawn_egg) {
                if (!stack.hasTagCompound() || !stack.getTagCompound().getBoolean("gomod_client")) {
                    me.napim.gomod.features.SpawnedMobTracker.onSpawnEggUsed(
                            stack.getMetadata(), player.posX, player.posY, player.posZ);
                }
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "processRightClickBlock", at = @At("HEAD"), require = 0)
    private void gomod$trackServerSpawnEgg(net.minecraft.client.entity.EntityPlayerSP player, net.minecraft.client.multiplayer.WorldClient worldIn, net.minecraft.util.BlockPos pos, net.minecraft.util.EnumFacing direction, net.minecraft.util.Vec3 hitVec, ItemStack stack, org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        try {
            if (stack != null && stack.getItem() == net.minecraft.init.Items.spawn_egg) {
                if (!stack.hasTagCompound() || !stack.getTagCompound().getBoolean("gomod_client")) {
                    me.napim.gomod.features.SpawnedMobTracker.onSpawnEggUsed(
                            stack.getMetadata(), player.posX, player.posY, player.posZ);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Some clients route item use through {@code sendUseItem} instead of {@code processRightClick}. */
    @Inject(method = "sendUseItem", at = @At("HEAD"))
    private void gomod$trackServerSpawnEggSendUseItem(net.minecraft.entity.player.EntityPlayer player, net.minecraft.world.World world, ItemStack held, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (player == null || world == null) {
                return;
            }
            if (held != null && held.getItem() == net.minecraft.init.Items.spawn_egg) {
                if (!held.hasTagCompound() || !held.getTagCompound().getBoolean("gomod_client")) {
                    me.napim.gomod.features.SpawnedMobTracker.onSpawnEggUsed(
                            held.getMetadata(), player.posX, player.posY, player.posZ);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // Also handle right-click use (sendUseItem) to catch air-throws path
    @Inject(method = "sendUseItem", at = @At("HEAD"), cancellable = true)
    private void gomod$clientUseProjectiles(net.minecraft.entity.player.EntityPlayer player, net.minecraft.world.World world, ItemStack held, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (RoleplayMode.isEnabled() || PlayersPlayMode.isEnabled()) {
                cir.setReturnValue(false);
                return;
            }
            if (player == null || world == null) return;
            if (held == null || held.getItem() == null) return;

            // Splash potion (client-given only)
            if (held.getItem() == net.minecraft.init.Items.potionitem && (held.getMetadata() & 16384) != 0 && held.hasTagCompound() && held.getTagCompound().getBoolean("gomod_client")) {
                net.minecraft.entity.projectile.EntityPotion potion = new net.minecraft.entity.projectile.EntityPotion((net.minecraft.world.World) world, (net.minecraft.entity.EntityLivingBase) player, held.copy());
                float yaw = player.rotationYaw;
                float pitch = player.rotationPitch;
                float f = -net.minecraft.util.MathHelper.sin(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                float f1 = -net.minecraft.util.MathHelper.sin(pitch * 0.017453292F);
                float f2 =  net.minecraft.util.MathHelper.cos(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                double vel = 0.5D;
                potion.motionX = f * vel;
                potion.motionY = f1 * vel + 0.1D;
                potion.motionZ = f2 * vel;
                world.spawnEntityInWorld(potion);
                player.playSound("random.bow", 0.5F, 0.4F / (world.rand.nextFloat() * 0.4F + 0.8F));
                if (!player.capabilities.isCreativeMode) {
                    held.stackSize--;
                    if (held.stackSize <= 0) {
                        player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                    }
                }
                cir.setReturnValue(true);
                return;
            }

            // Experience bottle (client-given only)
            if (held.getItem() == net.minecraft.init.Items.experience_bottle && held.hasTagCompound() && held.getTagCompound().getBoolean("gomod_client")) {
                net.minecraft.entity.item.EntityExpBottle xp = new net.minecraft.entity.item.EntityExpBottle(world, player);
                float yaw = player.rotationYaw;
                float pitch = player.rotationPitch;
                float f = -net.minecraft.util.MathHelper.sin(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                float f1 = -net.minecraft.util.MathHelper.sin(pitch * 0.017453292F);
                float f2 =  net.minecraft.util.MathHelper.cos(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                double vel = 0.7D;
                xp.motionX = f * vel;
                xp.motionY = f1 * vel + 0.1D;
                xp.motionZ = f2 * vel;
                world.spawnEntityInWorld(xp);
                player.playSound("random.bow", 0.5F, 0.4F / (world.rand.nextFloat() * 0.4F + 0.8F));
                if (!player.capabilities.isCreativeMode) {
                    held.stackSize--;
                    if (held.stackSize <= 0) {
                        player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                    }
                }
                cir.setReturnValue(true);
                return;
            }

            // Ender pearl (air use, client-given only) - no immediate freecam; watcher will trigger on land
            if (held.getItem() == net.minecraft.init.Items.ender_pearl && held.hasTagCompound() && held.getTagCompound().getBoolean("gomod_client")) {
                net.minecraft.entity.item.EntityEnderPearl pearl = new net.minecraft.entity.item.EntityEnderPearl(world, player);
                float yaw = player.rotationYaw;
                float pitch = player.rotationPitch;
                float f = -net.minecraft.util.MathHelper.sin(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                float f1 = -net.minecraft.util.MathHelper.sin(pitch * 0.017453292F);
                float f2 =  net.minecraft.util.MathHelper.cos(yaw * 0.017453292F) * net.minecraft.util.MathHelper.cos(pitch * 0.017453292F);
                double vel = 1.1D;
                pearl.motionX = f * vel;
                pearl.motionY = f1 * vel + 0.05D;
                pearl.motionZ = f2 * vel;
                world.spawnEntityInWorld(pearl);
                me.napim.gomod.features.FakeBreak.registerClientEntity(pearl);
                try { me.napim.gomod.features.FakeBreak.class.getMethod("watchPearl", int.class).invoke(null, pearl.getEntityId()); } catch (Throwable t) { System.err.println("[gomod] Error in ItemInWorldMixin: " + t.getMessage()); }
                player.playSound("random.bow", 0.5F, 0.4F / (world.rand.nextFloat() * 0.4F + 0.8F));
                if (!player.capabilities.isCreativeMode) {
                    held.stackSize--;
                    if (held.stackSize <= 0) {
                        player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
                    }
                }
                cir.setReturnValue(true);
            }
        } catch (Throwable t) { System.err.println("[gomod] Error in ItemInWorldMixin: " + t.getMessage()); }
    }
}
