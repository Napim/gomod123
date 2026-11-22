package me.napim.gomod.mixins;

import me.napim.gomod.features.PlayerStandaloneCape;
import me.napim.gomod.features.SelfOpacity;
import me.napim.gomod.features.VisibleSpecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Suppresses only the local Lunar cloak when the user selects the gomod cape source. */
@Pseudo
@Mixin(
        targets = {
                "com.moonsworth.lunar.client.COROIRIHIIRCIIIOOCHROCOIOHCHIC.CIIOHCCOCRHORIRROROICHHRRRHHCC.CIIOHCCOCRHORIRROROICHHRRRHHCC.CCHCHHCHHROIRICOOOCORIIHHOHCCR",
                "com.moonsworth.lunar.client.ORCOIHHHROORRIIIRCHHRRCIICHOCH.HCOCIICRHHRCRIHIIIRRIOIHIIHCOC.HCOCIICRHHRCRIHIIIRRIOIHIIHCOC.ORCRCHHRCHRIIRHIRCIHRHOHRCHROC"
        },
        remap = false
)
public abstract class LunarCloakSourceMixin {
    @Unique
    private static boolean gomod$reportedCurrentRender;
    @Unique
    private static boolean gomod$reportedCurrentLayerCount;
    @Unique
    private static boolean gomod$reportedLegacyRender;
    @Unique
    private static boolean gomod$reportedLegacyLayerCount;
    @Unique
    private boolean gomod$currentSelfOpacityCosmetic;
    @Unique
    private boolean gomod$currentVisibleSpecsCosmetic;
    @Unique
    private boolean gomod$currentAltSelfOpacityCosmetic;
    @Unique
    private boolean gomod$currentAltVisibleSpecsCosmetic;
    @Unique
    private boolean gomod$legacySelfOpacityCosmetic;
    @Unique
    private boolean gomod$legacyVisibleSpecsCosmetic;
    @Unique
    private boolean gomod$clothSelfOpacityCosmetic;
    @Unique
    private boolean gomod$clothVisibleSpecsCosmetic;
    @Unique
    private boolean gomod$clothAltSelfOpacityCosmetic;
    @Unique
    private boolean gomod$clothAltVisibleSpecsCosmetic;

    @Inject(
            method = "IIOOIIHRIOCCIHHCIRICHIIHIHOHII("
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/COCHICRCCIRHIHRRCHCIOOIOOOHHIH/ICOCCRRCCCICCIHRCOOOOHCOHHIHCO;"
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/RHOOROCCICRCRICICHICIIHRCOIRHC/IIOOIIHRIOCCIHHCIRICHIIHIHOHII;"
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/COICHHIHCCCOOROCCHHICHRIRCCHRR/COICHHIHCCCOOROCCHHICHRIRCCHRR;"
                    + "FFFFFFF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void gomod$suppressLocalLunarCloak(@Coerce Object matrices, @Coerce Object playerBridge,
            @Coerce Object renderState, float limbSwing, float limbSwingAmount, float partialTicks,
            float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        gomod$currentSelfOpacityCosmetic = false;
        gomod$currentVisibleSpecsCosmetic = false;
        if (PlayerStandaloneCape.shouldSuppressLunarCloakForBridge(playerBridge)) {
            if (!gomod$reportedCurrentRender) {
                gomod$reportedCurrentRender = true;
                System.out.println("[gomod][cape-trace] suppressed current Lunar cloak render for local player");
            }
            ci.cancel();
            return;
        }
        gomod$currentVisibleSpecsCosmetic = VisibleSpecs.beginExternalLocalPlayerCosmeticRender(playerBridge);
        gomod$currentSelfOpacityCosmetic = !gomod$currentVisibleSpecsCosmetic
                && SelfOpacity.beginExternalLocalPlayerCosmeticRender(playerBridge);
    }

    @Inject(
            method = "RRHORRHIIIOHHCICIORHHOHHOHIIHR("
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/HOIHCCRHORIHIHHCHHIHHCCROHOHOR/HOOOIIIOCRHCIRRIHCIHOHROIHRCHH;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/ICHRRCIICOIIRHCIOOICORICHOOCHI/RRHORRHIIIOHHCICIORHHOHHOHIIHR;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/IORHHRRIHIRRIRRHIHCIRCRORIRHRI;"
                    + "FFFFFFF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void gomod$suppressClothLunarCloakRender(@Coerce Object matrices, @Coerce Object playerBridge,
            @Coerce Object renderState, float limbSwing, float limbSwingAmount, float partialTicks,
            float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        gomod$clothSelfOpacityCosmetic = false;
        gomod$clothVisibleSpecsCosmetic = false;
        if (PlayerStandaloneCape.shouldSuppressLunarCloakForBridge(playerBridge)) {
            if (!gomod$reportedCurrentRender) {
                gomod$reportedCurrentRender = true;
                System.out.println("[gomod][cape-trace] suppressed cloth Lunar cloak render for local player");
            }
            ci.cancel();
            return;
        }
        gomod$clothVisibleSpecsCosmetic = VisibleSpecs.beginExternalLocalPlayerCosmeticRender(playerBridge);
        gomod$clothSelfOpacityCosmetic = !gomod$clothVisibleSpecsCosmetic
                && SelfOpacity.beginExternalLocalPlayerCosmeticRender(playerBridge);
    }

