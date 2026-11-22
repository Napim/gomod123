package me.napim.gomod.features;

/**
 * Public-release stub: DM mirror is internal-only. No outbound HTTP or third-party chat APIs.
 */
public final class DcMirrorBridge {

    private DcMirrorBridge() {
    }

    public static void loadSettings() {
    }

    public static void warmSelfDisplayFromToken() {
    }

    public static boolean isEnabled() {
        return false;
    }

    public static void setEnabled(boolean value) {
    }

    public static void syncEnabledFromConfig() {
    }

    public static boolean shouldRedirectPlainChatOffServer() {
        return false;
    }

    public static void onOutgoingChat(String message) {
    }

    public static void echoRedirectedLine(String trimmed) {
    }

    public static void invalidateChannelCache() {
    }

    public static void invalidateTokenIdentityCaches() {
    }

    public static boolean isGatewayEligible() {
        return false;
    }

    public static String getRawTokenForGatewayIdentify(String pendingToken) {
        return null;
    }

    public static void setCachedSelfSnowflake(String id) {
    }

    public static String getCachedSelfSnowflake() {
        return null;
    }

    public static void cacheUserProfileFromJson(com.google.gson.JsonObject author, String authorId) {
    }

    public static boolean shouldProcessInboundDm(String authorId) {
        return false;
    }

    public static String formatAuthorLabelFromJson(com.google.gson.JsonObject author) {
        return "";
    }

    public static void recordLastInboundDmAuthor(String authorId) {
    }

    public static String getLastInboundDmAuthorSnowflake() {
        return null;
    }

    public static void queueOutboundDmToSnowflake(String rid, String text) {
    }

    public static String resolveMirrorRecipientSnowflake(String input) {
        return null;
    }

    public static String getRecipientLabelLine(String id) {
        return id == null ? "" : id;
    }

    public static int compareRecipientsByRecentTalk(String a, String b) {
        return 0;
    }

    public static void touchRecipientRecencyManual(String id) {
    }

    public static void prefetchRecipientsForGui(java.util.List<String> ids, Runnable onDone) {
        if (onDone != null) {
            onDone.run();
        }
    }

    public static void importDcFriendsAsync(Runnable onDone) {
        if (onDone != null) {
            onDone.run();
        }
    }

    public static void ensureGuiAvatarLoaded(String userId) {
    }

    public static boolean bindGuiAvatarIfReady(String userId) {
        return false;
    }

    public static int[] getGuiAvatarDimensions(String userId) {
        return new int[] {0, 0};
    }
}
