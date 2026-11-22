package me.napim.gomod.mixins;

import net.minecraft.entity.player.PlayerCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerCapabilities.class)
public interface PlayerCapabilitiesAccessor {
    @Accessor("walkSpeed")
    float gomod$getWalkSpeed();

    @Accessor("walkSpeed")
    void gomod$setWalkSpeed(float value);
}
