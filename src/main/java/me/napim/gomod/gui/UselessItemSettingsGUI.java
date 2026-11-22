package me.napim.gomod.gui;

import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.UselessItemOverlay;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

/**
 * Blitz-only useless overlay: optional always-red items and cross-tier sword vs Sharp I preferences.
 */
public class UselessItemSettingsGUI extends GuiScreen {

    private static final int BTN_BACK = 0;
    private static final int BTN_BOWL = 1;
    private static final int BTN_CAKE = 2;
    private static final int BTN_ROSE = 3;
    private static final int BTN_STORAGE_MINECART = 4;
    private static final int BTN_MINECART = 5;
    private static final int BTN_RAILS = 6;
    private static final int BTN_IRON_RED = 7;
    private static final int BTN_STONE_SHARP_RED = 8;
    private static final int BTN_DIAMOND_RED = 9;
    private static final int BTN_IRON_SHARP_RED = 10;
    private static final int BTN_STONE_RED = 11;
    private static final int BTN_GOLD_WOOD_SHARP_RED = 12;
    private static final int BTN_WHEAT = 13;
    private static final int BTN_SHARPNESS_FISH = 14;
    private static final int BTN_APPLE = 15;
    private static final int BTN_STICK = 16;

    private static final int BTN_SPLIT_W = 140;
    private static final int BTN_H = 20;
    private static final int ROW = 24;

    private final GuiScreen parent;
    private GuiButton bowlButton;
    private GuiButton cakeButton;
    private GuiButton roseButton;
    private GuiButton storageMinecartButton;
    private GuiButton minecartButton;
    private GuiButton railsButton;
    private GuiButton ironRedButton;
    private GuiButton stoneSharpRedButton;
    private GuiButton diamondRedButton;
    private GuiButton ironSharpRedButton;
    private GuiButton stoneRedButton;
    private GuiButton goldWoodSharpRedButton;
    private GuiButton wheatButton;
    private GuiButton sharpnessFishButton;
    private GuiButton appleButton;
    private GuiButton stickButton;
    private GuiButton backButton;

    private int questionLabelY;
    private int compare1LabelY;
    private int compare2LabelY;
    private int compare3LabelY;

