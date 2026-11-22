package me.napim.gomod.gui;

/** Shared geometry for profile drawing, clipping, scrolling and pointer targets. */
public final class PlayerProfileLayout {
    public static final int SUMMARY_HEIGHT = 48;
    public static final int SUMMARY_GAP = 6;
    public static final int DETAIL_PITCH = 28;
    public static final int KIT_HEIGHT = 28;
    public static final int KIT_PITCH = 32;

    public final int left, right, top, bottom;
    public final int previewRight, statsLeft;
    public final int contentLeft, contentRight, contentTop, contentBottom;
    public final int summaryColumns, detailColumns;
    public final int summaryWidth, detailWidth;
    public final int detailsHeadingY, detailsY, kitsHeadingY, kitsHeaderY, kitsY;
    public final int kitHeight, kitPitch;
    public final int modelTop, modelBottom, modelScale, modelBaseY;
    public final int dragHintY, cosmeticsHeadingY, cosmeticsTop, cosmeticsRowPitch, cosmeticsCellHeight;
    public final boolean compactHeader;
    public final boolean compactKits;

    public PlayerProfileLayout(int width, int height, int footerY) {
        this(width, height, footerY, false);
    }

    public PlayerProfileLayout(int width, int height, int footerY, boolean hideProfileBanner) {
        int outerWidth = Math.min(720, width - 16);
        left = (width - outerWidth) / 2;
        right = left + outerWidth;
        compactHeader = !hideProfileBanner && height < 330;
        int preferredTop = hideProfileBanner ? 56 : compactHeader ? 74 : 92;
        top = Math.min(preferredTop, Math.max(36, footerY - 110));
        bottom = footerY - 10;
        int previewWidth = Math.min(250, Math.max(120, outerWidth * 34 / 100));
        previewRight = left + previewWidth;
        statsLeft = previewRight + (outerWidth >= 500 ? 12 : 8);
        contentLeft = statsLeft + 10;
        contentRight = right - 15;
        contentTop = top + (bottom - top < 150 ? 32 : 40);
        contentBottom = bottom - 10;
        int contentWidth = contentRight - contentLeft;
        compactKits = contentWidth < 300;
        kitHeight = compactKits ? 40 : KIT_HEIGHT;
        kitPitch = compactKits ? 44 : KIT_PITCH;
        summaryColumns = contentWidth >= 280 ? 4 : 2;
        detailColumns = contentWidth >= 280 ? 2 : 1;
        summaryWidth = (contentWidth - (summaryColumns - 1) * SUMMARY_GAP) / summaryColumns;
        detailWidth = (contentWidth - (detailColumns - 1) * SUMMARY_GAP) / detailColumns;
        int summaryRows = 4 / summaryColumns;
        detailsHeadingY = summaryRows * SUMMARY_HEIGHT + (summaryRows - 1) * SUMMARY_GAP + 16;
        detailsY = detailsHeadingY;
        kitsHeadingY = detailsY + ((8 + detailColumns - 1) / detailColumns) * DETAIL_PITCH + 13;
        kitsHeaderY = kitsHeadingY + 20;
        kitsY = kitsHeaderY + (compactKits ? 0 : 18);
        int panelHeight = bottom - top;
        cosmeticsRowPitch = Math.max(24, Math.min(30, (panelHeight - 140) / 6));
        cosmeticsCellHeight = Math.max(16, cosmeticsRowPitch - 2);
        int cosmeticsListHeight = 5 * cosmeticsRowPitch + cosmeticsCellHeight;
        cosmeticsTop = bottom - 8 - cosmeticsListHeight;
        cosmeticsHeadingY = cosmeticsTop - 15;
        dragHintY = cosmeticsHeadingY - 18;
        modelTop = top + (hideProfileBanner || panelHeight >= 170 ? 36 : 10);
        modelBottom = Math.max(modelTop + 24, dragHintY - 10);
        int modelHeight = modelBottom - modelTop;
        modelScale = Math.max(12, Math.min(85,
                Math.min((modelHeight - 8) * 100 / 215, (previewWidth - 24) * 100 / 125)));
        modelBaseY = modelTop + (modelHeight + Math.round(modelScale * 1.95F)) / 2;
    }

    public int summaryX(int index) {
        return contentLeft + (index % summaryColumns) * (summaryWidth + SUMMARY_GAP);
    }

    public int summaryY(int index) {
        return (index / summaryColumns) * (SUMMARY_HEIGHT + SUMMARY_GAP);
    }

    public int detailX(int index) {
        return contentLeft + (index % detailColumns) * (detailWidth + SUMMARY_GAP);
    }

    public int detailY(int index) {
        return detailsY + (index / detailColumns) * DETAIL_PITCH;
    }

    private int kitStatsLeft() {
        return contentLeft + (contentRight - contentLeft) * 45 / 100;
    }

    public int kitNameWidth() {
        return kitStatsLeft() - contentLeft - 58;
    }

    public int kitStatWidth() {
        return (contentRight - 10 - kitStatsLeft()) / 3 - 8;
    }

    public int kitStatRight(int index) {
        int statsLeft = kitStatsLeft();
        return statsLeft + (contentRight - 10 - statsLeft) * (index + 1) / 3;
    }

    public int footerY(int kitCount) {
        return kitsY + (kitCount == 0 ? 36 : kitCount * kitPitch) + 10;
    }

    public int maxScroll(int kitCount) {
        return Math.max(0, footerY(kitCount) + 18 - (contentBottom - contentTop));
    }
}
