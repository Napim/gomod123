package me.napim.gomod.mixins;

import me.napim.gomod.features.DisguiseBlitzKitSelectorOverride;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Slot.class)
public abstract class SlotMixin {
    @Shadow public IInventory inventory;
    @Shadow public int slotNumber;

    @Inject(method = "getStack", at = @At("HEAD"), cancellable = true)
    private void gomod$overrideKitSelectorStack(CallbackInfoReturnable<ItemStack> cir) {
        me.napim.gomod.features.MixinStatus.slotMixinActive = true;
        ItemStack replacement = DisguiseBlitzKitSelectorOverride.getGuiReplacementStack(this.inventory, this.slotNumber);
        if (replacement != null || DisguiseBlitzKitSelectorOverride.isGuiReplacementSlotHidden(this.inventory, this.slotNumber)) {
            cir.setReturnValue(replacement);
        }
    }

    @Inject(method = "getHasStack", at = @At("HEAD"), cancellable = true)
    private void gomod$overrideKitSelectorHasStack(CallbackInfoReturnable<Boolean> cir) {
        me.napim.gomod.features.MixinStatus.slotMixinActive = true;
        if (DisguiseBlitzKitSelectorOverride.isGuiReplacementSlotHidden(this.inventory, this.slotNumber)) {
            cir.setReturnValue(false);
            return;
        }

        if (DisguiseBlitzKitSelectorOverride.hasGuiReplacementStack(this.inventory, this.slotNumber)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "decrStackSize", at = @At("HEAD"), cancellable = true)
    private void gomod$overrideKitSelectorDecrStack(int amount, CallbackInfoReturnable<ItemStack> cir) {
        me.napim.gomod.features.MixinStatus.slotMixinActive = true;
        if (!DisguiseBlitzKitSelectorOverride.shouldShadowSlotMutations(this.inventory, this.slotNumber)) {
            return;
        }

        cir.setReturnValue(DisguiseBlitzKitSelectorOverride.decrGuiReplacementStack(this.inventory, this.slotNumber, amount));
    }

    @Inject(method = "putStack", at = @At("HEAD"), cancellable = true)
    private void gomod$overrideKitSelectorPutStack(ItemStack stack, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        me.napim.gomod.features.MixinStatus.slotMixinActive = true;
        if (!DisguiseBlitzKitSelectorOverride.setGuiReplacementStack(this.inventory, this.slotNumber, stack)) {
            return;
        }

        this.inventory.markDirty();
        ci.cancel();
    }
}
