package me.napim.gomod.mixins;

import me.napim.gomod.features.DisguiseBlitzKitSelectorOverride;
import me.napim.gomod.features.DisguiseRenamedItemMasker;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method = "getTooltip", at = @At("HEAD"), cancellable = true)
    private void gomod$overrideSelectorTooltip(EntityPlayer playerIn, boolean advanced, CallbackInfoReturnable<List<String>> cir) {
        ItemStack stack = (ItemStack) (Object) this;
        if (!DisguiseBlitzKitSelectorOverride.hasCustomSelectorTooltip(stack)) {
            return;
        }

        cir.setReturnValue(new ArrayList<>(DisguiseBlitzKitSelectorOverride.getCustomSelectorTooltipLines(stack, advanced)));
    }

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void gomod$maskRenamedBlitzItemName(CallbackInfoReturnable<String> cir) {
        ItemStack stack = (ItemStack) (Object) this;
        String replayPlayer = me.napim.gomod.features.ReplayPlayerSelectorDisplay
                .rewriteStackDisplayName(stack, cir.getReturnValue());
        if (replayPlayer != null && !replayPlayer.isEmpty()) {
            cir.setReturnValue(replayPlayer);
        }
        String masked = DisguiseRenamedItemMasker.getMaskedDisplayName(stack);
        if (masked != null && !masked.isEmpty()) {
            cir.setReturnValue(masked);
        }
    }

    @Inject(method = "getTooltip", at = @At("RETURN"), cancellable = true)
    private void gomod$maskRenamedBlitzItemTooltip(EntityPlayer playerIn, boolean advanced, CallbackInfoReturnable<List<String>> cir) {
        ItemStack stack = (ItemStack) (Object) this;
        if (DisguiseBlitzKitSelectorOverride.hasCustomSelectorTooltip(stack)) {
            return;
        }
        List<String> masked = DisguiseRenamedItemMasker.buildMaskedTooltip(stack, cir.getReturnValue(), advanced);
        if (masked != null) {
            cir.setReturnValue(masked);
        }
        List<String> replayPlayer = me.napim.gomod.features.ReplayPlayerSelectorDisplay
                .rewriteStackTooltip(stack, cir.getReturnValue());
        if (replayPlayer != null) {
            cir.setReturnValue(replayPlayer);
        }
    }
}