    public UselessItemSettingsGUI(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();
        UselessItemOverlay.loadSettings();
        int cx = width / 2;
        int splitGap = 10;
        int leftSplitX = cx - splitGap / 2 - BTN_SPLIT_W;
        int rightSplitX = cx + splitGap / 2;
        int totalContentHeight = 12 + (5 * ROW) + 12 + (3 * (14 + ROW)) + 16 + BTN_H;
        int contentTop = Math.max(GuiHeader.standardContentTopAfterSubtitle(fontRendererObj), (height - totalContentHeight) / 2);
        int y = contentTop;

        questionLabelY = y;
        y += 12;

        bowlButton = new GuiButton(BTN_BOWL, leftSplitX, y, BTN_SPLIT_W, BTN_H, "Bowl");
        buttonList.add(bowlButton);
        cakeButton = new GuiButton(BTN_CAKE, rightSplitX, y, BTN_SPLIT_W, BTN_H, "Cake");
        buttonList.add(cakeButton);
        y += ROW;
        roseButton = new GuiButton(BTN_ROSE, leftSplitX, y, BTN_SPLIT_W, BTN_H, "KB 1 Rose");
        buttonList.add(roseButton);
        railsButton = new GuiButton(BTN_RAILS, rightSplitX, y, BTN_SPLIT_W, BTN_H, "Rails");
        buttonList.add(railsButton);
        y += ROW;
        minecartButton = new GuiButton(BTN_MINECART, leftSplitX, y, BTN_SPLIT_W, BTN_H, "Minecart");
        buttonList.add(minecartButton);
        storageMinecartButton = new GuiButton(BTN_STORAGE_MINECART, rightSplitX, y, BTN_SPLIT_W, BTN_H, "Storage minecart");
        buttonList.add(storageMinecartButton);
        y += ROW;
        wheatButton = new GuiButton(BTN_WHEAT, leftSplitX, y, BTN_SPLIT_W, BTN_H, "Wheat");
        buttonList.add(wheatButton);
        sharpnessFishButton = new GuiButton(BTN_SHARPNESS_FISH, rightSplitX, y, BTN_SPLIT_W, BTN_H, "Sharpness I fish");
        buttonList.add(sharpnessFishButton);
        y += ROW;
        appleButton = new GuiButton(BTN_APPLE, leftSplitX, y, BTN_SPLIT_W, BTN_H, "Apples (2+)");
        buttonList.add(appleButton);
        stickButton = new GuiButton(BTN_STICK, rightSplitX, y, BTN_SPLIT_W, BTN_H, "Sticks (2+)");
        buttonList.add(stickButton);

        y += ROW + 12;
        compare1LabelY = y;
        y += 14;
        ironRedButton = new GuiButton(BTN_IRON_RED, leftSplitX, y, BTN_SPLIT_W, BTN_H, "Iron no-sharp");
        stoneSharpRedButton = new GuiButton(BTN_STONE_SHARP_RED, rightSplitX, y, BTN_SPLIT_W, BTN_H, "Stone Sharp I");
        buttonList.add(ironRedButton);
        buttonList.add(stoneSharpRedButton);
        y += ROW;

        compare2LabelY = y;
        y += 14;
        diamondRedButton = new GuiButton(BTN_DIAMOND_RED, leftSplitX, y, BTN_SPLIT_W, BTN_H, "Diamond no-sharp");
        ironSharpRedButton = new GuiButton(BTN_IRON_SHARP_RED, rightSplitX, y, BTN_SPLIT_W, BTN_H, "Iron Sharp I");
        buttonList.add(diamondRedButton);
        buttonList.add(ironSharpRedButton);
        y += ROW;
        compare3LabelY = y;
        y += 14;
        stoneRedButton = new GuiButton(BTN_STONE_RED, leftSplitX, y, BTN_SPLIT_W, BTN_H, "Stone no-sharp");
        goldWoodSharpRedButton = new GuiButton(BTN_GOLD_WOOD_SHARP_RED, rightSplitX, y, BTN_SPLIT_W, BTN_H, "Gold/Wood Sharp I");
        buttonList.add(stoneRedButton);
        buttonList.add(goldWoodSharpRedButton);
        y += ROW + 16;

        backButton = new GuiButton(BTN_BACK, cx - 100, Math.min(height - 30, y), 200, BTN_H, EnumChatFormatting.GRAY + "Back");
        buttonList.add(backButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GuiHeader.drawTopLeft(this);
        String title = EnumChatFormatting.GOLD + "" + EnumChatFormatting.BOLD + "Useless item options";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, title, width, GuiHeader.STANDARD_TITLE_Y, 0xFFFFFF);
        String sub = EnumChatFormatting.GRAY + "Click to toggle or select what should be highlighted red.";
        GuiHeader.drawTitleCenteredWithShadow(fontRendererObj, sub, width, GuiHeader.STANDARD_SUBTITLE_Y, 0xFFFFFF);

        drawCenteredString(fontRendererObj, EnumChatFormatting.YELLOW + "Which to highlight red?", width / 2, questionLabelY, 0xFFFFFF);
        drawCenteredString(fontRendererObj, EnumChatFormatting.AQUA + "Iron vs Stone Sharp I", width / 2, compare1LabelY, 0xFFFFFF);
        drawCenteredString(fontRendererObj, EnumChatFormatting.AQUA + "Diamond vs Iron Sharp I", width / 2, compare2LabelY, 0xFFFFFF);
        drawCenteredString(fontRendererObj, EnumChatFormatting.AQUA + "Stone vs Gold/Wood Sharp I", width / 2, compare3LabelY, 0xFFFFFF);

        super.drawScreen(mouseX, mouseY, partialTicks);
        drawItemIcons();
        drawRedSelectionOverlays();
        GomodSearchHighlight.draw(buttonList, UselessItemSettingsGUI.class);
        // Draw tooltips last so overlays stay behind the tooltip box.
        drawSwordHoverTooltip(mouseX, mouseY);
    }

