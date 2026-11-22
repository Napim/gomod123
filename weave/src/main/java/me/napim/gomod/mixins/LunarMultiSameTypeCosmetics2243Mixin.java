package me.napim.gomod.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Consumer;

/** Preserves different equipped cosmetics of the same category in Lunar 2.22.43. */
// Superseded by LunarCompanionAndConflictTransform; retained for offline LunarMultiCosmeticCompatibilityCheck.
@Deprecated
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.COOHHRRCORRHIRIHRORIHRHICCIOIH."
                + "IOCIHHIRIROCOIROOCORCCIOOIIIRR."
                + "COHCCRCCIOORIRIIRIRCIHHCHIRCOH."
                + "IOHOHIHHHHORRCCRHCRCHCRIOIHRHH."
                + "ROOCCIHRCCHORHCCOIIRIHHHIIHHHO",
        remap = false
)
public abstract class LunarMultiSameTypeCosmetics2243Mixin {
    private static final String EQUIP_RESOLVER =
            "IOHOHIHHHHORRCCRHCRCHCRIOIHRHH(Lcom/moonsworth/lunar/client/"
                    + "COOHIICHOIRRHOIHHOCOICHRRHHIIC/IOCIHHIRIROCOIROOCORCCIOOIIIRR/"
                    + "CIIHHRHHHIOIIHHRROOHRHICIHOOOC;Ljava/util/List;Z)Ljava/util/List;";

    @Inject(method = EQUIP_RESOLVER, at = @At("HEAD"), require = 0, remap = false)
    private static void gomod$beginEquipResolution(
            @Coerce Object selectedCosmetic, List<?> equipped, boolean adding,
            CallbackInfoReturnable<List<?>> cir) {
        LunarMultiSameTypeCosmeticSupport.beginEquipResolution(selectedCosmetic, adding);
    }

    @Redirect(
            method = EQUIP_RESOLVER,
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;forEach(Ljava/util/function/Consumer;)V",
                    ordinal = 1
            ),
            require = 0,
            remap = false
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void gomod$preserveSameCategoryBeforeRemoval(List<?> conflicts,
                                                                 Consumer<?> removalAction) {
        Consumer<?> filtered = LunarMultiSameTypeCosmeticSupport
                .preserveEquippedSameCategory(removalAction);
        ((List) conflicts).forEach((Consumer) filtered);
    }

    @Inject(
            method = EQUIP_RESOLVER,
            at = @At("RETURN"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private static void gomod$filterReturnedConflicts(
            @Coerce Object selectedCosmetic, List<?> conflicts, boolean adding,
            CallbackInfoReturnable<List<?>> cir) {
        try {
            cir.setReturnValue(LunarMultiSameTypeCosmeticSupport.preserveSameCategory(
                    selectedCosmetic, cir.getReturnValue(), adding));
        } finally {
            LunarMultiSameTypeCosmeticSupport.endEquipResolution();
        }
    }
}
