package me.napim.gomod.util;

import com.google.gson.JsonObject;

/** Public-release stub. */
public final class DcMirrorFormatter {

    private DcMirrorFormatter() {
    }

    public static boolean messageHasDisplayableImage(JsonObject d) {
        return false;
    }

    public static String formatInboundBody(String content, boolean hasImage) {
        return content == null ? "" : content;
    }
}