    @Override
    protected void actionPerformed(GuiButton b) {
        if (b.id == BTN_BACK) {
            mc.displayGuiScreen(parent);
            return;
        }
        switch (b.id) {
            case BTN_BOWL:
                ConfigManager.setUselessAlwaysRedBowl(!ConfigManager.isUselessAlwaysRedBowl());
                break;
            case BTN_CAKE:
                ConfigManager.setUselessAlwaysRedCake(!ConfigManager.isUselessAlwaysRedCake());
                break;
            case BTN_ROSE:
                ConfigManager.setUselessAlwaysRedRose(!ConfigManager.isUselessAlwaysRedRose());
                break;
            case BTN_STORAGE_MINECART:
                ConfigManager.setUselessAlwaysRedChestMinecart(!ConfigManager.isUselessAlwaysRedChestMinecart());
                break;
            case BTN_MINECART:
                ConfigManager.setUselessAlwaysRedMinecart(!ConfigManager.isUselessAlwaysRedMinecart());
                break;
            case BTN_RAILS:
                ConfigManager.setUselessAlwaysRedRail(!ConfigManager.isUselessAlwaysRedRail());
                break;
            case BTN_WHEAT:
                ConfigManager.setUselessAlwaysRedWheat(!ConfigManager.isUselessAlwaysRedWheat());
                break;
            case BTN_SHARPNESS_FISH:
                ConfigManager.setUselessAlwaysRedSharpnessFish(!ConfigManager.isUselessAlwaysRedSharpnessFish());
                break;
            case BTN_APPLE:
                ConfigManager.setUselessAlwaysRedApple(!ConfigManager.isUselessAlwaysRedApple());
                break;
            case BTN_STICK:
                ConfigManager.setUselessAlwaysRedStick(!ConfigManager.isUselessAlwaysRedStick());
                break;
            case BTN_IRON_RED:
                ConfigManager.setUselessMeleeIronVsStoneSharpRed("IRON");
                break;
            case BTN_STONE_SHARP_RED:
                ConfigManager.setUselessMeleeIronVsStoneSharpRed("STONE");
                break;
            case BTN_DIAMOND_RED:
                ConfigManager.setUselessMeleeDiamondVsIronSharpRed("DIAMOND");
                break;
            case BTN_IRON_SHARP_RED:
                ConfigManager.setUselessMeleeDiamondVsIronSharpRed("IRON");
                break;
            case BTN_STONE_RED:
                ConfigManager.setUselessMeleeStoneVsGoldWoodSharpRed("STONE");
                break;
            case BTN_GOLD_WOOD_SHARP_RED:
                ConfigManager.setUselessMeleeStoneVsGoldWoodSharpRed("GOLD_WOOD");
                break;
            default:
                break;
        }
        initGui();
    }

    private void drawRedSelectionOverlays() {
        drawSelectedOverlayIfActive(bowlButton, ConfigManager.isUselessAlwaysRedBowl());
        drawSelectedOverlayIfActive(cakeButton, ConfigManager.isUselessAlwaysRedCake());
        drawSelectedOverlayIfActive(roseButton, ConfigManager.isUselessAlwaysRedRose());
        drawSelectedOverlayIfActive(storageMinecartButton, ConfigManager.isUselessAlwaysRedChestMinecart());
        drawSelectedOverlayIfActive(minecartButton, ConfigManager.isUselessAlwaysRedMinecart());
        drawSelectedOverlayIfActive(railsButton, ConfigManager.isUselessAlwaysRedRail());
        drawSelectedOverlayIfActive(wheatButton, ConfigManager.isUselessAlwaysRedWheat());
        drawSelectedOverlayIfActive(sharpnessFishButton, ConfigManager.isUselessAlwaysRedSharpnessFish());
        drawSelectedOverlayIfActive(appleButton, ConfigManager.isUselessAlwaysRedApple());
        drawSelectedOverlayIfActive(stickButton, ConfigManager.isUselessAlwaysRedStick());

        drawSelectedOverlayIfActive(ironRedButton, "IRON".equals(ConfigManager.getUselessMeleeIronVsStoneSharpRed()));
        drawSelectedOverlayIfActive(stoneSharpRedButton, "STONE".equals(ConfigManager.getUselessMeleeIronVsStoneSharpRed()));
        drawSelectedOverlayIfActive(diamondRedButton, "DIAMOND".equals(ConfigManager.getUselessMeleeDiamondVsIronSharpRed()));
        drawSelectedOverlayIfActive(ironSharpRedButton, "IRON".equals(ConfigManager.getUselessMeleeDiamondVsIronSharpRed()));
        drawSelectedOverlayIfActive(stoneRedButton, "STONE".equals(ConfigManager.getUselessMeleeStoneVsGoldWoodSharpRed()));
        drawSelectedOverlayIfActive(goldWoodSharpRedButton, "GOLD_WOOD".equals(ConfigManager.getUselessMeleeStoneVsGoldWoodSharpRed()));
    }

