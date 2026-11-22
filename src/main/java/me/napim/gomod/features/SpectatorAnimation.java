package me.napim.gomod.features;

/** Vanilla-style limb strength/phase advanced by client ticks, independent of render FPS. */
public final class SpectatorAnimation {
    private long lastAt = Long.MIN_VALUE;
    private double lastX, lastY, lastZ;
    public float amount, phase;
    public void tick(long now, double x, double y, double z) {
        long elapsed=lastAt==Long.MIN_VALUE?0:now-lastAt;
        double dx=x-lastX, dy=y-lastY, dz=z-lastZ;
        if (elapsed<=0 || elapsed>=SpectatorFeedState.STALE_MS || dx*dx+dy*dy+dz*dz>256D) {
            amount=phase=0F;
        } else {
            // Vanilla measures horizontal distance each 50 ms and eases amplitude by 0.4.
            double ticks=elapsed/50D;
            float target=(float)Math.min(1D,Math.sqrt(dx*dx+dz*dz)*4D/ticks);
            amount+=(target-amount)*(float)(1D-Math.pow(0.6D,ticks));
            phase+=amount*(float)ticks;
        }
        lastAt=now; lastX=x; lastY=y; lastZ=z;
    }
}
