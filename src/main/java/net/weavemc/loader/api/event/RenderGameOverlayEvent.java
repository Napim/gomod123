package net.weavemc.loader.api.event;

public class RenderGameOverlayEvent extends Event {
    private final float partialTicks;

    public RenderGameOverlayEvent(float partialTicks) {
        this.partialTicks = partialTicks;
    }

    public float getPartialTicks() {
        return partialTicks;
    }

    public static class Pre extends Event {
        private final float partialTicks;

        public Pre(float partialTicks) {
            this.partialTicks = partialTicks;
        }

        public float getPartialTicks() {
            return partialTicks;
        }
    }

    public static class Post extends Event {
        private final float partialTicks;

        public Post(float partialTicks) {
            this.partialTicks = partialTicks;
        }

        public float getPartialTicks() {
            return partialTicks;
        }
    }
}
