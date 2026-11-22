package me.napim.gomod.mixins;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import me.napim.gomod.features.LunarGomodMascotCatalog;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.Reader;

/** Registers goparky through Lunar's normal catalog and observes its final Locker state. */
// Superseded by LunarCatalogCompatibilityTransform; intentionally omitted from gomod.weave.mixins.json.
@Deprecated
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.IHCHHIIIHROCIROHROCHRCIROHOICH."
                + "IHIRRHORRRRHICCOHOHORCHIHHHIOO.OOIOHHHCIICRRRCICOIIIRRRICOCIC",
        remap = false
)
public abstract class LunarGomodMascotCatalogMixin {
    @Redirect(
            method = "HICROOIHCCHIOHOHOIRRIOOCRIHCRH",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/gson/Gson;fromJson(Ljava/io/Reader;Lcom/google/gson/reflect/TypeToken;)Ljava/lang/Object;",
                    ordinal = 0
            ),
            require = 0,
            remap = false
    )
    private Object gomod$appendCompanionToCatalog(Gson gson, Reader reader, TypeToken<?> type) {
        Object parsed = gson.fromJson(reader, type.getType());
        return LunarGomodMascotCatalog.appendToCatalog(parsed);
    }

    @Inject(method = "HICROOIHCCHIOHOHOIRRIOOCRIHCRH", at = @At("RETURN"), require = 0, remap = false)
    private void gomod$verifyCompanionCatalog(CallbackInfo ci) {
        // Retired: the structural catalog transform supplies the verified field from the active runtime.
    }

    @Inject(method = "OROOCOIOCROHOIORRIOOCIRRCCCHIR", at = @At("RETURN"), require = 0, remap = false)
    private void gomod$observeCompanionLocker(CallbackInfo ci) {
        LunarGomodMascotCatalog.onLockerPublished(this);
    }
}
