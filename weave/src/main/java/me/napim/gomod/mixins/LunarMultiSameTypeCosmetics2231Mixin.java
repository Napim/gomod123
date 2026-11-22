package me.napim.gomod.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

/** Preserves different same-category cosmetics in Lunar 2.23.1's mutable equip resolver. */
// Superseded by LunarCompanionAndConflictTransform; intentionally omitted from gomod.weave.mixins.json.
@Deprecated
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.CIRIIHCCROCIOIRORIOIOCHHIIHRCC."
                + "CICOHIIRRHHIIHRROHOICOCRHHHHOO.ROHIOOCICRORHRCIHHHHHHRICCCRRC."
                + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC.ORCOIHCCICICIHRCCCCHHHRICHHICI",
        remap = false
)
public abstract class LunarMultiSameTypeCosmetics2231Mixin {
    private static final String EQUIP_RESOLVER =
            "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC(Lcom/moonsworth/lunar/client/"
                    + "HIRCHOCCIROORHOHOOHOIOHOOIHRCR/CICOHIIRRHHIIHRROHOICOCRHHHHOO/"
                    + "RCHICCCIHRCRCHCIRRIIRRHICORHHO;Ljava/util/List;Z)Ljava/util/List;";
    private static final String CONFLICT_REMOVAL_LAMBDA =
            "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC(Ljava/util/List;Lcom/moonsworth/lunar/client/"
                    + "HIRCHOCCIROORHOHOOHOIOHOOIHRCR/CICOHIIRRHHIIHRROHOICOCRHHHHOO/"
                    + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC;Lcom/moonsworth/lunar/client/"
                    + "HIRCHOCCIROORHOHOOHOIOHOOIHRCR/CICOHIIRRHHIIHRROHOICOCRHHHHOO/"
                    + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC;)V";

    @Inject(method = EQUIP_RESOLVER, at = @At("HEAD"), require = 0, remap = false)
    private static void gomod$beginEquipResolution(
            @Coerce Object selectedCosmetic, List<?> equipped, boolean adding,
            CallbackInfoReturnable<List<?>> cir) {
        LunarMultiSameTypeCosmeticSupport.beginEquipResolution(selectedCosmetic, adding);
    }

    /**
     * Lunar removes conflicts from the live equipped list inside a generated lambda, before
     * returning its conflict list. Guard that mutation and filter the returned list separately.
     */
    @Redirect(
            method = CONFLICT_REMOVAL_LAMBDA,
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/List;removeIf(Ljava/util/function/Predicate;)Z"
            ),
            require = 0,
            remap = false
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean gomod$preserveEquippedSameCategory(
            List<?> equipped, Predicate<?> removePredicate) {
        Predicate<?> guarded = LunarMultiSameTypeCosmeticSupport
                .preserveSameCategoryRemovalPredicate(removePredicate);
        return ((List) equipped).removeIf((Predicate) guarded);
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
