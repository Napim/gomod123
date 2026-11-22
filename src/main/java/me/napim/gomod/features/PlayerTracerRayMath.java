package me.napim.gomod.features;

/** Advance to the far boundary of transparent voxels instead of retrying inside the same glass. */
public final class PlayerTracerRayMath {
    private PlayerTracerRayMath() {}

    public static double distancePastVoxel(double x, double y, double z, double dx, double dy, double dz,
                                           int blockX, int blockY, int blockZ) {
        double tx = exitDistance(x, dx, blockX);
        double ty = exitDistance(y, dy, blockY);
        double tz = exitDistance(z, dz, blockZ);
        double distance = Math.min(tx, Math.min(ty, tz));
        return Double.isInfinite(distance) ? 0.0001 : Math.max(0.0, distance) + 0.0001;
    }

    private static double exitDistance(double coordinate, double direction, int block) {
        if (direction > 1.0E-12) return (block + 1.0 - coordinate) / direction;
        if (direction < -1.0E-12) return (block - coordinate) / direction;
        return Double.POSITIVE_INFINITY;
    }
}
