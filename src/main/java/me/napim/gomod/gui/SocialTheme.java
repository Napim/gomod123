package me.napim.gomod.gui;



/**

 * Visual tokens for the gomod Social tab — stone panels + green accent (MC / preview v2).

 */

public final class SocialTheme {



    // ------------------------------------------------------------ layout ---

    public static final int DOCK_W = 224;
    public static final int DOCK_NARROW_W = 64;

    public static final int WORKSPACE_MIN_W = 400;
    public static final int WORKSPACE_MAX_W = 620;


    public static final int DOCK_TOP_H = 58;

    public static final int DOCK_TABS_H = 36;

    public static final int DOCK_SEARCH_H = 44;

    public static final int DOCK_FOOTER_H = 62;

    public static final int DOCK_ROW_H = 34;
    public static final int FORUM_ROW_H = 42;
    public static final int GROUP_HINT_H = 34;



    public static final int WORKSPACE_BAR_H = 48;

    public static final int WORKSPACE_TABS_H = 36;

    /** Bottom strip height (message field + send). */
    public static final int COMPOSER_H = 40;

    public static final int COMPOSER_FIELD_H = 20;

    public static final int COMPOSER_SEND_W = 54;

    public static final int COMPOSER_SEND_GAP = 6;

    public static final int CHAT_ACTION_H = 18;

    public static final int EXPIRY_BANNER_H = 22;

    public static final int SERVICE_BANNER_H = 36;



    public static final int CHAT_BUBBLE_PAD = 9;

    public static final int CHAT_MSG_LINE_H = 11;

    public static final int CHAT_TIME_LINE_H = 9;

    public static final int CHAT_NAME_LINE_H = 11;

    public static final int CHAT_BUBBLE_GAP = 10;

    public static final int CHAT_MSG_HEAD = 22;



    public static final int RADIUS_LG = 8;

    public static final int RADIUS_MD = 5;

    public static final int RADIUS_SM = 4;

    public static final int RADIUS_BTN = 5;

    public static final int RADIUS = RADIUS_MD;



    // ------------------------------------------------------------ colors ---

    public static final int BACKDROP            = 0xB00D0D0D;
    /** Lighter dim used when Social is opened over a vanilla/launcher menu. */
    public static final int MENU_BACKDROP       = 0x380D0D0D;

    public static final int DOCK                = 0xF0242426;
    public static final int DOCK_HIGHLIGHT      = 0xF0323235;
    public static final int DOCK_EDGE           = 0xFF161618;

    public static final int WORKSPACE           = 0xF02E2E30;
    public static final int WORKSPACE_TOP       = 0xF03A3A3D;

    public static final int PANEL               = 0xF02E2E30;
    public static final int PANEL_TOP           = 0xF03A3A3D;

    /** Inset background for text fields and recessed areas. */
    public static final int PANEL_INNER         = 0xF01C1C1E;

    /** Card surface — distinctly lighter than the dock/workspace background. */
    public static final int CARD                = 0xF0404042;

    public static final int CARD_HOVER          = 0xFF4A4A4D;

    public static final int CARD_ELEVATED       = 0xFF4E4E52;

    public static final int OVERLAY             = 0xC00D0D0D;



    public static final int BORDER              = 0xFF2A2A2E;
    public static final int BORDER_SUBTLE       = 0xFF505054;
    public static final int BORDER_LIGHT        = 0xFF686870;

    public static final int SHADOW              = 0x50000000;

    public static final int HIGHLIGHT_TOP       = 0x14FFFFFF;

    public static final int SHADOW_BOTTOM       = 0x59000000;



    public static final int TEXT                = 0xFFE6E6E6;

    public static final int TEXT_BRIGHT         = 0xFFFFFFFF;

    public static final int TEXT_MUTED          = 0xFFA0A0A0;

    public static final int TEXT_DIM            = 0xFF707070;

    public static final int TEXT_INVERSE        = 0xFF0E0E10;



    public static final int ACCENT              = 0xFF4CAF50;
    public static final int ACCENT_BRIGHT       = 0xFF6FCF73;
    public static final int ACCENT_DARK         = 0xFF2E7D32;
    public static final int ACCENT_SOFT         = 0x334CAF50;

    public static final int ACCENT_GLOW         = 0x00000000;



    public static final int INFO                = 0xFF60A5FA;

    public static final int INFO_SOFT           = 0x3360A5FA;



    public static final int ONLINE              = 0xFF66BB66;

    public static final int AWAY                = 0xFFCCAA44;

    public static final int OFFLINE             = 0xFF666666;

    public static final int DANGER              = 0xFFCC5555;

    public static final int DANGER_SOFT         = 0x3DCC5555;

    public static final int TEMP                = 0xFFAA8844;

    public static final int TEMP_BG             = 0x33AA8844;

    public static final int TEMP_BORDER         = 0x66AA8844;



    public static final int ROW_SEL             = 0x2655AA55;

    public static final int ROW_SEL_BAR         = 0xFF55AA55;

    public static final int ROW_HOVER           = 0x0AFFFFFF;

    public static final int TAB_ON              = 0xF0383840;
    public static final int TAB_OFF             = 0x00000000;
    public static final int TAB_ACCENT          = 0xFF4CAF50;



    public static final int BUBBLE_SELF_TOP     = 0xFF5CA85C;
    public static final int BUBBLE_SELF         = 0xFF3A5E3A;
    public static final int BUBBLE_OTHER        = 0xF0424244;
    public static final int BUBBLE_OTHER_TOP    = 0xF0484849;
    public static final int BUBBLE_SHOT         = 0xF03D3560;
    public static final int BUBBLE_SHOT_TOP     = 0xF053479A;



    public static final int SCROLL_TRACK        = 0x20FFFFFF;

    public static final int SCROLL_THUMB        = 0x55FFFFFF;

    public static final int MODAL_OVERLAY       = 0xCC0D0D0D;

    public static final int MODAL_BG            = 0xFF2B2B2C;

    public static final int MODAL_BG_TOP        = 0xFF353537;



    public static final int SERVICE_WARN_BG     = 0x40CC5555;

    public static final int SERVICE_WARN_BORDER = 0x88CC5555;



    private SocialTheme() {}

}

