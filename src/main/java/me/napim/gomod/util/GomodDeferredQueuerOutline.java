package me.napim.gomod.util;

import net.minecraft.entity.player.EntityPlayer;

/** Player Queuer include/exclude outline deferred to RenderGlobal chameleon pass (must not live in a mixin class). */
public final class GomodDeferredQueuerOutline {
    public final EntityPlayer player;
    public final int tintRgb;

    public GomodDeferredQueuerOutline(EntityPlayer player, int tintRgb) {
        this.player = player;
        this.tintRgb = tintRgb;
    }
}
