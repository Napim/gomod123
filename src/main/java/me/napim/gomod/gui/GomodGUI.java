package me.napim.gomod.gui;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.features.LocalDisguise;
import me.napim.gomod.features.PlayerStandaloneCape;
import me.napim.gomod.features.TabStatsRuntimeMute;
import me.napim.gomod.util.GomodClasspathTextures;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Mouse;
import org.lwjgl.input.Keyboard;

import java.util.Collections;

public class GomodGUI extends GuiScreen implements GomodMenuKeyboardCaptureGuard {
    private static final int BUTTON_HEIGHT      = 20;
    private static final int SPACING            = 30;
    private static final int BUTTON_WIDTH       = 200;
    private static final int HUD_EDITOR_BUTTON_ID = 15;
    private static final int BLITZ_CHALLENGES_BUTTON_ID = 16;
    private static final int SIDEBAR_TWEAKS_TEXT_RGB = 0x005AFF;
    private static final int DISGUISE_TEXT_RGB = 0x796BFF;
    private static final String PUBLIC_RELEASE_FUTURE_TOOLTIP =
            EnumChatFormatting.GRAY + "Available in future versions";
    /** Height reserved above the button grid for the search bar (bar + gap). */
    private static final int SEARCH_RESERVED    = 28;

    private String title = EnumChatFormatting.DARK_GREEN + "" + EnumChatFormatting.BOLD + "gomod Menu" + EnumChatFormatting.RESET;
    private boolean gomod$ignoreInitialClick = true;

    // ── Search bar ────────────────────────────────────────────────────────────────
    private GomodSearchBar searchBar;

    // ── Animation state ───────────────────────────────────────────────────────
    private long animOpenStartMs = -1L;

    public GomodGUI() {
        this(true);
    }

    private GomodGUI(boolean animateOpen) {
        super();
        animOpenStartMs = animateOpen ? System.currentTimeMillis() : -1L;
    }

    public static GomodGUI forInternalNavigation() {
        return new GomodGUI(false);
    }

    // Gostats icon robust loader (mirrors GuiHeader strategy)
    private static final ResourceLocation GOSTATS_ICON = new ResourceLocation("gomod123:textures/gostatsicon1.png");
    private static final ResourceLocation GOSTATS_ICON_RT = new ResourceLocation("gomod123:runtime/gostatsicon1");
    private static final ResourceLocation EDIT_HUD_ICON = new ResourceLocation("gomod123", "textures/edithudicon.png");
    private static final int EDIT_HUD_ICON_DRAW_W = 12;
    private static final int EDIT_HUD_ICON_DRAW_H = 12;
    private static final int EDIT_HUD_ICON_GAP = 3;
    private static final int EDIT_HUD_CONTENT_OFFSET_X = -2;
    private static boolean gsTried = false;
    private static boolean gsLoaded = false;
    private static boolean gsUsingFallback = false;
    private static int gsW = 160;
    private static int gsH = 160;

