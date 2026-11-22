package me.napim.gomod.gui;

import net.minecraft.client.gui.Gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Composites HUD-editor preview backgrounds as a single coverage layer.
 *
 * Transparent GUI rectangles are normally blended directly into the framebuffer. If two
 * modules overlap, the second rectangle therefore increases the opacity in the intersection.
 * During the HUD editor we keep track of the screen-space areas already used by a preview
 * background and only draw the uncovered pieces of the next one.
 */
public final class HudEditorBackgroundMask {

    private static final List<Rect> covered = new ArrayList<Rect>();
    private static final List<Transform> transforms = new ArrayList<Transform>();
    private static boolean active;

    private HudEditorBackgroundMask() {
    }

    public static void beginFrame() {
        active = true;
        covered.clear();
        transforms.clear();
        transforms.add(new Transform(0, 0, 1.0F));
    }

    public static void endFrame() {
        active = false;
        covered.clear();
        transforms.clear();
    }

    public static boolean isActive() {
        return active;
    }

    /** Keeps a textured/background panel from being drawn over by later preview backgrounds. */
    public static void reserveRect(int left, int top, int right, int bottom) {
        if (!active) {
            return;
        }
        Rect screen = transformedRect(left, top, right, bottom, currentTransform());
        if (screen.right > screen.left && screen.bottom > screen.top) {
            covered.add(screen);
        }
    }

    /** Draws a background rectangle, clipped to areas not already occupied by another preview. */
    public static void drawRect(int left, int top, int right, int bottom, int color) {
        if (!active) {
            Gui.drawRect(left, top, right, bottom, color);
            return;
        }
        Rect screen = transformedRect(left, top, right, bottom, currentTransform());
        drawUncovered(screen, color, currentTransform());
    }

    /**
     * Marks a scaled row while its matching OpenGL transform is active. Calls to drawRect from
     * that row remain in the row's unscaled coordinate system, but clipping is calculated in
     * screen coordinates so overlaps still line up exactly.
     */
    public static void pushTransform(int pivotX, int pivotY, float scale) {
        if (active) {
            transforms.add(new Transform(pivotX, pivotY, scale));
        }
    }

    public static void popTransform() {
        if (active && transforms.size() > 1) {
            transforms.remove(transforms.size() - 1);
        }
    }

    private static Transform currentTransform() {
        return transforms.isEmpty() ? new Transform(0, 0, 1.0F) : transforms.get(transforms.size() - 1);
    }

    private static Rect transformedRect(int left, int top, int right, int bottom, Transform transform) {
        int sx1 = transform.screenX(left);
        int sx2 = transform.screenX(right);
        int sy1 = transform.screenY(top);
        int sy2 = transform.screenY(bottom);
        return new Rect(Math.min(sx1, sx2), Math.min(sy1, sy2), Math.max(sx1, sx2), Math.max(sy1, sy2));
    }

    private static void drawUncovered(Rect source, int color, Transform transform) {
        if (source.right <= source.left || source.bottom <= source.top) {
            return;
        }
        List<Rect> remaining = new ArrayList<Rect>();
        remaining.add(source);
        for (Rect cutter : covered) {
            List<Rect> next = new ArrayList<Rect>();
            for (Rect piece : remaining) {
                subtract(piece, cutter, next);
            }
            remaining = next;
            if (remaining.isEmpty()) {
                break;
            }
        }
        for (Rect piece : remaining) {
            if (piece.right <= piece.left || piece.bottom <= piece.top) {
                continue;
            }
            int left = transform.localX(piece.left, true);
            int top = transform.localY(piece.top, true);
            int right = transform.localX(piece.right, false);
            int bottom = transform.localY(piece.bottom, false);
            if (right > left && bottom > top) {
                Gui.drawRect(left, top, right, bottom, color);
            }
        }
        // Reserve the full source, not just the pieces that were drawn. Later backgrounds must
        // not fill the intersection even when the current source was partly covered.
        covered.add(source);
    }

    private static void subtract(Rect source, Rect cutter, List<Rect> output) {
        int left = Math.max(source.left, cutter.left);
        int top = Math.max(source.top, cutter.top);
        int right = Math.min(source.right, cutter.right);
        int bottom = Math.min(source.bottom, cutter.bottom);
        if (left >= right || top >= bottom) {
            output.add(source);
            return;
        }
        if (source.top < top) {
            output.add(new Rect(source.left, source.top, source.right, top));
        }
        if (bottom < source.bottom) {
            output.add(new Rect(source.left, bottom, source.right, source.bottom));
        }
        if (source.left < left) {
            output.add(new Rect(source.left, top, left, bottom));
        }
        if (right < source.right) {
            output.add(new Rect(right, top, source.right, bottom));
        }
    }

    private static final class Rect {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;

        private Rect(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private static final class Transform {
        private final int pivotX;
        private final int pivotY;
        private final float scale;

        private Transform(int pivotX, int pivotY, float scale) {
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.scale = scale <= 0.0F ? 1.0F : scale;
        }

        private int screenX(int local) {
            return (int) Math.round(pivotX + (local - (double) pivotX) * scale);
        }

        private int screenY(int local) {
            return (int) Math.round(pivotY + (local - (double) pivotY) * scale);
        }

        private int localX(int screen, boolean start) {
            double value = pivotX + (screen - (double) pivotX) / scale;
            return start ? (int) Math.floor(value) : (int) Math.ceil(value);
        }

        private int localY(int screen, boolean start) {
            double value = pivotY + (screen - (double) pivotY) / scale;
            return start ? (int) Math.floor(value) : (int) Math.ceil(value);
        }
    }
}
