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

/** Hooks the equip-conflict resolver used by Lunar 2.22.39-2638. */
// Superseded by LunarCompanionAndConflictTransform; intentionally omitted from gomod.weave.mixins.json.
@Deprecated
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.IOHHOIIOCRHCHHCRORICCOHOHROOIH."
                + "IRCIIHHICIHRCOCRROCOICRIHHCCHH."
                + "OOROOCCIRCCRHOIOIORIHCHHOOCCOR."
                + "RCIRROCCCIIHCIHCCRHHCCHOHHHCHH."
                + "CRRRICCRROCOHHOHIICIHORCOORRRH",
        remap = false
)
public abstract class LunarMultiSameTypeCosmetics2239Mixin {
    private static final String EQUIP_RESOLVER =
            "RCIRROCCCIIHCIHCCRHHCCHOHHHCHH(Lcom/moonsworth/lunar/client/"
                    + "RIROICHCRROROHCCROOCCCCOCHCCRI/IRCIIHHICIHRCOCRROCOICRIHHCCHH/"
                    + "RRCRRCORICCHOHHIRCHIROOHIIOHCO;Ljava/util/List;Z)Ljava/util/List;";

    @Inject(method = EQUIP_RESOLVER, at = @At("HEAD"), require = 0, remap = false)
    private static void gomod$beginMultiEquip(
            @Coerce Object selectedCosmetic, List<?> equipped, boolean adding,
            CallbackInfoReturnable<List<?>> cir) {
        LunarMultiSameTypeCosmeticSupport.beginEquipResolution(selectedCosmetic, adding);
    }

    @Redirect(
            method = EQUIP_RESOLVER,
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/ArrayList;forEach(Ljava/util/function/Consumer;)V",
                    ordinal = 1
            ),
            require = 0,
            remap = false
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void gomod$preserveSameCategoryBeforeRemoval(java.util.ArrayList<?> equipped,
                                                                 Consumer<?> removalAction) {
        Consumer<?> filtered = LunarMultiSameTypeCosmeticSupport
                .preserveEquippedSameCategory(removalAction);
        ((java.util.ArrayList) equipped).forEach((Consumer) filtered);
    }

    @Inject(
            method = EQUIP_RESOLVER,
            at = @At("RETURN"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private static void gomod$filterReturnedConflicts(
            @Coerce Object selectedCosmetic, List<?> equipped, boolean adding,
            CallbackInfoReturnable<List<?>> cir) {
        try {
            cir.setReturnValue(LunarMultiSameTypeCosmeticSupport.preserveSameCategory(
                    selectedCosmetic, cir.getReturnValue(), adding));
        } finally {
            LunarMultiSameTypeCosmeticSupport.endEquipResolution();
        }
    }
}
