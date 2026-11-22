package me.napim.gomod.gui;



import me.napim.gomod.features.ConfigManager;

import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.GuiButton;

import me.napim.gomod.mixins.GuiButtonInvoker;

import net.minecraft.client.gui.GuiScreen;

import net.minecraft.client.gui.ScaledResolution;

import net.minecraft.util.EnumChatFormatting;



/**

 * GUI for configuring AutoQueue (win requeue) and death requeue — solo and teams toggles separately.

 */

public class AutoQueueGUI extends GuiScreen {

    private static final int BUTTON_HEIGHT = 20;

    private static final int SPACING = 30;

    /** Full width of each settings row (slider + gap + toggle); Back uses the same for alignment */

    private static final int ROW_WIDTH = 200;

    private static final int TOGGLE_BTN_WIDTH = 42;

    private static final int SLIDER_TOGGLE_GAP = 4;

    private static final int SLIDER_WIDTH = ROW_WIDTH - TOGGLE_BTN_WIDTH - SLIDER_TOGGLE_GAP;



    private GuiButton winSoloToggleButton;

    private GuiButton winTeamsToggleButton;

    private GuiButton deathSoloToggleButton;

    private GuiButton deathTeamsToggleButton;

    private GuiButton backButton;



    private SoloDelaySlider soloDelaySlider;

    private TeamsDelaySlider teamsDelaySlider;

    private DeathRequeueSoloDelaySlider deathRequeueSoloDelaySlider;

    private DeathRequeueTeamsDelaySlider deathRequeueTeamsDelaySlider;



    private GuiButton selectedButton = null;



    private static String onOffLabel(boolean on) {

        return on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF";

    }



    @Override

    public void initGui() {

        super.initGui();



        ScaledResolution scaledResolution = new ScaledResolution(Minecraft.getMinecraft());

        int centerX = scaledResolution.getScaledWidth() / 2;



        int totalRows = 5;

        int totalHeight = totalRows * BUTTON_HEIGHT + (totalRows - 1) * SPACING;

        int titleAndDescHeight = 60;

        int availableHeight = scaledResolution.getScaledHeight() - titleAndDescHeight;

        int startY = titleAndDescHeight + (availableHeight - totalHeight) / 2;



        int rowLeft = centerX - ROW_WIDTH / 2;

        int toggleX = rowLeft + SLIDER_WIDTH + SLIDER_TOGGLE_GAP;



        int yPos = startY;



        soloDelaySlider = new SoloDelaySlider(1, rowLeft, yPos, SLIDER_WIDTH,

                "Win Solo Delay", 0.0f, 5.0f, ConfigManager.getAutoQueueSoloDelay());

        winSoloToggleButton = new GuiButton(10, toggleX, yPos, TOGGLE_BTN_WIDTH, BUTTON_HEIGHT,

                onOffLabel(ConfigManager.isAutoQueueSoloEnabled()));

        yPos += SPACING;



        teamsDelaySlider = new TeamsDelaySlider(2, rowLeft, yPos, SLIDER_WIDTH,

                "Win Teams Delay", 0.0f, 5.0f, ConfigManager.getAutoQueueTeamsDelay());

        winTeamsToggleButton = new GuiButton(11, toggleX, yPos, TOGGLE_BTN_WIDTH, BUTTON_HEIGHT,

                onOffLabel(ConfigManager.isAutoQueueTeamsEnabled()));

        yPos += SPACING;



        deathRequeueSoloDelaySlider = new DeathRequeueSoloDelaySlider(4, rowLeft, yPos, SLIDER_WIDTH,

                "Death Solo Delay", 0.0f, 5.0f, ConfigManager.getDeathRequeueSoloDelay());

        deathSoloToggleButton = new GuiButton(12, toggleX, yPos, TOGGLE_BTN_WIDTH, BUTTON_HEIGHT,

                onOffLabel(ConfigManager.isDeathRequeueSoloEnabled()));

        yPos += SPACING;



        deathRequeueTeamsDelaySlider = new DeathRequeueTeamsDelaySlider(5, rowLeft, yPos, SLIDER_WIDTH,

                "Death Teams Delay", 0.0f, 5.0f, ConfigManager.getDeathRequeueTeamsDelay());

        deathTeamsToggleButton = new GuiButton(13, toggleX, yPos, TOGGLE_BTN_WIDTH, BUTTON_HEIGHT,

                onOffLabel(ConfigManager.isDeathRequeueTeamsEnabled()));

        yPos += SPACING;



        backButton = new GuiButton(99, rowLeft, yPos, ROW_WIDTH, BUTTON_HEIGHT,

                EnumChatFormatting.GRAY + "Back to Menu");



        this.buttonList.add(soloDelaySlider);

        this.buttonList.add(winSoloToggleButton);

        this.buttonList.add(teamsDelaySlider);

        this.buttonList.add(winTeamsToggleButton);

        this.buttonList.add(deathRequeueSoloDelaySlider);

        this.buttonList.add(deathSoloToggleButton);

        this.buttonList.add(deathRequeueTeamsDelaySlider);

        this.buttonList.add(deathTeamsToggleButton);

        this.buttonList.add(backButton);

    }



