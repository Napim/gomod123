package me.napim.gomod.util;

import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * Inserts intermediate waypoints so straight segments don't tunnel through walls
 * (eye-height raycasts between stand positions).
 */
public final class ChestRoutePathSmoother {

    private static final double EYE = 1.62;
    private static final double MIN_SEG = 0.42;
    private static final double REACH_EPS = 0.35;
    private static final int MAX_DEPTH = 14;

    private ChestRoutePathSmoother() {
    }

    public static List<Vec3> smooth(World world, List<Vec3> keyframes) {
        if (world == null || keyframes == null || keyframes.size() < 2) {
            return keyframes == null ? new ArrayList<>() : new ArrayList<>(keyframes);
        }
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < keyframes.size() - 1; i++) {
            Vec3 a = keyframes.get(i);
            Vec3 b = keyframes.get(i + 1);
            List<Vec3> seg = expand(world, a, b, 0);
            if (i == 0) {
                out.addAll(seg);
            } else {
                if (!seg.isEmpty() && !out.isEmpty()
                        && nearlySame(out.get(out.size() - 1), seg.get(0))) {
                    seg.remove(0);
                }
                out.addAll(seg);
            }
        }
        return out;
    }

    private static boolean nearlySame(Vec3 a, Vec3 b) {
        double dx = a.xCoord - b.xCoord;
        double dy = a.yCoord - b.yCoord;
        double dz = a.zCoord - b.zCoord;
        return dx * dx + dy * dy + dz * dz < 0.0004;
    }

    private static List<Vec3> expand(World world, Vec3 a, Vec3 b, int depth) {
        if (a.distanceTo(b) < MIN_SEG || depth > MAX_DEPTH || segmentClear(world, a, b)) {
            List<Vec3> two = new ArrayList<>(2);
            two.add(a);
            two.add(b);
            return two;
        }
        Vec3 mid = snapMid(world, a, b);
        List<Vec3> left = expand(world, a, mid, depth + 1);
        List<Vec3> right = expand(world, mid, b, depth + 1);
        List<Vec3> out = new ArrayList<>(left.size() + right.size());
        out.addAll(left);
        if (!out.isEmpty() && !right.isEmpty()) {
            out.remove(out.size() - 1);
        }
        out.addAll(right);
        return out;
    }

    private static boolean segmentClear(World world, Vec3 a, Vec3 b) {
        if (!singleEyeRayClear(world, a, b)) {
            return false;
        }
        // Offset parallel rays in XZ so thin walls / block edges don't read as "clear" on the center ray only.
        double dx = b.xCoord - a.xCoord;
        double dz = b.zCoord - a.zCoord;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.08) {
            return true;
        }
        double px = (-dz / len) * 0.28;
        double pz = (dx / len) * 0.28;
        Vec3 aL = new Vec3(a.xCoord + px, a.yCoord, a.zCoord + pz);
        Vec3 bL = new Vec3(b.xCoord + px, b.yCoord, b.zCoord + pz);
        Vec3 aR = new Vec3(a.xCoord - px, a.yCoord, a.zCoord - pz);
        Vec3 bR = new Vec3(b.xCoord - px, b.yCoord, b.zCoord - pz);
        return singleEyeRayClear(world, aL, bL) && singleEyeRayClear(world, aR, bR);
    }

    private static boolean singleEyeRayClear(World world, Vec3 feetA, Vec3 feetB) {
        Vec3 eyeA = new Vec3(feetA.xCoord, feetA.yCoord + EYE, feetA.zCoord);
        Vec3 eyeB = new Vec3(feetB.xCoord, feetB.yCoord + EYE, feetB.zCoord);
        MovingObjectPosition hit = world.rayTraceBlocks(eyeA, eyeB, false, true, false);
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return true;
        }
        double dHit = eyeA.distanceTo(hit.hitVec);
        double dTot = eyeA.distanceTo(eyeB);
        return dHit >= dTot - REACH_EPS;
    }

    private static Vec3 snapMid(World world, Vec3 a, Vec3 b) {
        double mx = (a.xCoord + b.xCoord) * 0.5;
        double mz = (a.zCoord + b.zCoord) * 0.5;
        // Pure height interpolation — avoids snapping mid-segment to the wrong floor/roof.
        double my = (a.yCoord + b.yCoord) * 0.5;
        return new Vec3(mx, my, mz);
    }
}
