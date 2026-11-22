package me.napim.gomod.mixins;

import me.napim.gomod.features.ChestDumpLogger;
import me.napim.gomod.features.ChestRouteManager;
import me.napim.gomod.features.CageChestOverlay;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records chest positions in order while {@code /ce on} is active.
 */
@Mixin(PlayerControllerMP.class)
public class ChestRouteRightClickMixin {

    @Inject(method = "onPlayerRightClick", at = @At("HEAD"), require = 0)
    private void gomod$chestRouteOnRightClick(EntityPlayerSP player, WorldClient world, ItemStack held,
                                             BlockPos pos, EnumFacing side, net.minecraft.util.Vec3 vec,
                                             CallbackInfoReturnable<Boolean> cir) {
        try {
            // Keeps sneak+hold drag alive when Lunar doesn't pulse rightClickMouse every tick
            ChestRouteManager.signalVanillaRightClick();
            ChestRouteManager.onRightClickBlock(world, pos, vec, player);
            CageChestOverlay.onRightClickBlock(world, pos);
            ChestDumpLogger.noteChestBlockInteraction(world, pos);
        } catch (Throwable t) {
            System.err.println("[gomod] ChestRouteRightClickMixin: " + t.getMessage());
        }
    }
}