    @Override

    public void drawScreen(int mouseX, int mouseY, float partialTicks) {

        drawDefaultBackground();

        GuiHeader.drawTopLeft(this);



        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, EnumChatFormatting.WHITE + "" + EnumChatFormatting.BOLD + "Requeue Settings", width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);



        String description = EnumChatFormatting.GRAY + "Enable solo vs teams separately; delays apply per mode";

        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, description, width, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);



        super.drawScreen(mouseX, mouseY, partialTicks);
        GomodSearchHighlight.draw(buttonList, AutoQueueGUI.class);
    }



    @Override

    public void actionPerformed(GuiButton button) {

        switch (button.id) {

            case 10:

                ConfigManager.setAutoQueueSoloEnabled(!ConfigManager.isAutoQueueSoloEnabled());

                button.displayString = onOffLabel(ConfigManager.isAutoQueueSoloEnabled());

                break;

            case 11:

                ConfigManager.setAutoQueueTeamsEnabled(!ConfigManager.isAutoQueueTeamsEnabled());

                button.displayString = onOffLabel(ConfigManager.isAutoQueueTeamsEnabled());

                break;

            case 12:

                ConfigManager.setDeathRequeueSoloEnabled(!ConfigManager.isDeathRequeueSoloEnabled());

                button.displayString = onOffLabel(ConfigManager.isDeathRequeueSoloEnabled());

                break;

            case 13:

                ConfigManager.setDeathRequeueTeamsEnabled(!ConfigManager.isDeathRequeueTeamsEnabled());

                button.displayString = onOffLabel(ConfigManager.isDeathRequeueTeamsEnabled());

                break;

            case 1:

                if (button instanceof SoloDelaySlider) {

                    SoloDelaySlider slider = (SoloDelaySlider) button;

                    ConfigManager.setAutoQueueSoloDelay(slider.getValue());

                }

                break;

            case 2:

                if (button instanceof TeamsDelaySlider) {

                    TeamsDelaySlider slider = (TeamsDelaySlider) button;

                    ConfigManager.setAutoQueueTeamsDelay(slider.getValue());

                }

                break;

            case 4:

                if (button instanceof DeathRequeueSoloDelaySlider) {

                    DeathRequeueSoloDelaySlider slider = (DeathRequeueSoloDelaySlider) button;

                    ConfigManager.setDeathRequeueSoloDelay(slider.getValue());

                }

                break;

            case 5:

                if (button instanceof DeathRequeueTeamsDelaySlider) {

                    DeathRequeueTeamsDelaySlider slider = (DeathRequeueTeamsDelaySlider) button;

                    ConfigManager.setDeathRequeueTeamsDelay(slider.getValue());

                }

                break;

            case 99:

                GomodMenuTransitions.openMainMenuWithoutAnimation(Minecraft.getMinecraft());

                break;

            default:

                break;

        }

    }



    @Override

    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws java.io.IOException {

        super.mouseClicked(mouseX, mouseY, mouseButton);



        for (GuiButton b : this.buttonList) {

            if (b instanceof SoloDelaySlider || b instanceof TeamsDelaySlider

                    || b instanceof DeathRequeueSoloDelaySlider || b instanceof DeathRequeueTeamsDelaySlider) {

                if (b.mousePressed(Minecraft.getMinecraft(), mouseX, mouseY)) {

                    this.selectedButton = b;

                    break;

                }

            }

        }

    }



    @Override

    public void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {

        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);



        if (this.selectedButton instanceof SoloDelaySlider || this.selectedButton instanceof TeamsDelaySlider

                || this.selectedButton instanceof DeathRequeueSoloDelaySlider || this.selectedButton instanceof DeathRequeueTeamsDelaySlider) {

            if (this.selectedButton instanceof GuiButtonInvoker) {

                ((GuiButtonInvoker) this.selectedButton).gomod$invokeMouseDragged(Minecraft.getMinecraft(), mouseX, mouseY);

            }



            if (this.selectedButton instanceof SoloDelaySlider) {

                SoloDelaySlider slider = (SoloDelaySlider) this.selectedButton;

                ConfigManager.setAutoQueueSoloDelay(slider.getValue());

            } else if (this.selectedButton instanceof TeamsDelaySlider) {

                TeamsDelaySlider slider = (TeamsDelaySlider) this.selectedButton;

                ConfigManager.setAutoQueueTeamsDelay(slider.getValue());

            } else if (this.selectedButton instanceof DeathRequeueSoloDelaySlider) {

                DeathRequeueSoloDelaySlider slider = (DeathRequeueSoloDelaySlider) this.selectedButton;

                ConfigManager.setDeathRequeueSoloDelay(slider.getValue());

            } else if (this.selectedButton instanceof DeathRequeueTeamsDelaySlider) {

                DeathRequeueTeamsDelaySlider slider = (DeathRequeueTeamsDelaySlider) this.selectedButton;

                ConfigManager.setDeathRequeueTeamsDelay(slider.getValue());

            }

        }

    }



    @Override

    public void mouseReleased(int mouseX, int mouseY, int state) {

        super.mouseReleased(mouseX, mouseY, state);



        if (this.selectedButton instanceof SoloDelaySlider || this.selectedButton instanceof TeamsDelaySlider

                || this.selectedButton instanceof DeathRequeueSoloDelaySlider || this.selectedButton instanceof DeathRequeueTeamsDelaySlider) {

            this.selectedButton.mouseReleased(mouseX, mouseY);



            if (this.selectedButton instanceof SoloDelaySlider) {

                SoloDelaySlider slider = (SoloDelaySlider) this.selectedButton;

                ConfigManager.setAutoQueueSoloDelay(slider.getValue());

            } else if (this.selectedButton instanceof TeamsDelaySlider) {

                TeamsDelaySlider slider = (TeamsDelaySlider) this.selectedButton;

                ConfigManager.setAutoQueueTeamsDelay(slider.getValue());

            } else if (this.selectedButton instanceof DeathRequeueSoloDelaySlider) {

                DeathRequeueSoloDelaySlider slider = (DeathRequeueSoloDelaySlider) this.selectedButton;

                ConfigManager.setDeathRequeueSoloDelay(slider.getValue());

            } else if (this.selectedButton instanceof DeathRequeueTeamsDelaySlider) {

                DeathRequeueTeamsDelaySlider slider = (DeathRequeueTeamsDelaySlider) this.selectedButton;

                ConfigManager.setDeathRequeueTeamsDelay(slider.getValue());

            }



            this.selectedButton = null;

        }

    }



    @Override

    public boolean doesGuiPauseGame() {

        return false;

    }

}

