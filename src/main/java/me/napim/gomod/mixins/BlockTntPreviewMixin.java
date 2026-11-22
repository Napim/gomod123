package me.napim.gomod.mixins;

import me.napim.gomod.features.TntPlacementPreviewAtlasHook;
import net.minecraft.block.Block;
import net.minecraft.block.BlockTNT;
import net.minecraft.block.material.Material;
import net.minecraft.util.EnumWorldBlockLayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Ghost TNT — same pattern as {@link BlockBarrierMixin}: {@code @Override} {@code getBlockLayer}
 * (an {@code @Inject} on inherited methods silently no-ops). Atlas alpha + TRANSLUCENT blends.
 */
@Mixin(BlockTNT.class)
public abstract class BlockTntPreviewMixin extends Block {

    private BlockTntPreviewMixin(Material material) {
        super(material);
    }

    @Override
    public EnumWorldBlockLayer getBlockLayer() {
        if (TntPlacementPreviewAtlasHook.isPreviewRenderActive()) {
            return EnumWorldBlockLayer.TRANSLUCENT;
        }
        return super.getBlockLayer();
    }
}
