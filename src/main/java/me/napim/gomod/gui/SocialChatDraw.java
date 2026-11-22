package me.napim.gomod.gui;

import me.napim.gomod.features.GomodSocialChat;
import me.napim.gomod.features.GomodSocialPublicMentions;
import me.napim.gomod.features.GomodCapeShareHistory;
import me.napim.gomod.features.LunarFitSharing;
import com.google.gson.JsonObject;
import me.napim.gomod.features.PlayerCustomCapes;
import me.napim.gomod.features.PlayerScreenshotFiles;
import me.napim.gomod.util.ScreenshotImageTextureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compact grouped chat renderer for gomod Social. Messages are laid out like a
 * modern DM conversation: date dividers, sender groups, tight bubbles, and one
 * avatar/name per group instead of one card per message.
 */
public final class SocialChatDraw {

    private static final int GROUP_WINDOW_MS = 5 * 60 * 1000;
    private static final int DATE_DIVIDER_H = 24;
    private static final int NEW_MESSAGES_DIVIDER_H = 24;
    private static final int GROUP_GAP = 10;
    private static final int SAME_GROUP_GAP = 3;
    private static final int NAME_H = 12;
    private static final int GROUP_TIME_H = 8;
    private static final float GROUP_TIME_SCALE = 0.72F;
    private static final int AVATAR_GAP = 7;
    private static final int EDGE_PAD = 8;
    private static final int MIN_BUBBLE_W = 34;
    private static final int SCREENSHOT_THUMB_MAX_W = 200;
    private static final int SCREENSHOT_THUMB_MAX_H = 112;
    private static final int SCREENSHOT_PLACEHOLDER_H = 48;
    private static final int CAPE_RECEIPT_H = 108;
    private static final int CAPE_RECEIPT_MIN_W = 230;
    private static final int CAPE_RECEIPT_MODEL_W = 76;
    private static final int CAPE_SHEET_W = 56;
    private static final int CAPE_SHEET_H = 28;

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MMMM d, yyyy", Locale.US);
    private static final Map<String, ResourceLocation> screenshotTextureCache = new HashMap<String, ResourceLocation>();
    private static final Map<String, int[]> screenshotDimCache = new HashMap<String, int[]>();
    private static final Map<String, File> screenshotResolvedFileCache = new HashMap<String, File>();
    private static final Set<String> screenshotMissingCache = new HashSet<String>();
    private static final Set<String> screenshotTextureLoading = new HashSet<String>();
    private static final Set<String> screenshotTextureFailed = new HashSet<String>();
    private static final Map<String, ResourceLocation> capeReceiptTextureCache = new HashMap<String, ResourceLocation>();
    private static final List<SenderHit> publicSenderHits = new ArrayList<SenderHit>();
    private static final List<ScreenshotHit> screenshotHits = new ArrayList<ScreenshotHit>();
    private static EntityOtherPlayerMP capeReceiptPreviewPlayer;
    private static String capeReceiptPreviewKey = "";
    private static final List<FitHit> fitHits=new ArrayList<FitHit>();
    private static int fitClipLeft,fitClipTop,fitClipRight,fitClipBottom;
    private static final class FitHit {
        final int x,y,w,h;final String request;
        FitHit(int x,int y,int w,int h,String request){this.x=x;this.y=y;this.w=w;this.h=h;this.request=request;}
    }
    public static boolean clickFit(int x,int y){
        for(FitHit hit:fitHits)if(x>=hit.x&&x<hit.x+hit.w&&y>=hit.y&&y<hit.y+hit.h){if(hit.request.contains("\"emote\":true"))me.napim.gomod.features.GomodEmoteSharing.decide(hit.request);else LunarFitSharing.decide(hit.request);return true;}
        return false;
    }

    private SocialChatDraw() {}

    private static final class SenderHit {
        final String sender;
        final int x;
        final int y;
        final int w;
        final int h;

