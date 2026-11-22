package me.napim.gomod.gui;

import me.napim.gomod.features.GomodSocialChat;
import me.napim.gomod.features.GomodSocialPublicChat;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.features.FriendListScanner;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Discord-style {@code @player} autocomplete for gomod Social public chat.
 * Suggestions come from players who have posted in the current channel thread.
 */
public final class SocialPublicChatMentions {

    private static final int MAX_VISIBLE = 6;
    private static final int ROW_H = 18;
    private static final int MAX_NAME_LEN = 16;

    private SocialPublicChatMentions() {}

    public static final class ActiveMention {
        public final int atIndex;
        public final String query;

        ActiveMention(int atIndex, String query) {
            this.atIndex = atIndex;
            this.query = query == null ? "" : query;
        }
    }

    public static final class DropdownBounds {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        DropdownBounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public boolean contains(int mx, int my) {
            return mx >= left && mx < right && my >= top && my < bottom;
        }
    }

    /** Live autocomplete state for one composer field. */
    public static final class Autocomplete {
        private List<String> suggestions = Collections.emptyList();
        private int selected;
        private ActiveMention active;
        private int hovered = -1;
        private int tabCycle;
        private String tabPrefix = "";

        public void refresh(GuiTextField field) {
            if (field == null || !field.isFocused()) {
                clear();
                return;
            }
            String text = field.getText();
            if (text == null) {
                text = "";
            }
            ActiveMention mention = findActive(text, cursorOf(field));
            if (mention == null) {
                clear();
                return;
            }
            List<String> next = suggestParticipants(mention.query);
            if (next.isEmpty()) {
                clear();
                return;
            }
            if (active == null || active.atIndex != mention.atIndex
                    || !active.query.equalsIgnoreCase(mention.query)) {
                tabCycle = 0;
                tabPrefix = mention.query;
            }
            active = mention;
            suggestions = next;
            if (selected >= suggestions.size()) {
                selected = 0;
            }
        }

        public void clear() {
            suggestions = Collections.emptyList();
            selected = 0;
            active = null;
            hovered = -1;
            tabCycle = 0;
            tabPrefix = "";
        }

        public boolean isOpen() {
            return active != null && !suggestions.isEmpty();
        }

        public DropdownBounds dropdownBounds(int fieldL, int fieldT, int fieldR) {
            if (!isOpen()) {
                return new DropdownBounds(0, 0, 0, 0);
            }
            int visible = Math.min(MAX_VISIBLE, suggestions.size());
            int h = visible * ROW_H + 4;
            int w = Math.max(120, fieldR - fieldL);
            int bottom = fieldT - 2;
            int top = bottom - h;
            return new DropdownBounds(fieldL, top, fieldL + w, bottom);
        }

        public void draw(FontRenderer fr, int fieldL, int fieldT, int fieldR, int mouseX, int mouseY) {
            if (!isOpen()) {
                hovered = -1;
                return;
            }
            DropdownBounds box = dropdownBounds(fieldL, fieldT, fieldR);
            int visible = Math.min(MAX_VISIBLE, suggestions.size());
            hovered = -1;

            SocialDraw.dropShadow(box.left, box.top, box.right, box.bottom, 4, SocialTheme.SHADOW);
            SocialDraw.roundedRect(box.left, box.top, box.right, box.bottom, SocialTheme.RADIUS_SM,
                    SocialTheme.CARD_ELEVATED);
            SocialDraw.fillRect(box.left + 6, box.top + 2, box.right - 6, box.top + 3, SocialTheme.BORDER_SUBTLE);

            for (int i = 0; i < visible; i++) {
                int rowT = box.top + 2 + i * ROW_H;
                int rowB = rowT + ROW_H;
                boolean sel = i == selected;
                boolean hov = mouseX >= box.left && mouseX < box.right && mouseY >= rowT && mouseY < rowB;
                if (hov) {
                    hovered = i;
                }
                if (sel || hov) {
                    SocialDraw.roundedFill(box.left + 3, rowT + 1, box.right - 3, rowB - 1,
                            SocialTheme.RADIUS_SM, sel ? SocialTheme.TAB_ON : SocialTheme.PANEL_INNER);
                }
                String name = suggestions.get(i);
                fr.drawString(EnumChatFormatting.AQUA + "@" + EnumChatFormatting.WHITE + name,
                        box.left + 8, rowT + 5, SocialTheme.TEXT_BRIGHT);
            }
        }

        public boolean handleTab(GuiTextField field) {
            if (!isOpen() || field == null) {
                return false;
            }
            String prefix = active == null ? "" : active.query;
            if (!prefix.equalsIgnoreCase(tabPrefix)) {
                tabCycle = 0;
                tabPrefix = prefix;
            }
            int pick = tabCycle % suggestions.size();
            tabCycle++;
            apply(field, active, suggestions.get(pick));
            refresh(field);
            return true;
        }

        public boolean handleEnter(GuiTextField field) {
            if (!isOpen() || field == null) {
                return false;
            }
            int pick = selected;
            if (pick < 0 || pick >= suggestions.size()) {
                pick = 0;
            }
            apply(field, active, suggestions.get(pick));
            refresh(field);
            return true;
        }