    private static void ensureGostatsIconLoaded() {
        if (gsTried) return;
        gsTried = true;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (mc != null && mc.getResourceManager() != null) {
                try {
                    mc.getResourceManager().getResource(GOSTATS_ICON);
                    gsLoaded = true;
                    gsUsingFallback = false;
                    System.out.println("[gomod] gostats icon found: " + GOSTATS_ICON);
                    return;
                } catch (Exception ignored) {}
            }
            // Fallback: classpath
            try {
                java.io.InputStream s = GomodGUI.class.getResourceAsStream("/assets/gomod123/textures/gostatsicon1.png");
                if (s != null && mc != null && mc.getTextureManager() != null) {
                    java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(s);
                    if (img != null) {
                        gsW = img.getWidth();
                        gsH = img.getHeight();
                        net.minecraft.client.renderer.texture.DynamicTexture dyn = new net.minecraft.client.renderer.texture.DynamicTexture(img);
                        mc.getTextureManager().loadTexture(GOSTATS_ICON_RT, dyn);
                        gsLoaded = true;
                        gsUsingFallback = true;
                        System.out.println("[gomod] gostats icon loaded fallback: " + gsW + "x" + gsH);
                        try { s.close(); } catch (Exception ignored) {}
                        return;
                    }
                }
            } catch (Throwable t) {
                System.out.println("[gomod] gostats icon fallback error: " + t.getMessage());
            }
        } catch (Throwable t) {
            System.out.println("[gomod] ensureGostatsIconLoaded error: " + t.getMessage());
        }
        gsLoaded = false;
    }

    @Override
    public void initGui() {
        super.initGui();
        gomod$ignoreInitialClick = true;
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int centerX = sr.getScaledWidth() / 2;

        // ── Search bar (compact, green-themed) ────────────────────────────────────
        int barW = Math.min(248, sr.getScaledWidth() - 40);
        int barX = centerX - barW / 2;
        int barY = GuiHeader.STANDARD_SUBTITLE_Y + fontRendererObj.FONT_HEIGHT + 5;
        searchBar = new GomodSearchBar(fontRendererObj, barX, barY, barW);

        // ── Two-column button grid ────────────────────────────────────────────────
        int leftColumnX  = centerX - BUTTON_WIDTH - 10;
        int rightColumnX = centerX + 10;
        int buttonsPerColumn = 6;
        int titleHeight = 60;

        // Push the grid below the search bar; also keep it vertically centred on taller screens
        int rawStartY = (sr.getScaledHeight() - (buttonsPerColumn * BUTTON_HEIGHT + (buttonsPerColumn - 1) * SPACING)) / 2
                + titleHeight / 2 + 20;
        int minStartY = barY + GomodSearchBar.BAR_H + 10;
        int startY = Math.max(rawStartY, minStartY);

        // Clamp so the bottom row doesn't fall off-screen (account for featured Blitz row + grid)
        boolean showBlitzChallengesButton = GomodInternalFeatures.isBlitzChallengesEnabled();
        int gridStartY = showBlitzChallengesButton ? startY + SPACING : startY;
        int bottomRowY  = gridStartY + SPACING * 6 + 20;
        int maxBottomY  = sr.getScaledHeight() - BUTTON_HEIGHT - 2;
        if (bottomRowY > maxBottomY) {
            startY -= (bottomRowY - maxBottomY);
        }
        
        // Dev-only featured modules share a row above the main menu grid.
        GuiButton blitzChallengesButton = showBlitzChallengesButton
                ? new GuiButton(BLITZ_CHALLENGES_BUTTON_ID,
                        centerX - BUTTON_WIDTH / 2, startY, BUTTON_WIDTH, BUTTON_HEIGHT,
                        EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Blitz Challenges")
                : null;

        // LEFT COLUMN (6 buttons)
        // Inventory HUD button
        GuiButton invHudButton = new GuiButton(1, leftColumnX, gridStartY, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.AQUA + "Inventory HUD");
        
        // Perspective Settings button
        GuiButton perspectiveButton = new GuiButton(2, leftColumnX, gridStartY + SPACING, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.BLUE + "Perspective Settings");
        
        // gostats123 AI button
        GuiButton goStatsButton = new GuiButton(3, leftColumnX, gridStartY + SPACING * 2, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.DARK_GREEN + "gostats123 AI");
        
        // Map Queuer button
        GuiButton mapQueuerButton = new GuiButton(5, leftColumnX, gridStartY + SPACING * 3, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.LIGHT_PURPLE + "Map Queuer");
        
        // Player Queuer button
        GuiButton playerQueuerButton = new GuiButton(10, leftColumnX, gridStartY + SPACING * 4, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.DARK_PURPLE + "Player Queuer");
        
        // Teams Nick Finder button
        GuiButton teamSpammerButton = new GuiButton(6, leftColumnX, gridStartY + SPACING * 5, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.GOLD + "Teams Nick Finder");
        
        // RIGHT COLUMN (6 buttons)
        // Disguise button
        GuiButton kitLevelsButton = new HexLabelButton(7, rightColumnX, gridStartY, BUTTON_WIDTH, BUTTON_HEIGHT,
                "Disguise", DISGUISE_TEXT_RGB);
        
        // Requeue Settings button
        GuiButton winRequeueButton = new GuiButton(8, rightColumnX, gridStartY + SPACING, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.WHITE + "Requeue Settings");
        
        // TAB QoL button
        GuiButton killCounterButton = new GuiButton(9, rightColumnX, gridStartY + SPACING * 2, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.DARK_RED + "TAB QoL");
        
        // Sidebar tweaks button
        GuiButton sidebarTweaksButton = new HexLabelButton(11, rightColumnX, gridStartY + SPACING * 3, BUTTON_WIDTH, BUTTON_HEIGHT,
                "Sidebar tweaks", SIDEBAR_TWEAKS_TEXT_RGB);
        
        // Teammate Tracker button
        GuiButton teammateTrackerButton = new GuiButton(12, rightColumnX, gridStartY + SPACING * 4, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.GREEN + "Teammate Tracker");
        
        // Leaderboard button
        GuiButton leaderboardButton = new GuiButton(13, rightColumnX, gridStartY + SPACING * 5, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.GOLD + "Leaderboard");
        
        // Bottom row — same width/alignment as the two columns above
        int bottomY = gridStartY + SPACING * 6 + 20;
        GuiButton apiKeysButton = new GuiButton(4, leftColumnX, bottomY, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.YELLOW + "API Keys");
        GuiButton miscButton = new GuiButton(14, rightColumnX, bottomY, BUTTON_WIDTH, BUTTON_HEIGHT,
                EnumChatFormatting.GRAY + "Miscellaneous");
        GuiButton hudEditorButton = new GuiButton(HUD_EDITOR_BUTTON_ID, this.width - 92, 14, 82, BUTTON_HEIGHT, "");
        
        if (blitzChallengesButton != null) {
            this.buttonList.add(blitzChallengesButton);
        }
        this.buttonList.add(invHudButton);
        this.buttonList.add(perspectiveButton);
        this.buttonList.add(goStatsButton);
        this.buttonList.add(mapQueuerButton);
        this.buttonList.add(playerQueuerButton);
        this.buttonList.add(teamSpammerButton);
        this.buttonList.add(kitLevelsButton);
        this.buttonList.add(winRequeueButton);
        this.buttonList.add(killCounterButton);
        this.buttonList.add(sidebarTweaksButton);
        this.buttonList.add(teammateTrackerButton);
        this.buttonList.add(leaderboardButton);
        this.buttonList.add(apiKeysButton);
        this.buttonList.add(miscButton);
        this.buttonList.add(hudEditorButton);

        applyTabStatsMainMenuButtonState();
        applyPublicReleaseMenuButtonState();
    }

    private void applyPublicReleaseMenuButtonState() {
        if (!GomodInternalFeatures.isEnabled()) {
            for (GuiButton b : this.buttonList) {
                if (b == null) {
                    continue;
                }
                if (b.id == 7) {
                    b.enabled = true;
                    b.displayString = "Disguise";
                }
            }
        }
    }

    private void applyTabStatsMainMenuButtonState() {
        for (GuiButton b : this.buttonList) {
            if (b != null && b.id == 9) {
                boolean muted = TabStatsRuntimeMute.isMuted();
                b.enabled = !muted;
                b.displayString = muted
                        ? EnumChatFormatting.DARK_GRAY + "TAB QoL " + EnumChatFormatting.GRAY + "(/tss paused)"
                        : EnumChatFormatting.DARK_RED + "TAB QoL";
                break;
            }
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (searchBar != null) searchBar.tick();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // ── Zoom Pop open animation ──────────────────────────────────────────
        float scaleVal = 1f;
        if (animOpenStartMs >= 0L) {
            float raw = MenuAnimationManager.clamp01((System.currentTimeMillis() - animOpenStartMs) / 260f);
            scaleVal = 0.82f + MenuAnimationManager.easeOutBack(raw) * 0.18f;
        }

        drawDefaultBackground();

        float cx = width  * 0.5f;
        float cy = height * 0.5f;
        GlStateManager.pushMatrix();
        if (scaleVal != 1f) {
            GlStateManager.translate(cx, cy, 0f);
            GlStateManager.scale(scaleVal, scaleVal, 1f);
            GlStateManager.translate(-cx, -cy, 0f);
        }

        // ── Draw GUI content ──────────────────────────────────────────────────
        GuiHeader.drawTopLeft(this);

        if (gomod$ignoreInitialClick && !Mouse.isButtonDown(0) && !Mouse.isButtonDown(1)) {
            gomod$ignoreInitialClick = false;
        }

        // Draw title
        ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
        int centerX = sr.getScaledWidth() / 2;
        int titleY = GuiHeader.STANDARD_TITLE_Y;

        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, this.title, sr.getScaledWidth(), titleY, 0xFFFFFF);

        // Draw description
        String description = EnumChatFormatting.GRAY + "Select a feature to configure:";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, description, sr.getScaledWidth(), GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        super.drawScreen(mouseX, mouseY, partialTicks);
        applyTabStatsMainMenuButtonState();
        applyPublicReleaseMenuButtonState();

        // ── Search bar (drawn on top of buttons, inside the scale matrix) ─────────
        if (searchBar != null) {
            searchBar.draw(mouseX, mouseY);
        }

        // Draw gostats icon inside the "gostats123 AI" button on the main menu, right before the text
        try {
            // Find the GoStats button by id 3 (see initGui)
            for (GuiButton b : this.buttonList) {
                if (b != null && b.id == 3) {
                    // Compute text width to place icon immediately before it, with small gap
                    String label = b.displayString != null ? b.displayString : "gostats123 AI";
                    int textWidth = this.fontRendererObj.getStringWidth(label);
                    int drawW = 12;
                    int drawH = 12;
                    int textStartX = b.xPosition + (b.width - textWidth) / 2;
                    int x = Math.max(b.xPosition + 4, textStartX - (drawW + 4));
                    int y = b.yPosition + (b.height - drawH) / 2;
                    GlStateManager.enableBlend();
                    GlStateManager.color(1f, 1f, 1f, 1f);
                    ensureGostatsIconLoaded();
                    try {
                        if (gsLoaded) {
                            Minecraft.getMinecraft().getTextureManager().bindTexture(gsUsingFallback ? GOSTATS_ICON_RT : GOSTATS_ICON);
                            net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(
                                x, y,
                                0, 0,
                                gsW, gsH,
                                drawW, drawH,
                                gsW, gsH
                            );
                        }
                    } catch (Throwable ignored) {}
                    GlStateManager.color(1f, 1f, 1f, 1f);
                    break;
                }
            }
        } catch (Throwable ignored) {}

        drawHudEditorButtonContents();
        drawPublicReleaseDisabledTooltips(mouseX, mouseY);

        // Color override removed - button now uses its original color (RED)

        GlStateManager.popMatrix();
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        if (searchBar != null) {
            int mx = org.lwjgl.input.Mouse.getEventX() * width / mc.displayWidth;
            int my = height - org.lwjgl.input.Mouse.getEventY() * height / mc.displayHeight - 1;
            searchBar.handleMouseWheel(mx, my);
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        if (gomod$ignoreInitialClick) {
            return;
        }
        // Let the search bar handle clicks first
        if (searchBar != null) {
            GomodSearchEntry selected = searchBar.mouseClicked(mouseX, mouseY, mouseButton);
            if (selected != null) {
                navigateTo(selected);
                return;
            }
            // If click was inside search bar region, don't forward to buttons
            if (searchBar.isMouseOver(mouseX, mouseY)) {
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private void navigateTo(GomodSearchEntry entry) {
        if (entry != null && GomodInternalFeatures.isRestrictedSearchEntry(entry.label)) {
            return;
        }
        if (entry.highlightButtonId >= 0 && entry.highlightGuiClass != null) {
            GomodSearchHighlight.schedule(entry.highlightButtonId, entry.highlightGuiClass);
        } else {
            GomodSearchHighlight.clear();
        }
        mc.displayGuiScreen(entry.factory.create(this));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws java.io.IOException {
        // Let search bar consume keypresses when active
        if (searchBar != null) {
            boolean consumed = searchBar.keyTyped(typedChar, keyCode);
            if (consumed) {
                // ESC while search has content clears search but doesn't close the menu
                return;
            }
        }
        if (keyCode == 1) { // ESC
            GomodMenuTransitions.closeToGame(mc);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean shouldBlockGomodMenuMovementAndDragLook() {
        return searchBar != null && searchBar.shouldBlockMenuMovement();
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 0:
                // Close
                GomodMenuTransitions.closeToGame(this.mc);
                break;
            case 1:
                // Open Inventory HUD GUI
                this.mc.displayGuiScreen(new InventoryHUDGUI());
                break;
            case 2:
                // Open Perspective Settings GUI
                this.mc.displayGuiScreen(new PerspectiveGUI());
                break;
            case 3:
                // Open gostats123 AI GUI
                this.mc.displayGuiScreen(new GoStatsGUI());
                break;
            case 4:
                // Open API Keys GUI
                this.mc.displayGuiScreen(new ApiKeyGUI(this));
                break;
            case 5:
                // Open Map Queuer GUI
                this.mc.displayGuiScreen(new me.napim.gomod.gui.MapQueuerGUI(this));
                break;
            case 10:
                // Open Player Queuer GUI
                this.mc.displayGuiScreen(new me.napim.gomod.gui.PlayerQueuerGUI(this));
                break;
            case 6:
                // Open Team Spammer GUI
                this.mc.displayGuiScreen(new TeamSpamGUI(this));
                break;
            case 7:
                if (GomodInternalFeatures.isEnabled()) {
                    // Internal/dev builds keep the complete disguise settings screen.
                    this.mc.displayGuiScreen(new DisguiseGUI(this));
                } else {
                    // Public builds expose only standalone capes, so skip the empty wrapper screen.
                    LocalDisguise.prepareSelfCapeCatalogForPicker();
                    PlayerStandaloneCape.ensureCatalogLoaded();
                    this.mc.displayGuiScreen(new MinecraftCapeSelectorGUI(
                            this, MinecraftCapeSelectorGUI.Mode.SELF));
                }
                break;
            case 8:
                // Open Requeue Settings GUI
                this.mc.displayGuiScreen(new AutoQueueGUI());
                break;
            case 9:
                // Open TAB QoL Settings GUI
                if (TabStatsRuntimeMute.isMuted()) {
                    return;
                }
                this.mc.displayGuiScreen(new KillCounterSettingsGUI());
                break;
            case 11:
                // Open Sidebar tweaks GUI
                this.mc.displayGuiScreen(new SidebarTweaksGUI(this));
                break;
            case 12:
                // Open Teammate Tracker GUI
                this.mc.displayGuiScreen(new TeammateTrackerGUI(this));
                break;
            case 14:
                // Open Miscellaneous GUI
                this.mc.displayGuiScreen(new MiscellaneousGUI(this));
                break;
            case 13:
                // Open Leaderboard GUI
                this.mc.displayGuiScreen(new LeaderboardGUI(this));
                break;
            case BLITZ_CHALLENGES_BUTTON_ID:
                if (!GomodInternalFeatures.isBlitzChallengesEnabled()) {
                    return;
                }
                this.mc.displayGuiScreen(new BlitzChallengesGUI(this));
                break;
            case HUD_EDITOR_BUTTON_ID:
                // Open HUD editor
                this.mc.displayGuiScreen(new GomodHudEditorGUI(this));
                break;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawHudEditorButtonContents() {
        GuiButton button = null;
        for (GuiButton candidate : this.buttonList) {
            if (candidate != null && candidate.id == HUD_EDITOR_BUTTON_ID) {
                button = candidate;
                break;
            }
        }
        if (button == null || !button.visible || fontRendererObj == null) {
            return;
        }

        String label = EnumChatFormatting.YELLOW + "Edit HUD";
        int textWidth = fontRendererObj.getStringWidth(label);
        int contentWidth = EDIT_HUD_ICON_DRAW_W + EDIT_HUD_ICON_GAP + textWidth;
        int contentX = button.xPosition + Math.max(0, (button.width - contentWidth) / 2) + EDIT_HUD_CONTENT_OFFSET_X;
        int iconX = contentX;
        int iconY = button.yPosition + (button.height - EDIT_HUD_ICON_DRAW_H) / 2;
        int textX = iconX + EDIT_HUD_ICON_DRAW_W + EDIT_HUD_ICON_GAP;
        int textY = button.yPosition + (button.height - 8) / 2;

        try {
            GomodClasspathTextures.bindTexture(mc, EDIT_HUD_ICON);
            int texW = GomodClasspathTextures.getWidth(EDIT_HUD_ICON, EDIT_HUD_ICON_DRAW_W);
            int texH = GomodClasspathTextures.getHeight(EDIT_HUD_ICON, EDIT_HUD_ICON_DRAW_H);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1f, 1f, 1f, button.enabled ? 0.96f : 0.5f);
            net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(iconX, iconY, 0, 0, texW, texH,
                    EDIT_HUD_ICON_DRAW_W, EDIT_HUD_ICON_DRAW_H, texW, texH);
        } catch (Throwable ignored) {
        } finally {
            GlStateManager.color(1f, 1f, 1f, 1f);
        }

        fontRendererObj.drawStringWithShadow(label, textX, textY, button.enabled ? 0xFFFFFF : 0xFFA0A0A0);
    }

    private void drawPublicReleaseDisabledTooltips(int mouseX, int mouseY) {
        if (GomodInternalFeatures.isEnabled()) {
            return;
        }
        for (GuiButton button : this.buttonList) {
            if (button == null || button.enabled) {
                continue;
            }
            if (button.id != 7) {
                continue;
            }
            if (isHoveringButton(button, mouseX, mouseY)) {
                drawHoveringText(Collections.singletonList(PUBLIC_RELEASE_FUTURE_TOOLTIP),
                        mouseX, Math.max(0, mouseY - 12));
                return;
            }
        }
    }

    private static boolean isHoveringButton(GuiButton button, int mouseX, int mouseY) {
        return button != null && button.visible
                && mouseX >= button.xPosition && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition && mouseY < button.yPosition + button.height;
    }

    private static int dimRgb(int rgb) {
        int r = ((rgb >> 16) & 0xFF) * 3 / 5;
        int g = ((rgb >> 8) & 0xFF) * 3 / 5;
        int b = (rgb & 0xFF) * 3 / 5;
        return (r << 16) | (g << 8) | b;
    }

    private static String stripFormatting(String text) {
        return text == null ? "" : text.replaceAll("\u00A7.", "");
    }

    private static final class HexLabelButton extends GuiButton {
        private final int enabledRgb;

        private HexLabelButton(int id, int x, int y, int width, int height, String text, int enabledRgb) {
            super(id, x, y, width, height, text);
            this.enabledRgb = enabledRgb;
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!this.visible) {
                return;
            }
            String label = this.displayString;
            this.displayString = "";
            super.drawButton(mc, mouseX, mouseY);
            this.displayString = label;
            if (mc == null || mc.fontRendererObj == null) {
                return;
            }
            String plain = stripFormatting(label);
            int textColor = this.enabled ? enabledRgb : dimRgb(enabledRgb);
            int textX = this.xPosition + (this.width - mc.fontRendererObj.getStringWidth(plain)) / 2;
            int textY = this.yPosition + (this.height - 8) / 2;
            mc.fontRendererObj.drawStringWithShadow(plain, textX, textY, textColor);
        }
    }
}
