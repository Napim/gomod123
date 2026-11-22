package me.napim.gomod.mixins;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NetworkPlayerInfo.class)
public interface NetworkPlayerInfoAccessor {
    @Accessor("gameProfile")
    GameProfile gomod$getGameProfile();

    @Accessor("playerTexturesLoaded")
    void gomod$setPlayerTexturesLoaded(boolean value);

    @Accessor("playerTexturesLoaded")
    boolean gomod$getPlayerTexturesLoaded();

    @Accessor("locationSkin")
    void gomod$setLocationSkin(ResourceLocation value);

    @Accessor("locationSkin")
    ResourceLocation gomod$getLocationSkin();

    @Accessor("locationCape")
    void gomod$setLocationCape(ResourceLocation value);

    @Accessor("locationCape")
    ResourceLocation gomod$getLocationCape();

    @Accessor("skinType")
    void gomod$setSkinType(String value);

    @Accessor("skinType")
    String gomod$getSkinType();

    @Accessor("displayName")
    IChatComponent gomod$getDisplayName();

    @Accessor("displayName")
    void gomod$setDisplayName(IChatComponent value);

}
