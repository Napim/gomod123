package me.napim.gomod.features;

import me.napim.gomod.Main;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.weavemc.loader.api.event.ChatReceivedEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

import java.util.Locale;

public class ScreenshotChatPrompt {

    private static long lastSharePromptWallMs;

    /**
     * When {@code ScreenShotHelper} mixins do not run (some clients), we still offer the share row after the
     * screenshot key was pressed, unless a successful save path already cleared this countdown.
     */
    private static volatile int screenshotShareFallbackTicks = -1;

    /** Unformatted first; some mods only populate formatted siblings. */
    public static String chatToRawText(IChatComponent c) {
        if (c == null) {
            return "";
        }
        String u = c.getUnformattedText();
        if (u != null && !u.trim().isEmpty()) {
            return u;
        }
        String f = c.getFormattedText();
        if (f == null) {
            return "";
        }
        String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(f);
        return stripped == null ? "" : stripped;
    }

    /**
     * Called from {@link me.napim.gomod.mixins.GuiNewChatMixin},
     * {@link me.napim.gomod.mixins.EntityPlayerSPMixin}, and the screenshot-key fallback in
     * {@link me.napim.gomod.mixins.MinecraftRunTickFeaturesMixin} (Forge cannot mixin {@code ScreenShotHelper}
     * without an ASM 5/9 clash).
     * {@link ChatReceivedEvent} does not run for those client-only paths.
     */
    public static void onScreenshotFeedbackPrinted(String gameMessageUnformatted) {
        cancelScreenshotShareFallback();
        sendShareRowDebounced();
    }

    @SubscribeEvent
    public void onChatReceived(ChatReceivedEvent event) {
        if (event == null || event.getMessage() == null) {
            return;
        }
        String raw = chatToRawText(event.getMessage());
        if (!looksLikeScreenshotFeedback(raw)) {
            return;
        }
        cancelScreenshotShareFallback();
        sendShareRowDebounced();
    }

    public static boolean looksLikeScreenshotFeedback(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        // Never treat our own mod messages as screenshot feedback
        if (lower.contains("[gomod]")) {
            return false;
        }
        if (lower.contains("shared screenshot with") || lower.contains("sending latest screenshot")) {
            return false;
        }
        if (lower.contains("saved shared screenshot")) {
            return false;
        }
        if (lower.contains("couldn't") || lower.contains("could not") || lower.contains("unable to")
                || lower.contains("failed to") || lower.contains("error ")) {
            return false;
        }
        // Patcher: only the initial save line is capture feedback — COPY/UPLOAD/etc. also say "screenshot".
        if (lower.contains("[patcher]") && lower.contains("screenshot")) {
            if (lower.contains("copied") || lower.contains("clipboard")
                    || lower.contains("upload") || lower.contains("imgur")
                    || lower.contains("deleted") || lower.contains("favorited")) {
                return false;
            }
            return lower.contains("saved");
        }
        return lower.contains("saved screenshot as")
                || lower.contains("screenshot saved")
                || (lower.contains("saved to") && lower.contains("screenshot"))
                || (lower.contains("screenshot") && (lower.contains("saved") || lower.contains("save")))
                || (lower.contains("screenshot") && lower.contains(".png"))
                || lower.contains("screenshots\\")
                || lower.contains("screenshots/")
                || (lower.contains("saved as") && lower.contains(".png"));
    }

    public static void armScreenshotShareFallback(int ticks) {
        if (ticks < 1) {
            return;
        }
        screenshotShareFallbackTicks = ticks;
    }

    public static boolean isScreenshotShareFallbackPending() {
        return screenshotShareFallbackTicks > 0;
    }

    public static void cancelScreenshotShareFallback() {
        screenshotShareFallbackTicks = -1;
    }

    private static long lastFallbackDecrementWallMs;

