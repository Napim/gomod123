package me.napim.gomod.gui;

import com.google.gson.JsonObject;
import me.napim.gomod.Main;
import me.napim.gomod.features.GoStatsBlitzClient;
import me.napim.gomod.features.GoStatsLeaderboardClient;
import me.napim.gomod.features.GomodMenuSettings;
import me.napim.gomod.features.HypixelPlayerDataCache;
import me.napim.gomod.features.HypixelTabStatsRankCache;
import me.napim.gomod.features.KillCounter;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.TeamSpamManager;
import me.napim.gomod.features.TeamSpamManager.Player;
import me.napim.gomod.features.MojangUsernameResolver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.UUID;

/** Player detail screen shared by Recent Opponents and Teams Nick Finder. */
public final class TeamPlayerProfileGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private static final int ID_UPDATE = 1;
    private static final int ID_DELETE = 2;
    private static final int ID_CANCEL = 0;
    private static final int ID_SORT = 3;
    private static final int ID_ACTIVITY = 4;
    private static final int PANEL_BG = 0xF012181F;
    private static final int PANEL_BORDER = 0xFF2B3640;
    private static final int CARD_BG = 0xFF1B242D;
    private static final int TEXT = 0xFFEAF2FA;
    private static final int MUTED = 0xFF91A1AF;
    private static final int GOLD = 0xFFFFC857;
    private static final int GREEN = 0xFF65D68A;
    private static final int RED = 0xFFFF6B78;
    // Keep each stat's familiar color, tuned for the dark profile surfaces.
    private static final int STAT_KILLS = 0xFF2AFF00;
    private static final int STAT_WINS = 0xFFFCFF00;
    private static final int STAT_DEATHS = 0xFFFF0000;
    private static final int STAT_GAMES = 0xFF65D68A;
    private static final int STAT_RATIO = 0xFFFFB30E;
    private static final int STAT_COINS = 0xFFFFB30E;
    private static final int LEVEL_I = 0xFF55FF55;
    private static final int LEVEL_II = 0xFF54FA55;
    private static final int LEVEL_III = 0xFF08A504;
    private static final int LEVEL_IV = 0xFF00A805;
    private static final int LEVEL_V = 0xFFFFFF56;
    private static final int LEVEL_VI = 0xFFFFFF56;
    private static final int LEVEL_VII = 0xFFFDA900;
    private static final int LEVEL_VIII = 0xFFF8A500;
    private static final int LEVEL_IX = 0xFFFF5556;
    private static final int LEVEL_X = 0xFFB00000;
    private static final int LEVEL_PRESTIGE = 0xFFFFAA00;
    private static final ResourceLocation PARTICLE_ATLAS = new ResourceLocation("textures/particle/particles.png");
    private static final ResourceLocation EXPERIENCE_ORB_TEXTURE =
            new ResourceLocation("textures/entity/experience_orb.png");
    private static final DecimalFormat NUMBER = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT));

    private final GuiScreen parent;
    private final Player player;
    private final String denickedNick;
    private final boolean recentOpponentProfile;
    private final boolean readOnlyProfile;
    private volatile UUID playerUuid;
    private EntityOtherPlayerMP previewPlayer;
    private EntityArrow previewFlameArrow;
    private GoStatsBlitzClient.Snapshot snapshot;
    private java.util.List<GoStatsBlitzClient.Kit> displayedKits = java.util.Collections.emptyList();
    private boolean recentKits;
    private boolean activityRequested;
    private boolean activityLoading;
    private long activityLoadedAt;
    private String activityError;
    private GuiButton kitSortButton;
    private GuiButton kitActivityButton;
    private String profileDisplayName;
    private String profileRankTag;
    private String profileUsernameColor;
    private volatile boolean loading;
    private volatile String loadError;
    private PlayerProfileLayout layout;
    private int layoutScreenWidth = -1;
    private int layoutScreenHeight = -1;
    private int statsPanelLeft;
    private int statsPanelTop;
    private int statsPanelRight;
    private int statsPanelBottom;
    private int previewPanelLeft;
    private int previewPanelTop;
    private int previewPanelRight;
    private int previewPanelBottom;
    private int scrollOffset;
    private float renderedScrollOffset;
    private int maxScroll;
    private int statsScrollbarTrackLeft;
    private int statsScrollbarTrackTop;
    private int statsScrollbarTrackRight;
    private int statsScrollbarTrackBottom;
    private int statsScrollbarThumbTop;
    private int statsScrollbarThumbBottom;
    private boolean draggingStatsScrollbar;
    private int statsScrollbarGrabOffset;
    private boolean draggingStatsContent;
    private int lastStatsContentMouseY;
    private boolean draggingPreview;
    private int lastDragMouseX;
    private int lastDragMouseY;
    // 45 degrees presents the player's face in this preview; 155 degrees showed the back by default.
    private float previewYaw = 45.0F;
    private float previewDisplayYaw = 45.0F;
    // Start slightly above eye level so the player is still facing right/front,
    // but the preview also shows the top of the head instead of opening perfectly flat.
    private float previewPitch = -6.0F;
    private float previewDisplayPitch = -6.0F;
    private static final float PREVIEW_YAW_SMOOTH = 0.32F;
    private static final float PREVIEW_PITCH_SMOOTH = 0.32F;

    public TeamPlayerProfileGUI(GuiScreen parent, Player player) {
        this(parent, player, null, false);
    }

    public TeamPlayerProfileGUI(GuiScreen parent, Player player, String denickedNick) {
        this(parent, player, denickedNick, false);
    }

    public TeamPlayerProfileGUI(GuiScreen parent, Player player, String denickedNick,
                                boolean recentOpponentProfile) {
        this(parent, player, denickedNick, recentOpponentProfile, false);
    }

    public TeamPlayerProfileGUI(GuiScreen parent, Player player, String denickedNick,
                                boolean recentOpponentProfile, boolean readOnlyProfile) {
        this.parent = parent;
        this.player = player;
        this.denickedNick = denickedNick == null || denickedNick.trim().isEmpty() ? null : denickedNick.trim();
        this.recentOpponentProfile = recentOpponentProfile;
        this.readOnlyProfile = readOnlyProfile;
        this.playerUuid = parseUuid(player == null ? null : player.getUuid());
        resolveProfilePlayerIdentity();
    }

    /** Uses the leaderboard row's authoritative rank fields when opening its profile. */
    public TeamPlayerProfileGUI(GuiScreen parent, Player player, String denickedNick,
                                boolean recentOpponentProfile, boolean readOnlyProfile,
                                GoStatsLeaderboardClient.Player leaderboardPlayer) {
        this(parent, player, denickedNick, recentOpponentProfile, readOnlyProfile);
        if (leaderboardPlayer == null || leaderboardPlayer.rankDisplay == null
                || leaderboardPlayer.rankDisplay.trim().isEmpty()) {
            return;
        }
        profileRankTag = HypixelTabStatsRankCache.formatOfficialRankTag(
                leaderboardPlayer.rankDisplay, leaderboardPlayer.rankPlusColor,
                leaderboardPlayer.monthlyRankColor);
        profileUsernameColor = HypixelTabStatsRankCache.formatOfficialUsernameColor(
                leaderboardPlayer.rankDisplay, leaderboardPlayer.monthlyRankColor);
    }

    @Override
    public void initGui() {
        calculateLayout();
        buttonList.clear();
        kitSortButton = new ProfileActionButton(ID_SORT, 0, 0, 68, "Lifetime", GREEN);
        kitSortButton.visible = false;
        buttonList.add(kitSortButton);
        kitActivityButton = new ProfileActionButton(ID_ACTIVITY, 0, 0, 68, "Activity", GREEN);
        kitActivityButton.visible = false;
        buttonList.add(kitActivityButton);
        int buttonY = GuiHeader.backButtonY(height, 20);
        int buttonWidth = 100;
        if (recentOpponentProfile || readOnlyProfile) {
            String backLabel = readOnlyProfile ? "Back to Leaderboard" : "Back";
            buttonList.add(new GuiButton(ID_CANCEL, width / 2 - 75, buttonY, 150, 20,
                    EnumChatFormatting.GRAY + backLabel));
        } else {
            int gap = 10;
            int total = buttonWidth * 3 + gap * 2;
            int startX = width / 2 - total / 2;
            buttonList.add(new ProfileActionButton(ID_UPDATE, startX, buttonY, buttonWidth, "Update", GREEN));
            buttonList.add(new ProfileActionButton(ID_DELETE, startX + buttonWidth + gap, buttonY,
                    buttonWidth, "Delete", RED));
            buttonList.add(new ProfileActionButton(ID_CANCEL, startX + (buttonWidth + gap) * 2, buttonY,
                    buttonWidth, "Cancel", MUTED));
        }

        if (mc.theWorld != null) {
            previewPlayer = MountPreviewHelper.createCapePreviewPlayer(mc.theWorld, mc, null,
                    player == null ? "Player" : player.getUsername(), playerUuid);
            previewFlameArrow = new EntityArrow(mc.theWorld);
            previewFlameArrow.setFire(10);
            previewFlameArrow.rotationYaw = 0.0F;
            previewFlameArrow.prevRotationYaw = 0.0F;
            previewFlameArrow.rotationPitch = 0.0F;
            previewFlameArrow.prevRotationPitch = 0.0F;
        }
        beginStatsFetch();
    }

    private void calculateLayout() {
        if (layout != null && layoutScreenWidth == width && layoutScreenHeight == height) return;
        layoutScreenWidth = width;
        layoutScreenHeight = height;
        // Finder and Recent Opponents share the same compact profile presentation.
        layout = new PlayerProfileLayout(width, height, GuiHeader.backButtonY(height, 20), true);
        previewPanelLeft = layout.left;
        previewPanelTop = layout.top;
        previewPanelRight = layout.previewRight;
        previewPanelBottom = layout.bottom;
        statsPanelLeft = layout.statsLeft;
        statsPanelTop = layout.top;
        statsPanelRight = layout.right;
        statsPanelBottom = layout.bottom;
    }

    private void beginStatsFetch() {
        loading = true;
        loadError = null;
        Thread worker = new Thread(() -> {
            try {
                UUID resolvedUuid = playerUuid;
                if (denickedNick != null && player != null && player.getUsername() != null
                        && denickedNick.equalsIgnoreCase(player.getUsername())) {
                    // Keep the Hypixel-generated session UUID for the model preview, but never query stats with it.
                    resolvedUuid = null;
                }
                if (resolvedUuid == null && player != null && player.getUsername() != null) {
                    MojangUsernameResolver.MojangProfile profile =
                            MojangUsernameResolver.resolveProfile(player.getUsername());
                    resolvedUuid = parseUuid(profile == null ? null : profile.id);
                }
                if (resolvedUuid == null) {
                    throw new IllegalStateException("Could not resolve this player's Mojang UUID.");
                }
                if (player != null && player.getUsername() != null
                        && (denickedNick == null || !denickedNick.equalsIgnoreCase(player.getUsername()))) {
                    JsonObject locallyCachedPlayer = HypixelPlayerDataCache.getAny(player.getUsername());
                    if (locallyCachedPlayer != null) {
                        GoStatsBlitzClient.cacheHypixelPlayerData(resolvedUuid.toString(), locallyCachedPlayer);
                    }
                }
                GoStatsBlitzClient.Snapshot result = GoStatsBlitzClient.fetch(resolvedUuid.toString());
                final UUID finalUuid = resolvedUuid;
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    playerUuid = finalUuid;
                    snapshot = result;
                    updateDisplayedKits();
                    loading = false;
                });
            } catch (Throwable error) {
                String message = error.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = "request failed";
                }
                final String finalMessage = message;
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    loadError = finalMessage;
                    loading = false;
                });
            }
        }, "gomod-player-profile-stats");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        calculateLayout();
        updateScrollForDraw(mouseY);

        drawPreviewPanel(mouseX, mouseY, partialTicks);
        drawStatsPanel();
        positionKitSortButton();
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawKitActivityTooltip(mouseX, mouseY);
    }

    private void positionKitSortButton() {
        if (kitSortButton == null) return;
        int y = layout.contentTop - visibleScrollOffset() + profileKitsHeadingY() - 5;
        kitSortButton.xPosition = layout.contentRight - 140;
        kitSortButton.yPosition = y;
        kitSortButton.visible = snapshot != null && !loading && y >= layout.contentTop
                && y + 20 <= layout.contentBottom && layout.contentRight - layout.contentLeft >= 174;
        kitSortButton.enabled = true;
        kitActivityButton.xPosition = layout.contentRight - 68;
        kitActivityButton.yPosition = y;
        kitActivityButton.visible = kitSortButton.visible;
        kitActivityButton.enabled = !activityLoading;
    }

    private void updateDisplayedKits() {
        displayedKits = snapshot == null ? new java.util.ArrayList<GoStatsBlitzClient.Kit>()
                : GoStatsBlitzClient.sortedKits(snapshot.kits, recentKits);
        if (recentKits) displayedKits.removeIf(kit -> kit.lastActivityAt <= 0L);
    }

    private int profileKitsHeadingY() { return recentKits ? activityKitsHeadingY() : layout.kitsHeadingY; }
    private int activityCoreHeadingY() { return 32; }
    private int activityCoreY() { return activityCoreHeadingY() + 12; }
    private int activityModeHeadingY() { return activityCoreY() + PlayerProfileLayout.SUMMARY_HEIGHT + 8; }
    private int activityModeRowsY() { return activityModeHeadingY() + 12; }
    private int activityExtrasHeadingY() { return activityModeRowsY() + 2 * PlayerProfileLayout.DETAIL_PITCH + 5; }
    private int activityExtrasRowsY() { return activityExtrasHeadingY() + 12; }
    private int activityKitsHeadingY() { return activityExtrasRowsY() + 2 * PlayerProfileLayout.DETAIL_PITCH + 5; }
    private int kitRowsY() { return recentKits ? activityKitsHeadingY() + 32 : layout.kitsY; }
    private int kitRowHeight() { return recentKits ? 82 : layout.kitHeight; }
    private int kitRowPitch() { return recentKits ? 88 : layout.kitPitch; }
    private int kitFooterY() { return kitRowsY() + Math.max(36, displayedKits.size() * kitRowPitch()) + 10; }
    private int profileFooterY() { return kitFooterY(); }

    private int profileContentBottomY() {
        int kitCount = displayedKits.size();
        if (kitCount <= 0) {
            // The empty-state text is the only content below the Kits heading.
            return kitRowsY() + (recentKits ? 34 : 20);
        }
        int lastKitRowBottom = kitRowsY() + (kitCount - 1) * kitRowPitch() + kitRowHeight();
        if (recentKits) {
            // Activity has a footer below its cards; Lifetime has no footer.
            return Math.max(lastKitRowBottom, profileFooterY() + 9);
        }
        return lastKitRowBottom;
    }

    private void drawKitActivityTooltip(int mouseX, int mouseY) {
        if (!recentKits || snapshot == null || mouseX < layout.contentLeft
                || mouseX > layout.contentRight || mouseY < layout.contentTop || mouseY > layout.contentBottom) return;
        int top = layout.contentTop - visibleScrollOffset() + kitRowsY();
        int index = (mouseY - top) / kitRowPitch();
        if (mouseY < top || index < 0 || index >= displayedKits.size()
                || mouseY >= top + index * kitRowPitch() + kitRowHeight()) return;
        GoStatsBlitzClient.Kit kit = displayedKits.get(index);
        java.util.List<String> text = new java.util.ArrayList<String>();
        text.add("Gains per latest scan");
        if (kit.lastActivityAt > 0) {
            text.add(EnumChatFormatting.GRAY + "Older saved check: " + EnumChatFormatting.WHITE
                    + activityTime(kit.activityAfter));
            text.add(EnumChatFormatting.GRAY + "Latest saved check: " + EnumChatFormatting.WHITE
                    + activityTime(kit.activityBefore));
            String span = activitySpan(kit.activityAfter, kit.activityBefore);
            if (!span.isEmpty()) text.add(EnumChatFormatting.GRAY + "Time between checks: "
                    + EnumChatFormatting.WHITE + span);
            if (kit.activity.timePlayedSeconds >= 0)
                text.add("Playtime gained: " + formatDuration(kit.activity.timePlayedSeconds));
            text.add(EnumChatFormatting.DARK_GRAY + "Lifetime: " + number(kit.kills) + " kills, "
                    + number(kit.wins) + " wins");
        } else if (activityError != null) {
            text.add(EnumChatFormatting.GRAY + "Shared history is unavailable right now");
        }
        drawHoveringText(text, mouseX, mouseY);
    }

    private String activityTime(String iso) {
        try {
            java.time.Instant instant = parseActivityInstant(iso);
            if (instant == null) return "Unknown";
            return instant.atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm:ss", Locale.ROOT));
        } catch (Exception ignored) { return "Unknown"; }
    }

    private java.time.Instant parseActivityInstant(String iso) {
        if (iso == null || iso.trim().isEmpty()) return null;
        try {
            return java.time.OffsetDateTime.parse(iso).toInstant();
        } catch (Exception ignored) {
            try { return java.time.Instant.parse(iso); }
            catch (Exception ignoredAgain) { return null; }
        }
    }

    private String activitySpan(String olderCheck, String latestCheck) {
        java.time.Instant older = parseActivityInstant(olderCheck);
        java.time.Instant latest = parseActivityInstant(latestCheck);
        if (older == null || latest == null || latest.isBefore(older)) return "";
        return formatDuration(java.time.Duration.between(older, latest).getSeconds());
    }

    private void requestKitActivity() {
        if (snapshot == null || activityLoading || (activityRequested
                && System.currentTimeMillis() - activityLoadedAt < 60_000L)) return;
        activityRequested = true;
        activityLoading = true;
        final GoStatsBlitzClient.Snapshot current = snapshot;
        Thread worker = new Thread(() -> {
            try {
                GoStatsBlitzClient.Snapshot enriched = GoStatsBlitzClient.withSharedActivity(current);
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    if (snapshot == current) {
                        snapshot = enriched;
                        updateDisplayedKits();
                        activityLoadedAt = System.currentTimeMillis();
                        activityError = null;
                    }
                    activityLoading = false;
                });
            } catch (Exception ignored) {
                Minecraft.getMinecraft().addScheduledTask(() -> {
                    activityError = "unavailable";
                    activityLoading = false;
                    activityRequested = false;
                });
            }
        }, "gomod-kit-activity");
        worker.setDaemon(true);
        worker.start();
    }

    private void drawPreviewPanel(int mouseX, int mouseY, float partialTicks) {
        drawPanel(previewPanelLeft, previewPanelTop, previewPanelRight, previewPanelBottom);
        drawFitted(formatProfilePlayerName(), previewPanelLeft + 12, previewPanelTop + 12,
                previewPanelRight - previewPanelLeft - 24, 0.95F, TEXT, 0);
        SocialDraw.roundedRect(previewPanelLeft + 7, layout.modelTop,
                previewPanelRight - 7, layout.modelBottom + 3, 5, 0xFF0E151B);
        int centerX = (previewPanelLeft + previewPanelRight) / 2;
        long effectTick = System.currentTimeMillis() / 50L;
        SocialDraw.enableScissor(previewPanelLeft + 8, layout.modelTop,
                previewPanelRight - 8, layout.modelBottom + 3);
        try {
            if (previewPlayer != null) {
                float previewYaw = tickPreviewDisplayYaw(partialTicks);
                syncKillEffectHeldItem();
                if (isLevelUpEffect(previewKillEffect())) {
                    drawLevelUpOrbs(centerX, false, effectTick);
                }
                MountPreviewHelper.updateCapePreviewPlayer(mc, previewPlayer, null,
                        player == null ? "Player" : player.getUsername(), playerUuid);
                MountPreviewHelper.renderCapePreviewPlayer(mc, previewPlayer, centerX, layout.modelBaseY,
                        layout.modelScale, 1.0F, previewYaw,
                        tickPreviewDisplayPitch(partialTicks), 1.0F);
                drawKillEffectPreview(centerX, effectTick, previewYaw);
            } else {
                drawFitted("Preview unavailable", centerX,
                        (layout.modelTop + layout.modelBottom) / 2, previewPanelRight - previewPanelLeft - 24,
                        0.8F, MUTED, 1);
            }
        } finally {
            SocialDraw.disableScissor();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
        drawFitted("Drag to rotate", centerX,
                layout.dragHintY, previewPanelRight - previewPanelLeft - 20,
                1.0F, MUTED, 1);
        drawCosmeticSection();
    }

    private String previewKillEffect() {
        if (snapshot == null || snapshot.cosmetics == null || snapshot.cosmetics.killEffect == null) return "";
        return snapshot.cosmetics.killEffect.trim().replace(' ', '_').replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }

    private boolean isFlamingArrowsEffect(String effect) {
        return "FLAMING_ARROWS".equals(effect) || "FLAME_ARROW".equals(effect)
                || "FLAME".equals(effect) || "RAPID_FIRE".equals(effect);
    }

    private boolean isLevelUpEffect(String effect) {
        return "LEVEL_UP".equals(effect) || "LEVELUP".equals(effect);
    }

    private void syncKillEffectHeldItem() {
        if (previewPlayer == null) return;
        String effect = previewKillEffect();
        boolean shouldHoldBow = isFlamingArrowsEffect(effect);
        boolean shouldHoldRottenFlesh = "GRAVEDIGGER".equals(effect);
        ItemStack held = previewPlayer.inventory.getStackInSlot(0);
        if (shouldHoldBow || shouldHoldRottenFlesh) {
            net.minecraft.item.Item desired = shouldHoldBow ? Items.bow : Items.rotten_flesh;
            if (held == null || held.getItem() != desired) {
                previewPlayer.inventory.setInventorySlotContents(0, new ItemStack(desired));
            }
            previewPlayer.inventory.currentItem = 0;
            if (previewPlayer.isUsingItem()) previewPlayer.clearItemInUse();
        } else {
            if (previewPlayer.isUsingItem() && previewPlayer.getItemInUse() != null
                    && (previewPlayer.getItemInUse().getItem() == Items.bow
                    || previewPlayer.getItemInUse().getItem() == Items.rotten_flesh)) {
                previewPlayer.clearItemInUse();
            }
            if (held != null && (held.getItem() == Items.bow
                    || held.getItem() == Items.rotten_flesh)) {
                previewPlayer.inventory.setInventorySlotContents(0, null);
            }
        }
    }

    /** Uses the texture pack's native particle sprites and entity renderers in the model preview. */
    private void drawKillEffectPreview(int centerX, long tick, float playerPreviewYaw) {
        String effect = previewKillEffect();
        if (effect.isEmpty() || "RANDOM".equals(effect)) return;
        if (isLevelUpEffect(effect)) {
            drawLevelUpOrbs(centerX, true, tick);
            return;
        }

        boolean flameArrow = isFlamingArrowsEffect(effect);
        boolean regeneration = "REGENERATION".equals(effect);
        boolean potionEffect = "SPEED".equals(effect) || "RESISTANCE".equals(effect)
                || regeneration;
        if (!flameArrow && !potionEffect) return;

        if (flameArrow && previewFlameArrow != null) {
            int arrowX = centerX + 27;
            int arrowY = layout.modelBaseY + 8;
            MountPreviewHelper.renderEntityPreview(mc, previewFlameArrow, arrowX,
                    arrowY, 23, 1.0F, playerPreviewYaw, false);
            for (int i = 0; i < 3; i++) {
                int flameAge = (int) Math.floorMod(tick + i * 11L, 32L);
                int particleIndex = 48 + (7 - flameAge * 8 / 32);
                int flameX = arrowX + (i - 1) * 4;
                int flameY = arrowY - 6 - flameAge * 12 / 32;
                drawParticleAtlasSprite(particleIndex, flameX - 5, flameY - 5,
                        10, 0xFFFFFFFF, 0.9F);
            }
            return;
        }

        int count = regeneration ? 6 : 8;
        int modelHeight = Math.max(20, layout.modelBottom - layout.modelTop - 14);
        int sideRadius = Math.max(24, Math.min(38, (previewPanelRight - previewPanelLeft) / 2 - 14));
        int tint = "SPEED".equals(effect) ? 0xFF7CAFC6
                : "RESISTANCE".equals(effect) ? 0xFF99453A
                : regeneration ? 0xFF000000 | (Potion.regeneration.getLiquidColor() & 0xFFFFFF)
                : 0xFFFFFFFF;
        for (int i = 0; i < count; i++) {
            int age = (int) Math.floorMod(tick + i * 6L, 32L);
            int y = layout.modelBottom - 8 - age * modelHeight / 32;
            double angle = i * (Math.PI * 2.0D / count) + age * 0.055D;
            int x = centerX + (int) Math.round(Math.cos(angle) * sideRadius);
            int particleIndex;
            if (flameArrow) {
                particleIndex = 48 + (7 - age * 8 / 32);
            } else {
                // Regeneration, Speed, and Resistance use the native potion spell sprites.
                particleIndex = 128 + (7 - age * 8 / 32);
            }
            int particleSize = flameArrow ? 11 : 8;
            drawParticleAtlasSprite(particleIndex, x - particleSize / 2, y - particleSize / 2,
                    particleSize, tint, 0.88F);
        }
    }

    /** Draws the active resource pack's XP-orb sprites without changing player-render state. */
    private void drawLevelUpOrbs(int centerX, boolean foreground, long tick) {
        int orbCount = 3;
        int modelScale = Math.max(12, layout.modelScale);
        int radius = Math.max(18, Math.min(modelScale * 52 / 100,
                (previewPanelRight - previewPanelLeft) / 2 - 20));
        int orbitHeight = Math.max(14, Math.min(modelScale * 58 / 100,
                (layout.modelBottom - layout.modelTop) / 3));
        int orbitCenterY = layout.modelBaseY - Math.round(modelScale * 0.98F);

        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean lightingEnabled = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean alphaEnabled = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean cullEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        boolean textureEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        int boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        float alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        int blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

        GlStateManager.pushMatrix();
        try {
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.003921569F);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            mc.getTextureManager().bindTexture(EXPERIENCE_ORB_TEXTURE);

            WorldRenderer renderer = Tessellator.getInstance().getWorldRenderer();
            renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            for (int i = 0; i < orbCount; i++) {
                double angle = tick * 0.045D + i * (Math.PI * 2.0D / orbCount);
                boolean orbInFront = Math.sin(angle) > 0.0D;
                if (orbInFront != foreground) continue;

                long orbTick = tick + i * 9L;
                int xpValue = i == 0 ? 1 : i == 1 ? 3 : 5;
                int textureIndex = experienceOrbTextureIndex(xpValue);
                float u = (textureIndex % 4) * 0.25F;
                float v = (textureIndex / 4) * 0.25F;
                float u2 = u + 0.25F;
                float v2 = v + 0.25F;
                double phase = orbTick * 0.5D;
                int red = Math.max(0, Math.min(255,
                        (int) ((Math.sin(phase) + 1.0D) * 0.5D * 255.0D)));
                int blue = Math.max(0, Math.min(255,
                        (int) ((Math.sin(phase + 4.1887903D) + 1.0D) * 0.1D * 255.0D)));
                int size = foreground ? Math.max(12, Math.min(18, modelScale / 4))
                        : Math.max(10, Math.min(15, modelScale / 5));
                int alpha = 128;
                int x = centerX + (int) Math.round(Math.cos(angle) * radius) - size / 2;
                int y = orbitCenterY + (int) Math.round(Math.sin(angle) * orbitHeight) - size / 2;
                renderer.pos(x, y + size, 0.0D).tex(u, v2).color(red, 255, blue, alpha).endVertex();
                renderer.pos(x + size, y + size, 0.0D).tex(u2, v2).color(red, 255, blue, alpha).endVertex();
                renderer.pos(x + size, y, 0.0D).tex(u2, v).color(red, 255, blue, alpha).endVertex();
                renderer.pos(x, y, 0.0D).tex(u, v).color(red, 255, blue, alpha).endVertex();
            }
            Tessellator.getInstance().draw();
        } finally {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.bindTexture(boundTexture);
            if (textureEnabled) GlStateManager.enableTexture2D(); else GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(activeTexture);
            GL11.glAlphaFunc(alphaFunc, alphaRef);
            if (alphaEnabled) GlStateManager.enableAlpha(); else GlStateManager.disableAlpha();
            if (depthEnabled) GlStateManager.enableDepth(); else GlStateManager.disableDepth();
            GlStateManager.depthFunc(depthFunc);
            GlStateManager.depthMask(depthMask);
            if (lightingEnabled) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
            if (cullEnabled) GlStateManager.enableCull(); else GlStateManager.disableCull();
            if (blendEnabled) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
            GlStateManager.tryBlendFuncSeparate(blendSrcRgb, blendDstRgb,
                    blendSrcAlpha, blendDstAlpha);
            GlStateManager.popMatrix();
        }
    }

    private int experienceOrbTextureIndex(int xpValue) {
        if (xpValue >= 2477) return 10;
        if (xpValue >= 1237) return 9;
        if (xpValue >= 617) return 8;
        if (xpValue >= 307) return 7;
        if (xpValue >= 149) return 6;
        if (xpValue >= 73) return 5;
        if (xpValue >= 37) return 4;
        if (xpValue >= 17) return 3;
        if (xpValue >= 7) return 2;
        if (xpValue >= 3) return 1;
        return 0;
    }

    private void drawParticleAtlasSprite(int particleIndex, int x, int y, int size,
                                         int tint, float opacity) {
        if (mc == null || mc.getTextureManager() == null || size <= 0) return;
        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean lightingEnabled = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        int index = Math.floorMod(particleIndex, 256);
        float u = (index % 16) / 16.0F;
        float v = (index / 16) / 16.0F;
        float u2 = u + 0.0624375F;
        float v2 = v + 0.0624375F;
        int alpha = Math.max(0, Math.min(255, Math.round(opacity * 255.0F)));
        int red = (tint >> 16) & 255;
        int green = (tint >> 8) & 255;
        int blue = tint & 255;

        GlStateManager.pushMatrix();
        try {
            GlStateManager.disableDepth();
            GlStateManager.disableLighting();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            mc.getTextureManager().bindTexture(PARTICLE_ATLAS);
            WorldRenderer renderer = Tessellator.getInstance().getWorldRenderer();
            renderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            renderer.pos(x, y + size, 0.0D).tex(u, v2).color(red, green, blue, alpha).endVertex();
            renderer.pos(x + size, y + size, 0.0D).tex(u2, v2).color(red, green, blue, alpha).endVertex();
            renderer.pos(x + size, y, 0.0D).tex(u2, v).color(red, green, blue, alpha).endVertex();
            renderer.pos(x, y, 0.0D).tex(u, v).color(red, green, blue, alpha).endVertex();
            Tessellator.getInstance().draw();
        } finally {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            if (depthEnabled) GlStateManager.enableDepth(); else GlStateManager.disableDepth();
            if (lightingEnabled) GlStateManager.enableLighting(); else GlStateManager.disableLighting();
            if (blendEnabled) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
            GlStateManager.popMatrix();
        }
    }

    private void drawStatsPanel() {
        drawPanel(statsPanelLeft, statsPanelTop, statsPanelRight, statsPanelBottom);
        drawFitted(recentKits ? "Activity overview" : "Lifetime overview",
                statsPanelLeft + 11, statsPanelTop + 12,
                statsPanelRight - statsPanelLeft - 24, 1.0F, TEXT, 0);
        if (statsPanelRight - statsPanelLeft >= 290) {
            String badge = recentKits ? "official + gomod"
                    : snapshot == null ? "LIFETIME" : snapshot.cacheLabel();
            drawFitted(badge, statsPanelRight - 12, statsPanelTop + 13,
                    125, 1.0F, MUTED, 2);
        }
        Gui.drawRect(statsPanelLeft + 10, layout.contentTop - 8,
                statsPanelRight - 10, layout.contentTop - 7, 0xFF26323C);

        if (loading || snapshot == null) {
            scrollOffset = maxScroll = 0;
            renderedScrollOffset = 0.0F;
            draggingStatsScrollbar = false;
            statsScrollbarThumbTop = statsScrollbarThumbBottom = 0;
            drawStatsState();
            return;
        }
        updateStatsScrollBounds();
        SocialDraw.enableScissor(layout.contentLeft, layout.contentTop,
                layout.contentRight, layout.contentBottom);
        try {
            int originY = layout.contentTop - visibleScrollOffset();
            int contentWidth = layout.contentRight - layout.contentLeft;
            if (recentKits) drawActivityStats(originY, contentWidth);
            else drawLifetimeStats(originY, contentWidth);
        } finally {
            SocialDraw.disableScissor();
        }
        drawStatsEdgeFades(layout.contentTop, layout.contentBottom);
        drawStatsScrollbar(layout.contentTop, layout.contentBottom);
    }

    private void drawLifetimeStats(int originY, int contentWidth) {
        String[] labels = {"Kills", "Wins", "K/D", "W/L"};
        String[] values = {number(snapshot.kills), number(snapshot.wins),
                decimal(snapshot.kd), decimal(snapshot.wlr)};
        int[] colors = {STAT_KILLS, STAT_WINS, STAT_RATIO, STAT_RATIO};
        for (int i = 0; i < labels.length; i++) {
            drawSummaryCard(layout.summaryX(i), originY + layout.summaryY(i),
                    layout.summaryWidth, labels[i], values[i], colors[i]);
        }

        String[] detailLabels = {"Deaths", "Games", "Solo kills", "Team kills", "Solo wins", "Team wins",
                "Coins", "Playtime"};
        long[] detailValues = {snapshot.deaths, snapshot.games, snapshot.soloKills,
                snapshot.teamKills, snapshot.soloWins, snapshot.teamWins, snapshot.coins};
        int[] detailColors = {STAT_DEATHS, STAT_GAMES, STAT_KILLS, STAT_KILLS,
                STAT_WINS, STAT_WINS, STAT_COINS, STAT_GAMES};
        for (int i = 0; i < detailLabels.length; i++) {
            String value = i == 7
                    ? snapshot.timePlayedSeconds < 0L ? "—" : formatDuration(snapshot.timePlayedSeconds)
                    : number(detailValues[i]);
            drawDetailRow(layout.detailX(i), originY + layout.detailY(i), layout.detailWidth,
                    detailLabels[i], value, detailColors[i]);
        }

        drawSectionHeading("KITS", originY + layout.kitsHeadingY, "");
        int maxKits = displayedKits.size();
        if (maxKits > 0 && !layout.compactKits) drawKitTableHeader(originY + layout.kitsHeaderY);
        for (int i = 0; i < maxKits; i++) {
            int rowY = originY + kitRowsY() + i * kitRowPitch();
            drawKitRow(layout.contentLeft, rowY, contentWidth, i + 1, displayedKits.get(i));
        }
        if (maxKits == 0) {
            drawFitted("No kit statistics yet", layout.contentLeft + 8, originY + kitRowsY() + 10,
                    contentWidth - 16, 1.0F, MUTED, 0);
        }
    }

    private void drawActivityStats(int originY, int contentWidth) {
        GoStatsBlitzClient.ActivitySummary activity = snapshot.overallActivity;
        int x = layout.contentLeft;
        int right = layout.contentRight;
        drawActivityWindow(x, originY, contentWidth, activity);

        drawActivitySectionHeading("OVERALL CHANGES", originY + activityCoreHeadingY());
        String[] coreLabels = {"Kills", "Wins", "Deaths", "Games"};
        String[] coreValues = {activityGain(activity.kills), activityGain(activity.wins),
                activityGain(activity.deaths), activityGain(activity.games)};
        int[] coreColors = {STAT_KILLS, STAT_WINS, STAT_DEATHS, STAT_GAMES};
        for (int i = 0; i < coreLabels.length; i++) {
            drawSummaryCard(layout.summaryX(i), originY + activityCoreY(),
                    layout.summaryWidth, coreLabels[i], coreValues[i], coreColors[i]);
        }

        drawActivitySectionHeading("SOLO / TEAMS", originY + activityModeHeadingY());
        String[] modeLabels = {"Solo kills", "Team kills", "Solo wins", "Team wins"};
        long[] modeValues = {activity.soloKills, activity.teamKills, activity.soloWins, activity.teamWins};
        int[] modeColors = {STAT_KILLS, STAT_KILLS, STAT_WINS, STAT_WINS};
        for (int i = 0; i < modeLabels.length; i++) {
            drawDetailRow(layout.detailX(i), originY + activityModeRowsY()
                    + (i / 2) * PlayerProfileLayout.DETAIL_PITCH, layout.detailWidth,
                    modeLabels[i], activityGain(modeValues[i]), modeColors[i]);
        }

        drawActivitySectionHeading("RATIOS & EXTRAS", originY + activityExtrasHeadingY());
        String[] extraLabels = {"K/D", "W/L", "Coins", "Playtime gained"};
        String playtime = activity.timePlayedSeconds < 0L
                ? "—" : "+" + formatDuration(activity.timePlayedSeconds);
        String[] extraValues = {activityRatio(activity.kills, activity.deaths),
                activityRatio(activity.wins, activity.deaths), activityGain(activity.coins), playtime};
        int[] extraColors = {STAT_RATIO, STAT_RATIO, STAT_COINS, STAT_GAMES};
        for (int i = 0; i < extraLabels.length; i++) {
            drawDetailRow(layout.detailX(i), originY + activityExtrasRowsY()
                    + (i / 2) * PlayerProfileLayout.DETAIL_PITCH, layout.detailWidth,
                    extraLabels[i], extraValues[i], extraColors[i]);
        }

        int headingY = originY + profileKitsHeadingY();
        drawSectionHeading("KIT ACTIVITY", headingY, "");
        String hint = activityLoading ? "Loading saved activity..."
                : activityError != null ? "Activity unavailable. Select Activity to retry."
                : "Gains per latest scan · each kit shows its own older and latest saved check (local time)";
        drawFitted(hint, x + 2, headingY + 18, contentWidth - 4, 0.78F, MUTED, 0);

        int maxKits = displayedKits.size();
        for (int i = 0; i < maxKits; i++) {
            int rowY = originY + kitRowsY() + i * kitRowPitch();
            drawKitActivityRow(x, rowY, contentWidth, displayedKits.get(i));
        }
        if (maxKits == 0) {
            drawFitted(activityLoading ? "Loading kit activity..." : "No comparable kit changes yet",
                    x + 8, originY + kitRowsY() + 9, contentWidth - 16, 1.0F, MUTED, 0);
            if (!activityLoading) {
                drawFitted("Kits need two saved checks; missing data does not mean no activity.",
                        x + 8, originY + kitRowsY() + 23, contentWidth - 16, 0.75F, MUTED, 0);
            }
        }

        int footerY = originY + profileFooterY();
        int unchanged = Math.max(0, snapshot.kits.size() - maxKits);
        drawFitted(unchanged + " kits had no comparable changes in the latest scan.",
                x, footerY, right - x, 0.85F, MUTED, 0);
    }

    private void drawActivityWindow(int x, int y, int width,
                                   GoStatsBlitzClient.ActivitySummary activity) {
        SocialDraw.roundedRect(x, y, x + width, y + 24, 4, 0xB01B242D);
        SocialDraw.roundedRect(x + 7, y + 5, x + 10, y + 19, 1.5F, GREEN);
        drawFitted("OVERALL CHECKS", x + 16, y + 8, 92, 0.76F, MUTED, 0);
        String range = activity.isAvailable()
                ? activityTime(activity.before) + " → " + activityTime(activity.after)
                : activityLoading ? "Loading saved activity..."
                : activityError != null ? "Activity unavailable"
                : "No comparable overall scan yet";
        drawFitted(range, x + width - 8, y + 8, width - 118, 0.78F,
                activity.isAvailable() ? TEXT : MUTED, 2);
    }

    private void drawActivitySectionHeading(String title, int y) {
        if (!rowVisible(y, 10)) return;
        int width = layout.contentRight - layout.contentLeft;
        int textWidth = Math.round(fontRendererObj.getStringWidth(title) * 0.82F);
        drawFitted(title, layout.contentLeft, y, width, 0.82F, MUTED, 0);
        int lineLeft = layout.contentLeft + textWidth + 9;
        if (lineLeft < layout.contentRight - 4) {
            Gui.drawRect(lineLeft, y + 5, layout.contentRight, y + 6, 0xFF2B3640);
        }
    }

    private String activityGain(long value) {
        return value < 0L ? "—" : "+" + number(value);
    }

    private String activityRatio(long numerator, long denominator) {
        if (numerator < 0L || denominator < 0L) return "—";
        return decimal((double) numerator / Math.max(1L, denominator));
    }

    private void drawStatsState() {
        SocialDraw.enableScissor(layout.contentLeft, layout.contentTop, layout.contentRight, layout.contentBottom);
        try {
            int centerX = (layout.contentLeft + layout.contentRight) / 2;
            int contentWidth = layout.contentRight - layout.contentLeft;
            int top = layout.contentTop + Math.max(4, (layout.contentBottom - layout.contentTop) / 2 - 21);
            drawFitted(loading ? "Loading statistics" : "Statistics unavailable",
                    centerX, top, contentWidth - 12, 1.0F, loading ? TEXT : MUTED, 1);
            if (loading) {
                int barWidth = Math.min(100, contentWidth - 24);
                int barLeft = centerX - barWidth / 2;
                SocialDraw.roundedRect(barLeft, top + 22, barLeft + barWidth, top + 25, 1.5F, CARD_BG);
                int travel = Math.max(1, barWidth - 24);
                int offset = Math.round((float) ((Math.sin(System.nanoTime() / 550_000_000.0) + 1.0) * 0.5) * travel);
                SocialDraw.roundedRect(barLeft + offset, top + 22, barLeft + offset + 24,
                        top + 25, 1.5F, GREEN);
            } else {
                String message = denickedNick != null && player != null
                        && denickedNick.equalsIgnoreCase(player.getUsername())
                        ? "This nick's real IGN has not been resolved."
                        : loadError == null ? "Try opening this profile again shortly." : loadError;
                java.util.List<String> lines = fontRendererObj.listFormattedStringToWidth(message,
                        Math.max(32, (int) ((contentWidth - 16) / 0.8F)));
                int count = Math.min(3, lines.size());
                for (int i = 0; i < count; i++) {
                    drawFitted(lines.get(i), centerX, top + 19 + i * 10,
                            contentWidth - 16, 0.8F, MUTED, 1);
                }
            }
        } finally {
            SocialDraw.disableScissor();
        }
    }

    private void drawSummaryCard(int x, int y, int width, String label, String value, int accent) {
        if (!rowVisible(y, PlayerProfileLayout.SUMMARY_HEIGHT)) return;
        SocialDraw.roundedRect(x, y, x + width, y + PlayerProfileLayout.SUMMARY_HEIGHT, 5, CARD_BG);
        SocialDraw.roundedRect(x + 8, y + 9, x + 11, y + 39, 1.5F, accent);
        drawFitted(label, x + 17, y + 8, width - 24, 1.0F, MUTED, 0);
        drawFitted(value, x + 17, y + 25, width - 24, 1.45F, accent, 0);
    }

    private void drawDetailRow(int x, int y, int width, String label, String value, int valueColor) {
        if (!rowVisible(y, 24)) return;
        SocialDraw.roundedRect(x, y, x + width, y + 24, 4, 0xB01B242D);
        int valueWidth = Math.max(34, width / 2 - 6);
        drawFitted(label, x + 8, y + 8, width - valueWidth - 21, 1.0F, MUTED, 0);
        drawFitted(value, x + width - 8, y + 8, valueWidth, 1.0F, valueColor, 2);
    }

    private void drawKitTableHeader(int y) {
        if (!rowVisible(y, 14)) return;
        drawFitted("Kit", layout.contentLeft + 50, y, layout.kitNameWidth() - 20, 0.85F, MUTED, 0);
        String[] labels = {"Kills", "Wins", "K/D"};
        for (int i = 0; i < labels.length; i++) {
            drawFitted(labels[i], layout.kitStatRight(i), y, layout.kitStatWidth(), 0.85F, MUTED, 2);
        }
        Gui.drawRect(layout.contentLeft + 8, y + 12, layout.contentRight - 8, y + 13, PANEL_BORDER);
    }

    private void drawCosmeticSection() {
        int panelWidth = previewPanelRight - previewPanelLeft;
        int headingY = layout.cosmeticsHeadingY;
        drawFitted("COSMETICS", previewPanelLeft + 12, headingY,
                panelWidth - 24, 0.9F, MUTED, 0);
        Gui.drawRect(previewPanelLeft + 10, headingY + 10,
                previewPanelRight - 10, headingY + 11, PANEL_BORDER);

        if (loading || snapshot == null) {
            int centerX = (previewPanelLeft + previewPanelRight) / 2;
            int sectionTop = layout.cosmeticsTop;
            int sectionBottom = previewPanelBottom - 8;
            SocialDraw.enableScissor(previewPanelLeft + 8, sectionTop,
                    previewPanelRight - 8, sectionBottom);
            try {
                int centerY = sectionTop + Math.max(12, (sectionBottom - sectionTop) / 2);
                drawFitted(loading ? "Loading cosmetics" : "Cosmetics unavailable",
                        centerX, centerY - 8, panelWidth - 24, 1.0F, loading ? TEXT : MUTED, 1);
                if (loading) {
                    int barWidth = Math.min(100, panelWidth - 36);
                    int barLeft = centerX - barWidth / 2;
                    SocialDraw.roundedRect(barLeft, centerY + 7, barLeft + barWidth,
                            centerY + 10, 1.5F, CARD_BG);
                    int travel = Math.max(1, barWidth - 24);
                    int offset = Math.round((float) ((Math.sin(System.nanoTime() / 550_000_000.0) + 1.0)
                            * 0.5) * travel);
                    SocialDraw.roundedRect(barLeft + offset, centerY + 7,
                            barLeft + offset + 24, centerY + 10, 1.5F, GREEN);
                }
            } finally {
                SocialDraw.disableScissor();
            }
            return;
        }

        GoStatsBlitzClient.Cosmetics cosmetics = snapshot.cosmetics;
        String[][] fields = {
                {"defaultkit", "Default Kit", cosmetics.defaultKit},
                {"afterkill", "Kill Effect", cosmetics.killEffect},
                {"victory", "Victory Dance", cosmetics.victoryDance},
                {"finisher", "Finisher", cosmetics.finisher},
                {"taunt", "Taunt", cosmetics.taunt},
                {"aura", "Aura", cosmetics.aura}
        };
        int x = previewPanelLeft + 7;
        int cellWidth = panelWidth - 14;
        int textX = x + 26;
        int textWidth = cellWidth - 34;
        for (int i = 0; i < fields.length; i++) {
            int y = layout.cosmeticsTop + i * layout.cosmeticsRowPitch;
            int cellHeight = layout.cosmeticsCellHeight;
            SocialDraw.roundedRect(x, y, x + cellWidth, y + cellHeight, 4, CARD_BG);
            String value = cosmeticDisplayValue(fields[i][0], fields[i][2], cosmetics);
            int textY = y + Math.max(1, (cellHeight - fontRendererObj.FONT_HEIGHT) / 2) + 1;
            int labelWidth = Math.min(textWidth, Math.max(1,
                    Math.round(fontRendererObj.getStringWidth(fields[i][1]) * 0.78F)));
            int valueWidth = Math.max(1, textWidth - labelWidth - 6);
            drawFitted(fields[i][1], textX, textY, labelWidth, 0.78F, MUTED, 0);
            drawFittedComplete(value, x + cellWidth - 7, textY, valueWidth, 1.0F,
                    cosmeticValueColor(fields[i][0], fields[i][2]), 2);
            int iconY = y + Math.max(1, (cellHeight - 16) / 2);
            if ("defaultkit".equals(fields[i][0])) {
                String rawKit = fields[i][2] == null ? "" : fields[i][2].trim();
                if (!rawKit.isEmpty() && !"none".equalsIgnoreCase(rawKit)) {
                    BlitzKitIconRenderer.draw(rawKit, x + 5, iconY);
                } else {
                    drawCosmeticItem(new ItemStack(Items.iron_sword), x + 5, iconY);
                }
            } else {
                ItemStack icon = LeaderboardGUI.profileCosmeticIcon(fields[i][0], fields[i][2]);
                drawCosmeticItem(icon, x + 5, iconY);
            }
        }
    }

    private String displayCosmeticValue(String category, String value) {
        if ("defaultkit".equals(category)) {
            if (value == null || value.trim().isEmpty()) return "None";
            if ("none".equalsIgnoreCase(value.trim())) return "None";
            return GoStatsBlitzClient.displayKitName(value);
        }
        if (value == null || value.trim().isEmpty()) return "Unknown";
        return LeaderboardGUI.displayProfileCosmeticLabel(value);
    }

    private String cosmeticDisplayValue(String category, String rawValue,
                                         GoStatsBlitzClient.Cosmetics cosmetics) {
        String value = displayCosmeticValue(category, rawValue);
        if ("defaultkit".equals(category)) return defaultKitDisplayValue(rawValue, value);
        if ("victory".equals(category) && "Unknown".equalsIgnoreCase(value)) return "None";
        if ("taunt".equals(category)) {
            String raw = rawValue == null ? "" : rawValue.trim();
            if ("DEFAULT".equalsIgnoreCase(raw)) {
                return "Default";
            }
            // Hypixel only includes chosen_taunt after the player has unlocked a taunt.
            // A missing/empty value therefore means the taunt is still locked, not Default.
            if (raw.isEmpty() || "NONE".equalsIgnoreCase(raw)) return "Locked";
        }
        if (cosmetics != null && cosmetics.isAvailable()
                && ("finisher".equals(category) || "aura".equals(category))
                && "Unknown".equalsIgnoreCase(value)) {
            value = "None";
        }
        if (!"aura".equals(category) || cosmetics == null) return value;
        if (cosmetics.auraEnabled != null) {
            if ("Unknown".equals(value)) value = "None";
            return value + (cosmetics.auraEnabled.booleanValue() ? " (Enabled)" : " (Disabled)");
        }
        if ("Unknown".equals(value) || "None".equalsIgnoreCase(value)) return value;
        return value + " (Enabled/Disabled)";
    }

    private int cosmeticValueColor(String category, String rawValue) {
        if ("defaultkit".equals(category)) {
            GoStatsBlitzClient.Kit kit = findProfileKit(rawValue);
            return kit == null ? TEXT : kitLevelColor(kit);
        }
        if ("afterkill".equals(category)) {
            return 0xFF000000 | (KillCounter.getKillEffectColorForEffect(rawValue) & 0xFFFFFF);
        }
        return TEXT;
    }

    private String defaultKitDisplayValue(String rawKit, String displayName) {
        if (rawKit == null || rawKit.trim().isEmpty() || "None".equalsIgnoreCase(displayName)
                || "Unknown".equalsIgnoreCase(displayName)) {
            return displayName;
        }
        GoStatsBlitzClient.Kit kit = findProfileKit(rawKit);
        String level = kitLevelLabel(kit);
        if (level.isEmpty()) return displayName;
        return displayName + " " + level;
    }

    private GoStatsBlitzClient.Kit findProfileKit(String name) {
        String target = normalizeKitName(name);
        if (target.isEmpty() || snapshot == null || snapshot.kits == null) return null;
        for (GoStatsBlitzClient.Kit kit : snapshot.kits) {
            if (target.equals(normalizeKitName(kit.name))) return kit;
        }
        return null;
    }

    private static String normalizeKitName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private void drawCosmeticItem(ItemStack icon, int x, int y) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (icon == null || minecraft == null || minecraft.getRenderItem() == null) return;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableDepth();
            RenderHelper.enableGUIStandardItemLighting();
            minecraft.getRenderItem().renderItemAndEffectIntoGUI(icon, x, y);
        } finally {
            RenderHelper.disableStandardItemLighting();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.popMatrix();
        }
    }

    private void drawKitRow(int x, int y, int width, int index, GoStatsBlitzClient.Kit kit) {
        if (!rowVisible(y, layout.kitHeight)) return;
        SocialDraw.roundedRect(x, y, x + width, y + layout.kitHeight, 4, CARD_BG);
        int nameY = y + (layout.compactKits ? 7 : 10);
        drawFitted(String.valueOf(index), x + 15, nameY, 12, 0.85F, MUTED, 1);
        BlitzKitIconRenderer.draw(kit.name, x + 30, nameY - 4);
        String kitName = GoStatsBlitzClient.displayKitName(kit.name);
        drawFitted(kitTitle(kit), x + 50, nameY,
                layout.compactKits ? width - 60 : layout.kitNameWidth() - 20, 1.0F, kitLevelColor(kit), 0);
        String[] values = {number(kit.kills), number(kit.wins), kitRatio(kit)};
        int[] colors = {STAT_KILLS, STAT_WINS, STAT_RATIO};
        if (!layout.compactKits) {
            for (int i = 0; i < values.length; i++) {
                drawFitted(values[i], layout.kitStatRight(i), nameY,
                        layout.kitStatWidth(), 1.0F, colors[i], 2);
            }
            return;
        }

        String[] labels = {"Kills", "Wins", "K/D"};
        int cellWidth = (width - 16) / 3;
        for (int i = 0; i < values.length; i++) {
            int cellX = x + 8 + i * cellWidth;
            int labelWidth = Math.round(fontRendererObj.getStringWidth(labels[i]) * 0.75F);
            drawFitted(labels[i], cellX, y + 25, labelWidth, 0.75F, MUTED, 0);
            drawFitted(values[i], cellX + cellWidth - 5, y + 25,
                    Math.max(10, cellWidth - labelWidth - 9), 0.9F, colors[i], 2);
        }
    }

    private String activityGain(long value, String label, EnumChatFormatting color) {
        return color + (value < 0L ? "?" : "+" + number(value)) + EnumChatFormatting.GRAY + " " + label;
    }

    private boolean activityLevelChanged(GoStatsBlitzClient.Kit kit) {
        if (kit == null || isUnupgradableKitName(kit.name)) return false;
        GoStatsBlitzClient.Activity activity = kit.activity;
        if (activity.levelBefore <= 0L || activity.levelAfter <= 0L) return false;
        return activity.levelAfter > activity.levelBefore
                || activity.prestigeBefore >= 0L && activity.prestigeAfter > activity.prestigeBefore;
    }

    private String activityLevelLabel(long level, long prestige) {
        if (prestige >= 2L) return "✫✫";
        if (prestige == 1L) return "✫";
        return level >= 1L && level <= 10L ? toRoman((int) level)
                : level > 0L ? Long.toString(level) : "—";
    }

    private void drawKitActivityRow(int x, int y, int width, GoStatsBlitzClient.Kit kit) {
        if (!rowVisible(y, kitRowHeight())) return;
        int cardLeft = x + 4;
        SocialDraw.roundedRect(cardLeft, y, x + width, y + kitRowHeight(), 4, CARD_BG);
        Gui.drawRect(cardLeft, y + 6, cardLeft + 2, y + kitRowHeight() - 6, GREEN);
        String title = kitTitle(kit);
        BlitzKitIconRenderer.draw(kit.name, cardLeft + 8, y + 5);
        int titleLeft = cardLeft + 30;
        int titleRight = x + width - 10;
        if (activityLevelChanged(kit)) {
            String transition = "LEVEL UP  "
                    + activityLevelLabel(kit.activity.levelBefore, kit.activity.prestigeBefore)
                    + " → "
                    + activityLevelLabel(kit.activity.levelAfter, kit.activity.prestigeAfter);
            int badgeWidth = Math.min(width - 30,
                    Math.round(fontRendererObj.getStringWidth(transition) * 0.72F) + 14);
            int badgeLeft = titleRight - badgeWidth;
            SocialDraw.roundedRect(badgeLeft, y + 5, titleRight, y + 20, 4, 0xFF3A3120);
            drawFitted(transition, titleRight - 6, y + 9, badgeWidth - 10, 0.72F, GOLD, 2);
            titleRight = badgeLeft - 7;
        }
        drawFitted(title, titleLeft, y + 8, titleRight - titleLeft, 1.0F, kitLevelColor(kit), 0);
        java.util.List<String> metrics = new java.util.ArrayList<String>();
        if (kit.activity.games >= 0) metrics.add(activityGain(kit.activity.games, "games", EnumChatFormatting.AQUA));
        if (kit.activity.kills >= 0) metrics.add(activityGain(kit.activity.kills, "kills", EnumChatFormatting.GREEN));
        if (kit.activity.wins >= 0) metrics.add(activityGain(kit.activity.wins, "wins", EnumChatFormatting.YELLOW));
        int metricLeft = cardLeft + 28;
        int metricRight = x + width - 10;
        if (metrics.isEmpty()) {
            String fallback = kit.activity.timePlayedSeconds >= 0
                    ? "Playtime gained: " + formatDuration(kit.activity.timePlayedSeconds)
                    : "Counter gains unavailable";
            drawFitted(fallback, metricLeft, y + 29, metricRight - metricLeft, 0.82F, MUTED, 0);
        } else {
            int metricWidth = (metricRight - metricLeft) / metrics.size();
            for (int i = 0; i < metrics.size(); i++) {
                int cellLeft = metricLeft + i * metricWidth;
                if (i > 0) Gui.drawRect(cellLeft, y + 27, cellLeft + 1, y + 39, 0xFF2B3640);
                drawFitted(metrics.get(i), cellLeft + metricWidth / 2.0F, y + 29,
                        metricWidth - 8, 0.82F, TEXT, 1);
            }
        }
        int checkDivider = cardLeft + (metricRight - cardLeft) / 2;
        Gui.drawRect(checkDivider, y + 47, checkDivider + 1, y + 74, 0xFF2B3640);
        drawFitted("OLDER SAVED CHECK", cardLeft + 10, y + 48,
                checkDivider - cardLeft - 17, 0.66F, MUTED, 0);
        drawFitted("LATEST SAVED CHECK", checkDivider + 8, y + 48,
                metricRight - checkDivider - 8, 0.66F, MUTED, 0);
        drawFitted(activityTime(kit.activityAfter), cardLeft + 10, y + 62,
                checkDivider - cardLeft - 17, 0.72F, TEXT, 0);
        drawFitted(activityTime(kit.activityBefore), checkDivider + 8, y + 62,
                metricRight - checkDivider - 8, 0.72F, TEXT, 0);
    }

    private void drawSectionHeading(String title, int y, String trailing) {
        if (!rowVisible(y, 10)) return;
        drawFitted(title, layout.contentLeft, y, layout.contentRight - layout.contentLeft - 55,
                1.0F, MUTED, 0);
        if (!trailing.isEmpty()) {
            drawFitted(trailing, layout.contentRight, y, 48, 1.0F, MUTED, 2);
        }
    }

    private boolean rowVisible(int top, int rowHeight) {
        return GuiScrollFade.intersectsVisible(top, rowHeight, layout.contentTop, layout.contentBottom);
    }

    private void updateStatsScrollBounds() {
        int viewportHeight = layout.contentBottom - layout.contentTop;
        int contentBottom = profileContentBottomY() + 8;
        maxScroll = snapshot == null ? 0 : Math.max(0, contentBottom - viewportHeight);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        renderedScrollOffset = Math.max(0.0F, Math.min(renderedScrollOffset, maxScroll));
        if (maxScroll <= 0) draggingStatsScrollbar = false;
    }

    private void updateScrollForDraw(int mouseY) {
        if (draggingStatsContent) {
            int deltaY = mouseY - lastStatsContentMouseY;
            if (deltaY != 0) {
                scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset - deltaY));
                lastStatsContentMouseY = mouseY;
            }
        }
        // GuiScreen.mouseClickMove is dispatched at tick rate. Sample its cursor on
        // every draw frame during content dragging; wheel and scrollbar input stay direct.
        renderedScrollOffset = scrollOffset;
    }

    private int visibleScrollOffset() {
        return Math.round(renderedScrollOffset);
    }

    private void drawStatsEdgeFades(int top, int bottom) {
        int fadeHeight = Math.min(8, (bottom - top) / 4);
        for (int i = 0; i < fadeHeight; i++) {
            int color = GuiScrollFade.argb(0.8F * (fadeHeight - i) / fadeHeight, PANEL_BG & 0xFFFFFF);
            if (renderedScrollOffset > 0.5F) Gui.drawRect(layout.contentLeft, top + i, layout.contentRight, top + i + 1, color);
            if (renderedScrollOffset < maxScroll - 0.5F) {
                Gui.drawRect(layout.contentLeft, bottom - i - 1, layout.contentRight, bottom - i, color);
            }
        }
    }

    private void drawStatsScrollbar(int top, int bottom) {
        statsScrollbarTrackLeft = statsPanelRight - 8;
        statsScrollbarTrackTop = top;
        statsScrollbarTrackRight = statsPanelRight - 3;
        statsScrollbarTrackBottom = bottom;
        if (maxScroll <= 0) {
            statsScrollbarThumbTop = statsScrollbarThumbBottom = 0;
            return;
        }
        int trackHeight = Math.max(1, bottom - top);
        int thumbHeight = Math.min(trackHeight, Math.max(20,
                Math.round(trackHeight * (trackHeight / (float) (trackHeight + maxScroll)))));
        int travel = Math.max(0, trackHeight - thumbHeight);
        int thumbY = top + Math.round(travel * (renderedScrollOffset / maxScroll));
        statsScrollbarThumbTop = thumbY;
        statsScrollbarThumbBottom = thumbY + thumbHeight;
        SocialDraw.roundedRect(statsScrollbarTrackLeft + 1, top, statsScrollbarTrackLeft + 4, bottom,
                1.5F, 0x501B242D);
        SocialDraw.roundedRect(statsScrollbarTrackLeft + 1, thumbY, statsScrollbarTrackLeft + 4,
                thumbY + thumbHeight, 1.5F, draggingStatsScrollbar ? GREEN : 0xFF647787);
    }

    private void drawPanel(int left, int top, int right, int bottom) {
        SocialDraw.roundedRect(left, top + 3, right, bottom + 3, 7, 0x30000000);
        SocialDraw.roundedBorder(left, top, right, bottom, 7, PANEL_BG, PANEL_BORDER);
    }

    /** Fit every value to its own column, including unusually large stats and long kit names. */
    private void drawFitted(String text, float anchorX, float y, int maxWidth,
                            float requestedScale, int color, int alignment) {
        if (text == null || maxWidth <= 0) return;
        int textWidth = Math.max(1, fontRendererObj.getStringWidth(text));
        float scale = Math.min(requestedScale, maxWidth / (float) (textWidth + 1));
        float minimumScale = Math.min(0.72F, requestedScale);
        if (scale < minimumScale) {
            scale = minimumScale;
            text = trim(text, Math.max(1, (int) (maxWidth / scale) - 1));
            textWidth = fontRendererObj.getStringWidth(text);
        }
        float x = alignment == 1 ? anchorX - textWidth * scale / 2.0F
                : alignment == 2 ? anchorX - textWidth * scale : anchorX;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, 0);
            GlStateManager.scale(scale, scale, 1.0F);
            fontRendererObj.drawStringWithShadow(text, 0, 0, color);
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** Keeps cosmetic values at normal size unless the text exceeds its usable column width. */
    private void drawFittedComplete(String text, float anchorX, float y, int maxWidth,
                                    float requestedScale, int color, int alignment) {
        if (text == null || maxWidth <= 0 || requestedScale <= 0.0F) return;
        int textWidth = Math.max(1, fontRendererObj.getStringWidth(text));
        float scale = requestedScale;
        float usableWidth = Math.max(1.0F, maxWidth - 2.0F);
        if (textWidth * scale > usableWidth) {
            scale = usableWidth / (float) textWidth;
        }
        float x = alignment == 1 ? anchorX - textWidth * scale / 2.0F
                : alignment == 2 ? anchorX - textWidth * scale : anchorX;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(x, y, 0);
            GlStateManager.scale(scale, scale, 1.0F);
            fontRendererObj.drawStringWithShadow(text, 0, 0, color);
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    private final class ProfileActionButton extends GuiButton {
        private final int accent;
        ProfileActionButton(int id, int x, int y, int width, String text, int accent) {
            super(id, x, y, width, 20, text);
            this.accent = accent;
        }

        @Override
        public void drawButton(Minecraft client, int mouseX, int mouseY) {
            if (!visible) return;
            boolean over = enabled && mouseX >= xPosition && mouseX < xPosition + width
                    && mouseY >= yPosition && mouseY < yPosition + height;
            boolean selected = id == ID_SORT && !recentKits || id == ID_ACTIVITY && recentKits;
            SocialDraw.roundedBorder(xPosition, yPosition, xPosition + width, yPosition + height, 5,
                    selected || over ? 0xFF24352E : PANEL_BG, selected || over ? accent : PANEL_BORDER);
            drawFitted(displayString, xPosition + width / 2.0F, yPosition + 6,
                    width - 12, 1.0F, selected || over ? accent : TEXT, 1);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button == null) {
            return;
        }
        if ((button.id == ID_SORT || button.id == ID_ACTIVITY) && snapshot != null) {
            recentKits = button.id == ID_ACTIVITY;
            updateDisplayedKits();
            if (recentKits) requestKitActivity();
            return;
        }
        if (button.id == ID_CANCEL) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (readOnlyProfile && (button.id == ID_UPDATE || button.id == ID_DELETE)) {
            return;
        }
        if (button.id == ID_UPDATE) {
            mc.displayGuiScreen(parent);
            String username = player.getUsername();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "Updating " + username + "...");
            new Thread(() -> {
                String result = TeamSpamManager.updatePlayerInfo(username);
                Main.sendMessage(result);
                TeamSpamGUI.refreshCurrentGUI();
            }, "gomod-team-player-update").start();
            return;
        }
        if (button.id == ID_DELETE) {
            String result = TeamSpamManager.removePlayer(player.getUsername());
            Main.sendMessage(result);
            TeamSpamGUI.refreshCurrentGUI();
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && maxScroll > 0
                && mouseX >= statsScrollbarTrackLeft - 4 && mouseX <= statsScrollbarTrackRight + 4
                && mouseY >= statsScrollbarTrackTop && mouseY <= statsScrollbarTrackBottom) {
            if (mouseY >= statsScrollbarThumbTop && mouseY <= statsScrollbarThumbBottom) {
                draggingStatsScrollbar = true;
                statsScrollbarGrabOffset = mouseY - statsScrollbarThumbTop;
            } else {
                int trackHeight = Math.max(1, statsScrollbarTrackBottom - statsScrollbarTrackTop);
                int thumbHeight = Math.max(1, statsScrollbarThumbBottom - statsScrollbarThumbTop);
                int travel = Math.max(1, trackHeight - thumbHeight);
                float ratio = (mouseY - statsScrollbarTrackTop - thumbHeight / 2.0F) / travel;
                ratio = Math.max(0.0F, Math.min(1.0F, ratio));
                scrollOffset = Math.round(maxScroll * ratio);
            }
            return;
        }
        boolean overViewToggle = isVisibleButtonAt(kitSortButton, mouseX, mouseY)
                || isVisibleButtonAt(kitActivityButton, mouseX, mouseY);
        if (mouseButton == 0 && !overViewToggle && maxScroll > 0
                && mouseX >= layout.contentLeft && mouseX <= layout.contentRight
                && mouseY >= layout.contentTop && mouseY <= layout.contentBottom) {
            draggingStatsContent = true;
            lastStatsContentMouseY = mouseY;
            return;
        }
        if (mouseButton == 0 && mouseX >= previewPanelLeft + 8 && mouseX <= previewPanelRight - 8
                && mouseY >= layout.modelTop && mouseY <= layout.modelBottom + 3) {
            draggingPreview = true;
            lastDragMouseX = mouseX;
            lastDragMouseY = mouseY;
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private boolean isVisibleButtonAt(GuiButton button, int mouseX, int mouseY) {
        return button != null && button.visible
                && mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton == 0 && draggingStatsScrollbar) {
            int trackHeight = Math.max(1, statsScrollbarTrackBottom - statsScrollbarTrackTop);
            int thumbHeight = Math.max(1, statsScrollbarThumbBottom - statsScrollbarThumbTop);
            int travel = Math.max(0, trackHeight - thumbHeight);
            int thumbTop = mouseY - statsScrollbarGrabOffset;
            int relative = Math.max(0, Math.min(travel, thumbTop - statsScrollbarTrackTop));
            scrollOffset = travel <= 0 ? 0 : Math.round(maxScroll * (relative / (float) travel));
            return;
        }
        if (clickedMouseButton == 0 && draggingStatsContent) {
            return;
        }
        if (clickedMouseButton == 0 && draggingPreview) {
            previewYaw += (mouseX - lastDragMouseX) * 0.8F;
            previewPitch += (lastDragMouseY - mouseY) * 0.5F;
            // Dragging down moves the orbit above the player's head. Keep enough
            // range for a clear top-down view without flipping the model upside down.
            previewPitch = Math.max(-75.0F, Math.min(75.0F, previewPitch));
            lastDragMouseX = mouseX;
            lastDragMouseY = mouseY;
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0) {
            draggingPreview = false;
            draggingStatsScrollbar = false;
            draggingStatsContent = false;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dWheel = Mouse.getEventDWheel();
        if (dWheel == 0 || snapshot == null) {
            return;
        }
        int eventX = Mouse.getEventX() * width / Math.max(1, mc.displayWidth);
        int eventY = height - Mouse.getEventY() * height / Math.max(1, mc.displayHeight) - 1;
        if (eventX >= statsPanelLeft && eventX <= statsPanelRight
                && eventY >= statsPanelTop && eventY <= statsPanelBottom) {
            scrollOffset = Math.max(0, Math.min(maxScroll, scrollOffset + (dWheel > 0 ? -35 : 35)));
        }
    }

    @Override
    public void onGuiClosed() {
        MountPreviewHelper.discardCapePreviewPlayer(mc);
        super.onGuiClosed();
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        // This profile screen has no text field. Let the global menu-controls setting
        // provide WASD movement and right-drag look just like the other gomod menus.
        return !GomodMenuSettings.isMoveWhileMenuEnabled();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private String formatProfilePlayerName() {
        if (player == null || player.getUsername() == null) {
            return EnumChatFormatting.WHITE + "Player";
        }
        String username = profileDisplayName == null || profileDisplayName.isEmpty()
                ? player.getUsername() : profileDisplayName;
        String killPrefix = "";
        if (snapshot != null) {
            int kills = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, snapshot.kills));
            killPrefix = LocalDisguise.formatBsg2KillBracketForDisplay(kills) + " ";
        }
        String rankTag = profileRankTag == null ? "" : profileRankTag;
        String nameColor = profileUsernameColor == null || profileUsernameColor.isEmpty()
                ? EnumChatFormatting.GRAY.toString() : profileUsernameColor;
        return killPrefix + (rankTag == null || rankTag.isEmpty() ? "" : rankTag + " ")
                + nameColor + username + EnumChatFormatting.RESET;
    }

    /** Resolve identity while the GUI opens so a later TAB cache clear cannot strip its rank styling. */
    private void resolveProfilePlayerIdentity() {
        if (player == null || player.getUsername() == null || player.getUsername().trim().isEmpty()) {
            profileDisplayName = "Player";
            profileRankTag = "";
            profileUsernameColor = EnumChatFormatting.GRAY.toString();
            return;
        }

        String username = player.getUsername().trim();
        profileDisplayName = username;
        JsonObject cachedPlayer = HypixelPlayerDataCache.getAny(username);
        if (cachedPlayer != null) {
            if (cachedPlayer.has("displayname") && !cachedPlayer.get("displayname").isJsonNull()) {
                try {
                    String cachedName = cachedPlayer.get("displayname").getAsString();
                    if (cachedName != null && !cachedName.trim().isEmpty()) {
                        profileDisplayName = cachedName.trim();
                    }
                } catch (Exception ignored) {
                }
            }
            if (HypixelTabStatsRankCache.getSnapshotForProfile(username) == null) {
                HypixelTabStatsRankCache.ingest(username, cachedPlayer);
                HypixelTabStatsRankCache.ingest(profileDisplayName, cachedPlayer);
            }
        }

        profileRankTag = HypixelTabStatsRankCache.getFormattedRankTag(profileDisplayName);
        profileUsernameColor = HypixelTabStatsRankCache.getUsernameColorFormatting(profileDisplayName);
        if ((profileRankTag == null || profileRankTag.isEmpty())
                && !profileDisplayName.equalsIgnoreCase(username)) {
            profileRankTag = HypixelTabStatsRankCache.getFormattedRankTag(username);
        }
        if ((profileUsernameColor == null || profileUsernameColor.isEmpty())
                && !profileDisplayName.equalsIgnoreCase(username)) {
            profileUsernameColor = HypixelTabStatsRankCache.getUsernameColorFormatting(username);
        }
        if (profileRankTag == null || profileRankTag.isEmpty()) {
            profileRankTag = fallbackRankTag(player);
        }
        if (profileUsernameColor == null || profileUsernameColor.isEmpty()) {
            profileUsernameColor = fallbackNameColor(player);
        }
    }

    private String fallbackRankTag(Player value) {
        String rank = value.getRank();
        if (rank == null || rank.isEmpty()) {
            rank = "Default";
        }
        if ("MVP++".equals(rank)) {
            String base = rankColor(value.getMonthlyRankColor(), GOLD);
            String plus = rankColor(value.getRankPlusColor(), GOLD);
            return base + "[MVP" + plus + "++]";
        }
        if ("MVP+".equals(rank)) {
            return EnumChatFormatting.AQUA + "[MVP" + rankColor(value.getRankPlusColor(), GOLD) + "+"
                    + EnumChatFormatting.AQUA + "]";
        }
        if ("Default".equals(rank)) {
            return "";
        }
        return rankColor(rank, GOLD) + "[" + rank + "]";
    }

    private String fallbackNameColor(Player value) {
        String rank = value.getRank();
        if (rank == null || rank.isEmpty()) {
            rank = "Default";
        }
        if ("MVP++".equals(rank)) {
            return rankColor(value.getMonthlyRankColor(), GOLD);
        }
        if ("MVP+".equals(rank)) {
            return EnumChatFormatting.AQUA.toString();
        }
        if ("Default".equals(rank)) {
            return EnumChatFormatting.GRAY.toString();
        }
        return rankColor(rank, 0xFFFFFFFF);
    }

    private String rankColor(String rank, int fallbackColor) {
        if (rank == null) {
            return colorCode(fallbackColor);
        }
        try {
            return EnumChatFormatting.valueOf(rank).toString();
        } catch (IllegalArgumentException ignored) {
            return colorCode(fallbackColor);
        }
    }

    private String colorCode(int color) {
        if (color == GOLD) return EnumChatFormatting.GOLD.toString();
        if (color == 0xFFFFFFFF) return EnumChatFormatting.WHITE.toString();
        return EnumChatFormatting.YELLOW.toString();
    }

    private String number(long value) {
        return NUMBER.format(Math.max(0L, value));
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", Math.max(0.0, value));
    }

    private String kitRatio(GoStatsBlitzClient.Kit kit) {
        return kit != null && kit.ratiosAvailable ? decimal(kit.kd) : "—";
    }

    private String kitLevelLabel(GoStatsBlitzClient.Kit kit) {
        if (kit == null) {
            return "—";
        }
        if (isUnupgradableKitName(kit.name)) {
            return "";
        }
        long prestige = displayedKitPrestige(kit);
        if (prestige >= 2L) {
            return "✫✫";
        }
        if (prestige == 1L) {
            return "✫";
        }
        long level = displayedKitLevel(kit);
        if (level <= 0L && (kit.games > 0L || isBasicKitName(kit.name))) {
            level = 1L;
        }
        if (level >= 1L && level <= 10L) {
            return toRoman((int) level);
        }
        return level > 0L ? Long.toString(level) : "—";
    }

    private int kitLevelColor(GoStatsBlitzClient.Kit kit) {
        if (kit != null && isUnupgradableKitName(kit.name)) {
            return LEVEL_I;
        }
        if (kit == null || displayedKitPrestige(kit) > 0L) {
            return LEVEL_PRESTIGE;
        }
        long level = displayedKitLevel(kit);
        if (level <= 0L && (kit.games > 0L || isBasicKitName(kit.name))) {
            level = 1L;
        }
        switch ((int) level) {
            case 1: return LEVEL_I;
            case 2: return LEVEL_II;
            case 3: return LEVEL_III;
            case 4: return LEVEL_IV;
            case 5: return LEVEL_V;
            case 6: return LEVEL_VI;
            case 7: return LEVEL_VII;
            case 8: return LEVEL_VIII;
            case 9: return LEVEL_IX;
            case 10: return LEVEL_X;
            default: return MUTED;
        }
    }

    private long displayedKitLevel(GoStatsBlitzClient.Kit kit) {
        long level = kit == null ? 0L : kit.level;
        if (kit != null && kit.activity != null && kit.activity.levelAfter >= 0L) {
            level = Math.max(level, kit.activity.levelAfter);
        }
        return level;
    }

    private long displayedKitPrestige(GoStatsBlitzClient.Kit kit) {
        long prestige = kit == null ? 0L : kit.prestige;
        if (kit != null && kit.activity != null && kit.activity.prestigeAfter >= 0L) {
            prestige = Math.max(prestige, kit.activity.prestigeAfter);
        }
        return prestige;
    }

    private String kitTitle(GoStatsBlitzClient.Kit kit) {
        if (kit == null) return "—";
        String name = GoStatsBlitzClient.displayKitName(kit.name);
        String level = kitLevelLabel(kit);
        return level.isEmpty() ? name : name + " " + level;
    }

    private boolean isUnupgradableKitName(String name) {
        return name != null && "rambo".equals(name.trim().toLowerCase(Locale.ROOT));
    }

    private boolean isBasicKitName(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return "archer".equals(normalized) || "meatmaster".equals(normalized)
                || "speleologist".equals(normalized) || "baker".equals(normalized)
                || "knight".equals(normalized) || "guardian".equals(normalized)
                || "scout".equals(normalized) || "hunter".equals(normalized)
                || "hype train".equals(normalized) || "fisherman".equals(normalized)
                || "armorer".equals(normalized);
    }

    private String toRoman(int value) {
        switch (value) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            case 6: return "VI";
            case 7: return "VII";
            case 8: return "VIII";
            case 9: return "IX";
            case 10: return "X";
            default: return Integer.toString(value);
        }
    }

    private String formatDuration(long seconds) {
        long hours = Math.max(0L, seconds) / 3600L;
        long minutes = (Math.max(0L, seconds) % 3600L) / 60L;
        return hours + "h " + minutes + "m";
    }

    private float tickPreviewDisplayYaw(float partialTicks) {
        float delta = wrapYawDelta(this.previewYaw - this.previewDisplayYaw);
        float blend = PREVIEW_YAW_SMOOTH * (0.35F + partialTicks * 0.65F);
        this.previewDisplayYaw += delta * Math.min(1.0F, blend);
        return this.previewDisplayYaw;
    }

    private float tickPreviewDisplayPitch(float partialTicks) {
        float delta = this.previewPitch - this.previewDisplayPitch;
        float blend = PREVIEW_PITCH_SMOOTH * (0.35F + partialTicks * 0.65F);
        this.previewDisplayPitch += delta * Math.min(1.0F, blend);
        return this.previewDisplayPitch;
    }

    private static float wrapYawDelta(float degrees) {
        float delta = degrees % 360.0F;
        if (delta > 180.0F) {
            delta -= 360.0F;
        } else if (delta < -180.0F) {
            delta += 360.0F;
        }
        return delta;
    }

    private String trim(String text, int maxWidth) {
        if (text == null || maxWidth <= 0) return "";
        if (fontRendererObj.getStringWidth(text) <= maxWidth) {
            return text;
        }
        int suffixWidth = fontRendererObj.getStringWidth("...");
        if (suffixWidth >= maxWidth) return fontRendererObj.trimStringToWidth(text, maxWidth);
        return fontRendererObj.trimStringToWidth(text, maxWidth - suffixWidth) + "...";
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            String normalized = value.replace("-", "").trim();
            if (!normalized.matches("[0-9a-fA-F]{32}")) {
                return null;
            }
            return UUID.fromString(normalized.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                    "$1-$2-$3-$4-$5"));
        } catch (Exception ignored) {
            return null;
        }
    }
}
