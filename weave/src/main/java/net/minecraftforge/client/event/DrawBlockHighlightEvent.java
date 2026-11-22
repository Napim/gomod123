package net.minecraftforge.client.event;

/** Linkage shim for the Forge-only callback retained by the shared block-overlay class. */
public class DrawBlockHighlightEvent {
    public final float partialTicks;
    private boolean cancelled;

    public DrawBlockHighlightEvent(float partialTicks) {
        this.partialTicks = partialTicks;
    }

    public void setCanceled(boolean cancelled) { this.cancelled = cancelled; }
    public boolean isCanceled() { return cancelled; }
}