        public boolean handleArrow(GuiTextField field, boolean up) {
            if (!isOpen() || field == null) {
                return false;
            }
            if (up) {
                selected = (selected - 1 + suggestions.size()) % suggestions.size();
            } else {
                selected = (selected + 1) % suggestions.size();
            }
            return true;
        }

        public boolean handleClick(GuiTextField field, int mouseX, int mouseY, int fieldL, int fieldT, int fieldR) {
            if (!isOpen() || field == null) {
                return false;
            }
            DropdownBounds box = dropdownBounds(fieldL, fieldT, fieldR);
            if (!box.contains(mouseX, mouseY)) {
                return false;
            }
            int relY = mouseY - box.top - 2;
            int idx = relY / ROW_H;
            if (idx < 0 || idx >= Math.min(MAX_VISIBLE, suggestions.size())) {
                return false;
            }
            apply(field, active, suggestions.get(idx));
            refresh(field);
            return true;
        }
    }

    public static ActiveMention findActive(String text, int cursor) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int len = text.length();
        int pos = cursor;
        if (pos < 0) {
            pos = 0;
        }
        if (pos > len) {
            pos = len;
        }
        int scan = pos - 1;
        while (scan >= 0 && text.charAt(scan) != '@') {
            char c = text.charAt(scan);
            if (!isQueryChar(c)) {
                return null;
            }
            scan--;
        }
        if (scan < 0 || text.charAt(scan) != '@') {
            return null;
        }
        if (scan > 0) {
            char before = text.charAt(scan - 1);
            if (!Character.isWhitespace(before)) {
                return null;
            }
        }
        String query = text.substring(scan + 1, pos);
        if (!isValidQuery(query)) {
            return null;
        }
        return new ActiveMention(scan, query);
    }

    public static List<String> suggestParticipants(String prefix) {
        String lower = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
        Set<String> seen = new LinkedHashSet<String>();
        List<String> out = new ArrayList<String>();
        for (GomodSocialChat.Message m : GomodSocialPublicChat.getMessages()) {
            if (m == null || m.from == null) {
                continue;
            }
            String name = m.from.trim();
            if (name.isEmpty() || name.length() > MAX_NAME_LEN) {
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            if (seen.contains(key)) {
                continue;
            }
            if (!lower.isEmpty() && !key.startsWith(lower)) {
                continue;
            }
            seen.add(key);
            out.add(resolveDisplayName(name));
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    private static String resolveDisplayName(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            return "";
        }
        String friend = FriendListScanner.matchFriendSender(name);
        if (friend != null && !friend.trim().isEmpty()) {
            return friend.trim();
        }
        if (ConfigManager.isGomodFriend(name)) {
            String display = ConfigManager.getGomodFriendDisplayName(name);
            if (display != null && !display.trim().isEmpty()) {
                return display.trim();
            }
        }
        if (!hasUppercase(name)) {
            for (GomodSocialChat.Message m : GomodSocialPublicChat.getMessages()) {
                if (m != null && m.from != null && m.from.equalsIgnoreCase(name) && hasUppercase(m.from)) {
                    return m.from.trim();
                }
            }
        }
        return name;
    }

    private static boolean hasUppercase(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isUpperCase(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    public static void apply(GuiTextField field, ActiveMention mention, String username) {
        if (field == null || mention == null || username == null || username.trim().isEmpty()) {
            return;
        }
        String name = username.trim();
        String text = field.getText();
        if (text == null) {
            text = "";
        }
        int cursor = cursorOf(field);
        int end = cursor;
        if (end < mention.atIndex) {
            end = text.length();
        }
        String before = text.substring(0, mention.atIndex);
        String after = end < text.length() ? text.substring(end) : "";
        String completed = before + "@" + name + " " + after;
        field.setText(completed);
        setCursor(field, before.length() + name.length() + 2);
    }

    private static boolean isQueryChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_';
    }

    private static boolean isValidQuery(String query) {
        if (query == null || query.length() > MAX_NAME_LEN) {
            return false;
        }
        for (int i = 0; i < query.length(); i++) {
            if (!isQueryChar(query.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int cursorOf(GuiTextField field) {
        Integer reflected = readCursor(field);
        if (reflected != null) {
            return reflected;
        }
        String text = field.getText();
        return text == null ? 0 : text.length();
    }

    private static void setCursor(GuiTextField field, int pos) {
        if (writeCursor(field, pos)) {
            return;
        }
        field.setCursorPositionEnd();
    }

    private static Integer readCursor(GuiTextField field) {
        try {
            Method m = field.getClass().getMethod("getCursorPosition");
            Object v = m.invoke(field);
            if (v instanceof Integer) {
                return (Integer) v;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        for (String name : new String[] { "cursorPosition", "field_146224_q" }) {
            try {
                Field f = GuiTextField.class.getDeclaredField(name);
                f.setAccessible(true);
                return f.getInt(field);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }

    private static boolean writeCursor(GuiTextField field, int pos) {
        try {
            Method m = field.getClass().getMethod("setCursorPosition", int.class);
            m.invoke(field, pos);
            return true;
        } catch (ReflectiveOperationException ignored) {
        }
        for (String name : new String[] { "cursorPosition", "field_146224_q" }) {
            try {
                Field f = GuiTextField.class.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(field, pos);
                return true;
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return false;
    }
}
