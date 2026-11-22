package me.napim.gomod.mixins;

import me.napim.gomod.features.LocalDisguise;
import net.minecraft.entity.boss.BossStatus;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Rewrites boss-bar text when BossStatus receives it instead of during every FontRenderer draw. */
@Mixin(BossStatus.class)
public abstract class BossStatusDisguiseMixin {

    @Redirect(
            method = "setBossStatus",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/util/IChatComponent;getFormattedText()Ljava/lang/String;"
            ),
            require = 0
    )
    private static String gomod$rewriteDisguiseBossNameSource(IChatComponent component) {
        String original = component == null ? "" : component.getFormattedText();
        return LocalDisguise.rewriteRenderedTextFast(original);
    }
}
