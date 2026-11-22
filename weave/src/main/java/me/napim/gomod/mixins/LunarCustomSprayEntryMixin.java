package me.napim.gomod.mixins;

import com.google.gson.JsonElement;
import me.napim.gomod.features.LunarCustomSpray;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Retired mixin intentionally omitted from {@code gomod.weave.mixins.json}: superseded by
 * {@link me.napim.gomod.weave.LunarCatalogCompatibilityTransform} (retained for offline compatibility checks).
 */
@Deprecated
@Pseudo
@Mixin(targets = LunarCustomSpray.NATIVE_ENTRY_CLASS, remap = false)
public abstract class LunarCustomSprayEntryMixin {

    @Shadow(remap = false)
    public abstract int getId();

    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/moonsworth/lunar/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
                            + "OIORRCRHOIHCIORCOOOORICHCIRHHI/OIORRCRHOIHCIORCOOOORICHCIRHHI;"
                            + "ROHIOOCICRORHRCIHHHHHHRICCCRRC(Ljava/lang/String;Ljava/lang/String;)"
                            + "Lcom/moonsworth/lunar/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
                            + "OIORRCRHOIHCIORCOOOORICHCIRHHI/OIORRCRHOIHCIORCOOOORICHCIRHHI;",
                    remap = false),
            index = 0,
            require = 0,
            remap = false)
    private String gomod$usePackagedAssetForCustomSpray(String namespace) {
        return "lunar-jit".equals(namespace) && LunarCustomSpray.isConstructingCustomEntry()
                ? "gomod"
                : namespace;
    }

    @Inject(method = "provide()Lcom/google/gson/JsonElement;", at = @At("RETURN"),
            cancellable = true, require = 0, remap = false)
    private void gomod$providePackagedWheelImage(CallbackInfoReturnable<JsonElement> callback) {
        int sprayId = getId();
        if (!LunarCustomSpray.isCustomSprayId(sprayId)) {
            return;
        }
        JsonElement catalogEntry = callback.getReturnValue();
        if (catalogEntry == null || !catalogEntry.isJsonObject()) {
            return;
        }
        String dataUri = LunarCustomSpray.getWheelTextureDataUri(sprayId);
        if (dataUri != null) {
            catalogEntry.getAsJsonObject().addProperty("texture", dataUri);
        }
    }
}