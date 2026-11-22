package me.napim.gomod.gui;

/** Shared drawing/input geometry for the cape list; does not change the selected cape. */
public final class CapeListScrollbar {
    private boolean dragging;
    private int grabOffset;
    private int pressY;
    private int pressIndex;

    public static final class Geometry {
        public final int x, top, height, thumbTop, thumbHeight, maxScroll, scrollIndex;

        public Geometry(int x, int top, int height, int totalRows, int visibleRows, int scrollIndex) {
            this.x = x;
            this.top = top;
            this.height = Math.max(1, height);
            this.maxScroll = visibleRows <= 0 ? 0 : Math.max(0, totalRows - visibleRows);
            this.scrollIndex = Math.max(0, Math.min(maxScroll, scrollIndex));
            this.thumbHeight = Math.min(this.height, Math.max(18,
                    totalRows <= 0 ? this.height : this.height * Math.max(0, visibleRows) / totalRows));
            this.thumbTop = top + (maxScroll == 0 ? 0
                    : (this.height - thumbHeight) * this.scrollIndex / maxScroll);
        }

        public boolean isVisible() { return maxScroll > 0; }

        int indexAt(int mouseY, int offset) {
            int travel = height - thumbHeight;
            if (travel <= 0) return 0;
            double fraction = (double) (mouseY - top - offset) / travel;
            return Math.max(0, Math.min(maxScroll, (int) Math.round(fraction * maxScroll)));
        }
    }

    public boolean press(int mouseX, int mouseY, Geometry geometry) {
        if (!geometry.isVisible() || mouseX < geometry.x - 4 || mouseX >= geometry.x + 5
                || mouseY < geometry.top || mouseY >= geometry.top + geometry.height) return false;
        boolean onThumb = mouseY >= geometry.thumbTop
                && mouseY < geometry.thumbTop + geometry.thumbHeight;
        grabOffset = onThumb ? mouseY - geometry.thumbTop : geometry.thumbHeight / 2;
        pressY = mouseY;
        pressIndex = onThumb ? geometry.scrollIndex : geometry.indexAt(mouseY, grabOffset);
        dragging = true;
        return true;
    }

    public int drag(int mouseY, Geometry geometry) {
        if (!dragging || !geometry.isVisible()) return geometry.scrollIndex;
        return mouseY == pressY ? pressIndex : geometry.indexAt(mouseY, grabOffset);
    }

    public boolean isDragging() { return dragging; }
    public void release() { dragging = false; }
}