    private void drawSelectedOverlayIfActive(GuiButton btn, boolean active) {
        if (!active || btn == null || !btn.visible) {
            return;
        }
        net.minecraft.client.renderer.GlStateManager.enableBlend();
        Gui.drawRect(btn.xPosition, btn.yPosition, btn.xPosition + btn.width, btn.yPosition + 2, 0xFFCC3333);
        Gui.drawRect(btn.xPosition, btn.yPosition, btn.xPosition + 2, btn.yPosition + btn.height, 0xFFCC3333);
        Gui.drawRect(btn.xPosition, btn.yPosition + btn.height - 2, btn.xPosition + btn.width, btn.yPosition + btn.height, 0xFFCC3333);
        Gui.drawRect(btn.xPosition + btn.width - 2, btn.yPosition, btn.xPosition + btn.width, btn.yPosition + btn.height, 0xFFCC3333);
    }

    private void drawItemIcons() {
        drawItemIconOnButton(bowlButton, new ItemStack(Items.bowl));
        drawItemIconOnButton(cakeButton, new ItemStack(Items.cake));
        drawItemIconOnButton(roseButton, knockbackRose());
        drawItemIconOnButton(storageMinecartButton, new ItemStack(Items.chest_minecart));
        drawItemIconOnButton(minecartButton, new ItemStack(Items.minecart));
        drawItemIconOnButton(railsButton, new ItemStack(Blocks.rail));
        drawItemIconOnButton(wheatButton, new ItemStack(Items.wheat));
        drawItemIconOnButton(sharpnessFishButton, sharpnessFish());
        drawItemIconOnButton(appleButton, new ItemStack(Items.apple));
        drawItemIconOnButton(stickButton, new ItemStack(Items.stick));

        drawItemIconOnButton(ironRedButton, new ItemStack(Items.iron_sword));
        drawItemIconOnButton(stoneSharpRedButton, sharpSword(Items.stone_sword));
        drawItemIconOnButton(diamondRedButton, new ItemStack(Items.diamond_sword));
        drawItemIconOnButton(ironSharpRedButton, sharpSword(Items.iron_sword));
        drawItemIconOnButton(stoneRedButton, new ItemStack(Items.stone_sword));
        drawItemIconOnButton(goldWoodSharpRedButton, sharpSword(Items.golden_sword));
    }

    private void drawItemIconOnButton(GuiButton button, ItemStack stack) {
        if (button == null || stack == null || !button.visible) {
            return;
        }
        try {
            GlStateManager.pushMatrix();
            RenderHelper.enableGUIStandardItemLighting();
            float prevZ = itemRender.zLevel;
            // Below vanilla drawHoveringText (z ~300); avoid matrix translate that stacked above tooltips.
            itemRender.zLevel = 100.0F;
            itemRender.renderItemAndEffectIntoGUI(stack, button.xPosition + 4, button.yPosition + 2);
            itemRender.zLevel = prevZ;
            RenderHelper.disableStandardItemLighting();
            GlStateManager.popMatrix();
        } catch (Throwable ignored) {
        }
    }

    private static ItemStack sharpSword(net.minecraft.item.Item swordItem) {
        ItemStack stack = new ItemStack(swordItem);
        try {
            stack.addEnchantment(Enchantment.sharpness, 1);
        } catch (Throwable ignored) {
        }
        return stack;
    }

    private static ItemStack sharpnessFish() {
        ItemStack stack = new ItemStack(Items.fish);
        try {
            stack.addEnchantment(Enchantment.sharpness, 1);
        } catch (Throwable ignored) {
        }
        return stack;
    }

    private static ItemStack knockbackRose() {
        ItemStack stack = new ItemStack(Blocks.red_flower, 1, 0);
        try {
            stack.addEnchantment(Enchantment.knockback, 1);
        } catch (Throwable ignored) {
        }
        return stack;
    }

