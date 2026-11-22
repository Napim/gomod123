package me.napim.gomod.gui;

import me.napim.gomod.Main;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.GomodFriendRelay;
import me.napim.gomod.features.GomodFriendRelay.FriendAddSource;
import me.napim.gomod.features.GomodServiceStatus;
import me.napim.gomod.features.GomodCapeShareHistory;
import me.napim.gomod.features.GomodSocialAccount;
import me.napim.gomod.features.GomodSocialChat;
import me.napim.gomod.features.GomodSocialForumRelay;
import me.napim.gomod.features.GomodSocialPresence;
import me.napim.gomod.features.GomodSocialPublicChat;
import me.napim.gomod.features.GomodSocialForums;
import me.napim.gomod.features.GomodSocialRequests;
import me.napim.gomod.features.GomodSocialServiceSync;
import me.napim.gomod.features.GomodSocialStore;
import me.napim.gomod.features.GomodSocialTempGroups;
import me.napim.gomod.features.IncomingFriendDmOverlay;
import me.napim.gomod.features.IncomingFriendRequestOverlay;
import me.napim.gomod.features.IncomingPublicPingOverlay;
import me.napim.gomod.features.IncomingCapeDesigns;
import me.napim.gomod.features.IncomingScreenshotManager;
import me.napim.gomod.features.PlayerScreenshotFiles;
import me.napim.gomod.features.PlayerCustomCapes;
import me.napim.gomod.features.ScreenshotShareService;
import me.napim.gomod.shareinv.ShareInvManager;
import me.napim.gomod.util.GomodFileDialogs;
import me.napim.gomod.util.ScreenshotImageTextureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * gomod Social UI — matches preview/social-tab-demo v2 (left dock + expandable workspace).
 */
public class GomodSocialGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {

    private static final int ID_SEND = 1;
    private static final int ID_MODAL_OK = 2;
    private static final int ID_MODAL_CANCEL = 3;

    private final GuiScreen parent;
    private final String openChatWith;

    private SocialUi.DockPanel dockPanel = SocialUi.DockPanel.FRIENDS;
    private int workspacePanel; // 0 chat 1 shots 2 capes 3 requests 4 privacy 5 forum
    private boolean workspaceOpen;
    private int openAnim; // 0..8 workspace slide
    private int screenOpenAnim; // 0..12 whole panel slide-in from left
    private boolean groupMode;
    private boolean publicChatMode;
    private String chatPeer;
    private String groupId;
    private String forumId;
    private String threadId;

    private List<String> friends = new ArrayList<String>();
    private List<String> online = new ArrayList<String>();
    private List<String> offline = new ArrayList<String>();
    private List<String> nonFriendChats = new ArrayList<String>();
    private List<String> incoming = new ArrayList<String>();
    private List<String> outgoing = new ArrayList<String>();
    /** Requests that were unseen when this screen opened, highlighted for this visit. */
    private final Set<String> newlySeenIncoming = new HashSet<String>();

    private SocialUi.GuiTextFieldStyled searchField;
    private GuiTextField messageField;
    private GuiTextField modalField;
    private GuiTextField modalMembersField;

    private GuiButton sendButton;
    private GuiButton modalOk;
    private GuiButton modalCancel;

    /** 0 none, 1 add friend, 2 create temp group, 3 thread, 4 add members, 5 remove friend, 6 delete cape history, 7 delete screenshot, 8 public player actions, 9 screenshot actions */
    private int modalKind;
    private String modalGroupAddTargetId;
    private String modalCapeDeleteId;
    private File modalScreenshotDeleteFile;
    private File modalScreenshotActionFile;
    private String modalPublicPlayer;
    private int scrollFriends;
    private int scrollGroups;
    private int scrollThreads;
    private int scrollChat;
    /** Previous chat layout, used to keep following messages only while the user is already at the bottom. */
    private String trackedChatScrollKey = "";
    private int trackedChatMessageCount = -1;
    private long trackedChatLatestTs = Long.MIN_VALUE;
    private int trackedChatMaxScroll;
    private int scrollThreadPosts;
    private int threadDeleteL;
    private int threadDeleteT;
    private int threadDeleteR;
    private int threadDeleteB;
    private int scrollRequests;
    private int scrollShots;
    private int scrollCapes;
    private int scrollPrivacy;
    private boolean capesScrollbarDragging;
    private int capesScrollbarGrabOffset;
    private boolean privacyScrollbarDragging;
    private int privacyScrollbarGrabOffset;

    private File pendingShot;
    private ResourceLocation pendingShotTex;
    private final Map<String, ResourceLocation> thumbCache = new HashMap<String, ResourceLocation>();
    private final Set<String> thumbLoading = new HashSet<String>();
    private final Set<String> thumbFailed = new HashSet<String>();
    private final List<int[]> shotHits = new ArrayList<int[]>();

    private int expandL, expandT, expandR, expandB;
    private int collapseL, collapseT, collapseW = GomodSocialBackButtonTextures.DRAW_SIZE,
            collapseH = GomodSocialBackButtonTextures.DRAW_SIZE;
    private int addLinkL, addLinkT, addLinkR, addLinkB;
    private int bodyL, bodyT, bodyR, bodyB;
    private int wsL, wsT, wsR, wsB;
    private int chatL, chatT, chatR, chatB;
    private int composerT;
    private int composerFieldL, composerFieldT, composerFieldR, composerFieldB;
    private int sendBtnL, sendBtnT, sendBtnR, sendBtnB;
    private int shareInvBtnL, shareInvBtnT, shareInvBtnR, shareInvBtnB;
    private int shotBtnL, shotBtnT, shotBtnR, shotBtnB;
    private int removeFriendBtnL, removeFriendBtnT, removeFriendBtnR, removeFriendBtnB;
    private int groupAddBtnL, groupAddBtnT, groupAddBtnR, groupAddBtnB;
    private int groupExpiryBannerT, groupExpiryBannerB;

    private final SocialPublicChatMentions.Autocomplete publicMentionAssist =
            new SocialPublicChatMentions.Autocomplete();

    private boolean openPublicOnInit;
    private int openWorkspacePanelOnInit = -1;
    /** Notification/deep-link targets are applied once; later initGui calls must preserve navigation. */
    private boolean initialViewApplied;
    /** Direct HUD/toast entries should leave Social on the first Escape press. */
    private boolean closeOnFirstEscape;
    /** Toast/HUD opens should land on the first unread message before the thread is marked read. */
    private boolean scrollToUnreadOnInit;
    private boolean socialServiceSyncStarted;
    private boolean socialServiceReady;
    private boolean socialServiceFailed;
    private String socialServiceError = "";
    private long openedAtMs;

    public GomodSocialGUI(GuiScreen parent) {
        this(parent, null, false);
    }

    public GomodSocialGUI(GuiScreen parent, String openChatWith) {
        this(parent, openChatWith, false);
    }

    private GomodSocialGUI(GuiScreen parent, String openChatWith, boolean openPublicOnInit) {
        this.parent = parent;
        this.openChatWith = openChatWith == null ? null : openChatWith.trim();
        this.openPublicOnInit = openPublicOnInit;
        screenOpenAnim = 0;
    }

