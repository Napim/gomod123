package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import java.util.Collection;
import java.util.UUID;

/** Lunar owns its cosmetic subscriptions only in the native Weave runtime. */
public final class LunarSpectatorCosmetics {
    private LunarSpectatorCosmetics() {}
    public static void update(Minecraft mc, Collection<UUID> players, long now) {}
    public static void clear() {}
}