    private void drawSwordHoverTooltip(int mouseX, int mouseY) {
        java.util.List<String> lines = null;
        if (isMouseOver(wheatButton, mouseX, mouseY)) {
            if (ConfigManager.isUselessAlwaysRedWheat()) {
                lines = java.util.Arrays.asList(
                        EnumChatFormatting.YELLOW + "Wheat highlight enabled",
                        EnumChatFormatting.GRAY + "Red if no other food or no horse/donkey.",
                        EnumChatFormatting.GRAY + "Only chest extras turn red at 9 wheat.",
                        EnumChatFormatting.GRAY + "A carried block (not a chest) makes wheat red."
                );
            } else {
                lines = java.util.Arrays.asList(
                        EnumChatFormatting.YELLOW + "Wheat highlight disabled",
                        EnumChatFormatting.GRAY + "Enable this to mark extra chest wheat red at 9.",
                        EnumChatFormatting.GRAY + "A carried block (not a chest) still makes wheat red."
                );
            }
        } else if (isMouseOver(sharpnessFishButton, mouseX, mouseY)) {
            if (ConfigManager.isUselessAlwaysRedSharpnessFish()) {
                lines = java.util.Arrays.asList(
                        EnumChatFormatting.YELLOW + "Sharpness I fish highlight enabled",
                        EnumChatFormatting.GRAY + "Highlighted red when another food option is available.",
                        EnumChatFormatting.GRAY + "Kept if it is your only food."
                );
            } else {
                lines = java.util.Arrays.asList(
                        EnumChatFormatting.YELLOW + "Sharpness I fish highlight disabled",
                        EnumChatFormatting.GRAY + "This fish will not be highlighted red."
                );
            }
        } else if (isMouseOver(appleButton, mouseX, mouseY)) {
            if (ConfigManager.isUselessAlwaysRedApple()) {
                lines = java.util.Arrays.asList(
                        EnumChatFormatting.YELLOW + "Apples chest filter enabled",
                        EnumChatFormatting.GRAY + "Chest apples turn red when you already",
                        EnumChatFormatting.GRAY + "carry 2 or more apples."
                );
            } else {
                lines = java.util.Arrays.asList(
                        EnumChatFormatting.YELLOW + "Apples chest filter disabled",
                        EnumChatFormatting.GRAY + "Enable this to mark extra chest apples",
                        EnumChatFormatting.GRAY + "red at 2 or more carried apples."
                );
            }
        } else if (isMouseOver(stickButton, mouseX, mouseY)) {
            if (ConfigManager.isUselessAlwaysRedStick()) {
                lines = java.util.Arrays.asList(
                        EnumChatFormatting.YELLOW + "Sticks chest filter enabled",
                        EnumChatFormatting.GRAY + "Chest sticks turn red when you already",
                        EnumChatFormatting.GRAY + "carry 2 or more sticks.",
                        EnumChatFormatting.GRAY + "Enchanted sticks are never affected."
                );
            } else {
                lines = java.util.Arrays.asList(
                        EnumChatFormatting.YELLOW + "Sticks chest filter disabled",
                        EnumChatFormatting.GRAY + "Enable this to mark extra chest sticks",
                        EnumChatFormatting.GRAY + "red at 2 or more carried sticks.",
                        EnumChatFormatting.GRAY + "Enchanted sticks are never affected."
                );
            }
        } else if (isMouseOver(ironRedButton, mouseX, mouseY) || isMouseOver(stoneSharpRedButton, mouseX, mouseY)) {
            lines = java.util.Arrays.asList(
                    EnumChatFormatting.AQUA + "Iron vs Stone Sharp I priority",
                    EnumChatFormatting.GRAY + "If both these weapons are present,",
                    EnumChatFormatting.GRAY + "choose which side should be considered worse (red)."
            );
        } else if (isMouseOver(diamondRedButton, mouseX, mouseY) || isMouseOver(ironSharpRedButton, mouseX, mouseY)) {
            lines = java.util.Arrays.asList(
                    EnumChatFormatting.AQUA + "Diamond vs Iron Sharp I priority",
                    EnumChatFormatting.GRAY + "If both these weapons are present,",
                    EnumChatFormatting.GRAY + "choose which side should be considered worse (red)."
            );
        } else if (isMouseOver(stoneRedButton, mouseX, mouseY) || isMouseOver(goldWoodSharpRedButton, mouseX, mouseY)) {
            lines = java.util.Arrays.asList(
                    EnumChatFormatting.AQUA + "Stone vs Gold/Wood Sharp I priority",
                    EnumChatFormatting.GRAY + "If both these weapons are present,",
                    EnumChatFormatting.GRAY + "choose which side should be considered worse (red)."
            );
        }
        if (lines == null) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        try {
            drawHoveringText(lines, mouseX, Math.max(0, mouseY - 12));
        } finally {
            GlStateManager.enableDepth();
            GlStateManager.popMatrix();
        }
    }

    private boolean isMouseOver(GuiButton button, int mouseX, int mouseY) {
        return button != null
                && mouseX >= button.xPosition
                && mouseX < button.xPosition + button.width
                && mouseY >= button.yPosition
                && mouseY < button.yPosition + button.height;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
