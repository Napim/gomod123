package me.napim.gomod.features;

/** True while any gomod top-right social toast is on screen. */
public final class GomodSocialToastVisibility {

    private GomodSocialToastVisibility() {}

    public static boolean isAnyToastShowing() {
        return IncomingScreenshotOverlay.isShowing()
                || IncomingFriendRequestOverlay.isShowing()
                || IncomingFriendDmOverlay.isShowing()
                || IncomingPublicPingOverlay.isShowing()
                || IncomingCapeDesignOverlay.isShowing();
    }
}