    /**
     * Decrements the delayed share-row countdown at most once per client tick (~50ms throttle avoids double steps
     * when Forge and mixins both run hub logic in the same wall-clock window).
     */
    public static void tickScreenshotShareFallback() {
        long now = System.currentTimeMillis();
        if (now - lastFallbackDecrementWallMs < 45L) {
            return;
        }
        lastFallbackDecrementWallMs = now;
        if (screenshotShareFallbackTicks <= 0) {
            return;
        }
        if (--screenshotShareFallbackTicks == 0) {
            screenshotShareFallbackTicks = -1;
            sendShareRowDebounced();
        }
    }

    private static void sendShareRowDebounced() {
        long now = System.currentTimeMillis();
        if (now - lastSharePromptWallMs < 900L) {
            return;
        }
        lastSharePromptWallMs = now;
        cancelScreenshotShareFallback();
        Main.sendChatMessage(buildShareHeadlineLine());
        Main.sendChatMessage(buildShareActionLine());
    }

    /** Middle-dot separators avoid hyphen bidirectional quirks; no invisible Unicode — 1.8 font draws U+200E as junk. */
    private static final String ACTION_SEP = " " + '\u00B7' + " ";

    private static ChatComponentText buildShareHeadlineLine() {
        ChatComponentText line = new ChatComponentText("");
        line.appendSibling(new ChatComponentText(Main.CHAT_PREFIX));
        ChatComponentText headline = new ChatComponentText("Screenshot captured.");
        ChatStyle headSt = new ChatStyle();
        headSt.setColor(EnumChatFormatting.GRAY);
        headline.setChatStyle(headSt);
        line.appendSibling(headline);
        return line;
    }

    private static ChatComponentText buildShareActionLine() {
        ChatComponentText line = new ChatComponentText("");
        line.appendSibling(new ChatComponentText(Main.CHAT_PREFIX));

        ChatComponentText sendNow = new ChatComponentText(EnumChatFormatting.BOLD + "[SEND TO FRIEND]" + EnumChatFormatting.RESET);
        ChatStyle st = new ChatStyle();
        st.setColor(EnumChatFormatting.AQUA);
        st.setBold(false);
        st.setUnderlined(false);
        st.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gssend"));
        st.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.YELLOW + "Share screenshot")));
        sendNow.setChatStyle(st);
        line.appendSibling(sendNow);

        ChatComponentText sep1 = new ChatComponentText(ACTION_SEP);
        ChatStyle sep1St = new ChatStyle();
        sep1St.setColor(EnumChatFormatting.DARK_GRAY);
        sep1St.setBold(false);
        sep1St.setUnderlined(false);
        sep1.setChatStyle(sep1St);
        line.appendSibling(sep1);

        ChatComponentText friends = new ChatComponentText(EnumChatFormatting.BOLD + "[FRIENDS LIST]" + EnumChatFormatting.RESET);
        ChatStyle fs = new ChatStyle();
        fs.setColor(EnumChatFormatting.GREEN);
        fs.setBold(false);
        fs.setUnderlined(false);
        fs.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gfriend gui"));
        fs.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.YELLOW + "Open friends list")));
        friends.setChatStyle(fs);
        line.appendSibling(friends);

        ChatComponentText sep2 = new ChatComponentText(ACTION_SEP);
        ChatStyle sep2St = new ChatStyle();
        sep2St.setColor(EnumChatFormatting.DARK_GRAY);
        sep2St.setBold(false);
        sep2St.setUnderlined(false);
        sep2.setChatStyle(sep2St);
        line.appendSibling(sep2);

        ChatComponentText edit = new ChatComponentText(EnumChatFormatting.BOLD + "[EDIT]" + EnumChatFormatting.RESET);
        ChatStyle es = new ChatStyle();
        es.setColor(EnumChatFormatting.GOLD);
        es.setBold(false);
        es.setUnderlined(false);
        es.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gsedit"));
        es.setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.YELLOW + "Open screenshot editor")));
        edit.setChatStyle(es);
        line.appendSibling(edit);

        return line;
    }
}