    /** True when this screen is open on the public chat workspace. */
    public static boolean isViewingPublicChat() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !(mc.currentScreen instanceof GomodSocialGUI)) {
            return false;
        }
        return ((GomodSocialGUI) mc.currentScreen).publicChatMode && ((GomodSocialGUI) mc.currentScreen).workspaceOpen;
    }

    public static boolean isViewingThread(String forumId, String threadId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !(mc.currentScreen instanceof GomodSocialGUI)) {
            return false;
        }
        GomodSocialGUI gui = (GomodSocialGUI) mc.currentScreen;
        return gui.workspaceOpen && threadId != null && threadId.equals(gui.threadId)
                && (forumId == null || forumId.equals(gui.forumId));
    }

    public static boolean isViewingGroup(String groupId) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || !(mc.currentScreen instanceof GomodSocialGUI)) {
            return false;
        }
        GomodSocialGUI gui = (GomodSocialGUI) mc.currentScreen;
        return gui.workspaceOpen && gui.groupMode && groupId != null && groupId.equals(gui.groupId);
    }

    /** Opens Social on the thread with the most unread (public chat or friend DM). */
    public static void openBestUnread() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (!hasSocialIdentity(mc)) {
                    return;
                }
                IncomingFriendDmOverlay.dismiss();
                int publicUnread = GomodSocialPublicChat.getUnreadCount();
                String bestFriend = null;
                int bestFriendUnread = 0;
                for (String peer : GomodSocialStore.getDirectConversationNames()) {
                    if (peer == null || peer.trim().isEmpty()) {
                        continue;
                    }
                    int unread = GomodSocialStore.getUnreadCount(peer);
                    if (unread > bestFriendUnread) {
                        bestFriendUnread = unread;
                        bestFriend = peer;
                    }
                }
                if (publicUnread > 0 && publicUnread >= bestFriendUnread) {
                    openPublicChatView();
                } else if (bestFriend != null && bestFriendUnread > 0) {
                    openChatWith(bestFriend);
                } else {
                    displayAfterClosingCurrent(mc, new GomodSocialGUI(mainMenuParent(mc)));
                }
            }
        });
    }

    /** Opens Social on the public chat tab (client thread). */
    public static void openPublicChatView() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (!hasSocialIdentity(mc)) {
                    return;
                }
                IncomingPublicPingOverlay.dismiss();
                GomodSocialGUI gui = new GomodSocialGUI(mainMenuParent(mc), null, true);
                gui.closeOnFirstEscape = true;
                gui.scrollToUnreadOnInit = true;
                displayAfterClosingCurrent(mc, gui);
            }
        });
    }

    public static void openThreadView(final String forumId, final String threadId) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || threadId == null || threadId.trim().isEmpty()) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (!hasSocialIdentity(mc)) return;
                IncomingPublicPingOverlay.dismiss();
                GomodSocialGUI gui = new GomodSocialGUI(mainMenuParent(mc));
                gui.initialViewApplied = true;
                gui.dockPanel = SocialUi.DockPanel.THREADS;
                gui.workspaceOpen = true;
                gui.openAnim = 8;
                gui.forumId = forumId == null || forumId.isEmpty()
                        ? GomodSocialForums.DEFAULT_FORUM_ID : forumId;
                gui.threadId = threadId.trim();
                gui.closeOnFirstEscape = true;
                displayAfterClosingCurrent(mc, gui);
            }
        });
    }

    public static void openGroupView(final String groupId) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || groupId == null || groupId.trim().isEmpty()) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (!hasSocialIdentity(mc)) return;
                IncomingPublicPingOverlay.dismiss();
                GomodSocialGUI gui = new GomodSocialGUI(mainMenuParent(mc));
                gui.initialViewApplied = true;
                gui.dockPanel = SocialUi.DockPanel.GROUPS;
                gui.workspaceOpen = true;
                gui.openAnim = 8;
                gui.groupMode = true;
                gui.groupId = groupId.trim();
                gui.closeOnFirstEscape = true;
                displayAfterClosingCurrent(mc, gui);
            }
        });
    }

    /** Opens Social with a friend DM thread (client thread). */
    public static void openChatWith(final String peer) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || peer == null || peer.trim().isEmpty()) {
            return;
        }
        final String target = peer.trim();
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (!hasSocialIdentity(mc)) {
                    return;
                }
                IncomingFriendDmOverlay.dismiss();
                IncomingPublicPingOverlay.dismiss();
                GomodSocialGUI gui = new GomodSocialGUI(mainMenuParent(mc), target);
                gui.closeOnFirstEscape = true;
                gui.scrollToUnreadOnInit = true;
                displayAfterClosingCurrent(mc, gui);
            }
        });
    }

    public static void openRequestsView() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (!hasSocialIdentity(mc)) {
                    return;
                }
                GomodSocialGUI gui = new GomodSocialGUI(mainMenuParent(mc));
                gui.openWorkspacePanelOnInit = 3;
                gui.closeOnFirstEscape = true;
                gui.captureAndAcknowledgeIncomingRequests();
                displayAfterClosingCurrent(mc, gui);
            }
        });
    }

    public static void openCapesView(final String peer) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (!hasSocialIdentity(mc)) {
                    return;
                }
                GomodSocialGUI gui = new GomodSocialGUI(mainMenuParent(mc), peer);
                gui.openWorkspacePanelOnInit = 2;
                gui.closeOnFirstEscape = true;
                displayAfterClosingCurrent(mc, gui);
            }
        });
    }

    private static void displayAfterClosingCurrent(final Minecraft mc, final GuiScreen gui) {
        if (mc == null || gui == null) {
            return;
        }
        GomodGuiOpenHelper.openCleanAfterMouseRelease(mc, gui);
    }

    private static boolean hasSocialIdentity(Minecraft mc) {
        return !GomodSocialPresence.sessionPlayerName(mc).isEmpty();
    }

    private static GuiScreen mainMenuParent(Minecraft mc) {
        return mc != null && mc.currentScreen instanceof GuiMainMenu ? mc.currentScreen : null;
    }

    @Override
    public void initGui() {
        super.initGui();
        if (openedAtMs <= 0L) {
            openedAtMs = System.currentTimeMillis();
        }
        GomodSocialToggleTextures.preload(mc);
        GomodSocialBackButtonTextures.preload(mc);
        GomodSocialSearchIconTextures.preload(mc);
        GomodSocialPresenceIconTextures.preload(mc);
        GomodSocialSpeechBubbleTextures.preload(mc);
        GomodSocialCircleBubbleTextures.preload(mc);
        GomodSocialClickableButtonTextures.preload(mc);
        fontRendererObj = mc.fontRendererObj;
        ConfigManager.refreshGomodFriendDisplayNamesAsync();
        if (searchField == null) {
            searchField = new SocialUi.GuiTextFieldStyled(10, fontRendererObj, 32);
        }
        if (messageField == null) {
            messageField = new GuiTextField(11, fontRendererObj, 0, 0, 100, 18);
            messageField.setMaxStringLength(500);
            messageField.setCanLoseFocus(true);
            messageField.setEnableBackgroundDrawing(false);
            messageField.setTextColor(0xFFEDEDED);
        }
        if (modalField == null) {
            modalField = new GuiTextField(12, fontRendererObj, 0, 0, 100, 18);
            modalField.setMaxStringLength(48);
            modalField.setEnableBackgroundDrawing(false);
            modalField.setTextColor(0xFFEDEDED);
        }
        if (modalMembersField == null) {
            modalMembersField = new GuiTextField(13, fontRendererObj, 0, 0, 100, 18);
            modalMembersField.setMaxStringLength(120);
            modalMembersField.setEnableBackgroundDrawing(false);
            modalMembersField.setTextColor(0xFFEDEDED);
        }

        startSocialServiceSyncIfNeeded();
        reloadData();
        if (!initialViewApplied) {
            initialViewApplied = true;
            if (openChatWith != null && !openChatWith.isEmpty()) {
                chatPeer = resolveFriend(openChatWith);
                if (chatPeer != null) {
                    workspaceOpen = true;
                    openAnim = 8;
                    dockPanel = SocialUi.DockPanel.FRIENDS;
                    workspacePanel = openWorkspacePanelOnInit >= 0 ? openWorkspacePanelOnInit : 0;
                    groupMode = false;
                }
            } else if (openPublicOnInit) {
                openPublicOnInit = false;
                dockPanel = SocialUi.DockPanel.PUBLIC;
                openPublicChat();
            } else if (openWorkspacePanelOnInit >= 0) {
                workspaceOpen = true;
                openAnim = 8;
                dockPanel = SocialUi.DockPanel.FRIENDS;
                workspacePanel = openWorkspacePanelOnInit;
            }
        }

        ensureBackingButtons();
        layoutRects();
        applyUnreadScrollOnInit();
        syncButtons();
    }

    private void startSocialServiceSyncIfNeeded() {
        if (socialServiceSyncStarted) {
            return;
        }
        socialServiceSyncStarted = true;
        socialServiceReady = false;
        socialServiceFailed = false;
        socialServiceError = "";
        GomodSocialServiceSync.syncOnOpen(mc, new GomodSocialServiceSync.Callback() {
            @Override
            public void onResult(boolean ok, String error) {
                socialServiceReady = ok;
                socialServiceFailed = !ok;
                socialServiceError = error == null ? "" : error;
                if (ok) {
                    reloadData();
                    initialViewApplied = false;
                    initGui();
                }
            }
        });
    }

    private void applyUnreadScrollOnInit() {
        if (!scrollToUnreadOnInit || !workspaceOpen || workspacePanel != 0) {
            return;
        }
        scrollToUnreadOnInit = false;
        int msgTop = chatT + 8;
        if (groupMode && groupId != null && groupAddBtnR > groupAddBtnL) {
            msgTop = Math.max(msgTop, groupAddBtnB + 4);
        }
        if (publicChatMode) {
            long lastRead = GomodSocialPublicChat.getLastReadMs();
            if (GomodSocialPublicChat.getUnreadCount() > 0 && lastRead > 0L) {
                scrollChat = SocialChatDraw.scrollToFirstUnread(fontRendererObj, GomodSocialPublicChat.getMessages(),
                        chatL + 8, msgTop, chatR - 10, chatB - 6, true, lastRead);
            }
            return;
        }
        if (chatPeer != null && !groupMode && threadId == null) {
            long lastRead = GomodSocialStore.getLastReadMs(chatPeer);
            if (GomodSocialStore.getUnreadCount(chatPeer) > 0 && lastRead > 0L) {
                scrollChat = SocialChatDraw.scrollToFirstUnread(fontRendererObj, GomodSocialStore.getThread(chatPeer),
                        chatL + 8, msgTop, chatR - 10, chatB - 6, false, lastRead);
            }
        }
    }

    private void reloadData() {
        GomodSocialAccount.refresh(mc);
        GomodSocialChat.init();
        if (socialServiceSyncStarted && !socialServiceReady) {
            friends = new ArrayList<String>();
            online = new ArrayList<String>();
            offline = new ArrayList<String>();
            nonFriendChats = new ArrayList<String>();
            incoming = new ArrayList<String>();
            outgoing = new ArrayList<String>();
            return;
        }
        friends = new ArrayList<String>(ConfigManager.getGomodFriends());
        Collections.sort(friends, String.CASE_INSENSITIVE_ORDER);
        nonFriendChats = new ArrayList<String>();
        for (String peer : GomodSocialStore.getDirectConversationNames()) {
            if (peer != null && !peer.trim().isEmpty() && !ConfigManager.isGomodFriend(peer)
                    && !isSelfName(peer)) {
                nonFriendChats.add(peer);
            }
        }
        filterFriends();
        incoming = mergeIncoming();
        outgoing = GomodSocialRequests.sortedOutgoingForDisplay();
    }

    private void captureAndAcknowledgeIncomingRequests() {
        for (String name : GomodSocialRequests.unseenIncoming()) {
            if (name != null) {
                newlySeenIncoming.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        GomodSocialRequests.markAllIncomingSeen();
        IncomingFriendRequestOverlay.dismiss();
    }

    private List<String> mergeIncoming() {
        List<String> list = GomodSocialRequests.sortedIncoming();
        String live = IncomingFriendRequestOverlay.peekActiveIncomingFrom();
        if (live != null) {
            boolean found = false;
            for (String s : list) {
                if (s != null && s.equalsIgnoreCase(live)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                list = new ArrayList<String>(list);
                list.add(0, live);
            }
        }
        return list;
    }

    private void filterFriends() {
        String q = searchField.field.getText().trim().toLowerCase();
        online.clear();
        offline.clear();
        for (String f : friends) {
            if (f == null || isSelfName(f)) {
                continue;
            }
            if (!q.isEmpty() && !f.toLowerCase().contains(q)) {
                continue;
            }
            if (SocialUi.presenceKind(f, mc) == 1 || SocialUi.presenceKind(f, mc) == 2) {
                online.add(f);
            } else {
                offline.add(f);
            }
        }
        if (!q.isEmpty()) {
            java.util.Iterator<String> it = nonFriendChats.iterator();
            while (it.hasNext()) {
                String peer = it.next();
                if (peer == null || !peer.toLowerCase().contains(q)) {
                    it.remove();
                }
            }
        }
    }

    private boolean isSelfName(String name) {
        String self = GomodSocialPresence.sessionPlayerName(mc);
        return name != null && !self.isEmpty() && name.equalsIgnoreCase(self);
    }

    private String socialSelfLabel() {
        String self = GomodSocialPresence.sessionPlayerName(mc);
        return self.isEmpty() ? "You" : self;
    }

    private String resolveFriend(String name) {
        if (name == null || name.isEmpty() || isSelfName(name)) {
            return null;
        }
        if (ConfigManager.isGomodFriend(name)) {
            return ConfigManager.getGomodFriendDisplayName(name);
        }
        for (String f : friends) {
            if (f != null && f.equalsIgnoreCase(name)) {
                return f;
            }
        }
        if (!GomodSocialStore.getThread(name).isEmpty()) {
            return name.trim();
        }
        return null;
    }

    private void layoutRects() {
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        int sh = sr.getScaledHeight();

        int dockR = SocialTheme.DOCK_W;
        bodyL = 0;
        bodyT = 0;
        bodyR = dockR;
        bodyB = sh;

        int listBot = sh - SocialTheme.DOCK_FOOTER_H;
        expandL = 12;
        // Keep the CTA clear of Lunar's version label at the bottom of the title screen.
        final int openButtonRaise = 5;
        expandT = sh - SocialTheme.DOCK_FOOTER_H + 12 - openButtonRaise;
        expandR = dockR - 12;
        expandB = sh - 14 - openButtonRaise;
        addLinkL = 12;
        addLinkT = listBot - 30;
        addLinkR = dockR - 12;
        addLinkB = listBot - 8;

        if (workspaceOpen) {
            float t = openAnim / 8.0F;
            int slide = (int) ((1.0F - t) * 18);
            wsL = dockR + slide;
            wsT = 0;
            int availableW = Math.max(220, sw - wsL - 8);
            int workspaceW = Math.min(SocialTheme.WORKSPACE_MAX_W, availableW);
            if (workspaceW < SocialTheme.WORKSPACE_MIN_W) {
                workspaceW = availableW;
            }
            wsR = Math.min(sw, wsL + workspaceW);
            wsB = sh;
            collapseL = wsL + 10;
            collapseT = wsT + (SocialTheme.WORKSPACE_BAR_H - collapseH) / 2;
            int tabsH = groupMode || publicChatMode || threadId != null ? 0 : SocialTheme.WORKSPACE_TABS_H;
            int barH = SocialTheme.WORKSPACE_BAR_H;
            chatL = wsL + 12;
            chatT = wsT + barH + tabsH + 10;
            chatR = wsR - 12;
            boolean dmActions = workspacePanel == 0 && chatPeer != null && !groupMode && !publicChatMode
                    && threadId == null;
            boolean chatComposerVisible = workspacePanel == 0 || threadId != null;
            int composerBottomPad = 8;
            int actionBlockBelow = dmActions ? SocialTheme.CHAT_ACTION_H + 4 : 0;
            composerT = wsB - SocialTheme.COMPOSER_H - actionBlockBelow - composerBottomPad;
            chatB = chatComposerVisible ? composerT - 8 : wsB - 8;
            composerFieldL = chatL;
            composerFieldT = composerT + (SocialTheme.COMPOSER_H - SocialTheme.COMPOSER_FIELD_H) / 2;
            composerFieldB = composerFieldT + SocialTheme.COMPOSER_FIELD_H;
            int sendH = composerFieldB - composerFieldT;
            int sendW = GomodSocialClickableButtonTextures.widthForHeightAndLabel(fontRendererObj, sendH,
                    EnumChatFormatting.BOLD + "Send");
            sendBtnR = chatR;
            sendBtnL = sendBtnR - sendW;
            sendBtnT = composerFieldT;
            sendBtnB = composerFieldB;
            composerFieldR = sendBtnL - SocialTheme.COMPOSER_SEND_GAP;
            if (groupMode && groupId != null) {
                groupExpiryBannerT = chatT;
                groupExpiryBannerB = groupExpiryBannerT + SocialTheme.EXPIRY_BANNER_H;
                chatT = groupExpiryBannerB + 8;
                if (workspacePanel == 0) {
                    int pillW = fontRendererObj.getStringWidth("+ Add members") + 20;
                    groupAddBtnR = chatR - 8;
                    groupAddBtnL = groupAddBtnR - pillW;
                    groupAddBtnT = chatT;
                    groupAddBtnB = groupAddBtnT + 20;
                    chatT = groupAddBtnB + 10;
                } else {
                    groupAddBtnL = groupAddBtnT = groupAddBtnR = groupAddBtnB = 0;
                }
            } else {
                groupAddBtnL = groupAddBtnT = groupAddBtnR = groupAddBtnB = 0;
                groupExpiryBannerT = groupExpiryBannerB = 0;
            }
            if (dmActions) {
                int actT = composerT + SocialTheme.COMPOSER_H + 4;
                int actB = wsB - composerBottomPad;
                int gap = 8;
                int actH = actB - actT;
                int shareW = fontRendererObj.getStringWidth("ShareInv") + 18;
                int shotW = fontRendererObj.getStringWidth("Screenshot") + 18;
                int removeW = fontRendererObj.getStringWidth("Remove friend") + 18;
                int room=Math.max(3,chatR-chatL-16-gap*2),desired=shareW+shotW+removeW;
                if(desired>room){shareW=shareW*room/desired;shotW=shotW*room/desired;removeW=room-shareW-shotW;}
                shareInvBtnL = chatL + 8;
                shareInvBtnT = actT;
                shareInvBtnR = shareInvBtnL + shareW;
                shareInvBtnB = actB;
                shotBtnL = shareInvBtnR + gap;
                shotBtnT = actT;
                shotBtnR = shotBtnL + shotW;
                shotBtnB = actB;
                removeFriendBtnL = shotBtnR + gap;
                removeFriendBtnT = actT;
                removeFriendBtnR = removeFriendBtnL + removeW;
                removeFriendBtnB = actB;
            } else {
                removeFriendBtnL = removeFriendBtnT = removeFriendBtnR = removeFriendBtnB = 0;
            }
        } else {
            sendBtnL = sendBtnT = sendBtnR = sendBtnB = 0;
        }
    }

    private int dockTabsTop() {
        int bannerExtra = GomodServiceStatus.isServiceConnected() ? 0 : SocialTheme.SERVICE_BANNER_H + 4;
        return SocialTheme.DOCK_TOP_H + bannerExtra;
    }

    private void syncButtons() {
        // We render and route Social buttons ourselves. Keep lightweight backing
        // GuiButtons only as action ids; do not let vanilla/global button hooks
        // draw hover chrome over the custom textured controls.
        ensureBackingButtons();
        if (sendButton != null) {
            sendButton.visible = false;
        }
        if (modalOk != null) {
            modalOk.visible = false;
        }
        if (modalCancel != null) {
            modalCancel.visible = false;
        }
    }

    private void ensureBackingButtons() {
        if (sendButton == null) {
            sendButton = new GuiButton(ID_SEND, 0, 0, 52, 20, EnumChatFormatting.BLACK + "Send");
        }
        if (modalOk == null) {
            modalOk = new GuiButton(ID_MODAL_OK, 0, 0, 64, 18, EnumChatFormatting.GREEN + "OK");
        }
        if (modalCancel == null) {
            modalCancel = new GuiButton(ID_MODAL_CANCEL, 0, 0, 56, 18, EnumChatFormatting.GRAY + "Cancel");
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        reloadData();
        if (workspaceOpen && openAnim < 8) {
            openAnim++;
        } else if (!workspaceOpen && openAnim > 0) {
            openAnim--;
        }
        layoutRects();
        syncButtons();

        // GuiScreen replaces the previous menu while Social is open. Paint that parent first so
        // opening Social from Multiplayer/server-list screens does not turn the whole menu into a
        // flat grey background. The menu-specific dim keeps the underlying controls readable.
        if (parent != null) {
            try {
                parent.drawScreen(mouseX, mouseY, partialTicks);
            } catch (Throwable ignored) {
                // A launcher-provided title screen may not be safe to repaint after replacement.
            }
        }
        SocialUi.drawBackdrop(width, height,
                parent == null ? SocialTheme.BACKDROP : SocialTheme.MENU_BACKDROP);

        if (screenOpenAnim < 12) {
            screenOpenAnim++;
        }
        float openT = screenOpenAnim / 12.0F;
        float eased = 1.0F - (1.0F - openT) * (1.0F - openT);
        int slideX = (int) ((1.0F - eased) * -SocialTheme.DOCK_W * 1.15F);

        GlStateManager.pushMatrix();
        GlStateManager.translate(slideX, 0.0F, 0.0F);
        drawDock(mouseX - slideX, mouseY);
        if (workspaceOpen && openAnim > 0) {
            drawWorkspace(mouseX - slideX, mouseY);
        }
        GlStateManager.popMatrix();

        if (modalKind != 0) {
            drawModal(mouseX, mouseY);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawDock(int mx, int my) {
        SocialUi.drawDockGradient(bodyL, bodyT, bodyR, bodyB);
        SocialUi.drawBrandHead(mc, 14, 14);
        SocialUi.drawBrandTitle(fontRendererObj, mc, 44, 14);

        int bannerT = SocialTheme.DOCK_TOP_H + 2;
        SocialUi.drawServiceDisconnectedBanner(fontRendererObj, 10, bannerT, SocialTheme.DOCK_W - 10);
        int bannerExtra = GomodServiceStatus.isServiceConnected() ? 0 : SocialTheme.SERVICE_BANNER_H + 4;

        int tabsT = dockTabsTop();
        boolean hidePublicBadge = publicChatMode && workspaceOpen;
        SocialUi.drawDockTabs(fontRendererObj, 12, tabsT, SocialTheme.DOCK_W - 24, dockPanel, hidePublicBadge);
        int listTop;
        if (dockPanel == SocialUi.DockPanel.PUBLIC) {
            listTop = tabsT + SocialTheme.DOCK_TABS_H + 6;
        } else {
            int searchT = tabsT + SocialTheme.DOCK_TABS_H - 4;
            SocialUi.drawSearchBox(fontRendererObj, searchField, 12, searchT, SocialTheme.DOCK_W - 24);
            listTop = searchT + SocialTheme.DOCK_SEARCH_H + 4;
        }
        int listH = height - listTop - SocialTheme.DOCK_FOOTER_H - 40;
        int l = 12;
        int r = SocialTheme.DOCK_W - 12;

        if (dockPanel == SocialUi.DockPanel.FRIENDS) {
            drawFriendList(mx, my, l, listTop, r, listH);
            boolean hoverLink = SocialUi.inRect(mx, my, addLinkL, addLinkT, addLinkR - addLinkL, addLinkB - addLinkT);
            SocialUi.drawDashedLink(fontRendererObj, addLinkL, addLinkT, addLinkR, addLinkB,
                    "+ Add friend", hoverLink);
        } else if (dockPanel == SocialUi.DockPanel.GROUPS) {
            SocialUi.drawGroupHint(fontRendererObj, l, listTop, r);
            scrollGroups = drawGroupList(mx, my, l, listTop + SocialTheme.GROUP_HINT_H + 6, r, listH - SocialTheme.GROUP_HINT_H - 12);
            boolean hover = SocialUi.inRect(mx, my, addLinkL, addLinkT, addLinkR - addLinkL, addLinkB - addLinkT);
            SocialUi.drawDashedLink(fontRendererObj, addLinkL, addLinkT, addLinkR, addLinkB,
                    "+ Create temp group", hover);
        } else if (dockPanel == SocialUi.DockPanel.PUBLIC) {
            SocialUi.drawPublicDockInfo(fontRendererObj, l, listTop, r, listH - 8);
        } else {
            scrollThreads = drawThreadDockList(mx, my, l, listTop, r, listH);
            boolean hover = SocialUi.inRect(mx, my, addLinkL, addLinkT, addLinkR - addLinkL, addLinkB - addLinkT);
            SocialUi.drawDashedLink(fontRendererObj, addLinkL, addLinkT, addLinkR, addLinkB,
                    "+ Create thread", hover);
        }

        // Stop before the dock's right-edge separator so the horizontal divider cannot paint over it.
        SocialDraw.fillRect(0, height - SocialTheme.DOCK_FOOTER_H, SocialTheme.DOCK_W - 1,
                height - SocialTheme.DOCK_FOOTER_H + 1,
                SocialTheme.BORDER_SUBTLE);

        if (!workspaceOpen) {
            boolean hov = SocialUi.inRect(mx, my, expandL, expandT, expandR - expandL, expandB - expandT);
            SocialUi.drawExpandButton(fontRendererObj, expandL, expandT, expandR, expandB, hov);
        }
    }

    private void drawFriendList(int mx, int my, int l, int top, int r, int h) {
        if (h <= 0) {
            return;
        }
        SocialDraw.enableScissor(l, top, r, top + h);
        try {
            int maxScroll = drawFriendListContents(mx, my, l, top, r, h);
            drawFriendListScrollFades(l, top, r, top + h, maxScroll);
        } finally {
            SocialDraw.disableScissor();
        }
    }

    private int drawFriendListContents(int mx, int my, int l, int top, int r, int h) {
        int rowH = SocialTheme.DOCK_ROW_H;
        int labelH = 14;
        int gap = 6;
        int conversationH = nonFriendChats.isEmpty()
                ? 0 : gap + labelH + nonFriendChats.size() * rowH;
        int total = labelH + online.size() * rowH + gap + labelH + offline.size() * rowH
                + conversationH;
        int maxScroll = Math.max(0, total - h);
        scrollFriends = SocialUi.clamp(scrollFriends, 0, maxScroll);
        int y = top - scrollFriends;
        SocialUi.drawSectionLabel(fontRendererObj, "Online \u2014 " + online.size(), l + 2, y);
        y += labelH;
        for (String f : online) {
            if (y + rowH >= top && y <= top + h) {
                boolean sel = f.equals(chatPeer) && !groupMode && !publicChatMode && forumId == null;
                boolean hov = mx >= l && mx < r && my >= y && my < y + rowH;
                boolean away = SocialUi.presenceKind(f, mc) == 2;
                SocialUi.drawFriendRow(fontRendererObj, mc, f, l, y, r, sel, hov, away,
                        GomodSocialStore.getUnreadCount(f), mx, my);
            }
            y += rowH;
        }
        y += gap;
        SocialUi.drawSectionLabel(fontRendererObj, "Offline \u2014 " + offline.size(), l + 2, y);
        y += labelH;
        for (String f : offline) {
            if (y + rowH >= top && y <= top + h) {
                boolean sel = f.equals(chatPeer) && !groupMode && !publicChatMode && forumId == null;
                boolean hov = mx >= l && mx < r && my >= y && my < y + rowH;
                SocialUi.drawFriendRow(fontRendererObj, mc, f, l, y, r, sel, hov, false,
                        GomodSocialStore.getUnreadCount(f), mx, my);
            }
            y += rowH;
        }
        if (!nonFriendChats.isEmpty()) {
            y += gap;
            SocialUi.drawSectionLabel(fontRendererObj, "Messages \u2014 " + nonFriendChats.size(), l + 2, y);
            y += labelH;
            for (String peer : nonFriendChats) {
                if (y + rowH >= top && y <= top + h) {
                    boolean sel = peer.equalsIgnoreCase(chatPeer) && !groupMode && !publicChatMode
                            && forumId == null;
                    boolean hov = mx >= l && mx < r && my >= y && my < y + rowH;
                    SocialUi.drawFriendRow(fontRendererObj, mc, peer, l, y, r, sel, hov, false,
                            GomodSocialStore.getUnreadCount(peer), mx, my);
                }
                y += rowH;
            }
        }
        return maxScroll;
    }

    /** Fade the full clipped row width into the dock surface as content scrolls past either edge. */
    private void drawFriendListScrollFades(int l, int top, int r, int bottom, int maxScroll) {
        int viewportHeight = bottom - top;
        if (maxScroll <= 0 || viewportHeight <= 1) {
            return;
        }
        int fadeH = Math.min(38, Math.max(8, viewportHeight / 5));
        fadeH = Math.min(fadeH, viewportHeight / 2);
        int clearDock = SocialDraw.withAlpha(SocialTheme.DOCK, 0);
        if (scrollFriends > 0) {
            SocialDraw.verticalGradient(l, top, r, top + fadeH,
                    SocialTheme.DOCK, clearDock);
        }
        if (scrollFriends < maxScroll) {
            SocialDraw.verticalGradient(l, bottom - fadeH, r, bottom,
                    clearDock, SocialTheme.DOCK);
        }
    }

    private int drawGroupList(int mx, int my, int l, int top, int r, int h) {
        List<GomodSocialTempGroups.Group> groups = GomodSocialTempGroups.sortedGroups();
        int rowH = SocialTheme.DOCK_ROW_H;
        int virtual = groups.size() * rowH;
        int maxScroll = Math.max(0, virtual - h);
        scrollGroups = SocialUi.clamp(scrollGroups, 0, maxScroll);
        int y = top - scrollGroups;
        for (GomodSocialTempGroups.Group g : groups) {
            if (y + rowH >= top && y <= top + h) {
                boolean sel = g.id.equals(groupId);
                boolean hov = mx >= l && mx < r && my >= y && my < y + rowH;
                SocialUi.drawGroupRow(fontRendererObj, mc, g, l, y, r, sel, hov, mx, my);
            }
            y += rowH;
        }
        return scrollGroups;
    }

    private int drawThreadDockList(int mx, int my, int l, int top, int r, int h) {
        List<GomodSocialForums.ThreadEntry> threads = GomodSocialForums.sortedThreadEntries();
        int virtual = threads.size() * SocialTheme.FORUM_ROW_H;
        int maxScroll = Math.max(0, virtual - h);
        scrollThreads = SocialUi.clamp(scrollThreads, 0, maxScroll);
        int y = top - scrollThreads;
        for (GomodSocialForums.ThreadEntry entry : threads) {
            if (y + SocialTheme.FORUM_ROW_H >= top && y <= top + h) {
                boolean sel = entry.thread != null && entry.thread.id.equals(threadId)
                        && entry.forumId.equals(forumId);
                boolean hov = mx >= l && mx < r && my >= y && my < y + SocialTheme.FORUM_ROW_H;
                SocialUi.drawThreadDockRow(fontRendererObj, entry, l, y, r, sel, hov);
            }
            y += SocialTheme.FORUM_ROW_H;
        }
        return scrollThreads;
    }

    private void drawWorkspace(int mx, int my) {
        SocialUi.drawWorkspaceGradient(wsL, wsT, wsR, wsB);
        String title = workspaceTitle();
        String tag = workspaceTag();
        SocialUi.drawWorkspaceBar(fontRendererObj, wsL, wsT, wsR, wsT + SocialTheme.WORKSPACE_BAR_H, title, tag);

        boolean hovCollapse = GomodSocialBackButtonTextures.hitTest(mx, my, collapseL, collapseT);
        if (!GomodSocialBackButtonTextures.draw(collapseL, collapseT, hovCollapse)) {
            SocialUi.drawIconButton(fontRendererObj, collapseL, collapseT, collapseL + collapseW, collapseT + collapseH,
                    "\u2039", hovCollapse);
        }

        if (threadId != null && forumId != null) {
            drawThreadWorkspace(mx, my);
            return;
        }
        if (!socialServiceReady) {
            drawSocialServiceGate();
            return;
        }
        if (!groupMode && !publicChatMode) {
            SocialUi.drawWorkspaceTabs(fontRendererObj, wsL, wsT + SocialTheme.WORKSPACE_BAR_H, wsR - wsL,
                    workspacePanel, GomodSocialRequests.pendingBadgeCount());
        }

        if (workspacePanel == 0) {
            drawChatPanel(mx, my);
        } else if (workspacePanel == 1) {
            drawScreenshots(mx, my);
        } else if (workspacePanel == 2) {
            drawCapes(mx, my);
        } else if (workspacePanel == 3) {
            drawRequests(mx, my);
        } else if (workspacePanel == 4) {
            drawPrivacy(mx, my);
        }
    }

    private void drawSocialServiceGate() {
        SocialUi.drawCard(chatL, chatT, chatR, chatB);
        String title = socialServiceFailed
                ? EnumChatFormatting.RED + "gomod Service unavailable"
                : EnumChatFormatting.YELLOW + "Loading gomod Social...";
        int cx = (chatL + chatR) / 2;
        int cy = (chatT + chatB) / 2;
        fontRendererObj.drawString(title, cx - fontRendererObj.getStringWidth(title) / 2, cy - 12,
                SocialTheme.TEXT_BRIGHT);
        if (!socialServiceFailed) {
            String sub = EnumChatFormatting.GRAY + "Checking the relay for your friends and messages.";
            fontRendererObj.drawString(sub, cx - fontRendererObj.getStringWidth(sub) / 2, cy + 4,
                    SocialTheme.TEXT_MUTED);
        }
        if (socialServiceFailed && socialServiceError != null && !socialServiceError.trim().isEmpty()) {
            String err = EnumChatFormatting.DARK_GRAY + socialServiceError.trim();
            int max = Math.max(60, chatR - chatL - 50);
            while (err.length() > 4 && fontRendererObj.getStringWidth(err) > max) {
                err = err.substring(0, err.length() - 4) + "...";
            }
            fontRendererObj.drawString(err, cx - fontRendererObj.getStringWidth(err) / 2, cy + 4,
                    SocialTheme.TEXT_DIM);
        }
    }

    private String workspaceTitle() {
        if (publicChatMode) {
            return "Public chat";
        }
        if (groupMode && groupId != null) {
            GomodSocialTempGroups.Group g = GomodSocialTempGroups.get(groupId);
            return g == null ? "Group" : g.name;
        }
        if (threadId != null) {
            GomodSocialForums.ThreadEntry e = GomodSocialForums.findThread(forumId, threadId);
            return e == null || e.thread == null ? "Thread" : e.thread.title;
        }
        return chatPeer == null ? "Social" : chatPeer;
    }

    private String workspaceTag() {
        if (publicChatMode) {
            return null;
        }
        if (groupMode) {
            return "TEMP GROUP";
        }
        if (threadId != null) {
            return "THREAD";
        }
        return null;
    }

    private void drawChatPanel(int mx, int my) {
        SocialUi.drawCard(chatL, chatT, chatR, chatB);
        if (groupMode && groupId != null) {
            GomodSocialTempGroups.Group g = GomodSocialTempGroups.get(groupId);
            if (g != null && groupExpiryBannerB > groupExpiryBannerT) {
                SocialUi.drawExpiryBanner(fontRendererObj, chatL, groupExpiryBannerT, chatR, g);
            }
        }

        List<GomodSocialChat.Message> thread;
        boolean showNames;
        long publicNewSinceMs = 0L;
        if (publicChatMode) {
            thread = GomodSocialPublicChat.getMessages();
            showNames = true;
            publicNewSinceMs = GomodSocialPublicChat.getLastReadMs();
        } else if (groupMode && groupId != null) {
            GomodSocialTempGroups.Group g = GomodSocialTempGroups.get(groupId);
            thread = g == null ? Collections.<GomodSocialChat.Message>emptyList() : g.messages;
            showNames = true;
        } else if (chatPeer != null) {
            GomodSocialStore.markRead(chatPeer, System.currentTimeMillis());
            thread = GomodSocialStore.getThread(chatPeer);
            showNames = false;
        } else {
            String hint = EnumChatFormatting.GRAY + "Pick a friend, group, or Public tab to start chatting";
            int tw = fontRendererObj.getStringWidth(hint);
            fontRendererObj.drawString(hint, chatL + (chatR - chatL - tw) / 2, (chatT + chatB) / 2 - 4, SocialTheme.TEXT_MUTED);
            drawComposer(mx, my);
            return;
        }

        int msgTop = chatT + 8;
        if (groupMode && groupId != null && groupAddBtnR > groupAddBtnL) {
            msgTop = Math.max(msgTop, groupAddBtnB + 4);
        }
        String dmPeer = (!groupMode && !publicChatMode && threadId == null) ? chatPeer : null;
        if(!Mouse.isButtonDown(0))LunarFitChatPreview.releaseDrag();
        updateChatBottomFollow(thread, showNames, publicNewSinceMs, dmPeer, msgTop);
        scrollChat = SocialChatDraw.drawThread(fontRendererObj, mc, thread, chatL + 8, msgTop, chatR - 10, chatB - 6,
                scrollChat, showNames, publicNewSinceMs, dmPeer);
        if (groupMode && groupId != null && groupAddBtnR > groupAddBtnL) {
            boolean hovAdd = SocialUi.inRect(mx, my, groupAddBtnL, groupAddBtnT,
                    groupAddBtnR - groupAddBtnL, groupAddBtnB - groupAddBtnT);
            SocialUi.drawActionPill(fontRendererObj, groupAddBtnL, groupAddBtnT, groupAddBtnR, groupAddBtnB,
                    EnumChatFormatting.GREEN + "+ Add members", SocialTheme.ACCENT_BRIGHT, hovAdd);
        }
        drawComposer(mx, my);
        if (chatPeer != null && !groupMode && !publicChatMode) {
            drawChatActions(mx, my);
        }
    }

    private void updateChatBottomFollow(List<GomodSocialChat.Message> thread, boolean showNames,
            long newMessagesSinceMs, String dmPeer, int msgTop) {
        String key;
        if (publicChatMode) {
            key = "public";
        } else if (groupMode) {
            key = "group:" + (groupId == null ? "" : groupId.toLowerCase(Locale.ROOT));
        } else {
            key = "dm:" + (chatPeer == null ? "" : chatPeer.toLowerCase(Locale.ROOT));
        }

        int count = thread == null ? 0 : thread.size();
        long latestTs = 0L;
        if (thread != null) {
            for (GomodSocialChat.Message message : thread) {
                if (message != null && message.ts > latestTs) {
                    latestTs = message.ts;
                }
            }
        }

        int left = chatL + 8;
        int right = chatR - 10;
        int bottom = chatB - 6;
        int bubbleMaxW = Math.max(96, (right - left) * 68 / 100);
        int contentHeight = SocialChatDraw.measureThreadHeight(fontRendererObj, thread, bubbleMaxW,
                showNames, newMessagesSinceMs, dmPeer);
        int maxScroll = Math.max(0, contentHeight - (bottom - msgTop));

        if (key.equals(trackedChatScrollKey)) {
            boolean newMessage = count > trackedChatMessageCount || latestTs > trackedChatLatestTs;
            boolean wasAtBottom = scrollChat >= Math.max(0, trackedChatMaxScroll - 2);
            if (newMessage && wasAtBottom) {
                scrollChat = maxScroll;
            }
        }

        trackedChatScrollKey = key;
        trackedChatMessageCount = count;
        trackedChatLatestTs = latestTs;
        trackedChatMaxScroll = maxScroll;
    }

    private void drawChatActions(int mx, int my) {
        boolean hovSi = SocialUi.inRect(mx, my, shareInvBtnL, shareInvBtnT, shareInvBtnR - shareInvBtnL, shareInvBtnB - shareInvBtnT);
        boolean hovSh = SocialUi.inRect(mx, my, shotBtnL, shotBtnT, shotBtnR - shotBtnL, shotBtnB - shotBtnT);
        boolean hovRm = SocialUi.inRect(mx, my, removeFriendBtnL, removeFriendBtnT,
                removeFriendBtnR - removeFriendBtnL, removeFriendBtnB - removeFriendBtnT);
        SocialUi.drawCompactChatAction(fontRendererObj, shareInvBtnL, shareInvBtnT, shareInvBtnR, shareInvBtnB,
                "ShareInv", SocialTheme.TEXT_BRIGHT, hovSi,
                GomodSocialClickableButtonTextures.Style.SHARE_INV);
        SocialUi.drawCompactChatAction(fontRendererObj, shotBtnL, shotBtnT, shotBtnR, shotBtnB,
                "Screenshot", SocialTheme.TEXT_BRIGHT, hovSh,
                GomodSocialClickableButtonTextures.Style.SCREENSHOT);
        SocialUi.drawCompactChatAction(fontRendererObj, removeFriendBtnL, removeFriendBtnT, removeFriendBtnR,
                removeFriendBtnB, "Remove friend", SocialTheme.TEXT_BRIGHT, hovRm,
                GomodSocialClickableButtonTextures.Style.REMOVE_FRIEND);
        if(hovSi||hovSh||hovRm)drawHoveringText(java.util.Collections.singletonList(hovSi?"Share inventory":hovSh?"Send screenshot":"Remove friend"),mx,my);
    }

    private void drawThreadWorkspace(int mx, int my) {
        GomodSocialForums.ThreadEntry entry = GomodSocialForums.findThread(forumId, threadId);
        SocialUi.drawCard(chatL, chatT, chatR, chatB);
        if (entry == null || entry.thread == null) {
            fontRendererObj.drawString(EnumChatFormatting.GRAY + "Thread not found", chatL + 16, (chatT + chatB) / 2, SocialTheme.TEXT_MUTED);
            drawComposer(mx, my);
            threadDeleteL = threadDeleteT = threadDeleteR = threadDeleteB = 0;
            return;
        }
        if (GomodServiceStatus.isSocialAdmin()) {
            int delH = 20;
            int delW = GomodSocialClickableButtonTextures.widthForHeightAndLabel(
                    fontRendererObj, delH, "Delete");
            threadDeleteR = chatR - 10;
            threadDeleteL = threadDeleteR - delW;
            threadDeleteT = chatT + 8;
            threadDeleteB = threadDeleteT + delH;
            boolean hovDel = SocialUi.inRect(mx, my, threadDeleteL, threadDeleteT,
                    threadDeleteR - threadDeleteL, threadDeleteB - threadDeleteT);
            SocialUi.drawClickableActionButton(fontRendererObj, threadDeleteL, threadDeleteT, threadDeleteR,
                    threadDeleteB, "Delete", SocialTheme.TEXT_BRIGHT, hovDel,
                    GomodSocialClickableButtonTextures.Style.REMOVE_FRIEND);
        } else {
            threadDeleteL = threadDeleteT = threadDeleteR = threadDeleteB = 0;
        }
        int postsTop = chatT + (GomodServiceStatus.isSocialAdmin() ? 30 : 8);
        scrollThreadPosts = SocialThreadDraw.drawPosts(fontRendererObj, mc, entry.thread.posts,
                chatL + 4, postsTop, chatR - 8, chatB - 4, scrollThreadPosts);
        drawComposer(mx, my);
    }

    private void drawComposer(int mx, int my) {
        int l = chatL;
        int r = chatR;
        int t = composerT;
        SocialDraw.fillRect(l, t - 1, r, t, SocialTheme.BORDER_SUBTLE);
        SocialUi.drawInsetField(composerFieldL, composerFieldT, composerFieldR, composerFieldB);
        SocialUi.layoutTextField(messageField, composerFieldL + 8, composerFieldT + 2,
                composerFieldR - 4, composerFieldB - 2);
        boolean hovSend = sendBtnR > sendBtnL
                && SocialUi.inRect(mx, my, sendBtnL, sendBtnT, sendBtnR - sendBtnL, sendBtnB - sendBtnT);
        SocialUi.drawClickableActionButton(fontRendererObj, sendBtnL, sendBtnT, sendBtnR, sendBtnB,
                EnumChatFormatting.BOLD + "Send",
                SocialTheme.TEXT_BRIGHT, hovSend, GomodSocialClickableButtonTextures.Style.SEND);
        boolean canType = composerCanType();
        if (canType && publicChatMode && messageField.isFocused()) {
            publicMentionAssist.refresh(messageField);
        } else if (!publicChatMode || !messageField.isFocused()) {
            publicMentionAssist.clear();
        }
        if (canType && publicChatMode && publicMentionAssist.isOpen()) {
            publicMentionAssist.draw(fontRendererObj, composerFieldL, composerFieldT, composerFieldR, mx, my);
        }
        if (canType) {
            messageField.drawTextBox();
        }
        if (canType && messageField.getText().isEmpty() && !messageField.isFocused()) {
            String ph = threadId != null ? "Reply to thread\u2026"
                    : (publicChatMode ? "Message everyone on channel\u2026" : "Write a message\u2026");
            fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + ph,
                    messageField.xPosition, messageField.yPosition, SocialTheme.TEXT_DIM);
        }
    }

    private void drawScreenshots(int mx, int my) {
        SocialUi.drawCard(chatL, chatT, chatR, chatB);
        if (chatPeer == null) {
            String hint = EnumChatFormatting.GRAY + "Pick a friend to see their shared screenshots";
            int tw = fontRendererObj.getStringWidth(hint);
            fontRendererObj.drawString(hint, chatL + (chatR - chatL - tw) / 2, (chatT + chatB) / 2 - 4, SocialTheme.TEXT_MUTED);
            return;
        }
        List<File> shots = PlayerScreenshotFiles.listSharedForFriend(mc, chatPeer, 60);
        shotHits.clear();
        int pad = 12;
        int gap = 8;
        int cols = 3;
        int cellW = (chatR - chatL - pad * 2 - gap * (cols - 1)) / cols;
        int thumbH = Math.max(28, cellW * 9 / 16);
        int cellH = thumbH + 22;
        int y0 = chatT + 12 - scrollShots;
        if (shots.isEmpty()) {
            String hint = EnumChatFormatting.GRAY + "No shared screenshots from " + chatPeer + " yet";
            int tw = fontRendererObj.getStringWidth(hint);
            fontRendererObj.drawString(hint, chatL + (chatR - chatL - tw) / 2, chatT + 28, SocialTheme.TEXT_MUTED);
            return;
        }
        for (int i = 0; i < shots.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = chatL + pad + col * (cellW + gap);
            int cy = y0 + row * (cellH + gap);
            if (cy + cellH < chatT || cy > chatB) {
                continue;
            }
            boolean hov = mx >= cx && mx < cx + cellW && my >= cy && my < cy + cellH;
            int cellFill = hov ? SocialTheme.CARD_HOVER : SocialTheme.CARD;
            int cellBorder = hov ? SocialTheme.ACCENT_SOFT : SocialTheme.BORDER_SUBTLE;
            SocialDraw.roundedBorder(cx, cy, cx + cellW, cy + cellH, SocialTheme.RADIUS_MD, cellFill, cellBorder);
            drawThumb(cx + 4, cy + 4, cellW - 8, thumbH, shots.get(i));
            drawCardDeleteX(cx + cellW - 15, cy + 5, mx, my);
            String fn = shots.get(i).getName();
            if (fn.length() > 18) {
                fn = fn.substring(0, 15) + "\u2026";
            }
            fontRendererObj.drawString(fn, cx + 6, cy + thumbH + 8, SocialTheme.TEXT_MUTED);
            shotHits.add(new int[] { cx, cy, cellW, cellH, i });
        }
    }

    private void drawCapes(int mx, int my) {
        SocialUi.drawCard(chatL, chatT, chatR, chatB);
        List<GomodCapeShareHistory.Entry> capes = capeHistoryForFriend(chatPeer);
        int maxScroll = capesMaxScroll(capes.size());
        scrollCapes = SocialUi.clamp(scrollCapes, 0, maxScroll);
        int pad = 12;
        int gap = 8;
        int cols = 3;
        int cellW = (chatR - chatL - pad * 2 - gap * (cols - 1)) / cols;
        int thumbH = Math.max(28, cellW / 2);
        int cellH = thumbH + 44;
        int y0 = chatT + 12 - scrollCapes;
        SocialDraw.enableScissor(chatL + 1, chatT + 1, chatR - 1, chatB - 1);
        if (capes.isEmpty()) {
            String hint = EnumChatFormatting.GRAY + (chatPeer == null
                    ? "No shared capes yet"
                    : "No capes shared with " + chatPeer + " yet");
            int tw = fontRendererObj.getStringWidth(hint);
            fontRendererObj.drawString(hint, chatL + (chatR - chatL - tw) / 2, chatT + 28, SocialTheme.TEXT_MUTED);
            SocialDraw.disableScissor();
            return;
        }
        try {
            for (int i = 0; i < capes.size(); i++) {
                int col = i % cols;
                int row = i / cols;
                int cx = chatL + pad + col * (cellW + gap);
                int cy = y0 + row * (cellH + gap);
                if (cy + cellH < chatT || cy > chatB) {
                    continue;
                }
                boolean hov = mx >= cx && mx < cx + cellW && my >= cy && my < cy + cellH;
                int cellFill = hov ? SocialTheme.CARD_HOVER : SocialTheme.CARD;
                int cellBorder = hov ? SocialTheme.ACCENT_SOFT : SocialTheme.BORDER_SUBTLE;
                SocialDraw.roundedBorder(cx, cy, cx + cellW, cy + cellH, SocialTheme.RADIUS_MD, cellFill, cellBorder);
                GomodCapeShareHistory.Entry entry = capes.get(i);
                drawCapeHistoryThumb(entry, cx + 4, cy + 4, cellW - 8, thumbH);
                drawCardDeleteX(cx + cellW - 15, cy + 5, mx, my);
                drawCapeHistoryName(entry, cx + 6, cy + thumbH + 7, cellW - 12);
                drawCapeHistoryActions(entry, cx + 6, cy + thumbH + 22, cellW - 12, mx, my);
            }
        } finally {
            SocialDraw.disableScissor();
        }
        drawCapesScrollFades(maxScroll);
        SocialUi.drawScrollbar(capesScrollbarTrackR(), capesScrollbarTop(), capesScrollbarHeight(),
                capesContentHeight(capes.size()), scrollCapes, maxScroll);
    }

    private void drawCapesScrollFades(int maxScroll) {
        if (maxScroll <= 0) {
            return;
        }
        int fadeH = Math.min(38, Math.max(18, (chatB - chatT) / 5));
        int fadeL = chatL + SocialTheme.RADIUS_MD;
        int fadeR = chatR - 10;
        int clearCard = SocialDraw.withAlpha(SocialTheme.CARD, 0);
        SocialDraw.enableScissor(chatL + 1, chatT + 1, chatR - 1, chatB - 1);
        try {
            if (scrollCapes > 0) {
                SocialDraw.verticalGradient(fadeL, chatT + 2, fadeR, chatT + 2 + fadeH,
                        SocialTheme.CARD, clearCard);
            }
            if (scrollCapes < maxScroll) {
                int bottom = chatB + 4;
                SocialDraw.verticalGradient(fadeL, bottom - fadeH, fadeR, bottom,
                        clearCard, SocialTheme.CARD);
            }
        } finally {
            SocialDraw.disableScissor();
        }
    }

    private int capesContentHeight(int capeCount) {
        int pad = 12;
        int gap = 8;
        int cols = 3;
        int cellW = (chatR - chatL - pad * 2 - gap * (cols - 1)) / cols;
        int cellH = Math.max(28, cellW / 2) + 44;
        int rows = (Math.max(0, capeCount) + cols - 1) / cols;
        return pad * 2 + rows * cellH + Math.max(0, rows - 1) * gap;
    }

    private int capesMaxScroll() {
        return capesMaxScroll(capeHistoryForFriend(chatPeer).size());
    }

    private int capesMaxScroll(int capeCount) {
        return Math.max(0, capesContentHeight(capeCount) - Math.max(1, chatB - chatT - 2));
    }

    private int capesScrollbarTrackR() {
        return chatR - 3;
    }

    private int capesScrollbarTop() {
        return chatT + 5;
    }

    private int capesScrollbarHeight() {
        return Math.max(1, chatB - chatT - 10);
    }

    private int capesScrollbarThumbHeight() {
        int contentHeight = capesContentHeight(capeHistoryForFriend(chatPeer).size());
        return Math.max(18, capesScrollbarHeight() * capesScrollbarHeight() / Math.max(1, contentHeight));
    }

    private int capesScrollbarThumbY() {
        int maxScroll = capesMaxScroll();
        int trackH = capesScrollbarHeight();
        int thumbH = capesScrollbarThumbHeight();
        if (maxScroll <= 0 || trackH <= thumbH) {
            return capesScrollbarTop();
        }
        return capesScrollbarTop() + (int) ((long) (trackH - thumbH) * scrollCapes / maxScroll);
    }

    private boolean isOverCapesScrollbar(int mx, int my) {
        if (!workspaceOpen || workspacePanel != 2 || capesMaxScroll() <= 0) {
            return false;
        }
        return SocialUi.inRect(mx, my, capesScrollbarTrackR() - 6, capesScrollbarTop(),
                10, capesScrollbarHeight());
    }

    private void setCapesScrollFromMouse(int my, int grabOffset) {
        int maxScroll = capesMaxScroll();
        int trackH = capesScrollbarHeight();
        int thumbH = capesScrollbarThumbHeight();
        int travel = Math.max(1, trackH - thumbH);
        int thumbTop = SocialUi.clamp(my - grabOffset, capesScrollbarTop(), capesScrollbarTop() + travel);
        scrollCapes = SocialUi.clamp((int) ((long) (thumbTop - capesScrollbarTop()) * maxScroll / travel),
                0, maxScroll);
    }

    private void drawCapeHistoryName(GomodCapeShareHistory.Entry entry, int x, int y, int maxW) {
        if (entry == null) {
            return;
        }
        String sender = GomodCapeShareHistory.SENT.equals(entry.direction)
                ? socialSelfLabel()
                : entry.peer;
        String label = entry.label == null || entry.label.trim().isEmpty() ? "Shared cape" : entry.label.trim();
        int head = 10;
        int gap = 4;
        int textX = x + head + gap;
        int textMax = Math.max(12, maxW - head - gap);
        String senderDraw = fitSocialCapeText(sender, Math.max(10, textMax / 2));
        String suffix = fitSocialCapeText(": " + label, Math.max(10, textMax - fontRendererObj.getStringWidth(senderDraw)));
        PlayerHeadTextureUtil.drawMinotarAvatar(sender, x, y - 1, head, 1);
        fontRendererObj.drawString(senderDraw, textX, y, 0x55FFFF);
        fontRendererObj.drawString(suffix, textX + fontRendererObj.getStringWidth(senderDraw), y, SocialTheme.TEXT_MUTED);
    }

    private void drawCapeHistoryActions(GomodCapeShareHistory.Entry entry, int x, int y, int maxW, int mx, int my) {
        if (entry == null) {
            return;
        }
        boolean pending = GomodCapeShareHistory.RECEIVED.equals(entry.direction)
                && !entry.accepted && entry.pendingId != null && !entry.pendingId.isEmpty();
        int right = x + maxW;
        String peer = entry.peer == null || entry.peer.trim().isEmpty() ? "Unknown" : entry.peer.trim();
        String status = pending ? "Pending from " + peer
                : (GomodCapeShareHistory.SENT.equals(entry.direction) ? "Sent to " + peer : "From " + peer);
        int actionW = pending ? 50 : (GomodCapeShareHistory.needsRedownload(entry) ? 78 : 0);
        String statusDraw = fitSocialCapeText(status, Math.max(12, maxW - (actionW > 0 ? actionW + 6 : 0)));
        fontRendererObj.drawString(statusDraw, x, y + 5, pending ? 0xFFFFAA55 : SocialTheme.TEXT_MUTED);
        if (pending) {
            drawSmallCapeAction("Accept", right - 50, y, 50, mx, my, 0xFF2EA85D, 0xFF4FDB86);
        } else if (GomodCapeShareHistory.needsRedownload(entry)) {
            drawSmallCapeAction("Redownload", right - 78, y, 78, mx, my, 0xFF227CA8, 0xFF55CCFF);
        }
    }

    private void drawSmallCapeAction(String label, int x, int y, int w, int mx, int my, int fill, int border) {
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + 18;
        SocialDraw.roundedBorder(x, y, x + w, y + 18, SocialTheme.RADIUS_SM,
                hov ? SocialDraw.withAlpha(fill, 230) : SocialDraw.withAlpha(fill, 185),
                hov ? border : SocialDraw.withAlpha(border, 170));
        int tw = fontRendererObj.getStringWidth(label);
        fontRendererObj.drawString(label, x + (w - tw) / 2, y + 5, SocialTheme.TEXT_BRIGHT);
    }

    private void drawCardDeleteX(int x, int y, int mx, int my) {
        boolean hov = mx >= x && mx < x + 10 && my >= y && my < y + 10;
        int col = hov ? 0xFFFF5555 : 0xFFCC3333;
        fontRendererObj.drawStringWithShadow("x", x + 2, y + 1, col);
    }

    private boolean handleScreenshotClicks(int mx, int my) {
        if (!SocialUi.inRect(mx, my, chatL, chatT, chatR - chatL, chatB - chatT) || chatPeer == null) {
            return false;
        }
        List<File> shots = PlayerScreenshotFiles.listSharedForFriend(mc, chatPeer, 60);
        if (shots.isEmpty()) {
            return false;
        }
        int pad = 12;
        int gap = 8;
        int cols = 3;
        int cellW = (chatR - chatL - pad * 2 - gap * (cols - 1)) / cols;
        int thumbH = Math.max(28, cellW * 9 / 16);
        int cellH = thumbH + 22;
        int y0 = chatT + 12 - scrollShots;
        for (int i = 0; i < shots.size(); i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = chatL + pad + col * (cellW + gap);
            int cy = y0 + row * (cellH + gap);
            if (cy + cellH < chatT || cy > chatB) {
                continue;
            }
            if (SocialUi.inRect(mx, my, cx + cellW - 15, cy + 5, 10, 10)) {
                SocialUi.playButtonClickSound(mc);
                openScreenshotDeleteConfirm(shots.get(i));
                return true;
            }
        }
        return false;
    }

    private boolean handleCapeHistoryClicks(int mx, int my) {
        if (!SocialUi.inRect(mx, my, chatL, chatT, chatR - chatL, chatB - chatT)) {
            return false;
        }
        List<GomodCapeShareHistory.Entry> capes = capeHistoryForFriend(chatPeer);
        if (capes.isEmpty()) {
            return false;
        }
        int pad = 12;
        int gap = 8;
        int cols = 3;
        int cellW = (chatR - chatL - pad * 2 - gap * (cols - 1)) / cols;
        int thumbH = Math.max(28, cellW / 2);
        int cellH = thumbH + 44;
        int y0 = chatT + 12 - scrollCapes;
        for (int i = 0; i < capes.size(); i++) {
            GomodCapeShareHistory.Entry entry = capes.get(i);
            int col = i % cols;
            int row = i / cols;
            int cx = chatL + pad + col * (cellW + gap);
            int cy = y0 + row * (cellH + gap);
            int actionX = cx + 6;
            int actionY = cy + thumbH + 22;
            int maxW = cellW - 12;
            int right = actionX + maxW;
            if (entry != null && SocialUi.inRect(mx, my, cx + cellW - 15, cy + 5, 10, 10)) {
                SocialUi.playButtonClickSound(mc);
                openCapeDeleteConfirm(entry.id);
                return true;
            }
            boolean pending = entry != null && GomodCapeShareHistory.RECEIVED.equals(entry.direction)
                    && !entry.accepted && entry.pendingId != null && !entry.pendingId.isEmpty();
            if (pending) {
                int acceptW = 50;
                int acceptX = right - acceptW;
                if (SocialUi.inRect(mx, my, acceptX, actionY, acceptW, 18)) {
                    SocialUi.playButtonClickSound(mc);
                    if (IncomingCapeDesigns.accept(entry.pendingId)) {
                        reloadData();
                        initGui();
                    }
                    return true;
                }
            } else if (entry != null && GomodCapeShareHistory.needsRedownload(entry)) {
                int dlW = 78;
                int dlX = right - dlW;
                if (!SocialUi.inRect(mx, my, dlX, actionY, dlW, 18)) {
                    continue;
                }
                SocialUi.playButtonClickSound(mc);
                String savedId = GomodCapeShareHistory.downloadToCapeGui(entry.id);
                if (savedId != null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Redownloaded cape to Minecraft capes.");
                } else {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not redownload cape.");
                }
                initGui();
                return true;
            }
        }
        return false;
    }

    private void drawSharedCapeName(PlayerCustomCapes.SavedCape cape, int x, int y, int maxW) {
        if (cape == null) {
            return;
        }
        String sender = cape.sharedFrom == null || cape.sharedFrom.trim().isEmpty()
                ? (chatPeer == null ? "" : chatPeer.trim())
                : cape.sharedFrom.trim();
        String label = cape.label == null || cape.label.trim().isEmpty() ? "Shared cape" : cape.label.trim();
        int head = 10;
        int gap = 4;
        int textX = x + head + gap;
        int textMax = Math.max(12, maxW - head - gap);
        String senderDraw = fitSocialCapeText(sender, Math.max(10, textMax / 2));
        String suffix = fitSocialCapeText(": " + label, Math.max(10, textMax - fontRendererObj.getStringWidth(senderDraw)));
        PlayerHeadTextureUtil.drawMinotarAvatar(sender, x, y - 1, head, 1);
        fontRendererObj.drawString(senderDraw, textX, y, 0x55FFFF);
        fontRendererObj.drawString(suffix, textX + fontRendererObj.getStringWidth(senderDraw), y, SocialTheme.TEXT_MUTED);
    }

    private String fitSocialCapeText(String text, int maxW) {
        String out = text == null ? "" : text;
        while (out.length() > 3 && fontRendererObj.getStringWidth(out) > maxW) {
            out = out.substring(0, out.length() - 4) + "\u2026";
        }
        return out;
    }

    private List<PlayerCustomCapes.SavedCape> sharedCapesForFriend(String friend) {
        List<PlayerCustomCapes.SavedCape> out = new ArrayList<PlayerCustomCapes.SavedCape>();
        if (friend == null || friend.trim().isEmpty()) {
            return out;
        }
        String target = friend.trim();
        for (PlayerCustomCapes.SavedCape cape : PlayerCustomCapes.listSaved()) {
            if (cape != null && cape.sharedFrom != null && cape.sharedFrom.equalsIgnoreCase(target)) {
                out.add(cape);
            }
        }
        return out;
    }

    private List<GomodCapeShareHistory.Entry> capeHistoryForFriend(String friend) {
        if (friend == null || friend.trim().isEmpty()) {
            return GomodCapeShareHistory.all();
        }
        return GomodCapeShareHistory.forPeer(friend);
    }

    private void drawCapeHistoryThumb(GomodCapeShareHistory.Entry entry, int x, int y, int w, int h) {
        if (entry != null && entry.savedCapeId != null && !entry.savedCapeId.isEmpty()) {
            PlayerCustomCapes.SavedCape saved = PlayerCustomCapes.getById(entry.savedCapeId);
            if (saved != null && saved.texture != null) {
                mc.getTextureManager().bindTexture(saved.texture);
                GlStateManager.pushMatrix();
                GlStateManager.enableBlend();
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, 64, 32, w, h, 64, 32);
                GlStateManager.disableBlend();
                GlStateManager.popMatrix();
                return;
            }
        }
        if (entry != null && entry.imageBase64 != null && !entry.imageBase64.isEmpty()) {
            ResourceLocation rl = PlayerCustomCapes.registerEphemeralPeerWear("history_" + entry.id, entry.imageBase64);
            if (rl != null) {
                mc.getTextureManager().bindTexture(rl);
                GlStateManager.pushMatrix();
                GlStateManager.enableBlend();
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, 64, 32, w, h, 64, 32);
                GlStateManager.disableBlend();
                GlStateManager.popMatrix();
                return;
            }
        }
        Gui.drawRect(x, y, x + w, y + h, 0x33000000);
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, 0x22FFFFFF);
    }

    private void drawCapeThumb(PlayerCustomCapes.SavedCape cape, int x, int y, int w, int h) {
        if (cape != null && cape.texture != null) {
            mc.getTextureManager().bindTexture(cape.texture);
            GlStateManager.pushMatrix();
            GlStateManager.enableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            Gui.drawScaledCustomSizeModalRect(x, y, 0, 0, 64, 32, w, h, 64, 32);
            GlStateManager.disableBlend();
            GlStateManager.popMatrix();
            return;
        }
        Gui.drawRect(x, y, x + w, y + h, 0x33000000);
        Gui.drawRect(x + 1, y + 1, x + w - 1, y + h - 1, 0x22FFFFFF);
    }

    private void drawRequests(int mx, int my) {
        SocialUi.drawCard(chatL, chatT, chatR, chatB);
        if (incoming.isEmpty() && outgoing.isEmpty()) {
            String hint = EnumChatFormatting.GRAY + "No pending friend requests";
            int tw = fontRendererObj.getStringWidth(hint);
            fontRendererObj.drawString(hint, chatL + (chatR - chatL - tw) / 2, (chatT + chatB) / 2 - 4, SocialTheme.TEXT_MUTED);
            return;
        }
        int y = chatT + 12 - scrollRequests;
        if (!incoming.isEmpty()) {
            SocialUi.drawSectionLabel(fontRendererObj, "Incoming \u2014 " + incoming.size(), chatL + 16, y);
            y += 14;
        }
        for (String name : incoming) {
            if (y + 56 > chatT && y < chatB) {
                drawRequestCard(name, chatL + 12, y, chatR - 12, true, mx, my);
            }
            y += 62;
        }
        if (!outgoing.isEmpty()) {
            y += 6;
            SocialUi.drawSectionLabel(fontRendererObj, "Outgoing \u2014 " + outgoing.size(), chatL + 16, y);
            y += 14;
        }
        for (String name : outgoing) {
            if (y + 42 > chatT && y < chatB) {
                drawRequestCard(name, chatL + 12, y, chatR - 12, false, mx, my);
            }
            y += 48;
        }
    }

    private void drawRequestCard(String name, int l, int t, int r, boolean incoming, int mx, int my) {
        int b = t + (incoming ? 54 : 40);
        boolean isNew = incoming && name != null
                && newlySeenIncoming.contains(name.trim().toLowerCase(Locale.ROOT));
        if (isNew) {
            SocialDraw.roundedBorder(l, t, r, b, SocialTheme.RADIUS_MD,
                    SocialTheme.CARD_ELEVATED, SocialTheme.ACCENT_BRIGHT);
        } else {
            SocialDraw.roundedFill(l, t, r, b, SocialTheme.RADIUS_MD, SocialTheme.CARD);
        }
        SocialDraw.topHighlight(l, t, r, SocialTheme.RADIUS_MD, SocialTheme.HIGHLIGHT_TOP);
        int avatar = incoming ? 32 : 24;
        int ax = l + 12;
        int ay = t + (b - t - avatar) / 2;
        PlayerHeadTextureUtil.drawMinotarAvatar(name, ax, ay, avatar, 1);
        int textX = ax + avatar + 10;
        fontRendererObj.drawString(EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + name,
                textX, t + (incoming ? 10 : 14), SocialTheme.TEXT_BRIGHT);
        if (incoming) {
            if (isNew) {
                String newLabel = EnumChatFormatting.GREEN + "" + EnumChatFormatting.BOLD + "NEW";
                fontRendererObj.drawString(newLabel, r - fontRendererObj.getStringWidth(newLabel) - 12,
                        t + 10, SocialTheme.ACCENT_BRIGHT);
            }
            fontRendererObj.drawString(EnumChatFormatting.GRAY + "wants to be your friend",
                    textX, t + 22, SocialTheme.TEXT_MUTED);
            boolean hA = SocialUi.inRect(mx, my, r - 152, t + 28, 70, 20);
            boolean hD = SocialUi.inRect(mx, my, r - 78, t + 28, 70, 20);
            SocialUi.drawPrimaryButton(fontRendererObj, r - 152, t + 28, r - 82, t + 48,
                    EnumChatFormatting.BOLD + "Accept", hA);
            SocialUi.drawDangerButton(fontRendererObj, r - 78, t + 28, r - 8, t + 48,
                    EnumChatFormatting.BOLD + "Decline", hD);
        } else {
            fontRendererObj.drawString(EnumChatFormatting.GRAY + "Awaiting response\u2026",
                    r - fontRendererObj.getStringWidth("Awaiting response...") - 12, t + 14, SocialTheme.TEXT_MUTED);
        }
    }

    private void drawPrivacy(int mx, int my) {
        SocialUi.drawCard(chatL, chatT, chatR, chatB);
        int maxScroll = privacyMaxScroll();
        scrollPrivacy = SocialUi.clamp(scrollPrivacy, 0, maxScroll);
        int y = chatT + 16 - scrollPrivacy;
        SocialDraw.enableScissor(chatL + 1, chatT + 1, chatR - 1, chatB - 1);
        try {
            SocialUi.drawSectionLabel(fontRendererObj, "Privacy", chatL + 16, y);
            y += 18;
            y = drawToggleRow(y, "Friend screenshots", "Allow friends to send you screenshots",
                    ConfigManager.isAcceptIncomingFriendScreenshots(), mx, my);
            y = drawToggleRow(y, "ShareInv invites", "Allow friends to send ShareInv pings",
                    ConfigManager.isAcceptIncomingShareInv(), mx, my);
            y = drawToggleRow(y, "Messages from non-friends", "Allow any gomod user to send you direct messages",
                    ConfigManager.isAcceptIncomingNonFriendMessages(), mx, my);
            y = drawToggleRow(y, "Auto-accept capes", "Save cape designs from friends automatically",
                    ConfigManager.isAutoAcceptIncomingCapes(), mx, my);
            y = drawToggleRow(y, "Temp group invites", "Receive invites to 24h temp groups", true, mx, my);
            y = drawToggleRow(y, "Appear offline", "Hide your presence from other gomod users",
                    GomodSocialStore.isAppearOffline(), mx, my);
            y = drawToggleRow(y, "Public message popups", "Show a popup for new Public messages",
                    ConfigManager.isPublicMessagePopupsEnabled(), mx, my);
            drawToggleRow(y, "Message icon", "Top-right unread icon for DMs and public mentions",
                    ConfigManager.isSocialMsgIconHudEnabled(), mx, my);
        } finally {
            SocialDraw.disableScissor();
        }
        drawPrivacyScrollFades(maxScroll);
        SocialUi.drawScrollbar(privacyScrollbarTrackR(), privacyScrollbarTop(), privacyScrollbarHeight(),
                privacyContentHeight(), scrollPrivacy, maxScroll);
    }

    private void drawPrivacyScrollFades(int maxScroll) {
        if (maxScroll <= 0) {
            return;
        }
        int fadeH = Math.min(38, Math.max(18, (chatB - chatT) / 5));
        int fadeL = chatL + SocialTheme.RADIUS_MD;
        int fadeR = chatR - 10; // Leave the scrollbar clear and readable.
        int clearCard = SocialDraw.withAlpha(SocialTheme.CARD, 0);
        SocialDraw.enableScissor(chatL + 1, chatT + 1, chatR - 1, chatB - 1);
        try {
            if (scrollPrivacy > 0) {
                SocialDraw.verticalGradient(fadeL, chatT + 2, fadeR, chatT + 2 + fadeH,
                        SocialTheme.CARD, clearCard);
            }
            if (scrollPrivacy < maxScroll) {
                int bottom = chatB + 4;
                SocialDraw.verticalGradient(fadeL, bottom - fadeH, fadeR, bottom,
                        clearCard, SocialTheme.CARD);
            }
        } finally {
            SocialDraw.disableScissor();
        }
    }

    private int privacyContentHeight() {
        return 16 + 18 + 8 * 52;
    }

    private int privacyMaxScroll() {
        return Math.max(0, privacyContentHeight() - Math.max(1, chatB - chatT - 2));
    }

    private int privacyScrollbarTrackR() {
        return chatR - 3;
    }

    private int privacyScrollbarTop() {
        return chatT + 5;
    }

    private int privacyScrollbarHeight() {
        return Math.max(1, chatB - chatT - 10);
    }

    private int privacyScrollbarThumbHeight() {
        return Math.max(18, privacyScrollbarHeight() * privacyScrollbarHeight() / Math.max(1, privacyContentHeight()));
    }

    private int privacyScrollbarThumbY() {
        int maxScroll = privacyMaxScroll();
        int trackH = privacyScrollbarHeight();
        int thumbH = privacyScrollbarThumbHeight();
        if (maxScroll <= 0 || trackH <= thumbH) {
            return privacyScrollbarTop();
        }
        return privacyScrollbarTop() + (int) ((long) (trackH - thumbH) * scrollPrivacy / maxScroll);
    }

    private boolean isOverPrivacyScrollbar(int mx, int my) {
        if (!workspaceOpen || workspacePanel != 4 || privacyMaxScroll() <= 0) {
            return false;
        }
        int x = privacyScrollbarTrackR() - 6;
        return SocialUi.inRect(mx, my, x, privacyScrollbarTop(), 10, privacyScrollbarHeight());
    }

    private void setPrivacyScrollFromMouse(int my, int grabOffset) {
        int maxScroll = privacyMaxScroll();
        int trackH = privacyScrollbarHeight();
        int thumbH = privacyScrollbarThumbHeight();
        int travel = Math.max(1, trackH - thumbH);
        int thumbTop = SocialUi.clamp(my - grabOffset, privacyScrollbarTop(), privacyScrollbarTop() + travel);
        scrollPrivacy = SocialUi.clamp((int) ((long) (thumbTop - privacyScrollbarTop()) * maxScroll / travel),
                0, maxScroll);
    }

    private int drawToggleRow(int y, String label, String sub, boolean on, int mx, int my) {
        int rowL = chatL + 16;
        int rowR = chatR - 16;
        int rowB = y + 44;
        boolean rowHover = SocialUi.inRect(mx, my, rowL, y, rowR - rowL, rowB - y);
        int fill = rowHover ? SocialTheme.CARD_HOVER : SocialTheme.PANEL_INNER;
        SocialDraw.roundedFill(rowL, y, rowR, rowB, SocialTheme.RADIUS_MD, fill);
        fontRendererObj.drawString(EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + label,
                rowL + 14, y + 10, SocialTheme.TEXT_BRIGHT);
        fontRendererObj.drawString(EnumChatFormatting.GRAY + sub, rowL + 14, y + 24, SocialTheme.TEXT_MUTED);
        int sw = SocialUi.toggleSwitchWidth();
        int sh = SocialUi.toggleSwitchHeight();
        int sx = rowR - sw - 14;
        int sy = y + (rowB - y - sh) / 2;
        boolean switchHover = SocialUi.inRect(mx, my, sx, sy, sw, sh);
        SocialUi.drawToggleSwitch(sx, sy, sw, sh, on, switchHover);
        return rowB + 8;
    }

    private int modalHeight() {
        if (modalKind == 2) {
            return 168;
        }
        if (modalKind == 4) {
            return 128;
        }
        if (modalKind == 5) {
            return 112;
        }
        if (modalKind == 6) {
            return 112;
        }
        if (modalKind == 7) {
            return 112;
        }
        if (modalKind == 8) {
            return 132;
        }
        if (modalKind == 9) {
            return 124;
        }
        return 138;
    }

    private void drawModal(int mx, int my) {
        SocialDraw.fillRect(0, 0, width, height, SocialTheme.MODAL_OVERLAY);
        int mw = 280;
        int mh = modalHeight();
        int mxL = (width - mw) / 2;
        int myT = (height - mh) / 2;
        SocialDraw.dropShadow(mxL, myT, mxL + mw, myT + mh, 12, SocialTheme.SHADOW);
        SocialDraw.roundedBorder(mxL, myT, mxL + mw, myT + mh, SocialTheme.RADIUS_LG,
                SocialTheme.MODAL_BG, SocialTheme.BORDER_SUBTLE);
        SocialDraw.roundedTopGradient(mxL + 1, myT + 1, mxL + mw - 1, myT + 36,
                SocialTheme.RADIUS_LG - 1, SocialTheme.MODAL_BG_TOP,
                SocialDraw.withAlpha(SocialTheme.MODAL_BG_TOP, 0));

        String title;
        String sub;
        if (modalKind == 1) {
            title = "Add friend";
            sub = "Send a gomod friend request by Minecraft name";
        } else if (modalKind == 2) {
            title = "Create temp group";
            sub = "Private group \u2014 gomod friends only, expires after 24h of silence";
        } else if (modalKind == 4) {
            title = "Add members";
            GomodSocialTempGroups.Group addG = GomodSocialTempGroups.get(modalGroupAddTargetId);
            String gName = addG == null ? "group" : addG.name;
            sub = "Add gomod friends to " + gName;
        } else if (modalKind == 5) {
            title = "Remove friend?";
            String peer = chatPeer == null ? "" : chatPeer.trim();
            sub = peer.isEmpty() ? "Remove this friend from your list?"
                    : "Remove " + peer + " from your friends list?";
        } else if (modalKind == 6) {
            title = "Delete cape?";
            sub = "Remove this cape from gomod Social Capes?";
        } else if (modalKind == 7) {
            title = "Delete screenshot?";
            sub = "Remove this screenshot from gomod Social Screenshots?";
        } else if (modalKind == 8) {
            title = modalPublicPlayer == null ? "Player" : modalPublicPlayer;
            sub = "Choose what you want to do with this player";
        } else if (modalKind == 9) {
            title = "Screenshot";
            sub = "Open it full-size or save a copy";
        } else {
            title = "Create thread";
            sub = "Start a new thread \u2014 friends can reply with messages";
        }
        fontRendererObj.drawString(EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + title,
                mxL + 18, myT + 14, SocialTheme.TEXT_BRIGHT);
        fontRendererObj.drawString(EnumChatFormatting.GRAY + sub, mxL + 18, myT + 28, SocialTheme.TEXT_MUTED);

        if (modalKind == 8) {
            int playerY = myT + 48;
            PlayerHeadTextureUtil.drawMinotarAvatar(modalPublicPlayer, mxL + 18, playerY, 24, 1);
            int addL = mxL + 52;
            int gap = 8;
            int actionW = (mw - 70 - gap) / 2;
            int addR = addL + actionW;
            int msgL = addR + gap;
            int msgR = mxL + mw - 18;
            SocialUi.drawPrimaryButton(fontRendererObj, addL, playerY, addR, playerY + 24,
                    EnumChatFormatting.BOLD + "Add friend",
                    SocialUi.inRect(mx, my, addL, playerY, addR - addL, 24));
            SocialUi.drawPrimaryButton(fontRendererObj, msgL, playerY, msgR, playerY + 24,
                    EnumChatFormatting.BOLD + "Message",
                    SocialUi.inRect(mx, my, msgL, playerY, msgR - msgL, 24));
            int cancelT = myT + mh - 34;
            SocialUi.drawGhostButton(fontRendererObj, mxL + 18, cancelT, mxL + mw - 18, cancelT + 20,
                    "Cancel", SocialUi.inRect(mx, my, mxL + 18, cancelT, mw - 36, 20));
            return;
        }

        if (modalKind == 9) {
            int actionY = myT + 48;
            int gap = 8;
            int actionW = (mw - 36 - gap) / 2;
            int viewL = mxL + 18;
            int viewR = viewL + actionW;
            int saveL = viewR + gap;
            int saveR = mxL + mw - 18;
            SocialUi.drawPrimaryButton(fontRendererObj, viewL, actionY, viewR, actionY + 24,
                    EnumChatFormatting.BOLD + "View big",
                    SocialUi.inRect(mx, my, viewL, actionY, viewR - viewL, 24));
            SocialUi.drawClickableActionButton(fontRendererObj, saveL, actionY, saveR, actionY + 24,
                    EnumChatFormatting.BOLD + "Save", SocialTheme.TEXT_BRIGHT,
                    SocialUi.inRect(mx, my, saveL, actionY, saveR - saveL, 24),
                    GomodSocialClickableButtonTextures.Style.SEND);
            int cancelT = myT + mh - 34;
            SocialUi.drawGhostButton(fontRendererObj, mxL + 18, cancelT, mxL + mw - 18, cancelT + 20,
                    "Cancel", SocialUi.inRect(mx, my, mxL + 18, cancelT, mw - 36, 20));
            return;
        }

        if (modalKind == 4) {
            fontRendererObj.drawString(EnumChatFormatting.GRAY + "Members (gomod friends)",
                    mxL + 18, myT + 42, SocialTheme.TEXT_MUTED);
            SocialUi.drawInsetField(mxL + 18, myT + 52, mxL + mw - 18, myT + 82);
            SocialUi.layoutTextField(modalMembersField, mxL + 24, myT + 56, mxL + mw - 24, myT + 78);
            if (modalMembersField.getText().isEmpty() && !modalMembersField.isFocused()) {
                fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + "Friend1, Friend2\u2026",
                        modalMembersField.xPosition, modalMembersField.yPosition, SocialTheme.TEXT_DIM);
            }
            modalMembersField.drawTextBox();
        } else if (modalKind != 5 && modalKind != 6 && modalKind != 7) {
            SocialUi.drawInsetField(mxL + 18, myT + 44, mxL + mw - 18, myT + 74);
            SocialUi.layoutTextField(modalField, mxL + 24, myT + 48, mxL + mw - 24, myT + 70);
            if (modalField.getText().isEmpty() && !modalField.isFocused()) {
                String placeholder = modalKind == 1 ? "Username\u2026"
                        : (modalKind == 2 ? "Group name\u2026" : "Thread title\u2026");
                fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + placeholder,
                        modalField.xPosition, modalField.yPosition, SocialTheme.TEXT_DIM);
            }
            modalField.drawTextBox();
            if (modalKind == 2) {
                fontRendererObj.drawString(EnumChatFormatting.GRAY + "Members (gomod friends)",
                        mxL + 18, myT + 78, SocialTheme.TEXT_MUTED);
                SocialUi.drawInsetField(mxL + 18, myT + 88, mxL + mw - 18, myT + 118);
                SocialUi.layoutTextField(modalMembersField, mxL + 24, myT + 92, mxL + mw - 24, myT + 114);
                if (modalMembersField.getText().isEmpty() && !modalMembersField.isFocused()) {
                    fontRendererObj.drawString(EnumChatFormatting.DARK_GRAY + "Friend1, Friend2\u2026",
                            modalMembersField.xPosition, modalMembersField.yPosition, SocialTheme.TEXT_DIM);
                }
                modalMembersField.drawTextBox();
            }
        }

        int actT = myT + mh - 38;
        int actB = myT + mh - 14;
        int cancelL = mxL + 18;
        int cancelR = cancelL + 78;
        int okR = mxL + mw - 18;
        int okL = okR - 90;
        boolean hC = SocialUi.inRect(mx, my, cancelL, actT, cancelR - cancelL, actB - actT);
        boolean hOk = SocialUi.inRect(mx, my, okL, actT, okR - okL, actB - actT);
        SocialUi.drawGhostButton(fontRendererObj, cancelL, actT, cancelR, actB, "Cancel", hC);
        String okLabel;
        if (modalKind == 1) {
            okLabel = "Send request";
        } else if (modalKind == 4) {
            okLabel = "Add";
        } else if (modalKind == 5) {
            okLabel = "Remove";
        } else if (modalKind == 6 || modalKind == 7) {
            okLabel = "Delete";
        } else {
            okLabel = "Create";
        }
        if (modalKind == 5 || modalKind == 6 || modalKind == 7) {
            SocialUi.drawDangerButton(fontRendererObj, okL, actT, okR, actB,
                    EnumChatFormatting.BOLD + okLabel, hOk);
        } else {
            SocialUi.drawPrimaryButton(fontRendererObj, okL, actT, okR, actB,
                    EnumChatFormatting.BOLD + okLabel, hOk);
        }
    }

    private void blurModalFieldsExcept(GuiTextField keep) {
        if (modalField != null && modalField != keep && modalField.isFocused()) {
            modalField.setFocused(false);
        }
        if (modalMembersField != null && modalMembersField != keep && modalMembersField.isFocused()) {
            modalMembersField.setFocused(false);
        }
    }

    private void blurSocialTextFieldsExcept(GuiTextField keep) {
        if (searchField != null && searchField.field != null && searchField.field != keep
                && searchField.field.isFocused()) {
            searchField.field.setFocused(false);
        }
        if (messageField != null && messageField != keep && messageField.isFocused()) {
            messageField.setFocused(false);
        }
    }

    private void drawThumb(int x, int y, int w, int h, File file) {
        ResourceLocation tex = thumbFor(file);
        if (tex == null) {
            Gui.drawRect(x, y, x + w, y + h, SocialTheme.PANEL_INNER);
            return;
        }
        GlStateManager.color(1, 1, 1, 1);
        mc.getTextureManager().bindTexture(tex);
        Gui.drawModalRectWithCustomSizedTexture(x, y, 0, 0, w, h, w, h);
    }

    private ResourceLocation thumbFor(File file) {
        if (file == null) {
            return null;
        }
        final String key = file.getAbsolutePath();
        ResourceLocation c = thumbCache.get(key);
        if (c != null) {
            return c;
        }
        if (thumbLoading.contains(key) || thumbFailed.contains(key)) {
            return null;
        }
        thumbLoading.add(key);
        final File thumbFile = file;
        final Minecraft client = mc;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final BufferedImage thumb = ScreenshotImageTextureUtil.loadThumbnailFromFile(thumbFile, 256, 144);
                if (client == null) {
                    return;
                }
                client.addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        thumbLoading.remove(key);
                        if (thumb == null) {
                            thumbFailed.add(key);
                            return;
                        }
                        ResourceLocation rl = ScreenshotImageTextureUtil.registerDynamicTexture(client, thumb, "soc");
                        if (rl != null) {
                            thumbCache.put(key, rl);
                        } else {
                            thumbFailed.add(key);
                        }
                    }
                });
            }
        }, "gomod-social-thumb").start();
        return null;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && System.currentTimeMillis() - openedAtMs < 220L && Mouse.isButtonDown(0)) {
            return;
        }
        if (mouseButton != 0) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        if (modalKind != 0) {
            int mw = 280;
            int mh = modalHeight();
            int mxL = (width - mw) / 2;
            int myT = (height - mh) / 2;
            if (modalKind == 8) {
                int playerY = myT + 48;
                int addL = mxL + 52;
                int gap = 8;
                int actionW = (mw - 70 - gap) / 2;
                int addR = addL + actionW;
                int msgL = addR + gap;
                int msgR = mxL + mw - 18;
                if (SocialUi.inRect(mouseX, mouseY, addL, playerY, addR - addL, 24)) {
                    SocialUi.playButtonClickSound(mc);
                    String target = modalPublicPlayer;
                    closePublicPlayerActions();
                    if (target != null && !target.trim().isEmpty()) {
                        GomodFriendRelay.postFriendRequestAddAsync(mc, target, FriendAddSource.FRIENDS_GUI);
                    }
                    return;
                }
                if (SocialUi.inRect(mouseX, mouseY, msgL, playerY, msgR - msgL, 24)) {
                    SocialUi.playButtonClickSound(mc);
                    String target = modalPublicPlayer;
                    closePublicPlayerActions();
                    if (target != null && !target.trim().isEmpty()) {
                        openFriend(target);
                    }
                    return;
                }
                int cancelT = myT + mh - 34;
                if (SocialUi.inRect(mouseX, mouseY, mxL + 18, cancelT, mw - 36, 20)) {
                    SocialUi.playButtonClickSound(mc);
                    closePublicPlayerActions();
                }
                return;
            }
            if (modalKind == 9) {
                int actionY = myT + 48;
                int gap = 8;
                int actionW = (mw - 36 - gap) / 2;
                int viewL = mxL + 18;
                int viewR = viewL + actionW;
                int saveL = viewR + gap;
                int saveR = mxL + mw - 18;
                if (SocialUi.inRect(mouseX, mouseY, viewL, actionY, viewR - viewL, 24)) {
                    SocialUi.playButtonClickSound(mc);
                    File file = modalScreenshotActionFile;
                    closeScreenshotActions(false);
                    openScreenshotFileBig(file);
                    return;
                }
                if (SocialUi.inRect(mouseX, mouseY, saveL, actionY, saveR - saveL, 24)) {
                    SocialUi.playButtonClickSound(mc);
                    File file = modalScreenshotActionFile;
                    closeScreenshotActions(true);
                    saveScreenshotMessageFile(file);
                    return;
                }
                int cancelT = myT + mh - 34;
                if (SocialUi.inRect(mouseX, mouseY, mxL + 18, cancelT, mw - 36, 20)) {
                    SocialUi.playButtonClickSound(mc);
                    closeScreenshotActions(true);
                }
                return;
            }
            int actT = myT + mh - 38;
            int actB = myT + mh - 14;
            int cancelL = mxL + 18;
            int cancelR = cancelL + 78;
            int okR = mxL + mw - 18;
            int okL = okR - 90;
            if (SocialUi.inRect(mouseX, mouseY, okL, actT, okR - okL, actB - actT)) {
                actionPerformed(modalOk);
                return;
            }
            if (SocialUi.inRect(mouseX, mouseY, cancelL, actT, cancelR - cancelL, actB - actT)) {
                actionPerformed(modalCancel);
                return;
            }
            int fieldL = mxL + 18;
            int fieldR = mxL + mw - 18;
            if (modalKind == 5 || modalKind == 6 || modalKind == 7) {
                super.mouseClicked(mouseX, mouseY, mouseButton);
                return;
            }
            if (modalKind == 4) {
                if (SocialUi.inRect(mouseX, mouseY, fieldL, myT + 52, fieldR - fieldL, 30)) {
                    blurModalFieldsExcept(modalMembersField);
                    SocialUi.focusTextFieldAt(modalMembersField, fieldL, myT + 52, fieldR, myT + 82,
                            mouseX, mouseY, mouseButton);
                    return;
                }
            } else {
                if (SocialUi.inRect(mouseX, mouseY, fieldL, myT + 44, fieldR - fieldL, 30)) {
                    blurModalFieldsExcept(modalField);
                    SocialUi.focusTextFieldAt(modalField, fieldL, myT + 44, fieldR, myT + 74,
                            mouseX, mouseY, mouseButton);
                    return;
                }
                if (modalKind == 2 && SocialUi.inRect(mouseX, mouseY, fieldL, myT + 88, fieldR - fieldL, 30)) {
                    blurModalFieldsExcept(modalMembersField);
                    SocialUi.focusTextFieldAt(modalMembersField, fieldL, myT + 88, fieldR, myT + 118,
                            mouseX, mouseY, mouseButton);
                    return;
                }
            }
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        int tab = SocialUi.dockTabAt(mouseX, mouseY, 12, dockTabsTop(), SocialTheme.DOCK_W - 24);
        if (tab >= 0) {
            SocialUi.DockPanel next = SocialUi.DockPanel.values()[tab];
            if (publicChatMode) {
                leavePublicChatView();
            }
            blurSocialTextFieldsExcept(null);
            dockPanel = next;
            if (next == SocialUi.DockPanel.PUBLIC) {
                openPublicChat();
            } else {
                initGui();
            }
            return;
        }

        if (dockPanel == SocialUi.DockPanel.PUBLIC) {
            int tabsT = SocialTheme.DOCK_TOP_H;
            int listTop = tabsT + SocialTheme.DOCK_TABS_H + 6;
            int l = 12;
            int r = SocialTheme.DOCK_W - 12;
            int listH = height - listTop - 40;
            if (SocialUi.inRect(mouseX, mouseY, l, listTop, r - l, listH)) {
                openPublicChat();
                return;
            }
        }

        if (!workspaceOpen && SocialUi.inRect(mouseX, mouseY, expandL, expandT, expandR - expandL, expandB - expandT)) {
            openWorkspace();
            return;
        }

        if (workspaceOpen && GomodSocialBackButtonTextures.hitTest(mouseX, mouseY, collapseL, collapseT)) {
            SocialUi.playButtonClickSound(mc);
            if (threadId != null) {
                threadId = null;
                forumId = null;
                initGui();
                return;
            }
            if (publicChatMode) {
                leavePublicChatView();
            }
            workspaceOpen = false;
            openAnim = 0;
            threadId = null;
            forumId = null;
            publicChatMode = false;
            initGui();
            return;
        }

        if (dockPanel == SocialUi.DockPanel.FRIENDS && handleFriendClick(mouseX, mouseY)) {
            return;
        }
        if (dockPanel == SocialUi.DockPanel.GROUPS && handleGroupClick(mouseX, mouseY)) {
            return;
        }
        if (dockPanel == SocialUi.DockPanel.THREADS && handleThreadDockClick(mouseX, mouseY)) {
            return;
        }

        if (dockPanel != SocialUi.DockPanel.PUBLIC
                && SocialUi.inRect(mouseX, mouseY, addLinkL, addLinkT, addLinkR - addLinkL, addLinkB - addLinkT)) {
            if (dockPanel == SocialUi.DockPanel.FRIENDS) {
                modalKind = 1;
            } else if (dockPanel == SocialUi.DockPanel.GROUPS) {
                modalKind = 2;
                modalGroupAddTargetId = null;
            } else {
                modalKind = 3;
                modalGroupAddTargetId = null;
            }
            modalField.setText("");
            if (modalKind == 2) {
                modalMembersField.setText("");
            }
            initGui();
            return;
        }

        if (workspaceOpen && threadId == null && !groupMode && !publicChatMode) {
            int wtab = SocialUi.workspaceTabAt(fontRendererObj, mouseX, mouseY, wsL,
                    wsT + SocialTheme.WORKSPACE_BAR_H, wsR - wsL, GomodSocialRequests.pendingBadgeCount());
            if (wtab >= 0) {
                if (wtab == 3) {
                    captureAndAcknowledgeIncomingRequests();
                }
                workspacePanel = wtab;
                initGui();
                return;
            }
        }

        if (workspaceOpen && workspacePanel == 0 && groupMode && groupId != null
                && SocialUi.inRect(mouseX, mouseY, groupAddBtnL, groupAddBtnT,
                groupAddBtnR - groupAddBtnL, groupAddBtnB - groupAddBtnT)) {
            modalKind = 4;
            modalGroupAddTargetId = groupId;
            modalMembersField.setText("");
            initGui();
            return;
        }

        if (workspaceOpen && workspacePanel == 0 && publicChatMode) {
            String sender = SocialChatDraw.publicSenderAt(mouseX, mouseY);
            if (sender != null && !isSelfName(sender)) {
                SocialUi.playButtonClickSound(mc);
                modalPublicPlayer = sender.trim();
                modalKind = 8;
                initGui();
                return;
            }
        }

        if (workspaceOpen && workspacePanel == 0 && chatPeer != null && !groupMode && !publicChatMode
                && threadId == null) {
            if(mouseButton==0&&SocialChatDraw.clickFit(mouseX,mouseY)){SocialUi.playButtonClickSound(mc);return;}
            if(LunarFitChatPreview.startDrag(chatPeer,mouseX,mouseY))return;
            SocialChatDraw.ScreenshotHit screenshotHit = SocialChatDraw.screenshotAt(mouseX, mouseY);
            if (screenshotHit != null && screenshotHit.file != null && screenshotHit.file.isFile()) {
                SocialUi.playButtonClickSound(mc);
                openScreenshotActions(screenshotHit.file);
                return;
            }
        }

        if (workspaceOpen && workspacePanel == 0 && chatPeer != null && !groupMode && !publicChatMode
                && threadId == null) {
            if (SocialUi.inRect(mouseX, mouseY, shareInvBtnL, shareInvBtnT, shareInvBtnR - shareInvBtnL, shareInvBtnB - shareInvBtnT)) {
                SocialUi.playButtonClickSound(mc);
                ShareInvManager.sendInviteFromSocial(chatPeer);
                return;
            }
            if (SocialUi.inRect(mouseX, mouseY, shotBtnL, shotBtnT, shotBtnR - shotBtnL, shotBtnB - shotBtnT)) {
                SocialUi.playButtonClickSound(mc);
                onScreenshotShareClick();
                return;
            }
            if (SocialUi.inRect(mouseX, mouseY, removeFriendBtnL, removeFriendBtnT,
                    removeFriendBtnR - removeFriendBtnL, removeFriendBtnB - removeFriendBtnT)) {
                SocialUi.playButtonClickSound(mc);
                openRemoveFriendConfirm();
                return;
            }
        }

        if (workspaceOpen && threadId != null && GomodServiceStatus.isSocialAdmin()
                && threadDeleteR > threadDeleteL
                && SocialUi.inRect(mouseX, mouseY, threadDeleteL, threadDeleteT,
                threadDeleteR - threadDeleteL, threadDeleteB - threadDeleteT)) {
            SocialUi.playButtonClickSound(mc);
            confirmDeleteThread();
            return;
        }

        if (workspaceOpen && sendBtnR > sendBtnL
                && SocialUi.inRect(mouseX, mouseY, sendBtnL, sendBtnT, sendBtnR - sendBtnL, sendBtnB - sendBtnT)) {
            SocialUi.playButtonClickSound(mc);
            sendMessage();
            return;
        }

        if (workspaceOpen && workspacePanel == 4 && isOverPrivacyScrollbar(mouseX, mouseY)) {
            int thumbY = privacyScrollbarThumbY();
            int thumbH = privacyScrollbarThumbHeight();
            privacyScrollbarDragging = true;
            privacyScrollbarGrabOffset = SocialUi.inRect(mouseX, mouseY,
                    privacyScrollbarTrackR() - 6, thumbY, 10, thumbH)
                    ? mouseY - thumbY
                    : thumbH / 2;
            setPrivacyScrollFromMouse(mouseY, privacyScrollbarGrabOffset);
            return;
        }

        if (workspaceOpen && workspacePanel == 2 && isOverCapesScrollbar(mouseX, mouseY)) {
            int thumbY = capesScrollbarThumbY();
            int thumbH = capesScrollbarThumbHeight();
            capesScrollbarDragging = true;
            capesScrollbarGrabOffset = SocialUi.inRect(mouseX, mouseY,
                    capesScrollbarTrackR() - 6, thumbY, 10, thumbH)
                    ? mouseY - thumbY
                    : thumbH / 2;
            setCapesScrollFromMouse(mouseY, capesScrollbarGrabOffset);
            return;
        }

        if (workspaceOpen && workspacePanel == 3) {
            if (handleRequestClicks(mouseX, mouseY)) {
                return;
            }
        }
        if (workspaceOpen && workspacePanel == 2) {
            if (handleCapeHistoryClicks(mouseX, mouseY)) {
                return;
            }
        }
        if (workspaceOpen && workspacePanel == 1) {
            if (handleScreenshotClicks(mouseX, mouseY)) {
                return;
            }
        }
        if (workspaceOpen && workspacePanel == 4) {
            if (handlePrivacyClicks(mouseX, mouseY)) {
                return;
            }
        }

        if (dockPanel != SocialUi.DockPanel.PUBLIC) {
            int searchT = dockTabsTop() + SocialTheme.DOCK_TABS_H - 4;
            int searchL = 12;
            int searchW = SocialTheme.DOCK_W - 24;
            int searchH = 26;
            if (SocialUi.inRect(mouseX, mouseY, searchL, searchT, searchW, searchH)) {
                blurSocialTextFieldsExcept(searchField.field);
                SocialUi.focusTextFieldAt(searchField.field, searchL, searchT, searchL + searchW, searchT + searchH,
                        SocialUi.searchBoxIconInset(), mouseX, mouseY, mouseButton);
                return;
            }
            if (searchField.field.isFocused()) {
                searchField.field.setFocused(false);
            }
        }
        if (workspaceOpen && (workspacePanel == 0 || threadId != null) && composerFieldR > composerFieldL) {
            if (publicChatMode && publicMentionAssist.handleClick(messageField, mouseX, mouseY,
                    composerFieldL, composerFieldT, composerFieldR)) {
                return;
            }
            if (SocialUi.inRect(mouseX, mouseY, composerFieldL, composerFieldT,
                    composerFieldR - composerFieldL, composerFieldB - composerFieldT)) {
                blurSocialTextFieldsExcept(messageField);
                SocialUi.focusTextFieldAt(messageField, composerFieldL, composerFieldT, composerFieldR, composerFieldB,
                        mouseX, mouseY, mouseButton);
                return;
            }
            if (messageField.isFocused()) {
                messageField.setFocused(false);
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if(clickedMouseButton==0&&modalKind==0&&workspaceOpen&&workspacePanel==0&&chatPeer!=null&&!groupMode&&!publicChatMode&&threadId==null){
            if(LunarFitChatPreview.drag(chatPeer,mouseX))return;
        }else LunarFitChatPreview.releaseDrag();
        if (clickedMouseButton == 0 && capesScrollbarDragging) {
            setCapesScrollFromMouse(mouseY, capesScrollbarGrabOffset);
            return;
        }
        if (clickedMouseButton == 0 && privacyScrollbarDragging) {
            setPrivacyScrollFromMouse(mouseY, privacyScrollbarGrabOffset);
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0) {
            LunarFitChatPreview.releaseDrag();
            capesScrollbarDragging = false;
            privacyScrollbarDragging = false;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    private void onScreenshotShareClick() {
        if (chatPeer == null || chatPeer.trim().isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Select a friend first.");
            return;
        }
        if (!GomodServiceStatus.isServiceConnected()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Not connected to gomod Service — screenshot sharing unavailable.");
            return;
        }
        final Minecraft client = this.mc;
        final String targetPeer = chatPeer.trim();
        final File startDir = PlayerScreenshotFiles.getScreenshotsDir(client);
        if (startDir != null && !startDir.isDirectory()) {
            startDir.mkdirs();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final File picked = GomodFileDialogs.pickOpenPngFile("Choose screenshot to send", startDir);
                    if (picked == null) {
                        return;
                    }
                    if (client != null) {
                        client.addScheduledTask(new Runnable() {
                            @Override
                            public void run() {
                                ScreenshotShareService.shareFileToPlayer(targetPeer, picked);
                            }
                        });
                    } else {
                        ScreenshotShareService.shareFileToPlayer(targetPeer, picked);
                    }
                } catch (Throwable t) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                            + "Could not open screenshot picker: " + t.getMessage());
                }
            }
        }, "gomod-social-screenshot-picker").start();
    }

    private void leavePublicChatView() {
        if (publicChatMode) {
            GomodSocialPublicChat.markReadNow();
            publicChatMode = false;
            publicMentionAssist.clear();
        }
    }

    private void openWorkspace() {
        leavePublicChatView();
        workspaceOpen = true;
        openAnim = 0;
        if (chatPeer == null) {
            for (String f : online) {
                if (f != null && !isSelfName(f)) {
                    chatPeer = f;
                    break;
                }
            }
        }
        groupMode = false;
        forumId = null;
        threadId = null;
        workspacePanel = 0;
        initGui();
    }

    private void openPublicChat() {
        blurSocialTextFieldsExcept(null);
        publicChatMode = true;
        groupMode = false;
        groupId = null;
        chatPeer = null;
        forumId = null;
        threadId = null;
        workspacePanel = 0;
        workspaceOpen = true;
        openAnim = 8;
        layoutRects();
        long lastRead = GomodSocialPublicChat.getLastReadMs();
        if (GomodSocialPublicChat.getUnreadCount() > 0 && lastRead > 0L) {
            scrollChat = SocialChatDraw.scrollToFirstUnread(fontRendererObj, GomodSocialPublicChat.getMessages(),
                    chatL + 8, chatT + 8, chatR - 10, chatB - 6, true, lastRead);
        } else {
            scrollChat = 99999;
        }
        initGui();
    }

    private void openRemoveFriendConfirm() {
        if (chatPeer == null || chatPeer.trim().isEmpty()) {
            return;
        }
        modalKind = 5;
        modalGroupAddTargetId = null;
        modalCapeDeleteId = null;
        modalScreenshotDeleteFile = null;
        modalScreenshotActionFile = null;
        blurModalFieldsExcept(null);
        initGui();
    }

    private void openCapeDeleteConfirm(String entryId) {
        if (entryId == null || entryId.trim().isEmpty()) {
            return;
        }
        modalKind = 6;
        modalGroupAddTargetId = null;
        modalCapeDeleteId = entryId.trim();
        modalScreenshotDeleteFile = null;
        modalScreenshotActionFile = null;
        blurModalFieldsExcept(null);
        initGui();
    }

    private void openScreenshotDeleteConfirm(File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        modalKind = 7;
        modalGroupAddTargetId = null;
        modalCapeDeleteId = null;
        modalScreenshotDeleteFile = file;
        modalScreenshotActionFile = null;
        blurModalFieldsExcept(null);
        initGui();
    }

    private void openScreenshotActions(File file) {
        if (file == null || !file.isFile()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Screenshot file is unavailable.");
            return;
        }
        modalKind = 9;
        modalGroupAddTargetId = null;
        modalCapeDeleteId = null;
        modalScreenshotDeleteFile = null;
        modalScreenshotActionFile = file;
        blurModalFieldsExcept(null);
        initGui();
    }

    private void closeScreenshotActions(boolean refreshGui) {
        modalKind = 0;
        modalScreenshotActionFile = null;
        if (refreshGui) {
            initGui();
        }
    }

    private void openScreenshotFileBig(File file) {
        if (file == null || !file.isFile()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Screenshot file is unavailable.");
            initGui();
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            IncomingScreenshotManager.IncomingScreenshot screenshot =
                    new IncomingScreenshotManager.IncomingScreenshot(
                            chatPeer == null ? "Screenshot" : chatPeer,
                            file.getName(),
                            "image/png",
                            bytes,
                            file.lastModified());
            mc.displayGuiScreen(new ScreenshotViewerGUI(screenshot, this));
        } catch (IOException e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Could not open screenshot: " + e.getMessage());
            initGui();
        }
    }

    private void saveScreenshotMessageFile(File file) {
        if (file == null || !file.isFile()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Screenshot file is unavailable.");
            return;
        }
        File dir = PlayerScreenshotFiles.getScreenshotsDir(mc);
        if (dir == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Minecraft screenshots folder is unavailable.");
            return;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Could not create screenshots folder.");
            return;
        }
        String safeName = file.getName().replace('\\', '_').replace('/', '_').replace(':', '_').trim();
        if (safeName.isEmpty()) {
            safeName = "screenshot.png";
        }
        File out = uniqueScreenshotCopyTarget(dir, "gomod_saved_" + safeName);
        try {
            Files.copy(file.toPath(), out.toPath());
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Saved screenshot to "
                    + EnumChatFormatting.AQUA + "screenshots/" + out.getName());
        } catch (IOException e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Could not save screenshot: " + e.getMessage());
        }
    }

    private File uniqueScreenshotCopyTarget(File dir, String fileName) {
        File out = new File(dir, fileName);
        if (!out.exists()) {
            return out;
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : ".png";
        int i = 1;
        while (out.exists()) {
            out = new File(dir, base + "_" + i + ext);
            i++;
        }
        return out;
    }

    private void deleteCapeHistoryEntry() {
        if (modalCapeDeleteId == null || modalCapeDeleteId.trim().isEmpty()) {
            return;
        }
        if (GomodCapeShareHistory.deleteEntry(modalCapeDeleteId)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Deleted cape from gomod Social Capes.");
        }
        modalCapeDeleteId = null;
        reloadData();
    }

    private void deleteSelectedScreenshot() {
        File file = modalScreenshotDeleteFile;
        if (file == null || !file.isFile()) {
            modalScreenshotDeleteFile = null;
            return;
        }
        String key = file.getAbsolutePath();
        ResourceLocation old = thumbCache.remove(key);
        thumbLoading.remove(key);
        thumbFailed.remove(key);
        if (old != null && mc != null && mc.getTextureManager() != null) {
            mc.getTextureManager().deleteTexture(old);
        }
        try {
            Files.deleteIfExists(file.toPath());
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Deleted screenshot from gomod Social Screenshots.");
        } catch (IOException e) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED
                    + "Failed to delete screenshot.");
        }
        modalScreenshotDeleteFile = null;
        reloadData();
    }

    private void removeCurrentFriend() {
        if (chatPeer == null || chatPeer.trim().isEmpty()) {
            return;
        }
        String name = chatPeer.trim();
        GomodFriendRelay.postFriendRemoveAsync(name);
        chatPeer = null;
        pendingShot = null;
        pendingShotTex = null;
        modalCapeDeleteId = null;
        modalScreenshotDeleteFile = null;
        reloadData();
        initGui();
    }

    private void openFriend(String name) {
        if (isSelfName(name)) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "You can't message yourself.");
            return;
        }
        leavePublicChatView();
        chatPeer = name;
        groupMode = false;
        groupId = null;
        forumId = null;
        threadId = null;
        workspacePanel = 0;
        workspaceOpen = true;
        openAnim = 8;
        scrollChat = 99999;
        GomodSocialStore.markRead(name, System.currentTimeMillis());
        initGui();
    }

    private void closePublicPlayerActions() {
        modalKind = 0;
        modalPublicPlayer = null;
        initGui();
    }

    private void openGroup(String id) {
        leavePublicChatView();
        groupId = id;
        groupMode = true;
        chatPeer = null;
        forumId = null;
        threadId = null;
        workspacePanel = 0;
        workspaceOpen = true;
        openAnim = 8;
        scrollChat = 99999;
        initGui();
    }

    private void confirmDeleteThread() {
        if (forumId == null || threadId == null || !GomodServiceStatus.isSocialAdmin()) {
            return;
        }
        final String fid = forumId;
        final String tid = threadId;
        GomodSocialForumRelay.deleteThreadAsync(fid, tid, new GomodSocialForumRelay.SimpleCallback() {
            @Override
            public void onResult(boolean ok, String error) {
                if (ok) {
                    threadId = null;
                    forumId = null;
                    boolean orphan = "orphan".equals(error);
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN
                            + (orphan ? "Thread removed (was not on relay)." : "Thread deleted."));
                    initGui();
                } else {
                    GomodSocialForumRelay.notifyRelayError(error);
                }
            }
        });
    }

    private void openThread(String forumIdIn, String threadIdIn) {
        leavePublicChatView();
        forumId = forumIdIn;
        threadId = threadIdIn;
        groupMode = false;
        chatPeer = null;
        groupId = null;
        workspaceOpen = true;
        openAnim = 8;
        scrollThreadPosts = 99999;
        initGui();
    }

    private int dockListTop() {
        int tabsT = SocialTheme.DOCK_TOP_H;
        if (dockPanel == SocialUi.DockPanel.PUBLIC) {
            return tabsT + SocialTheme.DOCK_TABS_H + 6;
        }
        int searchT = tabsT + SocialTheme.DOCK_TABS_H - 4;
        return searchT + SocialTheme.DOCK_SEARCH_H + 4;
    }

    private boolean handleFriendClick(int mx, int my) {
        int listTop = dockListTop();
        int l = 12;
        int r = SocialTheme.DOCK_W - 12;
        int rowH = SocialTheme.DOCK_ROW_H;
        // Section label "Online — N" sits at listTop, so rows start 14px below.
        int y = listTop + 14 - scrollFriends;
        for (String f : online) {
            if (my >= y && my < y + rowH && mx >= l && mx < r) {
                openFriend(f);
                return true;
            }
            y += rowH;
        }
        // Spacing gutter + "Offline — N" label.
        y += 6 + 14;
        for (String f : offline) {
            if (my >= y && my < y + rowH && mx >= l && mx < r) {
                openFriend(f);
                return true;
            }
            y += rowH;
        }
        if (!nonFriendChats.isEmpty()) {
            y += 6 + 14;
            for (String peer : nonFriendChats) {
                if (my >= y && my < y + rowH && mx >= l && mx < r) {
                    openFriend(peer);
                    return true;
                }
                y += rowH;
            }
        }
        return false;
    }

    private boolean handleGroupClick(int mx, int my) {
        List<GomodSocialTempGroups.Group> groups = GomodSocialTempGroups.sortedGroups();
        int listTop = dockListTop() + SocialTheme.GROUP_HINT_H + 6;
        int l = 12;
        int r = SocialTheme.DOCK_W - 12;
        int rowH = SocialTheme.DOCK_ROW_H;
        int y = listTop - scrollGroups;
        for (GomodSocialTempGroups.Group g : groups) {
            if (my >= y && my < y + rowH && mx >= l && mx < r) {
                openGroup(g.id);
                return true;
            }
            y += rowH;
        }
        return false;
    }

    private boolean handleThreadDockClick(int mx, int my) {
        List<GomodSocialForums.ThreadEntry> threads = GomodSocialForums.sortedThreadEntries();
        int listTop = dockListTop();
        int l = 12;
        int r = SocialTheme.DOCK_W - 12;
        int y = listTop - scrollThreads;
        for (GomodSocialForums.ThreadEntry entry : threads) {
            if (my >= y && my < y + SocialTheme.FORUM_ROW_H && mx >= l && mx < r) {
                if (entry != null && entry.thread != null) {
                    openThread(entry.forumId, entry.thread.id);
                }
                return true;
            }
            y += SocialTheme.FORUM_ROW_H;
        }
        return false;
    }

    private boolean handleRequestClicks(int mx, int my) {
        int y = chatT + 12 - scrollRequests;
        if (!incoming.isEmpty()) {
            y += 14;
        }
        for (String name : incoming) {
            if (my >= y && my < y + 54) {
                int r = chatR - 12;
                if (SocialUi.inRect(mx, my, r - 152, y + 28, 70, 20)) {
                    acceptIncoming(name);
                } else if (SocialUi.inRect(mx, my, r - 78, y + 28, 70, 20)) {
                    declineIncoming(name);
                }
                return true;
            }
            y += 62;
        }
        return false;
    }

    private void acceptIncoming(String from) {
        GomodSocialRequests.removeIncoming(from);
        GomodSocialRequests.cancelOutgoing(from);
        ConfigManager.addGomodFriend(from);
        GomodFriendRelay.postFriendAccept(from);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Accepted " + from);
        reloadData();
    }

    private void declineIncoming(String from) {
        GomodSocialRequests.removeIncoming(from);
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                + "Declined " + from + "'s friend request.");
        reloadData();
    }

    private boolean handlePrivacyClicks(int mx, int my) {
        if (!SocialUi.inRect(mx, my, chatL + 1, chatT + 1,
                Math.max(0, chatR - chatL - 2), Math.max(0, chatB - chatT - 2))) {
            return false;
        }
        int y = chatT + 16 + 18 - scrollPrivacy;
        if (toggleSwitchHit(mx, my, y)) {
            ConfigManager.setAcceptIncomingFriendScreenshots(!ConfigManager.isAcceptIncomingFriendScreenshots());
            SocialUi.playButtonClickSound(mc);
            return true;
        }
        y += 52;
        if (toggleSwitchHit(mx, my, y)) {
            ConfigManager.setAcceptIncomingShareInv(!ConfigManager.isAcceptIncomingShareInv());
            SocialUi.playButtonClickSound(mc);
            return true;
        }
        y += 52;
        if (toggleSwitchHit(mx, my, y)) {
            ConfigManager.setAcceptIncomingNonFriendMessages(
                    !ConfigManager.isAcceptIncomingNonFriendMessages());
            SocialUi.playButtonClickSound(mc);
            return true;
        }
        y += 52;
        if (toggleSwitchHit(mx, my, y)) {
            ConfigManager.setAutoAcceptIncomingCapes(!ConfigManager.isAutoAcceptIncomingCapes());
            SocialUi.playButtonClickSound(mc);
            return true;
        }
        y += 52; // temp group row (toggle not wired yet)
        y += 52;
        if (toggleSwitchHit(mx, my, y)) {
            GomodSocialStore.setAppearOffline(!GomodSocialStore.isAppearOffline());
            SocialUi.playButtonClickSound(mc);
            return true;
        }
        y += 52;
        if (toggleSwitchHit(mx, my, y)) {
            ConfigManager.setPublicMessagePopupsEnabled(!ConfigManager.isPublicMessagePopupsEnabled());
            SocialUi.playButtonClickSound(mc);
            return true;
        }
        y += 52;
        if (toggleSwitchHit(mx, my, y)) {
            ConfigManager.setSocialMsgIconHudEnabled(!ConfigManager.isSocialMsgIconHudEnabled());
            SocialUi.playButtonClickSound(mc);
            return true;
        }
        return false;
    }

    private boolean toggleSwitchHit(int mx, int my, int rowY) {
        int sw = SocialUi.toggleSwitchWidth();
        int sh = SocialUi.toggleSwitchHeight();
        int sx = chatR - 16 - sw - 14;
        int sy = rowY + (44 - sh) / 2;
        return SocialUi.inRect(mx, my, sx, sy, sw, sh);
    }

    private boolean composerCanType() {
        return modalKind == 0 && workspaceOpen
                && (workspacePanel == 0 || threadId != null)
                && (publicChatMode || chatPeer != null || groupId != null || threadId != null);
    }

    private void sendMessage() {
        if (threadId != null && forumId != null) {
            final String author = GomodSocialPresence.sessionPlayerName(mc);
            if (author.isEmpty()) {
                return;
            }
            final String t = messageField.getText().trim();
            if (t.isEmpty()) {
                return;
            }
            final String fid = forumId;
            final String tid = threadId;
            messageField.setText("");
            GomodSocialForumRelay.postToThreadAsync(fid, tid, t, new GomodSocialForumRelay.SimpleCallback() {
                @Override
                public void onResult(boolean ok, String error) {
                    if (ok) {
                        GomodSocialForums.addPost(fid, tid, author, t);
                        scrollThreadPosts = 99999;
                    } else if (GomodSocialForumRelay.isRelayOutdatedError(error)) {
                        GomodSocialForums.addPost(fid, tid, author, t);
                        scrollThreadPosts = 99999;
                        GomodSocialForumRelay.notifyRelayError(error);
                    } else {
                        GomodSocialForumRelay.notifyRelayError(error);
                    }
                }
            });
            return;
        }
        if (publicChatMode) {
            String t = messageField.getText().trim();
            if (t.isEmpty()) {
                return;
            }
            GomodSocialPublicChat.send(t);
            messageField.setText("");
            publicMentionAssist.clear();
            scrollChat = 99999;
            return;
        }
        if (groupMode && groupId != null) {
            GomodSocialTempGroups.Group g = GomodSocialTempGroups.get(groupId);
            String author = GomodSocialPresence.sessionPlayerName(mc);
            if (g == null || author.isEmpty()) {
                return;
            }
            String t = messageField.getText().trim();
            if (t.isEmpty()) {
                return;
            }
            GomodSocialTempGroups.appendMessage(g, author, t, "text");
            messageField.setText("");
            scrollChat = 99999;
            return;
        }
        if (chatPeer == null) {
            return;
        }
        if (pendingShot != null) {
            GomodSocialChat.sendScreenshotToFriend(chatPeer, pendingShot);
            pendingShot = null;
        }
        String t = messageField.getText().trim();
        if (!t.isEmpty()) {
            GomodSocialChat.sendToFriend(chatPeer, t);
            messageField.setText("");
        }
        scrollChat = 99999;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == ID_SEND) {
            sendMessage();
        } else if (button.id == ID_MODAL_CANCEL) {
            SocialUi.playButtonClickSound(mc);
            modalKind = 0;
            modalGroupAddTargetId = null;
            modalCapeDeleteId = null;
            modalScreenshotDeleteFile = null;
            modalScreenshotActionFile = null;
            modalPublicPlayer = null;
            initGui();
        } else if (button.id == ID_MODAL_OK) {
            SocialUi.playButtonClickSound(mc);
            if (modalKind == 5) {
                removeCurrentFriend();
                modalKind = 0;
                modalGroupAddTargetId = null;
                modalCapeDeleteId = null;
                modalScreenshotDeleteFile = null;
                modalScreenshotActionFile = null;
                initGui();
                return;
            }
            if (modalKind == 6) {
                deleteCapeHistoryEntry();
                modalKind = 0;
                modalGroupAddTargetId = null;
                modalCapeDeleteId = null;
                modalScreenshotDeleteFile = null;
                modalScreenshotActionFile = null;
                initGui();
                return;
            }
            if (modalKind == 7) {
                deleteSelectedScreenshot();
                modalKind = 0;
                modalGroupAddTargetId = null;
                modalCapeDeleteId = null;
                modalScreenshotDeleteFile = null;
                modalScreenshotActionFile = null;
                initGui();
                return;
            }
            String text = modalField.getText().trim();
            if (modalKind == 1) {
                if (!text.isEmpty()) {
                    GomodFriendRelay.postFriendRequestAddAsync(mc, text, FriendAddSource.FRIENDS_GUI);
                }
            } else if (modalKind == 2) {
                if (!text.isEmpty()) {
                    List<String> requested = GomodSocialTempGroups.parseMemberNames(
                            modalMembersField == null ? "" : modalMembersField.getText());
                    GomodSocialTempGroups.Group g = GomodSocialTempGroups.create(text, requested);
                    if (g.members.size() <= 1 && !requested.isEmpty()) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                                + "No gomod friends matched — add friends first, then invite them.");
                    }
                    openGroup(g.id);
                }
            } else if (modalKind == 4) {
                GomodSocialTempGroups.Group g = GomodSocialTempGroups.get(modalGroupAddTargetId);
                List<String> requested = GomodSocialTempGroups.parseMemberNames(
                        modalMembersField == null ? "" : modalMembersField.getText());
                if (g != null && !requested.isEmpty()) {
                    int added = GomodSocialTempGroups.addMembers(g, requested);
                    if (added > 0) {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Added "
                                + added + " member" + (added == 1 ? "" : "s") + " to "
                                + EnumChatFormatting.AQUA + g.name + EnumChatFormatting.GREEN + ".");
                    } else {
                        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                                + "No new members added — names must be gomod friends not already in the group.");
                    }
                }
            } else if (modalKind == 3 && !text.isEmpty()) {
                final String title = text;
                final String author = GomodSocialPresence.sessionPlayerName(mc);
                GomodSocialForumRelay.createThreadAsync(title, new GomodSocialForumRelay.CreateCallback() {
                    @Override
                    public void onResult(boolean ok, String forumIdOut, String threadIdOut, String error) {
                        String fid = forumIdOut == null || forumIdOut.isEmpty()
                                ? GomodSocialForums.DEFAULT_FORUM_ID : forumIdOut;
                        if (ok && threadIdOut != null && !threadIdOut.isEmpty()) {
                            GomodSocialForums.addThreadFromService(fid, threadIdOut, title, author);
                            openThread(fid, threadIdOut);
                        } else if (GomodSocialForumRelay.isRelayOutdatedError(error)) {
                            GomodSocialForums.Forum def = GomodSocialForums.ensureDefaultForum();
                            GomodSocialForums.Thread th = GomodSocialForums.addThread(def.id, title, author);
                            if (th != null) {
                                openThread(def.id, th.id);
                            }
                            GomodSocialForumRelay.notifyRelayError(error);
                        } else {
                            GomodSocialForumRelay.notifyRelayError(error);
                        }
                    }
                });
            }
            modalKind = 0;
            modalGroupAddTargetId = null;
            modalCapeDeleteId = null;
            modalScreenshotActionFile = null;
            initGui();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            if (modalKind != 0) {
                actionPerformed(modalCancel);
                return;
            }
            if (closeOnFirstEscape) {
                if (parent != null) {
                    mc.displayGuiScreen(parent);
                } else {
                    GomodMenuTransitions.closeToGame(mc);
                }
                return;
            }
            if (workspaceOpen && threadId != null) {
                threadId = null;
                forumId = null;
                initGui();
                return;
            }
            if (workspaceOpen) {
                if (publicChatMode) {
                    leavePublicChatView();
                }
                workspaceOpen = false;
                openAnim = 0;
                threadId = null;
                forumId = null;
                initGui();
                return;
            }
            if (parent != null) {
                mc.displayGuiScreen(parent);
            } else {
                GomodMenuTransitions.closeToGame(mc);
            }
            return;
        }
        if (modalKind != 0) {
                if (modalKind == 5 || modalKind == 6 || modalKind == 7) {
                    if (keyCode == 28 || keyCode == 156) {
                        actionPerformed(modalOk);
                        return;
                    }
            } else if (modalKind == 8 || modalKind == 9) {
                return;
            } else if (modalKind == 4 || (modalKind == 2 && modalMembersField.isFocused())) {
                if (modalMembersField.textboxKeyTyped(typedChar, keyCode)) {
                    return;
                }
            } else if (modalField.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
            if (keyCode == 28 || keyCode == 156) {
                actionPerformed(modalOk);
                return;
            }
        }
        if (dockPanel == SocialUi.DockPanel.FRIENDS
                && searchField.field.textboxKeyTyped(typedChar, keyCode)) {
            filterFriends();
            return;
        }
        if (publicChatMode && messageField.isFocused() && composerCanType()) {
            if ((keyCode == 28 || keyCode == 156) && publicMentionAssist.handleEnter(messageField)) {
                return;
            }
            if (keyCode == 15 && publicMentionAssist.handleTab(messageField)) {
                return;
            }
            if (keyCode == 200 && publicMentionAssist.handleArrow(messageField, true)) {
                return;
            }
            if (keyCode == 208 && publicMentionAssist.handleArrow(messageField, false)) {
                return;
            }
        }
        if (workspaceOpen && (workspacePanel == 0 || threadId != null) && composerCanType()
                && (keyCode == 28 || keyCode == 156)) {
            sendMessage();
            return;
        }
        if (workspaceOpen && (workspacePanel == 0 || threadId != null) && messageField.textboxKeyTyped(typedChar, keyCode)) {
            if (publicChatMode) {
                publicMentionAssist.refresh(messageField);
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Mouse.getEventDWheel();
        if (d == 0) {
            return;
        }
        int delta = d > 0 ? -SocialTheme.DOCK_ROW_H * 2 : SocialTheme.DOCK_ROW_H * 2;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        int my = Mouse.getEventY() * height / mc.displayHeight;
        if (mx < SocialTheme.DOCK_W) {
            if (dockPanel == SocialUi.DockPanel.FRIENDS) {
                scrollFriends = SocialUi.clamp(scrollFriends + delta, 0, 9999);
            } else if (dockPanel == SocialUi.DockPanel.GROUPS) {
                scrollGroups = SocialUi.clamp(scrollGroups + delta, 0, 9999);
            } else {
                scrollThreads = SocialUi.clamp(scrollThreads + delta, 0, 9999);
            }
        } else if (workspaceOpen) {
            if (threadId != null) {
                scrollThreadPosts = SocialUi.clamp(scrollThreadPosts + delta, 0, 99999);
            } else if (workspacePanel == 0) {
                scrollChat = SocialUi.clamp(scrollChat + delta, 0, 99999);
            } else if (workspacePanel == 1) {
                scrollShots = SocialUi.clamp(scrollShots + delta, 0, 9999);
            } else if (workspacePanel == 2) {
                scrollCapes = SocialUi.clamp(scrollCapes + delta, 0, capesMaxScroll());
            } else if (workspacePanel == 3) {
                scrollRequests = SocialUi.clamp(scrollRequests + delta, 0, 9999);
            } else if (workspacePanel == 4) {
                scrollPrivacy = SocialUi.clamp(scrollPrivacy + delta, 0, privacyMaxScroll());
            }
        }
    }

    @Override
    public void updateScreen() {
        searchField.field.updateCursorCounter();
        messageField.updateCursorCounter();
        modalField.updateCursorCounter();
        if (modalMembersField != null) {
            modalMembersField.updateCursorCounter();
        }
    }

    @Override
    public void onGuiClosed() {
        LunarFitChatPreview.clear();
        for (ResourceLocation rl : thumbCache.values()) {
            if (rl != null && mc.getTextureManager() != null) {
                mc.getTextureManager().deleteTexture(rl);
            }
        }
        thumbCache.clear();
        thumbLoading.clear();
        thumbFailed.clear();
        super.onGuiClosed();
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return workspaceOpen || modalKind != 0
                || GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(searchField.field, messageField, modalField, modalMembersField);
    }

    public boolean shouldAllowCompactDockMovement() {
        return !workspaceOpen && modalKind == 0
                && !GomodMenuKeyboardCaptureGuards.anyTextFieldFocused(searchField.field, messageField, modalField, modalMembersField);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
