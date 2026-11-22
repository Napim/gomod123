package me.napim.gomod.mixins;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.features.GomodLunarPlusLocker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a client-only “No +” choice to the active Lunar native Locker model. */
// Superseded by LunarColorCompatibilityTransform; intentionally omitted from gomod.weave.mixins.json.
@Deprecated
@Pseudo
@Mixin(
        targets = {
                "com.moonsworth.lunar.client.OIOIICIHIOORICIIHRRIHIHOOCOOOO."
                        + "HCHORCCCCHCOHHHROOCIRICCCIRHHH.HCOOHCRIIRHROCROHROOOOORCRORIR",
                "com.moonsworth.lunar.client.CRICRCRHRRICCICRHICIIIOHRIRHRH."
                        + "HHICICROOCIIROOHOORRCIHOICHOIH.COOHIICHOIRRHOIHHOCOICHRRHHIIC",
                "com.moonsworth.lunar.client.IHCHHIIIHROCIROHROCHRCIROHOICH."
                        + "IHIRRHORRRRHICCOHOHORCHIHHHIOO.HIRCHOCCIROORHOHOOHOIOHOOIHRCR"
        },
        remap = false
)
public abstract class LunarPlusLockerModelMixin {
    private static final String STATE_SETTER =
            "Lcom/moonsworth/lunar/client/COOHHRRCORRHIRIHRORIHRHICCIOIH/"
                    + "IOCIHHIRIROCOIROOCORCCIOOIIIRR/"
                    + "COHCCRCCIOORIRIIRIRCIHHCHIRCOH/"
                    + "IHCCRCCOHRCCIORHHCROORROCICRHI;"
                    + "OCRRORHCHRCRIIHRORIRIRROCORHCI(Ljava/lang/String;Ljava/lang/Object;)V";
    private static final String REFRESH_METHOD = "OICCRCCHIHIHIORIRRHIOIRRICCIRI()V";
    private static final String CURRENT_REFRESH_METHOD = "HIHROOIOHHCRIOIIOHCIICHOHIHHCH()V";
    private static final String LUNAR_2231_REFRESH_METHOD = "CIHHCIOHHICOHIIHOHCOIHHRCICOHI()V";
    private static final String LOGIN_RESPONSE_METHOD =
            "IHCCRCCOHRCCIORHHCROORROCICRHI(Lcom/lunarclient/websocket/cosmetic/v2/LoginResponse;)V";
    private static final String LUNAR_2231_LOGIN_RESPONSE_METHOD =
            "IHHHICHHRICRCIOHCIOHHOHIOIORRH(Lcom/lunarclient/websocket/cosmetic/v2/LoginResponse;)V";
    private static final String LUNAR_2231_STATE_SETTER =
            "Lcom/moonsworth/lunar/client/CIRIIHCCROCIOIRORIOIOCHHIIHRCC/"
                    + "CICOHIIRRHHIIHRROHOICOCRHHHHOO/ROHIOOCICRORHRCIHHHHHHRICCCRRC/"
                    + "IHHHICHHRICRCIOHCIOHHOHIOIORRH;"
                    + "ROICRHIOIOCHHICCROCHHCRIORRIRH(Ljava/lang/String;Ljava/lang/Object;)V";

    @Inject(method = {LOGIN_RESPONSE_METHOD, LUNAR_2231_LOGIN_RESPONSE_METHOD},
            at = @At("RETURN"), require = 0, remap = false)
    private void gomod$registerSyntheticColor(@Coerce Object response, CallbackInfo ci) {
        GomodLunarPlusLocker.onLockerModelAvailable(this, response);
    }

    @Redirect(
            method = {REFRESH_METHOD, CURRENT_REFRESH_METHOD},
            at = @At(value = "INVOKE", target = STATE_SETTER),
            require = 0,
            remap = false
    )
    private void gomod$publishNoPlusChoice(@Coerce Object stateOwner, String stateKey, Object value) {
        int publishedColorCount = -1;
        if ("lunarPlus".equals(stateKey) && value instanceof JsonObject) {
            // Keep Lunar's actual subscription flag intact; this local presentation bit only
            // suppresses the SVG plus layer in the main-menu account tile while No+ is selected.
            JsonObject localPresentation = ((JsonObject) value).deepCopy();
            localPresentation.addProperty("gomodHidePlus", GomodLunarPlusLocker.isNoPlusSelectedForDisplay());
            value = localPresentation;
        }
        if ("active".equals(stateKey)) {
            boolean noPlusSelected = GomodLunarPlusLocker.isNoPlusSelected();
            if (value instanceof Number) {
                // Lunar's UI marks a row equipped when active equals its color. FullAccess
                // uses the persisted local choice because the native preference can lag behind
                // the profile update that already changed the nametag and tab.
                int nativeActive = ((Number) value).intValue();
                int activeColor = noPlusSelected ? GomodLunarPlusLocker.NO_PLUS_COLOR
                        : GomodLunarPlusLocker.activeColorForLocker(nativeActive);
                value = Integer.valueOf(activeColor);
                GomodLunarPlusLocker.recordPublishedNoPlusSelection(noPlusSelected, activeColor);
            } else if (noPlusSelected) {
                value = Integer.valueOf(GomodLunarPlusLocker.NO_PLUS_COLOR);
                GomodLunarPlusLocker.recordPublishedNoPlusSelection(noPlusSelected,
                        GomodLunarPlusLocker.NO_PLUS_COLOR);
            }
        }
        if ("availableColors".equals(stateKey) && value instanceof JsonArray) {
            JsonArray orderedColors = GomodLunarPlusLocker.withNoPlusChoice(
                    (JsonArray) value, GomodLunarPlusLocker.isFullAccessEnabled());
            value = orderedColors;
            publishedColorCount = orderedColors.size();
        }

        LunarLockerStateBridge.set(stateOwner, stateKey, value);
        if (publishedColorCount >= 0) {
            GomodLunarPlusLocker.recordPublishedAvailableColorCount(publishedColorCount);
        }
    }

    @Redirect(
            method = LUNAR_2231_REFRESH_METHOD,
            at = @At(value = "INVOKE", target = LUNAR_2231_STATE_SETTER),
            require = 0,
            remap = false
    )
    private void gomod$publishNoPlusChoice2231(@Coerce Object stateOwner,
                                               String stateKey, Object value) {
        gomod$publishNoPlusChoice(stateOwner, stateKey, value);
    }
}
