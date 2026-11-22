package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.MountHeadDim;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.EnumChatFormatting;

/**
 * Transparent mounts: live previews, master ON/OFF, per-type opacity sliders.
 * Layout anchors main content from {@link GuiHeader#STANDARD_SUBTITLE_Y} (same band as Miscellaneous description).
 */
public class TransparentMountsGUI extends GuiScreen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 200;
    private static final int ROW = 24;
    private static final int PREVIEW_BAND = 52;
    /** Max width for the five previews (tighter = mounts closer). */
    private static final int PREVIEW_ROW_MAX_WIDTH = 188;
    /** Scales: boat, pig, horse, donkey, minecart — matches {@link MountPreviewHelper#createMountPreviews} order. */
    private static final int[] PREVIEW_SCALES = {18, 26, 22, 22, 16};

    private final GuiScreen parent;
    private GuiButton toggleButton;
    private GuiButton backButton;
    private MountOpacitySlider sliderHorse;
    private MountOpacitySlider sliderDonkey;
    private MountOpacitySlider sliderPig;
    private MountOpacitySlider sliderBoat;
    private MountOpacitySlider sliderWagon;
    private Entity[] previewEntities = new Entity[5];
    /** 0 boat, 1 pig, 2 horse, 3 donkey, 4 minecart — which preview shows your player riding. */
    private int previewPlayerOnSlot = 2;
    private GuiButton selectedButton;
    private final ModuleSettingsEnabledOverlays enabledOverlays = new ModuleSettingsEnabledOverlays();

    /** First Y for mount previews (below standard title row). */
    private int layoutPreviewBaseY;
    private int layoutToggleY;
    private int layoutBackY;

    public TransparentMountsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        ScaledResolution sr = new ScaledResolution(mc);
        int cx = sr.getScaledWidth() / 2;
        int sh = sr.getScaledHeight();

        previewEntities = MountPreviewHelper.createMountPreviews(mc.theWorld, mc);
        MountPreviewHelper.placeLocalPlayerOnPreviewSlot(previewEntities, mc, previewPlayerOnSlot);

        final int gapBeforeToggle = 5;
        final int gapAfterToggle = 7;
        final int sliderBlockH = 5 * ROW;

        layoutBackY = GuiHeader.backButtonY(sh, BUTTON_HEIGHT);

        // Calculate total content height and center it vertically
        final int totalContentHeight = PREVIEW_BAND + gapBeforeToggle + BUTTON_HEIGHT + gapAfterToggle + sliderBlockH;
        int centerY = sh / 2;
        layoutPreviewBaseY = Math.max(GuiHeader.STANDARD_SUBTITLE_Y, centerY - totalContentHeight / 2);
        
        layoutToggleY = layoutPreviewBaseY + PREVIEW_BAND + gapBeforeToggle;
        int firstSliderY = layoutToggleY + BUTTON_HEIGHT + gapAfterToggle;
        int lastSliderBottom = firstSliderY + sliderBlockH;
        if (lastSliderBottom > layoutBackY - 8) {
            int shift = lastSliderBottom - (layoutBackY - 8);
            int minPreviewTop = GuiHeader.standardContentTopAfterTitle(mc.fontRendererObj);
            layoutPreviewBaseY = Math.max(minPreviewTop, layoutPreviewBaseY - shift);
            layoutToggleY = layoutPreviewBaseY + PREVIEW_BAND + gapBeforeToggle;
            firstSliderY = layoutToggleY + BUTTON_HEIGHT + gapAfterToggle;
        }

        int y = firstSliderY;
        toggleButton = new GuiButton(1, cx - BUTTON_WIDTH / 2, layoutToggleY,
                BUTTON_WIDTH, BUTTON_HEIGHT, getToggleButtonText());

        sliderHorse = new MountOpacitySlider(101, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH,
                "Horse opacity:", ConfigManager.getMountAlphaHorse());
        y += ROW;
        sliderDonkey = new MountOpacitySlider(102, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH,
                "Donkey opacity:", ConfigManager.getMountAlphaDonkey());
        y += ROW;
        sliderPig = new MountOpacitySlider(103, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH,
                "Pig opacity:", ConfigManager.getMountAlphaPig());
        y += ROW;
        sliderBoat = new MountOpacitySlider(104, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH,
                "Boat opacity:", ConfigManager.getMountAlphaBoat());
        y += ROW;
        sliderWagon = new MountOpacitySlider(105, cx - BUTTON_WIDTH / 2, y, BUTTON_WIDTH,
                "Minecart opacity:", ConfigManager.getMountAlphaWagon());
        y += ROW;

        backButton = new GuiButton(99, cx - BUTTON_WIDTH / 2, layoutBackY,
                BUTTON_WIDTH, BUTTON_HEIGHT, EnumChatFormatting.GRAY + "Back to Menu");

        buttonList.clear();
        buttonList.add(toggleButton);
        buttonList.add(backButton);
        buttonList.add(sliderHorse);
        buttonList.add(sliderDonkey);
        buttonList.add(sliderPig);
        buttonList.add(sliderBoat);
        buttonList.add(sliderWagon);
        enabledOverlays.trackModuleToggle("main", toggleButton, new ModuleSettingsEnabledOverlays.EnabledCheck() {
            @Override
            public boolean isOn() {
                return MountHeadDim.isEnabled();
            }
        });
    }

    @Override
    public void onGuiClosed() {
        MountPreviewHelper.discardPreviewRider();
        super.onGuiClosed();
    }

    private void syncPreviewPlayerToSlider(int sliderId) {
        int slot;
        switch (sliderId) {
            case 104:
                slot = 0;
                break;
            case 103:
                slot = 1;
                break;
            case 101:
                slot = 2;
                break;
            case 102:
                slot = 3;
                break;
            case 105:
                slot = 4;
                break;
            default:
                return;
        }
        previewPlayerOnSlot = slot;
        MountPreviewHelper.placeLocalPlayerOnPreviewSlot(previewEntities, mc, previewPlayerOnSlot);
    }

    private String getToggleButtonText() {
        return "Transparent mounts: " + (MountHeadDim.isEnabled()
                ? EnumChatFormatting.GREEN + "ON"
                : EnumChatFormatting.RED + "OFF");
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);

        int cx = width / 2;
        String title = EnumChatFormatting.GRAY + "" + EnumChatFormatting.BOLD + "Transparent mounts";
        GuiHeader.drawTitleCenteredAtWithShadow(fontRendererObj, title, cx, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);

        drawMountPreviewRow();

        super.drawScreen(mouseX, mouseY, partialTicks);
        enabledOverlays.drawAll();
        GomodSearchHighlight.draw(buttonList, TransparentMountsGUI.class);
    }

    private void drawMountPreviewRow() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        int totalW = Math.min(width - 20, PREVIEW_ROW_MAX_WIDTH);
        int slotW = totalW / 5;
        int left = (width - slotW * 5) / 2;
        int baseY = layoutPreviewBaseY + PREVIEW_BAND - 4;

        for (int i = 0; i < 5; i++) {
            Entity e = previewEntities[i];
            if (e == null) {
                continue;
            }
            int px = left + i * slotW + slotW / 2;
            int scale = PREVIEW_SCALES[i];
            float alpha = MountHeadDim.getAlphaForMount(e);
            MountPreviewHelper.renderEntityPreview(mc, e, px, baseY, scale, alpha,
                    45.0F, alpha < 0.99F);
        }
    }

    @Override
    public void actionPerformed(GuiButton button) {
        switch (button.id) {
            case 1: {
                boolean now = !MountHeadDim.isEnabled();
                MountHeadDim.setEnabledSilent(now);
                toggleButton.displayString = getToggleButtonText();
                enabledOverlays.onToggled("main", now);
                break;
            }
            case 99:
                mc.displayGuiScreen(parent);
                break;
            case 101:
                if (button instanceof MountOpacitySlider) {
                    ConfigManager.setMountAlphaHorse(((MountOpacitySlider) button).getValue());
                }
                break;
            case 102:
                if (button instanceof MountOpacitySlider) {
                    ConfigManager.setMountAlphaDonkey(((MountOpacitySlider) button).getValue());
                }
                break;
            case 103:
                if (button instanceof MountOpacitySlider) {
                    ConfigManager.setMountAlphaPig(((MountOpacitySlider) button).getValue());
                }
                break;
            case 104:
                if (button instanceof MountOpacitySlider) {
                    ConfigManager.setMountAlphaBoat(((MountOpacitySlider) button).getValue());
                }
                break;
            case 105:
                if (button instanceof MountOpacitySlider) {
                    ConfigManager.setMountAlphaWagon(((MountOpacitySlider) button).getValue());
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for (GuiButton button : this.buttonList) {
            if (button instanceof MountOpacitySlider) {
                if (button.mousePressed(Minecraft.getMinecraft(), mouseX, mouseY)) {
                    this.selectedButton = button;
                    applySliderRuntime((MountOpacitySlider) button);
                    syncPreviewPlayerToSlider(button.id);
                    break;
                }
            }
        }
    }

    @Override
    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (this.selectedButton instanceof MountOpacitySlider) {
            MountOpacitySlider slider = (MountOpacitySlider) this.selectedButton;
            slider.mouseDragged(Minecraft.getMinecraft(), mouseX, mouseY);
            applySliderRuntime(slider);
            syncPreviewPlayerToSlider(slider.id);
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        MountOpacitySlider drag = (this.selectedButton instanceof MountOpacitySlider)
                ? (MountOpacitySlider) this.selectedButton
                : null;
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0 && drag != null) {
            applySliderRuntime(drag);
            syncPreviewPlayerToSlider(drag.id);
            ConfigManager.persistMountAlphas();
        }
    }

    private static void applySliderRuntime(MountOpacitySlider s) {
        float v = s.getValue();
        switch (s.id) {
            case 101:
                ConfigManager.setMountAlphaHorseRuntime(v);
                break;
            case 102:
                ConfigManager.setMountAlphaDonkeyRuntime(v);
                break;
            case 103:
                ConfigManager.setMountAlphaPigRuntime(v);
                break;
            case 104:
                ConfigManager.setMountAlphaBoatRuntime(v);
                break;
            case 105:
                ConfigManager.setMountAlphaWagonRuntime(v);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

/**
 * Vanilla-style opacity slider; range matches {@link ConfigManager} mount alphas (0.05–1.0).
 */
class MountOpacitySlider extends GuiButton {

    private static final float MIN = 0.05f;
    private static final float MAX = 1.0f;

    private final float minValue = MIN;
    private final float maxValue = MAX;
    private final String labelPrefix;
    private final boolean percentLabel;
    private float currentValue;
    private float sliderValue;
    private boolean dragging;
    private boolean interactionLocked;

    public MountOpacitySlider(int id, int x, int y, int width, String labelPrefix, float current) {
        this(id, x, y, width, labelPrefix, current, false);
    }

    public MountOpacitySlider(int id, int x, int y, int width, String labelPrefix, float current,
                              boolean percentLabel) {
        super(id, x, y, width, 20, "");
        this.labelPrefix = labelPrefix;
        this.percentLabel = percentLabel;
        this.currentValue = Math.max(MIN, Math.min(MAX, current));
        this.sliderValue = (this.currentValue - minValue) / (maxValue - minValue);
        updateLabel();
    }

    public void setInteractionLocked(boolean locked) {
        this.interactionLocked = locked;
        if (locked) {
            this.currentValue = 1.0F;
            this.sliderValue = (this.currentValue - minValue) / (maxValue - minValue);
        }
        updateLabel();
    }

    private void updateLabel() {
        if (interactionLocked) {
            this.displayString = labelPrefix + " 100%";
            return;
        }
        if (percentLabel) {
            this.displayString = labelPrefix + String.format(" %d%%", Math.round(currentValue * 100.0F));
        } else {
            this.displayString = labelPrefix + String.format(" x%.2f", currentValue);
        }
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) {
            return;
        }
        mc.getTextureManager().bindTexture(buttonTextures);
        boolean controlEnabled = enabled && !interactionLocked;
        float dim = controlEnabled ? 1.0F : 0.55F;
        net.minecraft.client.renderer.GlStateManager.color(dim, dim, dim, 1.0F);
        int v = 46;
        int half = this.width / 2;
        this.drawTexturedModalRect(this.xPosition, this.yPosition, 0, v, half, 20);
        this.drawTexturedModalRect(this.xPosition + half, this.yPosition, 200 - half, v, half, 20);

        int knobX = this.xPosition + (int) (this.sliderValue * (this.width - 8));
        this.drawTexturedModalRect(knobX, this.yPosition, 0, 66, 4, 20);
        this.drawTexturedModalRect(knobX + 4, this.yPosition, 196, 66, 4, 20);

        boolean hovered = controlEnabled && mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width && mouseY < this.yPosition + this.height;
        int textColor = !controlEnabled ? 0x707070 : (hovered ? 0xFFFFA0 : 0xE0E0E0);
        int textWidth = mc.fontRendererObj.getStringWidth(this.displayString);
        int textX = this.xPosition + (this.width - textWidth) / 2;
        int textY = this.yPosition + (this.height - 8) / 2;
        mc.fontRendererObj.drawString(this.displayString, textX, textY, textColor);
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!enabled || interactionLocked) {
            return false;
        }
        if (this.visible && mouseX >= this.xPosition && mouseX <= this.xPosition + this.width
                && mouseY >= this.yPosition && mouseY <= this.yPosition + this.height) {
            this.dragging = true;
            applyMouseX(mouseX);
            return true;
        }
        return false;
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        this.dragging = false;
    }

    @Override
    public void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
        if (!enabled || interactionLocked) {
            this.dragging = false;
            return;
        }
        if (this.visible && this.dragging) {
            applyMouseX(mouseX);
        }
    }

    private void applyMouseX(int mouseX) {
        int innerLeft = this.xPosition + 4;
        int innerRight = this.xPosition + this.width - 4;
        int clamped = Math.max(innerLeft, Math.min(innerRight, mouseX));
        float normalizedPos = (float) (clamped - innerLeft) / (float) (this.width - 8);
        normalizedPos = Math.max(0.0f, Math.min(1.0f, normalizedPos));
        this.sliderValue = normalizedPos;
        this.currentValue = minValue + normalizedPos * (maxValue - minValue);
        updateLabel();
    }

    public float getValue() {
        return currentValue;
    }
}
