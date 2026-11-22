package me.napim.gomod.gui;

import me.napim.gomod.features.GomodSocialChat;
import me.napim.gomod.features.GomodSocialForums;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.List;

/** Thread posts — self on the right, others on the left (like DM chat). */
public final class SocialThreadDraw {

    private static final int AVATAR = 22;
    private static final int NAME_H = 11;
    private static final int BODY_LINE = 11;
    private static final int TIME_H = 9;
    private static final int PAD = 8;
    private static final int GAP = 10;

    private SocialThreadDraw() {}

    public static int drawPosts(FontRenderer fr, Minecraft mc, List<GomodSocialForums.Post> posts,
            int left, int top, int right, int bottom, int scroll) {
        int innerH = bottom - top;
        int bubbleMaxW = Math.max(80, (right - left) * 72 / 100);
        int contentH = measurePostsHeight(fr, posts, bubbleMaxW);
        int maxScroll = Math.max(0, contentH - innerH);
        scroll = SocialUi.clamp(scroll, 0, maxScroll);
        int y = top - scroll + 6;

        if (posts == null || posts.isEmpty()) {
            String hint = EnumChatFormatting.GRAY + "No replies yet \u2014 be the first!";
            int tw = fr.getStringWidth(hint);
            fr.drawString(hint, left + (right - left - tw) / 2, top + innerH / 2 - 4, SocialTheme.TEXT_DIM);
            SocialUi.drawScrollbar(right, top, innerH, contentH, scroll, maxScroll);
            return scroll;
        }

        String lastAuthor = null;
        for (GomodSocialForums.Post p : posts) {
            if (p == null) {
                continue;
            }
            String author = p.author == null ? "?" : p.author.trim();
            boolean self = isSelf(author);
            boolean showName = !self && (lastAuthor == null || !lastAuthor.equalsIgnoreCase(author));
            lastAuthor = author;

            String body = p.body == null ? "" : p.body.trim();
            if (body.isEmpty()) {
                continue;
            }
            String timeLabel = GomodSocialChat.formatMessageTime(p.ts);
            List<String> lines = wrapText(fr, body, bubbleMaxW);
            int textW = 0;
            for (String line : lines) {
                textW = Math.max(textW, fr.getStringWidth(line));
            }
            int timeW = timeLabel.isEmpty() ? 0 : fr.getStringWidth(timeLabel);
            int bubbleW = Math.min(bubbleMaxW, Math.max(60, Math.max(textW, timeW) + PAD * 2));
            int bubbleH = lines.size() * BODY_LINE + PAD * 2 + TIME_H + 4;

            if (showName) {
                if (y + NAME_H + 4 >= top && y <= bottom) {
                    fr.drawString(EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + author,
                            left + 8 + AVATAR + 8, y + 1, SocialTheme.TEXT_BRIGHT);
                }
                y += NAME_H + 4;
            }

            int edgePad = 8;
            int avatarGap = 8;
            int ax = avatarX(self, left, right, edgePad);
            int bx = bubbleX(self, left, right, bubbleW, edgePad, avatarGap);
            if (y + bubbleH >= top && y <= bottom) {
                drawPostAvatar(author, ax, y);
                drawPostBubble(bx, y, bubbleW, bubbleH, self);
                int ty = y + PAD + 1;
                int textColor = self ? SocialTheme.TEXT_BRIGHT : SocialTheme.TEXT;
                for (String line : lines) {
                    fr.drawString(line, bx + PAD, ty, textColor);
                    ty += BODY_LINE;
                }
                drawPostTime(fr, timeLabel, bx, y, bubbleW, bubbleH, self);
            }
            y += bubbleH + GAP;
        }
        SocialUi.drawScrollbar(right, top, innerH, contentH, scroll, maxScroll);
        return scroll;
    }

