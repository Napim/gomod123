package me.napim.gomod.command;

import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import me.napim.gomod.features.BarrierVisibility;
import me.napim.gomod.features.BarrierVisibilityStyles;
import me.napim.gomod.features.ConfigManager;
import me.napim.gomod.util.ClasspathGomodResourcePack;
import me.napim.gomod.util.GomodResourcePackInjector;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.weavemc.loader.api.command.Command;

/**
 * {@code /bv} — toggle barrier visibility on/off.
 * {@code /bv styles} — list texture styles with short descriptions.
 * {@code /bv style} — show current style + usage.
 * {@code /bv style <name>} — set style ({@code classic} / {@code outline}).
 * {@code /bv style next|prev} — cycle styles.
 * {@code /bv outline thickness <1-MAX>} — change OUTLINE perimeter thickness.
 * {@code /bv outline color <hex|rrr,ggg,bbb>} — change OUTLINE color.
 * {@code /bv debug} — diagnostics.
 */
public class BarrierVisibilityCommand extends Command {
    public BarrierVisibilityCommand() {
        super("bv", new String[]{"barriervisibility"});
    }

    @Override
    public void handle(String[] args) {
        if (args.length > 0) {
            String a0 = args[0].toLowerCase();
            if ("styles".equals(a0) || "list".equals(a0)) {
                sendStylesList();
                return;
            }
            if ("style".equals(a0)) {
                handleStyleSubcommand(args);
                return;
            }
            if ("outline".equals(a0)) {
                handleOutlineSubcommand(args);
                return;
            }
            if ("range".equals(a0) || "distance".equals(a0)) {
                handleRangeSubcommand(args);
                return;
            }
            if ("cursor".equals(a0) || "aim".equals(a0) || "look".equals(a0)) {
                handleCursorSubcommand(args);
                return;
            }
            if ("debug".equals(a0)) {
                if (!GomodInternalFeatures.requireInternalCommand("bv debug")) return;
                dumpDebug();
                return;
            }
            if ("on".equals(a0) || "enable".equals(a0)) {
                BarrierVisibility.setEnabledWithRendererReload(true);
                Main.sendMessage(Main.CHAT_PREFIX + "Barrier blocks: " + EnumChatFormatting.GREEN + "ON");
                return;
            }
            if ("off".equals(a0) || "disable".equals(a0)) {
                BarrierVisibility.setEnabledWithRendererReload(false);
                Main.sendMessage(Main.CHAT_PREFIX + "Barrier blocks: " + EnumChatFormatting.RED + "OFF");
                return;
            }
            sendUnknown(args[0]);
            return;
        }
        BarrierVisibility.toggleWithRendererReload();
        boolean on = ConfigManager.isBarrierVisibilityEnabled();
        Main.sendMessage(Main.CHAT_PREFIX + "Barrier blocks: "
                + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
    }

    private void handleStyleSubcommand(String[] args) {
        if (args.length < 2) {
            sendStyleUsage();
            return;
        }
        String a1 = args[1].toLowerCase();
        if ("next".equals(a1)) {
            BarrierVisibility.setStyleWithRendererReload(
                    BarrierVisibilityStyles.next(ConfigManager.getBarrierVisibilityStyle()));
            sendStyleApplied();
            return;
        }
        if ("prev".equals(a1) || "previous".equals(a1)) {
            BarrierVisibility.setStyleWithRendererReload(
                    BarrierVisibilityStyles.prev(ConfigManager.getBarrierVisibilityStyle()));
            sendStyleApplied();
            return;
        }
        if ("help".equals(a1) || "?".equals(a1)) {
            sendStylesList();
            sendStyleUsage();
            return;
        }
        String joined = joinArgs(args, 1);
        BarrierVisibility.setStyleWithRendererReload(BarrierVisibilityStyles.normalize(joined));
        sendStyleApplied();
    }

    private void handleOutlineSubcommand(String[] args) {
        if (args.length < 2) {
            sendOutlineUsage();
            return;
        }
        String key = args[1].toLowerCase();
        if ("thickness".equals(key) || "thick".equals(key) || "size".equals(key) || "width".equals(key)) {
            if (args.length < 3) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Current outline thickness: "
                        + EnumChatFormatting.WHITE + ConfigManager.getBarrierOutlineThickness()
                        + EnumChatFormatting.GRAY + " (1.."
                        + BarrierVisibilityStyles.MAX_OUTLINE_THICKNESS + ")");
                Main.sendMessage(EnumChatFormatting.GRAY + "Usage: " + EnumChatFormatting.WHITE
                        + "/bv outline thickness <1-" + BarrierVisibilityStyles.MAX_OUTLINE_THICKNESS + ">");
                return;
            }
            int requested;
            try {
                requested = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Not a number: " + args[2]);
                return;
            }
            int clamped = BarrierVisibilityStyles.clampThickness(requested);
            BarrierVisibility.setOutlineThicknessWithRendererReload(clamped);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Outline thickness: "
                    + EnumChatFormatting.WHITE + clamped
                    + (clamped != requested
                            ? EnumChatFormatting.DARK_GRAY + " (clamped from " + requested + ")"
                            : ""));
            return;
        }
        if ("color".equals(key) || "colour".equals(key)) {
            if (args.length < 3) {
                int cur = ConfigManager.getBarrierOutlineColorArgb();
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Current outline color: "
                        + EnumChatFormatting.WHITE + String.format("#%06X", cur & 0x00FFFFFF));
                Main.sendMessage(EnumChatFormatting.GRAY + "Usage: " + EnumChatFormatting.WHITE
                        + "/bv outline color <#RRGGBB | r,g,b>");
                return;
            }
            Integer rgb = parseColorRgb(joinArgs(args, 2));
            if (rgb == null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Bad color: "
                        + joinArgs(args, 2));
                Main.sendMessage(EnumChatFormatting.GRAY + "Examples: " + EnumChatFormatting.WHITE
                        + "#FF0000" + EnumChatFormatting.GRAY + ", "
                        + EnumChatFormatting.WHITE + "255,0,0");
                return;
            }
            BarrierVisibility.setOutlineColorWithRendererReload(0xFF000000 | (rgb & 0x00FFFFFF));
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Outline color: "
                    + EnumChatFormatting.WHITE + String.format("#%06X", rgb & 0x00FFFFFF));
            return;
        }
        sendOutlineUsage();
    }

    private void handleRangeSubcommand(String[] args) {
        if (args.length < 2) {
            boolean on = ConfigManager.isBarrierVisibilityRangeEnabled();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Range mode: "
                    + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                    + EnumChatFormatting.GRAY + ", current radius: "
                    + EnumChatFormatting.WHITE + ConfigManager.getBarrierVisibilityRange()
                    + EnumChatFormatting.GRAY + " blocks (" + ConfigManager.BARRIER_RANGE_MIN
                    + ".." + ConfigManager.BARRIER_RANGE_MAX + ")");
            Main.sendMessage(EnumChatFormatting.GRAY + "Usage: " + EnumChatFormatting.WHITE
                    + "/bv range <on|off|" + ConfigManager.BARRIER_RANGE_MIN + "-"
                    + ConfigManager.BARRIER_RANGE_MAX + ">");
            return;
        }
        String a = args[1].toLowerCase();
        if ("on".equals(a) || "enable".equals(a) || "true".equals(a)) {
            BarrierVisibility.setRangeEnabledWithRendererReload(true);
            Main.sendMessage(Main.CHAT_PREFIX + "Range mode: " + EnumChatFormatting.GREEN + "ON"
                    + EnumChatFormatting.GRAY + " (" + ConfigManager.getBarrierVisibilityRange() + " blocks)");
            return;
        }
        if ("off".equals(a) || "disable".equals(a) || "false".equals(a)) {
            BarrierVisibility.setRangeEnabledWithRendererReload(false);
            Main.sendMessage(Main.CHAT_PREFIX + "Range mode: " + EnumChatFormatting.RED + "OFF");
            return;
        }
        int requested;
        try {
            requested = Integer.parseInt(a);
        } catch (NumberFormatException ex) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Bad value: " + args[1]);
            Main.sendMessage(EnumChatFormatting.GRAY + "Usage: " + EnumChatFormatting.WHITE
                    + "/bv range <on|off|" + ConfigManager.BARRIER_RANGE_MIN + "-"
                    + ConfigManager.BARRIER_RANGE_MAX + ">");
            return;
        }
        int min = ConfigManager.BARRIER_RANGE_MIN;
        int max = ConfigManager.BARRIER_RANGE_MAX;
        int clamped = Math.max(min, Math.min(max, requested));
        BarrierVisibility.setRangeWithRendererReload(clamped);
        // Setting a number also turns range mode on — that's the obvious intent.
        if (!ConfigManager.isBarrierVisibilityRangeEnabled()) {
            BarrierVisibility.setRangeEnabledWithRendererReload(true);
        }
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Range: "
                + EnumChatFormatting.WHITE + clamped + EnumChatFormatting.GRAY + " blocks"
                + (clamped != requested
                        ? EnumChatFormatting.DARK_GRAY + " (clamped from " + requested + ")"
                        : ""));
    }

    private void handleCursorSubcommand(String[] args) {
        if (args.length < 2) {
            boolean on = ConfigManager.isBarrierVisibilityShowAtCursor();
            Main.sendMessage(Main.CHAT_PREFIX + "Reveal where I aim: "
                    + (on ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF")
                    + EnumChatFormatting.GRAY + " (only matters while Nearby-only is active)");
            Main.sendMessage(EnumChatFormatting.GRAY + "Usage: " + EnumChatFormatting.WHITE
                    + "/bv cursor <on|off|toggle>");
            return;
        }
        String a = args[1].toLowerCase();
        boolean target;
        if ("on".equals(a) || "enable".equals(a) || "true".equals(a)) {
            target = true;
        } else if ("off".equals(a) || "disable".equals(a) || "false".equals(a)) {
            target = false;
        } else if ("toggle".equals(a)) {
            target = !ConfigManager.isBarrierVisibilityShowAtCursor();
        } else {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Bad value: " + args[1]);
            Main.sendMessage(EnumChatFormatting.GRAY + "Usage: " + EnumChatFormatting.WHITE
                    + "/bv cursor <on|off|toggle>");
            return;
        }
        BarrierVisibility.setShowAtCursorWithRendererReload(target);
        Main.sendMessage(Main.CHAT_PREFIX + "Reveal where I aim: "
                + (target ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF"));
    }

    private void sendOutlineUsage() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "Outline settings");
        Main.sendMessage(EnumChatFormatting.GRAY + "  " + EnumChatFormatting.WHITE + "/bv outline thickness <1-"
                + BarrierVisibilityStyles.MAX_OUTLINE_THICKNESS + ">"
                + EnumChatFormatting.GRAY + " — line thickness in source pixels");
        Main.sendMessage(EnumChatFormatting.GRAY + "  " + EnumChatFormatting.WHITE
                + "/bv outline color <#RRGGBB | r,g,b>" + EnumChatFormatting.GRAY + " — line color");
        Main.sendMessage(EnumChatFormatting.GRAY + "Current: thickness="
                + EnumChatFormatting.WHITE + ConfigManager.getBarrierOutlineThickness()
                + EnumChatFormatting.GRAY + ", color="
                + EnumChatFormatting.WHITE + String.format("#%06X",
                        ConfigManager.getBarrierOutlineColorArgb() & 0x00FFFFFF));
    }

    private static Integer parseColorRgb(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.contains(",")) {
            String[] parts = s.split("\\s*,\\s*");
            if (parts.length != 3) {
                return null;
            }
            try {
                int r = clampByte(Integer.parseInt(parts[0].trim()));
                int g = clampByte(Integer.parseInt(parts[1].trim()));
                int b = clampByte(Integer.parseInt(parts[2].trim()));
                return (r << 16) | (g << 8) | b;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (s.startsWith("#")) {
            s = s.substring(1);
        } else if (s.startsWith("0x") || s.startsWith("0X")) {
            s = s.substring(2);
        }
        if (s.length() != 6 && s.length() != 8) {
            return null;
        }
        try {
            long v = Long.parseLong(s, 16);
            return (int) (v & 0xFFFFFFL);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int clampByte(int v) {
        if (v < 0) {
            return 0;
        }
        if (v > 255) {
            return 255;
        }
        return v;
    }

    private static String joinArgs(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (i > from) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private void sendStyleApplied() {
        String id = ConfigManager.getBarrierVisibilityStyle();
        Main.sendMessage(Main.CHAT_PREFIX + "Barrier texture: " + EnumChatFormatting.WHITE + id
                + EnumChatFormatting.GRAY + " — " + BarrierVisibilityStyles.getTitle(id));
    }

    private void sendStyleUsage() {
        String cur = ConfigManager.getBarrierVisibilityStyle();
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Current style: "
                + EnumChatFormatting.WHITE + cur + EnumChatFormatting.GRAY + " ("
                + BarrierVisibilityStyles.getTitle(cur) + ")");
        Main.sendMessage(EnumChatFormatting.GRAY + "  " + EnumChatFormatting.WHITE + "/bv style <name>"
                + EnumChatFormatting.GRAY + " — set (see /bv styles)");
        Main.sendMessage(EnumChatFormatting.GRAY + "  " + EnumChatFormatting.WHITE + "/bv style next"
                + EnumChatFormatting.GRAY + " / " + EnumChatFormatting.WHITE + "prev"
                + EnumChatFormatting.GRAY + " — cycle");
    }

    private void sendStylesList() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "Barrier texture styles");
        for (String id : BarrierVisibilityStyles.ORDER) {
            Main.sendMessage(EnumChatFormatting.GRAY + "  " + EnumChatFormatting.WHITE + id.toLowerCase()
                    + EnumChatFormatting.DARK_GRAY + " — " + EnumChatFormatting.GRAY
                    + BarrierVisibilityStyles.getTitle(id));
        }
    }

    private void sendUnknown(String token) {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Unknown: " + token);
        String usage = "/bv  |  /bv style outline  |  /bv range 32  |  /bv cursor on  |  /bv outline color #FF0000";
        if (GomodInternalFeatures.isEnabled()) usage += "  |  /bv debug";
        Main.sendMessage(EnumChatFormatting.GRAY + "Try: " + usage);
    }

    private void dumpDebug() {
        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GOLD + "Barrier Visibility debug");

        line("config.enabled", String.valueOf(ConfigManager.isBarrierVisibilityEnabled()));
        line("config.style", ConfigManager.getBarrierVisibilityStyle());
        line("config.keyCode", String.valueOf(ConfigManager.getBarrierVisibilityKeyCode()));
        line("config.outlineThickness", String.valueOf(ConfigManager.getBarrierOutlineThickness()));
        line("config.outlineColor",
                String.format("#%06X", ConfigManager.getBarrierOutlineColorArgb() & 0x00FFFFFF));
        line("config.rangeEnabled", String.valueOf(ConfigManager.isBarrierVisibilityRangeEnabled()));
        line("config.range", ConfigManager.getBarrierVisibilityRange() + " blocks");
        line("config.showAtCursor", String.valueOf(ConfigManager.isBarrierVisibilityShowAtCursor()));
        line("config.shownInCages", String.valueOf(ConfigManager.isBarrierBlocksShownInCages()));
        line("runtime.effectivelyEnabled", String.valueOf(BarrierVisibility.isEffectivelyEnabled()));

        try {
            Block barrier = Blocks.barrier;
            int rt = barrier.getRenderType();
            line("BlockBarrier.getRenderType()", String.valueOf(rt) + colorHint(rt == 3, "(expected 3 when enabled)"));
        } catch (Throwable t) {
            line("BlockBarrier.getRenderType()", EnumChatFormatting.RED + "threw " + t);
        }
        try {
            Object layer = Blocks.barrier.getBlockLayer();
            String name = layer == null ? "null" : layer.toString();
            line("BlockBarrier.getBlockLayer()", name + colorHint("CUTOUT".equals(name), "(expected CUTOUT)"));
        } catch (Throwable t) {
            line("BlockBarrier.getBlockLayer()", EnumChatFormatting.RED + "threw " + t);
        }

        IBakedModel model = null;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            BlockModelShapes shapes = mc.getBlockRendererDispatcher().getBlockModelShapes();
            model = shapes.getModelForState(Blocks.barrier.getDefaultState());
            String klass = model == null ? "null" : model.getClass().getName();
            line("model class", klass);
        } catch (Throwable t) {
            line("model lookup", EnumChatFormatting.RED + "threw " + t);
        }
        try {
            if (model != null) {
                Object particle = model.getParticleTexture();
                String iconName = particle == null ? "null"
                        : ((net.minecraft.client.renderer.texture.TextureAtlasSprite) particle).getIconName();
                boolean ok = iconName != null && !iconName.contains("missingno");
                line("model.particleTexture", iconName
                        + colorHint(ok, ok ? "(real sprite)" : "(missingno)"));
            }
        } catch (Throwable t) {
            line("model.particleTexture", EnumChatFormatting.RED + "threw " + t);
        }

        try {
            Minecraft mc = Minecraft.getMinecraft();
            net.minecraft.client.renderer.texture.TextureAtlasSprite s =
                    mc.getTextureMapBlocks().getAtlasSprite("minecraft:blocks/barrier");
            String iconName = s == null ? "null" : s.getIconName();
            int w = s == null ? -1 : s.getIconWidth();
            int h = s == null ? -1 : s.getIconHeight();
            int x = s == null ? -1 : s.getOriginX();
            int y = s == null ? -1 : s.getOriginY();
            boolean ok = s != null && iconName != null && !iconName.contains("missingno");
            line("atlas sprite blocks/barrier",
                    iconName + " " + w + "x" + h + " @ (" + x + "," + y + ")"
                            + colorHint(ok, ok ? "(in atlas)" : "(missing)"));
        } catch (Throwable t) {
            line("atlas sprite blocks/barrier", EnumChatFormatting.RED + "threw " + t);
        }

        try {
            Minecraft mc = Minecraft.getMinecraft();
            Object mapper = mc.getBlockRendererDispatcher().getBlockModelShapes().getBlockStateMapper();
            java.lang.reflect.Field f;
            try {
                f = mapper.getClass().getDeclaredField("field_178449_b");
            } catch (NoSuchFieldException e) {
                f = mapper.getClass().getDeclaredField("setBuiltInBlocks");
            }
            f.setAccessible(true);
            java.util.Set<?> blacklist = (java.util.Set<?>) f.get(mapper);
            boolean stillSkipped = blacklist != null && blacklist.contains(Blocks.barrier);
            line("BlockStateMapper.blacklist.contains(barrier)",
                    String.valueOf(stillSkipped)
                            + colorHint(!stillSkipped,
                            stillSkipped ? "(still skipped)" : "(ok)"));
        } catch (Throwable t) {
            line("BlockStateMapper.blacklist", EnumChatFormatting.RED + "threw " + t);
        }

        try {
            net.minecraft.client.renderer.texture.TextureAtlasSprite s =
                    me.napim.gomod.features.BarrierVisibilityAtlasHook.getBarrierSprite();
            line("AtlasHook.barrierSprite", s == null ? "null"
                    : s.getIconName() + " " + s.getIconWidth() + "x" + s.getIconHeight());
            String err = me.napim.gomod.features.BarrierVisibilityAtlasHook.getLastUploadError();
            line("AtlasHook.lastUploadError", err == null ? "(none)" : EnumChatFormatting.RED + err);
        } catch (Throwable ignored) {
        }

        line("ClasspathGomodResourcePack installed", String.valueOf(GomodResourcePackInjector.wasSuccessfullyInstalled()));
        line("gomod domain registered", String.valueOf(GomodResourcePackInjector.isGomodDomainActive()));

        checkResource("minecraft", "blockstates/barrier.json");
        checkResource("minecraft", "models/block/barrier.json");
        checkResource("minecraft", "textures/blocks/barrier.png");
        for (String id : BarrierVisibilityStyles.ORDER) {
            if (BarrierVisibilityStyles.FULL.equals(id)) {
                continue;
            }
            String path = "textures/barrier_style_" + id.toLowerCase(java.util.Locale.ROOT) + ".png";
            checkResource("gomod", path);
            line("classpath gomod:" + path,
                    String.valueOf(ClasspathGomodResourcePack.class.getResource("/assets/gomod/" + path) != null));
        }
    }

    private void checkResource(String domain, String path) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            IResourceManager rm = mc.getResourceManager();
            IResource res = rm.getResource(new ResourceLocation(domain, path));
            int size = -1;
            try {
                java.io.InputStream is = res.getInputStream();
                byte[] buf = new byte[4096];
                size = 0;
                int n;
                while ((n = is.read(buf)) > 0) {
                    size += n;
                }
                is.close();
            } catch (Throwable ignored) {
            }
            line("resource " + domain + ":" + path,
                    EnumChatFormatting.GREEN + "OK" + EnumChatFormatting.GRAY + " (" + size + " bytes)");
        } catch (Throwable t) {
            line("resource " + domain + ":" + path,
                    EnumChatFormatting.RED + "MISSING " + EnumChatFormatting.GRAY + "(" + t.getClass().getSimpleName() + ")");
        }
    }

    private void line(String key, String value) {
        Main.sendMessage(EnumChatFormatting.GRAY + "  " + key + ": " + EnumChatFormatting.WHITE + value);
    }

    private String colorHint(boolean good, String hint) {
        return " " + (good ? EnumChatFormatting.DARK_GREEN : EnumChatFormatting.RED) + hint + EnumChatFormatting.RESET;
    }
}
