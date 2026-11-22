package me.napim.gomod.mixins;

import net.minecraft.client.renderer.entity.RenderManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderManager.class)
public interface RenderManagerAccessor {
    @Accessor("renderPosX")
    double gomod$getRenderPosX();

    @Accessor("renderPosY")
    double gomod$getRenderPosY();

    @Accessor("renderPosZ")
    double gomod$getRenderPosZ();

    @Accessor("renderOutlines")
    boolean gomod$getRenderOutlines();

    @Accessor("playerViewY")
    float gomod$getPlayerViewY();

    @Accessor("playerViewY")
    void gomod$setPlayerViewY(float value);

    @Accessor("playerViewX")
    float gomod$getPlayerViewX();

    @Accessor("playerViewX")
    void gomod$setPlayerViewX(float value);
}