    private static void drawPostBubble(int bx, int y, int w, int h, boolean self) {
        if (GomodSocialSpeechBubbleTextures.draw(self, bx, y, w, h)) {
            return;
        }
        float rad = SocialTheme.RADIUS_MD;
        if (self) {
            SocialDraw.roundedTopSheen(bx, y, bx + w, y + h, rad, SocialTheme.BUBBLE_SELF, SocialTheme.BUBBLE_SELF_TOP);
        } else {
            SocialDraw.roundedBorder(bx, y, bx + w, y + h, rad, SocialTheme.BUBBLE_OTHER, SocialTheme.BORDER_SUBTLE);
            SocialDraw.verticalGradient(bx + rad, y + 1, bx + w - rad, y + h / 2,
                    SocialTheme.BUBBLE_OTHER_TOP, SocialDraw.withAlpha(SocialTheme.BUBBLE_OTHER_TOP, 0));
        }
    }

    private static void drawPostAvatar(String author, int x, int y) {
        PlayerHeadTextureUtil.drawMinotarAvatar(author, x, y, AVATAR, 1);
    }

    private static int avatarX(boolean self, int left, int right, int edgePad) {
        return self ? right - edgePad - AVATAR : left + edgePad;
    }

    private static int bubbleX(boolean self, int left, int right, int bubbleW, int edgePad, int avatarGap) {
        if (self) {
            return right - edgePad - AVATAR - avatarGap - bubbleW;
        }
        return left + edgePad + AVATAR + avatarGap;
    }

    private static void drawPostTime(FontRenderer fr, String timeLabel, int bx, int y, int w, int h, boolean self) {
        if (timeLabel == null || timeLabel.isEmpty()) {
            return;
        }
        int color = self ? SocialDraw.withAlpha(SocialTheme.TEXT_BRIGHT, 150) : SocialTheme.TEXT_DIM;
        int tw = fr.getStringWidth(timeLabel);
        int tx = bx + w - PAD - tw;
        int ty = y + h - TIME_H - 2;
        if (self) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(tx, ty + 1, 0);
            GlStateManager.scale(0.78f, 0.78f, 1.0f);
            fr.drawString(timeLabel, 0, 0, color);
            GlStateManager.popMatrix();
        } else {
            fr.drawString(timeLabel, tx, ty, color);
        }
    }

    private static int measurePostsHeight(FontRenderer fr, List<GomodSocialForums.Post> posts, int bubbleMaxW) {
        int total = 8;
        if (posts == null) {
            return total;
        }
        String lastAuthor = null;
        for (GomodSocialForums.Post p : posts) {
            if (p == null) {
                continue;
            }
            String author = p.author == null ? "?" : p.author.trim();
            boolean self = isSelf(author);
            boolean showName = !self && (lastAuthor == null || !lastAuthor.equalsIgnoreCase(author));
            lastAuthor = author;
            String body = p.body == null ? "" : p.body.trim();
            if (body.isEmpty()) {
                continue;
            }
            List<String> lines = wrapText(fr, body, bubbleMaxW);
            int bubbleH = lines.size() * BODY_LINE + PAD * 2 + TIME_H + 4;
            if (showName) {
                total += NAME_H + 4;
            }
            total += bubbleH + GAP;
        }
        return total;
    }

    private static boolean isSelf(String author) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && author != null
                && author.equalsIgnoreCase(mc.thePlayer.getName());
    }

    private static List<String> wrapText(FontRenderer fr, String body, int maxPx) {
        List<String> out = new ArrayList<String>();
        if (body == null || body.isEmpty()) {
            return out;
        }
        String[] tokens = body.split(" ");
        StringBuilder line = new StringBuilder();
        for (String token : tokens) {
            String candidate = line.length() == 0 ? token : line + " " + token;
            if (fr.getStringWidth(candidate) > maxPx && line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                line.append(token);
            } else if (fr.getStringWidth(token) > maxPx) {
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                }
                String rest = token;
                while (rest.length() > 0) {
                    int cut = 1;
                    while (cut < rest.length() && fr.getStringWidth(rest.substring(0, cut + 1)) <= maxPx) {
                        cut++;
                    }
                    out.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
            } else {
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(token);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }
}
