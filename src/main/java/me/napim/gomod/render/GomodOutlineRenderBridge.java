package me.napim.gomod.render;

/** Exposes gomod's entity-outline pass to loader-specific world-render fallbacks. */
public interface GomodOutlineRenderBridge {
    void gomod$renderGomodOutlineFromWorldEvent(float partialTicks);
}
