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

/** Preserves different equipped cosmetics of the same category in Lunar's shared Locker resolver. */
// Superseded by LunarCompanionAndConflictTransform; intentionally omitted from gomod.weave.mixins.json.
@Deprecated
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.OOIRIHRRCHRIHRHHCOOCICRIRCHIIO."
                + "COCIICOHOOCCHCHRIRHHCHORROOCOR.OHOIIOOOCOOIRIHRIHIIRROIICRICH."
                + "RCRHRICCICICHOOCCCIRRCIRCICIOC.HIOORCHCOIHRCHRRIIOIICCOHCHOCO",
        remap = false
)
public abstract class LunarMultiSameTypeCosmeticsMixin {
    private static final String EQUIP_RESOLVER =
            "RCRHRICCICICHOOCCCIRRCIRCICIOC(Lcom/moonsworth/lunar/client/"
                    + "HCOOHCRIIRHROCROHROOOOORCRORIR/COCIICOHOOCCHCHRIRHHCHORROOCOR/"
                    + "OHRIIRCCRIROIORIIHHOCRRIICIROI;Ljava/util/List;Z)Ljava/util/List;";

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
    private static void gomod$preserveSameCategoryBeforeRemoval(List<?> equipped,
                                                                 Consumer<?> removalAction) {
        Consumer<?> filtered = LunarMultiSameTypeCosmeticSupport
                .preserveEquippedSameCategory(removalAction);
        ((List) equipped).forEach((Consumer) filtered);
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