        SenderHit(String sender, int x, int y, int w, int h) {
            this.sender = sender;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    public static final class ScreenshotHit {
        public final File file;
        public final int x;
        public final int y;
        public final int w;
        public final int h;

        ScreenshotHit(File file, int x, int y, int w, int h) {
            this.file = file;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** Sender whose public-chat avatar or username occupies this point, or {@code null}. */
    public static String publicSenderAt(int mouseX, int mouseY) {
        for (SenderHit hit : publicSenderHits) {
            if (mouseX >= hit.x && mouseX < hit.x + hit.w
                    && mouseY >= hit.y && mouseY < hit.y + hit.h) {
                return hit.sender;
            }
        }
        return null;
    }

    /** Screenshot message bubble at this point, or {@code null}. */
    public static ScreenshotHit screenshotAt(int mouseX, int mouseY) {
        for (ScreenshotHit hit : screenshotHits) {
            if (mouseX >= hit.x && mouseX < hit.x + hit.w
                    && mouseY >= hit.y && mouseY < hit.y + hit.h) {
                return hit;
            }
        }
        return null;
    }

    public static final class MessageGroup {
        public final String sender;
        public final List<GomodSocialChat.Message> messages = new ArrayList<GomodSocialChat.Message>();
        public long firstTimestamp;
        public long lastTimestamp;

        MessageGroup(GomodSocialChat.Message first) {
            sender = first.from == null ? "" : first.from.trim();
            firstTimestamp = first.ts;
            lastTimestamp = first.ts;
            messages.add(first);
        }

        void add(GomodSocialChat.Message m) {
            messages.add(m);
            if (m.ts < firstTimestamp || firstTimestamp <= 0L) {
                firstTimestamp = m.ts;
            }
            if (m.ts > lastTimestamp) {
                lastTimestamp = m.ts;
            }
        }
    }

    public static int measureThreadHeight(FontRenderer fr, List<GomodSocialChat.Message> thread, int bubbleMaxW) {
        return measureThreadHeight(fr, thread, bubbleMaxW, true, 0L);
    }

    public static int measureThreadHeight(FontRenderer fr, List<GomodSocialChat.Message> thread, int bubbleMaxW,
            boolean showNames) {
        return measureThreadHeight(fr, thread, bubbleMaxW, showNames, 0L);
    }

    public static int measureThreadHeight(FontRenderer fr, List<GomodSocialChat.Message> thread, int bubbleMaxW,
            boolean showNames, long newMessagesSinceMs) {
        return measureThreadHeight(fr, thread, bubbleMaxW, showNames, newMessagesSinceMs, null);
    }

    public static int measureThreadHeight(FontRenderer fr, List<GomodSocialChat.Message> thread, int bubbleMaxW,
            boolean showNames, long newMessagesSinceMs, String chatPeer) {
        return measureLayout(fr, Minecraft.getMinecraft(), groupMessages(thread), bubbleMaxW, showNames,
                newMessagesSinceMs, chatPeer);
    }

    /** Scroll offset so the first unread message (or divider) sits near the top of the viewport. */
    public static int scrollToFirstUnread(FontRenderer fr, List<GomodSocialChat.Message> thread,
            int left, int top, int right, int bottom, boolean showNames, long newMessagesSinceMs) {
        int innerClip = bottom - top;
        int bubbleMaxW = Math.max(96, (right - left) * 68 / 100);
        List<MessageGroup> groups = groupMessages(thread);
        int contentH = measureLayout(fr, Minecraft.getMinecraft(), groups, bubbleMaxW, showNames, newMessagesSinceMs, null);
        int maxScroll = Math.max(0, contentH - innerClip);
        if (newMessagesSinceMs <= 0L || groups.isEmpty()) {
            return maxScroll;
        }
        int y = top + 6;
        long lastDay = Long.MIN_VALUE;
        boolean dividerPlaced = false;
        for (MessageGroup group : groups) {
            long day = dayKey(group.firstTimestamp);
            if (day != lastDay) {
                y += DATE_DIVIDER_H;
                lastDay = day;
            }
            if (!dividerPlaced && group.lastTimestamp > newMessagesSinceMs) {
                return SocialUi.clamp(y - top - 8, 0, maxScroll);
            }
            y += measureGroup(fr, Minecraft.getMinecraft(), group, bubbleMaxW, showNames, null) + GROUP_GAP;
        }
        return maxScroll;
    }

    public static int drawThread(FontRenderer fr, Minecraft mc, List<GomodSocialChat.Message> thread,
            int left, int top, int right, int bottom, int scroll, boolean showNames) {
        return drawThread(fr, mc, thread, left, top, right, bottom, scroll, showNames, 0L);
    }

    public static int drawThread(FontRenderer fr, Minecraft mc, List<GomodSocialChat.Message> thread,
            int left, int top, int right, int bottom, int scroll, boolean showNames, long newMessagesSinceMs) {
        return drawThread(fr, mc, thread, left, top, right, bottom, scroll, showNames, newMessagesSinceMs, null);
    }

    public static int drawThread(FontRenderer fr, Minecraft mc, List<GomodSocialChat.Message> thread,
            int left, int top, int right, int bottom, int scroll, boolean showNames, long newMessagesSinceMs,
            String chatPeer) {
        publicSenderHits.clear();
        fitHits.clear();fitClipLeft=left;fitClipTop=top;fitClipRight=right;fitClipBottom=bottom;
        LunarFitChatPreview.beginFrame(chatPeer);
        screenshotHits.clear();
        int innerClip = bottom - top;
        int bubbleMaxW = Math.max(96, (right - left) * 68 / 100);
        List<MessageGroup> groups = groupMessages(thread);
        int contentH = measureLayout(fr, mc, groups, bubbleMaxW, showNames, newMessagesSinceMs, chatPeer);
        int maxScroll = Math.max(0, contentH - innerClip);
        scroll = SocialUi.clamp(scroll, 0, maxScroll);
        int y = top - scroll + 6;
        long lastDay = Long.MIN_VALUE;
        boolean unreadDividerPlaced = false;

        SocialDraw.enableScissor(left, top, right, bottom);
        try {
            if (groups.isEmpty()) {
                String hint = EnumChatFormatting.GRAY + "No messages yet";
                int tw = fr.getStringWidth(hint);
                fr.drawString(hint, left + (right - left - tw) / 2, top + (bottom - top) / 2 - 4, SocialTheme.TEXT_DIM);
                SocialUi.drawScrollbar(right, top, innerClip, contentH, scroll, maxScroll);
                return scroll;
            }

            for (MessageGroup group : groups) {
                long day = dayKey(group.firstTimestamp);
                if (day != lastDay) {
                    if (y + DATE_DIVIDER_H >= top && y <= bottom) {
                        drawDateDivider(fr, left, right, y, group.firstTimestamp);
                    }
                    y += DATE_DIVIDER_H;
                    lastDay = day;
                }
                if (!unreadDividerPlaced && newMessagesSinceMs > 0L && group.lastTimestamp > newMessagesSinceMs) {
                    if (y + NEW_MESSAGES_DIVIDER_H >= top && y <= bottom) {
                        drawDivider(fr, left, right, y, EnumChatFormatting.YELLOW + "" + EnumChatFormatting.BOLD + "New messages",
                                SocialTheme.TEXT);
                    }
                    y += NEW_MESSAGES_DIVIDER_H;
                    unreadDividerPlaced = true;
                }

                int h = measureGroup(fr, mc, group, bubbleMaxW, showNames, chatPeer);
                if (y + h >= top && y <= bottom) {
                    drawGroup(fr, mc, group, left, right, y, bubbleMaxW, showNames, chatPeer);
                }
                y += h + GROUP_GAP;
            }
            SocialUi.drawScrollbar(right, top, innerClip, contentH, scroll, maxScroll);
        } finally {
            LunarFitChatPreview.endFrame();
            SocialDraw.disableScissor();
        }
        return scroll;
    }

    private static List<MessageGroup> groupMessages(List<GomodSocialChat.Message> thread) {
        List<MessageGroup> out = new ArrayList<MessageGroup>();
        if (thread == null) {
            return out;
        }
        MessageGroup current = null;
        for (GomodSocialChat.Message m : thread) {
            if (m == null || isEmptyMessage(m)) {
                continue;
            }
            if (current == null || !canGroup(current, m)) {
                current = new MessageGroup(m);
                out.add(current);
            } else {
                current.add(m);
            }
        }
        return out;
    }

    private static boolean canGroup(MessageGroup group, GomodSocialChat.Message m) {
        String sender = m.from == null ? "" : m.from.trim();
        return sender.equalsIgnoreCase(group.sender)
                && dayKey(group.lastTimestamp) == dayKey(m.ts)
                && Math.abs(m.ts - group.lastTimestamp) <= GROUP_WINDOW_MS;
    }

    private static int measureLayout(FontRenderer fr, Minecraft mc, List<MessageGroup> groups, int bubbleMaxW,
            boolean showNames, long newMessagesSinceMs, String chatPeer) {
        int total = 8;
        long lastDay = Long.MIN_VALUE;
        boolean unreadDividerPlaced = false;
        for (MessageGroup group : groups) {
            long day = dayKey(group.firstTimestamp);
            if (day != lastDay) {
                total += DATE_DIVIDER_H;
                lastDay = day;
            }
            if (!unreadDividerPlaced && newMessagesSinceMs > 0L && group.lastTimestamp > newMessagesSinceMs) {
                total += NEW_MESSAGES_DIVIDER_H;
                unreadDividerPlaced = true;
            }
            total += measureGroup(fr, mc, group, bubbleMaxW, showNames, chatPeer) + GROUP_GAP;
        }
        return total + 4;
    }

    private static int measureGroup(FontRenderer fr, Minecraft mc, MessageGroup group, int bubbleMaxW,
            boolean showNames, String chatPeer) {
        int h = 0;
        boolean self = isSelf(group.sender);
        if (showNames && !self) {
            h += NAME_H;
            GomodSocialChat.Message first = group.messages.get(0);
            if (isPublicMessage(first) && first.server != null && !first.server.isEmpty()) {
                h += 9;
            }
        }
        for (int i = 0; i < group.messages.size(); i++) {
            if (i > 0) {
                h += SAME_GROUP_GAP;
            }
            h += messageBubbleHeight(fr, mc, group.messages.get(i), bubbleMaxW, chatPeer);
        }
        return h + GROUP_TIME_H;
    }

    private static void drawGroup(FontRenderer fr, Minecraft mc, MessageGroup group, int left, int right, int y,
            int bubbleMaxW, boolean showNames, String chatPeer) {
        boolean self = isSelf(group.sender);
        int avatarSize = SocialTheme.CHAT_MSG_HEAD;
        int avatarX = avatarX(self, left, right, avatarSize, EDGE_PAD);
        int bubbleAreaL = left + EDGE_PAD + avatarSize + AVATAR_GAP;
        int bubbleAreaR = right - EDGE_PAD - avatarSize - AVATAR_GAP;
        int cursorY = y;

        if (showNames && !self) {
            String displayName = formatName(group.sender);
            fr.drawString(EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + displayName,
                    bubbleAreaL, cursorY + 1, SocialTheme.TEXT_BRIGHT);
            if (isPublicMessage(group.messages.get(0))) {
                publicSenderHits.add(new SenderHit(group.sender, bubbleAreaL, cursorY,
                        fr.getStringWidth(displayName), NAME_H));
            }
            cursorY += NAME_H;
            GomodSocialChat.Message first = group.messages.get(0);
            if (isPublicMessage(first) && first.server != null && !first.server.isEmpty()) {
                fr.drawString(EnumChatFormatting.GRAY + "@" + first.server, bubbleAreaL, cursorY - 1, SocialTheme.TEXT_DIM);
                cursorY += 9;
            }
        }

        int firstBubbleY = cursorY;
        int lastBubbleBottom = cursorY;
        for (int i = 0; i < group.messages.size(); i++) {
            GomodSocialChat.Message m = group.messages.get(i);
            int[] size = messageBubbleSize(fr, mc, m, bubbleMaxW, chatPeer);
            int bubbleW = size[0];
            int bubbleH = size[1];
            int bx = self ? bubbleAreaR - bubbleW : bubbleAreaL;
            drawMessageBubble(fr, mc, m, bx, cursorY, bubbleW, bubbleH, self, chatPeer);
            lastBubbleBottom = cursorY + bubbleH;
            cursorY = lastBubbleBottom + SAME_GROUP_GAP;
        }

        int avatarY = self ? Math.max(firstBubbleY, lastBubbleBottom - avatarSize) : firstBubbleY;
        drawMessageAvatar(group.sender, avatarX, avatarY, avatarSize);
        if (!self && isPublicMessage(group.messages.get(0))) {
            publicSenderHits.add(new SenderHit(group.sender, avatarX, avatarY, avatarSize, avatarSize));
        }

        String time = groupTimeLabel(group);
        int tw = fr.getStringWidth(time);
        int scaledW = Math.round(tw * GROUP_TIME_SCALE);
        int tx = self ? bubbleAreaR - scaledW : bubbleAreaL;
        GlStateManager.pushMatrix();
        GlStateManager.translate(tx, lastBubbleBottom + 2, 0);
        GlStateManager.scale(GROUP_TIME_SCALE, GROUP_TIME_SCALE, 1.0F);
        fr.drawString(time, 0, 0, SocialTheme.TEXT_DIM);
        GlStateManager.popMatrix();
    }

    private static void drawMessageBubble(FontRenderer fr, Minecraft mc, GomodSocialChat.Message m, int bx, int y,
            int bubbleW, int bubbleH, boolean self, String chatPeer) {
        if(!m.emoteAttachment.isEmpty()){drawGiftMessage(fr,mc,m,bx,y,bubbleW,bubbleH,self,chatPeer,false);return;}
        if(!m.fitAttachment.isEmpty()){
            drawGiftMessage(fr,mc,m,bx,y,bubbleW,bubbleH,self,chatPeer,true);return;
        }
        if ("screenshot".equalsIgnoreCase(m.kind)) {
            drawScreenshotMessage(fr, mc, m, bx, y, bubbleW, bubbleH, self, chatPeer);
            return;
        }
        if ("cape_accept_receipt".equalsIgnoreCase(m.kind)) {
            drawCapeAcceptReceiptMessage(fr, mc, m, bx, y, bubbleW, bubbleH, self, chatPeer);
            return;
        }
        drawBubble(bx, y, bubbleW, bubbleH, self);
        int ty = y + bubblePadY() + 1;
        int textColor = self ? SocialTheme.TEXT_BRIGHT : SocialTheme.TEXT;
        for (String line : wrapMessage(fr, m, bubbleW - bubblePadX() * 2)) {
            if (isPublicMessage(m)) {
                GomodSocialPublicMentions.drawDisplayText(fr, line, bx + bubblePadX(), ty, textColor);
            } else {
                fr.drawString(displayLine(line, false), bx + bubblePadX(), ty, textColor);
            }
            ty += SocialTheme.CHAT_MSG_LINE_H;
        }
    }

    private static void drawGiftMessage(FontRenderer fr,Minecraft mc,GomodSocialChat.Message m,int x,int y,int w,int h,boolean self,String chatPeer,boolean fit){
        if(y+h<fitClipTop||y>fitClipBottom)return;
        drawBubble(x,y,w,h,self);
        String wire=fit?m.fitAttachment:m.emoteAttachment;
        JsonObject info=fit?LunarFitSharing.describe(m.from,wire):me.napim.gomod.features.GomodEmoteSharing.describe(m.from,wire);
        String kind=fit?"fit":info.has("kind")?info.get("kind").getAsString():"emote";
        String title=fit?"Fit":"cosmetic".equals(kind)?"Style":"Emote";
        boolean narrow=w<240;int tx=x+bubblePadX(),ty=y+9,textW=Math.max(48,w-bubblePadX()*2-(narrow?0:120));
        fr.drawString(title+(self?" sent":" received"),tx,ty,SocialTheme.TEXT_DIM);
        List<String> names=wrapTextByWidth(fr,info.get("name").getAsString(),textW,false);
        for(int i=0;i<names.size()&&i<2;i++)fr.drawString(names.get(i),tx,ty+16+i*11,SocialTheme.TEXT_BRIGHT);
        String receiver=self?chatPeer:me.napim.gomod.features.GomodSocialPresence.sessionPlayerName(mc);
        List<String> people=wrapTextByWidth(fr,self?"To "+chatPeer:"From "+m.from,textW,false);
        for(int i=0;i<people.size()&&i<2;i++)fr.drawString(people.get(i),tx,ty+42+i*10,SocialTheme.TEXT_DIM);
        List<String> statuses=wrapTextByWidth(fr,info.get("status").getAsString(),textW,false);
        for(int i=0;i<statuses.size()&&i<3;i++)fr.drawString(statuses.get(i),tx,ty+65+i*10,SocialTheme.TEXT_DIM);
        int px=narrow?x+(w-112)/2:x+w-119,py=narrow?y+113:y+4,ph=narrow?114:124;
        String key=me.napim.gomod.features.LunarGiftPreviews.modelId("CHAT",wire,receiver);
        boolean model=LunarFitChatPreview.drawGift(mc,wire,key,fit,receiver,px,py,112,ph);
        if(model){
            LunarFitChatPreview.addHit(key,px,py,px+112,py+ph,fitClipLeft,fitClipTop,fitClipRight,fitClipBottom);
            String hint="Drag to rotate";float scale=.65F;GlStateManager.pushMatrix();GlStateManager.translate(px+56-fr.getStringWidth(hint)*scale/2F,py+ph-4,0);GlStateManager.scale(scale,scale,1F);fr.drawString(hint,0,0,SocialTheme.TEXT_DIM);GlStateManager.popMatrix();
        }else{fr.drawString("Loading preview",px+9,py+52,SocialTheme.TEXT_DIM);}
        if(info.get("canDecide").getAsBoolean()){
            int gap=5,bw=Math.max(36,Math.min(69,(w-bubblePadX()*2-gap)/2)),by=y+h-25;
            fitButton(fr,tx,by,bw,"Accept",0xFF267A4C,info,"accept");fitButton(fr,tx+bw+gap,by,bw,"Decline",0xFF414754,info,"decline");
        }
    }
    private static void fitButton(FontRenderer fr,int x,int y,int w,String label,int color,JsonObject info,String action){
        SocialDraw.roundedBorder(x,y,x+w,y+18,4F,color,SocialTheme.BORDER_SUBTLE);
        fr.drawString(label,x+(w-fr.getStringWidth(label))/2,y+5,SocialTheme.TEXT_BRIGHT);
        java.util.UUID owner=LunarFitSharing.account();if(owner==null)return;
        JsonObject request=new JsonObject();request.addProperty("account",owner.toString());request.addProperty("key",info.get("key").getAsString());request.addProperty("action",action);if(info.has("emote"))request.addProperty("emote",true);
        int top=Math.max(y,fitClipTop),bottom=Math.min(y+18,fitClipBottom);
        if(bottom>top)fitHits.add(new FitHit(x,top,w,bottom-top,request.toString()));
    }
    private static void drawCapeAcceptReceiptMessage(FontRenderer fr, Minecraft mc, GomodSocialChat.Message m,
            int bx, int y, int bubbleW, int bubbleH, boolean self, String chatPeer) {
        drawBubble(bx, y, bubbleW, bubbleH, self);
        int innerX = bx + bubblePadX();
        int innerY = y + bubblePadY() + 1;
        int modelX = bx + bubbleW - bubblePadX() - CAPE_RECEIPT_MODEL_W;
        int modelCenterX = modelX + CAPE_RECEIPT_MODEL_W / 2;
        int textW = Math.max(70, modelX - innerX - 8);
        int textColor = self ? SocialTheme.TEXT_BRIGHT : SocialTheme.TEXT;
        List<String> lines = wrapTextByWidth(fr, m.text == null ? "" : m.text.trim(), textW, false);
        int ty = innerY;
        for (int i = 0; i < lines.size() && i < 2; i++) {
            fr.drawString(displayLine(lines.get(i), false), innerX, ty, textColor);
            ty += SocialTheme.CHAT_MSG_LINE_H;
        }

        ResourceLocation cape = capeReceiptTexture(mc, chatPeer, m);
        int capeX = innerX;
        int capeY = y + bubbleH - bubblePadY() - CAPE_SHEET_H - 4;
        SocialDraw.roundedBorder(capeX - 3, capeY - 3, capeX + CAPE_SHEET_W + 3, capeY + CAPE_SHEET_H + 3,
                4.0F, 0x88212731, SocialTheme.BORDER_SUBTLE);
        if (cape != null) {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            mc.getTextureManager().bindTexture(cape);
            Gui.drawModalRectWithCustomSizedTexture(capeX, capeY, 0, 0, CAPE_SHEET_W, CAPE_SHEET_H,
                    CAPE_SHEET_W, CAPE_SHEET_H);
        } else {
            fr.drawString(EnumChatFormatting.GRAY + "Cape unavailable", capeX, capeY + 9, SocialTheme.TEXT_DIM);
        }

        if (cape != null && mc != null && mc.theWorld != null) {
            String sender = m.from == null ? "" : m.from.trim();
            String key = sender.toLowerCase(Locale.ROOT) + "|" + cape.toString();
            if (capeReceiptPreviewPlayer == null || !key.equals(capeReceiptPreviewKey)) {
                capeReceiptPreviewPlayer = MountPreviewHelper.createCapePreviewPlayer(mc.theWorld, mc, cape, sender);
                capeReceiptPreviewKey = key;
            } else {
                MountPreviewHelper.updateCapePreviewPlayer(mc, capeReceiptPreviewPlayer, cape, sender);
            }
            int py = y + bubbleH - 9;
            MountPreviewHelper.renderCapePreviewPlayer(mc, capeReceiptPreviewPlayer,
                    modelCenterX, py, 34, 1.0F, 165.0F, 10.0F, 1.0F);
        }
    }

    private static void drawScreenshotMessage(FontRenderer fr, Minecraft mc, GomodSocialChat.Message m, int bx, int y,
            int bubbleW, int bubbleH, boolean self, String chatPeer) {
        drawScreenshotBubble(fr, bx, y, bubbleW, bubbleH, self);
        int[] thumb = screenshotThumbSize(mc, chatPeer, m, bubbleW - bubblePadX() * 2);
        int ix = bx + bubblePadX();
        int iy = y + bubblePadY();
        File shotFile = resolveChatScreenshotFileCached(mc, chatPeer, m);
        if (shotFile != null && shotFile.isFile()) {
            screenshotHits.add(new ScreenshotHit(shotFile, bx, y, bubbleW, bubbleH));
        }
        ResourceLocation tex = shotFile == null ? null : textureForFile(mc, shotFile);
        if (tex != null) {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            mc.getTextureManager().bindTexture(tex);
            Gui.drawModalRectWithCustomSizedTexture(ix, iy, 0, 0, thumb[0], thumb[1], thumb[0], thumb[1]);
        } else {
            fr.drawString(EnumChatFormatting.GRAY + "Screenshot unavailable",
                    ix, iy + (thumb[1] - 8) / 2, SocialTheme.TEXT_DIM);
        }
    }

    private static int messageBubbleHeight(FontRenderer fr, Minecraft mc, GomodSocialChat.Message m,
            int bubbleMaxW, String chatPeer) {
        return messageBubbleSize(fr, mc, m, bubbleMaxW, chatPeer)[1];
    }

    private static int[] messageBubbleSize(FontRenderer fr, Minecraft mc, GomodSocialChat.Message m,
            int bubbleMaxW, String chatPeer) {
        if(!m.emoteAttachment.isEmpty())return new int[]{Math.min(bubbleMaxW,320),bubbleMaxW<240?260:154};
        if(!m.fitAttachment.isEmpty())return new int[]{Math.min(bubbleMaxW,320),bubbleMaxW<240?260:154};
        if ("screenshot".equalsIgnoreCase(m.kind)) {
            int[] thumb = screenshotThumbSize(mc, chatPeer, m, bubbleMaxW - bubblePadX() * 2);
            return new int[] {
                    Math.min(bubbleMaxW, Math.max(120, thumb[0] + bubblePadX() * 2)),
                    thumb[1] + bubblePadY() * 2
            };
        }
        if ("cape_accept_receipt".equalsIgnoreCase(m.kind)) {
            return new int[] {
                    Math.min(bubbleMaxW, Math.max(CAPE_RECEIPT_MIN_W, 150 + bubblePadX() * 2)),
                    CAPE_RECEIPT_H
            };
        }
        String body = m.text == null ? "" : m.text.trim();
        int wrapW = Math.max(40, bubbleMaxW - bubblePadX() * 2);
        List<String> lines = wrapTextByWidth(fr, body, wrapW, isPublicMessage(m));
        int textW = 0;
        for (String line : lines) {
            textW = Math.max(textW, fr.getStringWidth(displayLine(line, isPublicMessage(m))));
        }
        int w = Math.min(bubbleMaxW, Math.max(MIN_BUBBLE_W, textW + bubblePadX() * 2));
        int h = Math.max(18, lines.size() * SocialTheme.CHAT_MSG_LINE_H + bubblePadY() * 2);
        return new int[] { w, h };
    }

    private static List<String> wrapMessage(FontRenderer fr, GomodSocialChat.Message m, int maxW) {
        return wrapTextByWidth(fr, m.text == null ? "" : m.text.trim(), maxW, isPublicMessage(m));
    }

    private static List<String> wrapTextByWidth(FontRenderer fr, String body, int maxW, boolean publicMsg) {
        List<String> out = new ArrayList<String>();
        if (body == null || body.isEmpty()) {
            out.add("");
            return out;
        }
        String[] tokens = body.split(" ");
        StringBuilder line = new StringBuilder();
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            String prospective = line.length() == 0 ? token : line.toString() + " " + token;
            if (fr.getStringWidth(displayLine(prospective, publicMsg)) <= maxW) {
                line.setLength(0);
                line.append(prospective);
                continue;
            }
            if (line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
            }
            splitLongToken(fr, token, maxW, publicMsg, out, line);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    private static void splitLongToken(FontRenderer fr, String token, int maxW, boolean publicMsg,
            List<String> out, StringBuilder current) {
        StringBuilder part = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            part.append(token.charAt(i));
            if (fr.getStringWidth(displayLine(part.toString(), publicMsg)) > maxW && part.length() > 1) {
                char last = part.charAt(part.length() - 1);
                part.setLength(part.length() - 1);
                out.add(part.toString());
                part.setLength(0);
                part.append(last);
            }
        }
        current.append(part);
    }

    private static void drawDateDivider(FontRenderer fr, int left, int right, int y, long ts) {
        drawDivider(fr, left, right, y, EnumChatFormatting.GRAY + dateLabel(ts), SocialTheme.TEXT_MUTED);
    }

    private static void drawDivider(FontRenderer fr, int left, int right, int y, String label, int color) {
        int tw = fr.getStringWidth(label);
        int cx = left + (right - left) / 2;
        int textY = y + 7;
        int lineY = textY + fr.FONT_HEIGHT / 2;
        int gap = 7;
        SocialDraw.fillRect(left + 8, lineY, cx - tw / 2 - gap, lineY + 1, SocialTheme.BORDER);
        SocialDraw.fillRect(cx + tw / 2 + gap, lineY, right - 8, lineY + 1, SocialTheme.BORDER);
        fr.drawString(label, cx - tw / 2, textY, color);
    }

    private static void drawBubble(int bx, int y, int w, int h, boolean self) {
        if (GomodSocialSpeechBubbleTextures.draw(self, bx, y, w, h)) {
            return;
        }
        float rad = Math.min(7.0F, Math.max(4.0F, h / 2.0F));
        if (self) {
            SocialDraw.roundedTopSheen(bx, y, bx + w, y + h, rad, SocialTheme.BUBBLE_SELF, SocialTheme.BUBBLE_SELF_TOP);
        } else {
            SocialDraw.roundedBorder(bx, y, bx + w, y + h, rad, SocialTheme.BUBBLE_OTHER, SocialTheme.BORDER_SUBTLE);
        }
    }

    private static int[] screenshotThumbSize(Minecraft mc, String chatPeer, GomodSocialChat.Message m, int innerMaxW) {
        int maxW = Math.max(60, Math.min(SCREENSHOT_THUMB_MAX_W, innerMaxW));
        File file = resolveChatScreenshotFileCached(mc, chatPeer, m);
        if (file == null) {
            return new int[] { maxW, SCREENSHOT_PLACEHOLDER_H };
        }
        int[] dims = imageDimensions(file);
        return fitThumb(maxW, SCREENSHOT_THUMB_MAX_H, dims[0], dims[1]);
    }

    private static ResourceLocation capeReceiptTexture(Minecraft mc, String chatPeer, GomodSocialChat.Message m) {
        String image = m == null ? "" : m.capeImageBase64;
        if (image == null || image.trim().isEmpty()) {
            image = findCapeReceiptImageFromHistory(chatPeer, m);
        }
        if (image == null || image.trim().isEmpty()) {
            return null;
        }
        String sender = m.from == null ? "" : m.from.trim();
        String key = sender.toLowerCase(Locale.ROOT) + "|" + Integer.toHexString(image.hashCode());
        ResourceLocation cached = capeReceiptTextureCache.get(key);
        if (cached != null) {
            return cached;
        }
        ResourceLocation loaded = PlayerCustomCapes.registerEphemeralPeerWear("receipt_" + key.replace("|", "_"), image);
        if (loaded != null) {
            capeReceiptTextureCache.put(key, loaded);
        }
        return loaded;
    }

    private static String findCapeReceiptImageFromHistory(String chatPeer, GomodSocialChat.Message m) {
        String peer = chatPeer == null || chatPeer.trim().isEmpty()
                ? (m == null ? "" : m.from)
                : chatPeer.trim();
        String label = m == null || m.capeLabel == null ? "" : GomodCapeShareHistory.cleanLabel(m.capeLabel);
        if (label.isEmpty() && m != null) {
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(m.text == null ? "" : m.text);
            int idx = plain == null ? -1 : plain.lastIndexOf(':');
            if (idx >= 0 && idx + 1 < plain.length()) {
                label = GomodCapeShareHistory.cleanLabel(plain.substring(idx + 1));
            }
        }
        if (peer == null || peer.trim().isEmpty() || label.isEmpty()) {
            return findCapeReceiptImageByLabel(label);
        }
        List<GomodCapeShareHistory.Entry> entries = GomodCapeShareHistory.forPeer(peer);
        for (GomodCapeShareHistory.Entry e : entries) {
            if (e != null && GomodCapeShareHistory.SENT.equals(e.direction)
                    && label.equalsIgnoreCase(GomodCapeShareHistory.cleanLabel(e.label))
                    && e.imageBase64 != null && !e.imageBase64.isEmpty()) {
                return e.imageBase64;
            }
        }
        return findCapeReceiptImageByLabel(label);
    }

    private static String findCapeReceiptImageByLabel(String label) {
        String cleanLabel = GomodCapeShareHistory.cleanLabel(label);
        if (cleanLabel.isEmpty()) {
            return "";
        }
        List<GomodCapeShareHistory.Entry> entries = GomodCapeShareHistory.all();
        for (GomodCapeShareHistory.Entry e : entries) {
            if (e != null && GomodCapeShareHistory.SENT.equals(e.direction)
                    && cleanLabel.equalsIgnoreCase(GomodCapeShareHistory.cleanLabel(e.label))
                    && e.imageBase64 != null && !e.imageBase64.isEmpty()) {
                return e.imageBase64;
            }
        }
        return "";
    }

    private static int[] imageDimensions(File file) {
        if (file == null) {
            return new int[] { 16, 9 };
        }
        String key = file.getAbsolutePath();
        int[] cached = screenshotDimCache.get(key);
        if (cached != null) {
            return cached;
        }
        return new int[] { 16, 9 };
    }

    private static File resolveChatScreenshotFileCached(Minecraft mc, String chatPeer, GomodSocialChat.Message m) {
        String key = screenshotMessageKey(chatPeer, m);
        if (key.isEmpty()) {
            return null;
        }
        if (screenshotMissingCache.contains(key)) {
            return null;
        }
        File cached = screenshotResolvedFileCache.get(key);
        if (cached != null) {
            if (cached.isFile()) {
                return cached;
            }
            screenshotResolvedFileCache.remove(key);
            screenshotMissingCache.add(key);
            return null;
        }
        File resolved = PlayerScreenshotFiles.resolveChatScreenshotFile(mc, chatPeer, m);
        if (resolved != null && resolved.isFile()) {
            screenshotResolvedFileCache.put(key, resolved);
            return resolved;
        }
        screenshotMissingCache.add(key);
        return null;
    }

    private static String screenshotMessageKey(String chatPeer, GomodSocialChat.Message m) {
        if (m == null || !"screenshot".equalsIgnoreCase(m.kind)) {
            return "";
        }
        return (chatPeer == null ? "" : chatPeer.trim().toLowerCase(Locale.ROOT)) + "|"
                + (m.from == null ? "" : m.from.trim().toLowerCase(Locale.ROOT)) + "|"
                + m.ts + "|"
                + (m.text == null ? "" : m.text.trim().toLowerCase(Locale.ROOT));
    }

    private static int[] fitThumb(int maxW, int maxH, int imgW, int imgH) {
        if (imgW <= 0 || imgH <= 0) {
            return new int[] { maxW, SCREENSHOT_PLACEHOLDER_H };
        }
        float scale = Math.min((float) maxW / (float) imgW, (float) maxH / (float) imgH);
        scale = Math.min(scale, 1.0F);
        int w = Math.max(48, Math.round(imgW * scale));
        int h = Math.max(36, Math.round(imgH * scale));
        return new int[] { w, h };
    }

    private static ResourceLocation textureForFile(Minecraft mc, File file) {
        if (mc == null || file == null || !file.isFile()) {
            return null;
        }
        String key = file.getAbsolutePath();
        ResourceLocation cached = screenshotTextureCache.get(key);
        if (cached != null) {
            return cached;
        }
        if (screenshotTextureLoading.contains(key) || screenshotTextureFailed.contains(key)) {
            return null;
        }
        screenshotTextureLoading.add(key);
        final Minecraft client = mc;
        final File thumbFile = file;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final BufferedImage thumb = ScreenshotImageTextureUtil.loadThumbnailFromFile(
                        thumbFile, SCREENSHOT_THUMB_MAX_W, SCREENSHOT_THUMB_MAX_H);
                if (client == null) {
                    return;
                }
                client.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        screenshotTextureLoading.remove(key);
                        if (thumb == null) {
                            screenshotTextureFailed.add(key);
                            return;
                        }
                        screenshotDimCache.put(key, new int[] { thumb.getWidth(), thumb.getHeight() });
                        ResourceLocation rl = ScreenshotImageTextureUtil.registerDynamicTexture(client, thumb, "soc_chat");
                        if (rl != null) {
                            screenshotTextureCache.put(key, rl);
                        } else {
                            screenshotTextureFailed.add(key);
                        }
                    }
                });
            }
        }, "gomod-social-chat-thumb").start();
        return null;
    }

    private static void drawScreenshotBubble(FontRenderer fr, int bx, int y, int w, int h, boolean self) {
        if (GomodSocialSpeechBubbleTextures.draw(self, bx, y, w, h)) {
            return;
        }
        float rad = SocialTheme.RADIUS_MD;
        if (self) {
            SocialDraw.roundedFill(bx, y, bx + w, y + h, rad, SocialTheme.BUBBLE_SHOT);
        } else {
            SocialDraw.roundedBorder(bx, y, bx + w, y + h, rad, SocialTheme.BUBBLE_SHOT, SocialTheme.BORDER_SUBTLE);
        }
    }

    private static void drawMessageAvatar(String from, int x, int y, int size) {
        String name = from;
        if (name == null || name.isEmpty()) {
            Minecraft mc = Minecraft.getMinecraft();
            name = mc != null && mc.thePlayer != null ? mc.thePlayer.getName() : "MHF_Steve";
        }
        PlayerHeadTextureUtil.drawMinotarAvatar(name, x, y, size, 1);
    }

    private static int avatarX(boolean self, int left, int right, int avatarSize, int edgePad) {
        return self ? right - edgePad - avatarSize : left + edgePad;
    }

    private static boolean isSelf(String from) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.thePlayer != null && from != null && from.equalsIgnoreCase(mc.thePlayer.getName());
    }

    private static boolean isPublicMessage(GomodSocialChat.Message m) {
        return m != null && "public".equalsIgnoreCase(m.kind);
    }

    private static boolean isEmptyMessage(GomodSocialChat.Message m) {
        return !"screenshot".equalsIgnoreCase(m.kind) && (m.text == null || m.text.trim().isEmpty());
    }

    private static String formatName(String n) {
        return n == null ? "" : n.trim();
    }

    private static String displayLine(String line, boolean publicMsg) {
        if (!publicMsg || line == null || line.isEmpty()) {
            return line == null ? "" : line;
        }
        return GomodSocialPublicMentions.formatDisplayText(line);
    }

    private static int bubblePadX() {
        return 8;
    }

    private static int bubblePadY() {
        return 5;
    }

    private static String groupTimeLabel(MessageGroup group) {
        String first = GomodSocialChat.formatMessageTime(group.firstTimestamp);
        String last = GomodSocialChat.formatMessageTime(group.lastTimestamp);
        if (first == null || first.isEmpty() || first.equals(last)) {
            return last == null ? "" : last;
        }
        return first + " - " + last;
    }

    private static String dateLabel(long ts) {
        if (ts <= 0L) {
            return "";
        }
        return DATE_FORMAT.format(new Date(ts));
    }

    private static long dayKey(long ts) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(Math.max(0L, ts));
        return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR);
    }
}
