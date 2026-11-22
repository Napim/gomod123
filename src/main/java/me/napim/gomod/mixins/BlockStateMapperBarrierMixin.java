package me.napim.gomod.mixins;

import net.minecraft.client.renderer.block.statemap.BlockStateMapper;
import net.minecraft.init.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/**
 * Same strategy as the original BarrierVisibility weave mod ({@code MixinBlockStateMapper}):
 * when the mapper's built-in blacklist {@link Set#contains} is queried for {@link Blocks#barrier},
 * pretend the block is absent so barrier states get real model locations instead of {@code missingno},
 * without relying on a full {@link net.minecraft.client.Minecraft#refreshResources()} (several-second stall).
 *
 * <p>A previous implementation used a HEAD {@code @Inject} on Forge's SRG {@code func_188181_b}; that
 * target does not resolve in the MCP compile view and can fail to apply, which left
 * {@link me.napim.gomod.features.BarrierVisibilityModelFix} to call {@code refreshResources()} on the
 * title screen after stripping the skip set reflectively.
 */
@Mixin(BlockStateMapper.class)
public abstract class BlockStateMapperBarrierMixin {

    @Redirect(
            method = {"putAllStateModelLocations", "func_178446_a"},
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Set;contains(Ljava/lang/Object;)Z"
            ),
            require = 0
    )
    private boolean gomod$barrierBlacklistContains(Set<?> blacklist, Object block) {
        if (block == Blocks.barrier) {
            return false;
        }
        return blacklist.contains(block);
    }
}
