package net.minecraftforge.client.event;

import net.minecraft.client.renderer.texture.TextureMap;

/** Linkage shim; native Weave texture changes are driven by gomod's mixins/tick retry. */
public class TextureStitchEvent {
    public final TextureMap map;

    protected TextureStitchEvent(TextureMap map) {
        this.map = map;
    }

    public static final class Pre extends TextureStitchEvent {
        public Pre(TextureMap map) { super(map); }
    }

    public static final class Post extends TextureStitchEvent {
        public Post(TextureMap map) { super(map); }
    }
}