    @Inject(
            method = "RRHORRHIIIOHHCICIORHHOHHOHIIHR("
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/HOIHCCRHORIHIHHCHHIHHCCROHOHOR/HOOOIIIOCRHCIRRIHCIHOHROIHRCHH;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/ICHRRCIICOIIRHCIOOICORICHOOCHI/RRHORRHIIIOHHCICIORHHOHHOHIIHR;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/IORHHRRIHIRRIRRHIHCIRCRORIRHRI;"
                    + "FFFFFFF)V",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void gomod$clothLunarCloakRenderReturn(@Coerce Object matrices, @Coerce Object playerBridge,
            @Coerce Object renderState, float limbSwing, float limbSwingAmount, float partialTicks,
            float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        if (gomod$clothSelfOpacityCosmetic) {
            gomod$clothSelfOpacityCosmetic = false;
            SelfOpacity.endExternalLocalPlayerCosmeticRender();
        }
        if (gomod$clothVisibleSpecsCosmetic) {
            gomod$clothVisibleSpecsCosmetic = false;
            VisibleSpecs.endExternalLocalPlayerCosmeticRender();
        }
    }

    @Inject(
            method = "RRHORRHIIIOHHCICIORHHOHHOHIIHR("
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/HOIHCCRHORIHIHHCHHIHHCCROHOHOR/CCHHIRHRCOIHHHIOOICRIHORHCIHIC;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/HOIHCCRHORIHIHHCHHIHHCCROHOHOR/ICHRRCIICOIIRHCIOOICORICHOOCHI/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/ICHRRCIICOIIRHCIOOICORICHOOCHI;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/IORHHRRIHIRRIRRHIHCIRCRORIRHRI;"
                    + "II)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void gomod$suppressClothAltLunarCloakRender(@Coerce Object bufferSource, @Coerce Object playerBridge,
            @Coerce Object renderState, int packedLight, int overlay, CallbackInfo ci) {
        gomod$clothAltSelfOpacityCosmetic = false;
        gomod$clothAltVisibleSpecsCosmetic = false;
        if (PlayerStandaloneCape.shouldSuppressLunarCloakForBridge(playerBridge)) {
            if (!gomod$reportedCurrentRender) {
                gomod$reportedCurrentRender = true;
                System.out.println("[gomod][cape-trace] suppressed cloth alt Lunar cloak render for local player");
            }
            ci.cancel();
            return;
        }
        gomod$clothAltVisibleSpecsCosmetic = VisibleSpecs.beginExternalLocalPlayerCosmeticRender(playerBridge);
        gomod$clothAltSelfOpacityCosmetic = !gomod$clothAltVisibleSpecsCosmetic
                && SelfOpacity.beginExternalLocalPlayerCosmeticRender(playerBridge);
    }

    @Inject(
            method = "RRHORRHIIIOHHCICIORHHOHHOHIIHR("
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/HOIHCCRHORIHIHHCHHIHHCCROHOHOR/CCHHIRHRCOIHHHIOOICRIHORHCIHIC;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/HOIHCCRHORIHIHHCHHIHHCCROHOHOR/ICHRRCIICOIIRHCIOOICORICHOOCHI/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/ICHRRCIICOIIRHCIOOICORICHOOCHI;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/IORHHRRIHIRRIRRHIHCIRCRORIRHRI;"
                    + "II)V",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void gomod$clothAltLunarCloakRenderReturn(@Coerce Object bufferSource, @Coerce Object playerBridge,
            @Coerce Object renderState, int packedLight, int overlay, CallbackInfo ci) {
        if (gomod$clothAltSelfOpacityCosmetic) {
            gomod$clothAltSelfOpacityCosmetic = false;
            SelfOpacity.endExternalLocalPlayerCosmeticRender();
        }
        if (gomod$clothAltVisibleSpecsCosmetic) {
            gomod$clothAltVisibleSpecsCosmetic = false;
            VisibleSpecs.endExternalLocalPlayerCosmeticRender();
        }
    }

    @Inject(
            method = "IIOOIIHRIOCCIHHCIRICHIIHIHOHII("
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/COCHICRCCIRHIHRRCHCIOOIOOOHHIH/ICOCCRRCCCICCIHRCOOOOHCOHHIHCO;"
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/RHOOROCCICRCRICICHICIIHRCOIRHC/IIOOIIHRIOCCIHHCIRICHIIHIHOHII;"
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/COICHHIHCCCOOROCCHHICHRIRCCHRR/COICHHIHCCCOOROCCHHICHRIRCCHRR;"
                    + "FFFFFFF)V",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void gomod$selfOpacityCurrentLunarCloakReturn(@Coerce Object matrices, @Coerce Object playerBridge,
            @Coerce Object renderState, float limbSwing, float limbSwingAmount, float partialTicks,
            float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        if (gomod$currentSelfOpacityCosmetic) {
            gomod$currentSelfOpacityCosmetic = false;
            SelfOpacity.endExternalLocalPlayerCosmeticRender();
        }
        if (gomod$currentVisibleSpecsCosmetic) {
            gomod$currentVisibleSpecsCosmetic = false;
            VisibleSpecs.endExternalLocalPlayerCosmeticRender();
        }
    }

    @Inject(
            method = "IIOOIIHRIOCCIHHCIRICHIIHIHOHII("
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/COCHICRCCIRHIHRRCHCIOOIOOOHHIH/ICOCCRRCCCICCIHRCOOOOHCOHHIHCO;"
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORHHOCIHOCIHORIHIRCHCHROHRHCOO/ORHHOCIHOCIHORIHIRCHCHROHRHCOO;"
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/COICHHIHCCCOOROCCHHICHRIRCCHRR/COICHHIHCCCOOROCCHHICHRIRCCHRR;"
                    + "FFFFFFF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void gomod$suppressCurrentAltLunarCloak(@Coerce Object matrices, @Coerce Object playerBridge,
            @Coerce Object renderState, float limbSwing, float limbSwingAmount, float partialTicks,
            float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        gomod$currentAltSelfOpacityCosmetic = false;
        gomod$currentAltVisibleSpecsCosmetic = false;
        if (PlayerStandaloneCape.shouldSuppressLunarCloakForBridge(playerBridge)) {
            if (!gomod$reportedCurrentRender) {
                gomod$reportedCurrentRender = true;
                System.out.println("[gomod][cape-trace] suppressed current alt Lunar cloak render for local player");
            }
            ci.cancel();
            return;
        }
        gomod$currentAltVisibleSpecsCosmetic = VisibleSpecs.beginExternalLocalPlayerCosmeticRender(playerBridge);
        gomod$currentAltSelfOpacityCosmetic = !gomod$currentAltVisibleSpecsCosmetic
                && SelfOpacity.beginExternalLocalPlayerCosmeticRender(playerBridge);
    }

    @Inject(
            method = "IIOOIIHRIOCCIHHCIRICHIIHIHOHII("
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/COCHICRCCIRHIHRRCHCIOOIOOOHHIH/ICOCCRRCCCICCIHRCOOOOHCOHHIHCO;"
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORHHOCIHOCIHORIHIRCHCHROHRHCOO/ORHHOCIHOCIHORIHIRCHCHROHRHCOO;"
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/COICHHIHCCCOOROCCHHICHRIRCCHRR/COICHHIHCCCOOROCCHHICHRIRCCHRR;"
                    + "FFFFFFF)V",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void gomod$selfOpacityCurrentAltLunarCloakReturn(@Coerce Object matrices, @Coerce Object playerBridge,
            @Coerce Object renderState, float limbSwing, float limbSwingAmount, float partialTicks,
            float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        if (gomod$currentAltSelfOpacityCosmetic) {
            gomod$currentAltSelfOpacityCosmetic = false;
            SelfOpacity.endExternalLocalPlayerCosmeticRender();
        }
        if (gomod$currentAltVisibleSpecsCosmetic) {
            gomod$currentAltVisibleSpecsCosmetic = false;
            VisibleSpecs.endExternalLocalPlayerCosmeticRender();
        }
    }

    @Inject(
            method = "HCOCIICRHHRCRIHIIIRRIOIHIIHCOC("
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/COCHICRCCIRHIHRRCHCIOOIOOOHHIH/RHOOROCCICRCRICICHICIIHRCOIRHC/COICHHIHCCCOOROCCHHICHRIRCCHRR/RHOOROCCICRCRICICHICIIHRCOIRHC;"
                    + ")I",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void gomod$suppressCurrentLunarCloakLayerCount(@Coerce Object playerBridge,
            CallbackInfoReturnable<Integer> cir) {
        if (PlayerStandaloneCape.shouldSuppressLunarCloakForBridge(playerBridge)) {
            gomod$reportCurrentLayerCountSuppressed();
            cir.setReturnValue(0);
        }
    }

    @Inject(
            method = "IIOOIIHRIOCCIHHCIRICHIIHIHOHII("
                    + "Lcom/moonsworth/lunar/COICHHIHCCCOOROCCHHICHRIRCCHRR/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC/COCHICRCCIRHIHRRCHCIOOIOOOHHIH/RHOOROCCICRCRICICHICIIHRCOIRHC/COICHHIHCCCOOROCCHHICHRIRCCHRR/ROROCHCIRCCHHCRCCOOOIHIRHOOCOI;"
                    + ")I",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void gomod$suppressCurrentAltLunarCloakLayerCount(@Coerce Object playerBridge,
            CallbackInfoReturnable<Integer> cir) {
        if (PlayerStandaloneCape.shouldSuppressLunarCloakForBridge(playerBridge)) {
            gomod$reportCurrentLayerCountSuppressed();
            cir.setReturnValue(0);
        }
    }

    @Inject(
            method = "RRHORRHIIIOHHCICIORHHOHHOHIIHR("
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/HOIHCCRHORIHIHHCHHIHHCCROHOHOR/HOOOIIIOCRHCIRRIHCIHOHROIHRCHH;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/ICHRRCIICOIIRHCIOOICORICHOOCHI/RRHORRHIIIOHHCICIORHHOHHOHIIHR;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/IORHHRRIHIRRIRRHIHCIRCRORIRHRI;"
                    + "FFFFFFF)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void gomod$suppressLegacyLunarCloakRender(@Coerce Object matrices, @Coerce Object playerBridge,
            @Coerce Object renderState, float limbSwing, float limbSwingAmount, float partialTicks,
            float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        gomod$legacySelfOpacityCosmetic = false;
        gomod$legacyVisibleSpecsCosmetic = false;
        if (PlayerStandaloneCape.shouldSuppressLunarCloakForBridge(playerBridge)) {
            if (!gomod$reportedLegacyRender) {
                gomod$reportedLegacyRender = true;
                System.out.println("[gomod][cape-trace] suppressed legacy Lunar cloak render for local player");
            }
            ci.cancel();
            return;
        }
        gomod$legacyVisibleSpecsCosmetic = VisibleSpecs.beginExternalLocalPlayerCosmeticRender(playerBridge);
        gomod$legacySelfOpacityCosmetic = !gomod$legacyVisibleSpecsCosmetic
                && SelfOpacity.beginExternalLocalPlayerCosmeticRender(playerBridge);
    }

    @Inject(
            method = "RRHORRHIIIOHHCICIORHHOHHOHIIHR("
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/HOIHCCRHORIHIHHCHHIHHCCROHOHOR/HOOOIIIOCRHCIRRIHCIHOHROIHRCHH;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/ICHRRCIICOIIRHCIOOICORICHOOCHI/RRHORRHIIIOHHCICIORHHOHHOHIIHR;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/IORHHRRIHIRRIRRHIHCIRCRORIRHRI;"
                    + "FFFFFFF)V",
            at = @At("RETURN"),
            require = 0,
            remap = false
    )
    private void gomod$selfOpacityLegacyLunarCloakReturn(@Coerce Object matrices, @Coerce Object playerBridge,
            @Coerce Object renderState, float limbSwing, float limbSwingAmount, float partialTicks,
            float ageInTicks, float netHeadYaw, float headPitch, float scale, CallbackInfo ci) {
        if (gomod$legacySelfOpacityCosmetic) {
            gomod$legacySelfOpacityCosmetic = false;
            SelfOpacity.endExternalLocalPlayerCosmeticRender();
        }
        if (gomod$legacyVisibleSpecsCosmetic) {
            gomod$legacyVisibleSpecsCosmetic = false;
            VisibleSpecs.endExternalLocalPlayerCosmeticRender();
        }
    }

    @Inject(
            method = "CIIOHCCOCRHORIRROROICHHRRRHHCC("
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/HOIHCCRHORIHIHHCHHIHHCCROHOHOR/ICHRRCIICOIIRHCIOOICORICHOOCHI/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/ICHRRCIICOIIRHCIOOICORICHOOCHI;"
                    + ")I",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void gomod$suppressLegacyLunarCloakLayerCount(@Coerce Object playerBridge,
            CallbackInfoReturnable<Integer> cir) {
        if (PlayerStandaloneCape.shouldSuppressLunarCloakForBridge(playerBridge)) {
            if (!gomod$reportedLegacyLayerCount) {
                gomod$reportedLegacyLayerCount = true;
                System.out.println("[gomod][cape-trace] suppressed legacy Lunar cloak layer count for local player");
            }
            cir.setReturnValue(0);
        }
    }

    @Unique
    private static void gomod$reportCurrentLayerCountSuppressed() {
        if (!gomod$reportedCurrentLayerCount) {
            gomod$reportedCurrentLayerCount = true;
            System.out.println("[gomod][cape-trace] suppressed current Lunar cloak layer count for local player");
        }
    }
}
