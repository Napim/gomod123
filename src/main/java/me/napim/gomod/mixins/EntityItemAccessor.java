package me.napim.gomod.mixins;

import net.minecraft.entity.item.EntityItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EntityItem.class)
public interface EntityItemAccessor {
    @Accessor("delayBeforeCanPickup")
    int gomod$getDelayBeforeCanPickup();

    @Accessor("delayBeforeCanPickup")
    void gomod$setDelayBeforeCanPickup(int value);

    /** Ticks since spawn; used for teammate drop proximity heuristic. */
    @Accessor("age")
    int gomod$getItemAge();
}
