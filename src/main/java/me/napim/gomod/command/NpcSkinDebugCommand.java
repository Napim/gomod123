package me.napim.gomod.command;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import me.napim.gomod.GomodInternalFeatures;
import me.napim.gomod.Main;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.Vec3;
import net.weavemc.loader.api.command.Command;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dev-build-only command ({@code /npcskin}) to inspect and copy base64 {@code textures}
 * properties from nearby player entities / Hypixel lobby NPCs.
 */
public final class NpcSkinDebugCommand extends Command {

    private static final double DEFAULT_RADIUS = 16.0D;
    private static final Pattern SKIN_URL_PATTERN = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");
    private static final List<CapturedNpcSkin> LAST_CAPTURE = new ArrayList<CapturedNpcSkin>();

    private static final class CapturedNpcSkin {
        final String name;
        final String uuid;
        final String hologram;
        final double distance;
        final double aimDot;
        final String textureBase64;
        final String skinUrl;

        CapturedNpcSkin(String name, String uuid, String hologram, double distance, double aimDot,
                        String textureBase64, String skinUrl) {
            this.name = name;
            this.uuid = uuid;
            this.hologram = hologram;
            this.distance = distance;
            this.aimDot = aimDot;
            this.textureBase64 = textureBase64;
            this.skinUrl = skinUrl;
        }
    }

    public NpcSkinDebugCommand() {
        super("npcskin");
    }

    @Override
    public void handle(String[] args) {
        if (!GomodInternalFeatures.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "You must be in a world to use /npcskin.");
            return;
        }

        if (args != null && args.length == 1 && args[0].matches("\\d+")) {
            int idx = Integer.parseInt(args[0]) - 1;
            if (idx >= 0 && idx < LAST_CAPTURE.size()) {
                CapturedNpcSkin chosen = LAST_CAPTURE.get(idx);
                GuiScreen.setClipboardString(chosen.textureBase64);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Copied #" + (idx + 1)
                        + " (" + displayLabel(chosen) + ") base64 texture to clipboard ("
                        + chosen.textureBase64.length() + " chars).");
                return;
            }
        }

        double radius = DEFAULT_RADIUS;
        if (args != null && args.length == 1) {
            try {
                radius = Math.max(2.0D, Math.min(64.0D, Double.parseDouble(args[0])));
            } catch (NumberFormatException ignored) {
            }
        }

        Vec3 eyePos = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 lookVec = mc.thePlayer.getLook(1.0F).normalize();

        List<CapturedNpcSkin> found = new ArrayList<CapturedNpcSkin>();
        for (Object obj : mc.theWorld.playerEntities) {
            if (!(obj instanceof EntityPlayer) || obj == mc.thePlayer) {
                continue;
            }
            EntityPlayer ep = (EntityPlayer) obj;
            double dist = mc.thePlayer.getDistanceToEntity(ep);
            if (dist > radius) {
                continue;
            }
            String base64 = extractTextureBase64(mc, ep);
            if (base64 == null || base64.isEmpty()) {
                continue;
            }
            Vec3 toEntity = new Vec3(ep.posX - eyePos.xCoord,
                    (ep.posY + ep.getEyeHeight() * 0.75D) - eyePos.yCoord,
                    ep.posZ - eyePos.zCoord).normalize();
            double aimDot = lookVec.dotProduct(toEntity);
            String holo = findNearbyHologram(mc, ep);
            String url = extractSkinUrl(base64);
            found.add(new CapturedNpcSkin(
                    ep.getName() == null ? "" : ep.getName(),
                    ep.getUniqueID() == null ? "" : ep.getUniqueID().toString(),
                    holo,
                    dist,
                    aimDot,
                    base64,
                    url));
        }

        if (found.isEmpty()) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                    + "No textured player entities/NPCs found within " + (int) radius + " blocks.");
            return;
        }

        // Prefer an NPC directly under the crosshair (dot > 0.96), then sort by distance.
        Collections.sort(found, new Comparator<CapturedNpcSkin>() {
            @Override
            public int compare(CapturedNpcSkin a, CapturedNpcSkin b) {
                boolean aAimed = a.aimDot > 0.96D;
                boolean bAimed = b.aimDot > 0.96D;
                if (aAimed != bAimed) return aAimed ? -1 : 1;
                if (aAimed) return Double.compare(b.aimDot, a.aimDot);
                return Double.compare(a.distance, b.distance);
            }
        });

        LAST_CAPTURE.clear();
        LAST_CAPTURE.addAll(found);

        CapturedNpcSkin top = found.get(0);
        GuiScreen.setClipboardString(top.textureBase64);

        File outFile = new File(mc.mcDataDir, "gomod-npc-skins.txt");
        boolean saved = writeDumpFile(outFile, found);

        Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Found " + found.size()
                + " textured player/NPC skin(s) within " + (int) radius + "m. "
                + EnumChatFormatting.AQUA + "#1 copied to clipboard!");
        int shown = Math.min(found.size(), 8);
        for (int i = 0; i < shown; i++) {
            CapturedNpcSkin item = found.get(i);
            Main.sendMessage(EnumChatFormatting.GOLD + " #" + (i + 1) + " "
                    + EnumChatFormatting.WHITE + displayLabel(item)
                    + EnumChatFormatting.GRAY + String.format(" (%.1fm)", item.distance)
                    + (item.skinUrl.isEmpty() ? "" : EnumChatFormatting.DARK_GRAY + " " + shortUrl(item.skinUrl)));
        }
        Main.sendMessage(EnumChatFormatting.GRAY + "Use " + EnumChatFormatting.YELLOW + "/npcskin <number>"
                + EnumChatFormatting.GRAY + " to copy another."
                + (saved ? " Full dump saved to: " + EnumChatFormatting.WHITE + outFile.getAbsolutePath() : ""));
    }

    private static String extractTextureBase64(Minecraft mc, EntityPlayer ep) {
        String val = readTextureFromProfile(ep.getGameProfile());
        if (val != null && !val.isEmpty()) {
            return val;
        }
        try {
            if (mc.getNetHandler() != null && ep.getUniqueID() != null) {
                NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(ep.getUniqueID());
                if (info != null) {
                    return readTextureFromProfile(info.getGameProfile());
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String readTextureFromProfile(GameProfile profile) {
        if (profile == null || profile.getProperties() == null) {
            return null;
        }
        Collection<Property> props = profile.getProperties().get("textures");
        if (props == null) {
            return null;
        }
        for (Property prop : props) {
            if (prop != null && prop.getValue() != null && !prop.getValue().trim().isEmpty()) {
                return prop.getValue().trim();
            }
        }
        return null;
    }

    private static String findNearbyHologram(Minecraft mc, EntityPlayer npc) {
        List<String> lines = new ArrayList<String>();
        for (Object obj : mc.theWorld.loadedEntityList) {
            if (!(obj instanceof EntityArmorStand)) {
                continue;
            }
            Entity stand = (Entity) obj;
            double dx = stand.posX - npc.posX;
            double dz = stand.posZ - npc.posZ;
            double dy = stand.posY - npc.posY;
            if (dx * dx + dz * dz <= 2.25D && dy >= -0.5D && dy <= 4.0D) {
                String label = stand.getCustomNameTag();
                if (label != null && !label.trim().isEmpty()) {
                    String clean = stripFormatting(label).trim();
                    if (!clean.isEmpty() && !clean.equalsIgnoreCase("Armor Stand")) {
                        lines.add(clean);
                    }
                }
            }
        }
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines.size(), 3); i++) {
            if (i > 0) sb.append(" | ");
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    private static String extractSkinUrl(String base64) {
        try {
            String decoded = new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
            Matcher m = SKIN_URL_PATTERN.matcher(decoded);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String shortUrl(String url) {
        int slash = url.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < url.length()) {
            String hash = url.substring(slash + 1);
            return hash.length() > 12 ? hash.substring(0, 12) + "..." : hash;
        }
        return url;
    }

    private static String displayLabel(CapturedNpcSkin item) {
        if (item.hologram != null && !item.hologram.isEmpty()) {
            return item.hologram;
        }
        return item.name == null || item.name.isEmpty() ? item.uuid : item.name;
    }

    private static String stripFormatting(String s) {
        return s.replaceAll("(?i)\u00a7[0-9a-fk-or]", "");
    }

    private static boolean writeDumpFile(File outFile, List<CapturedNpcSkin> items) {
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(outFile, false), StandardCharsets.UTF_8))) {
            for (int i = 0; i < items.size(); i++) {
                CapturedNpcSkin item = items.get(i);
                pw.println("=== #" + (i + 1) + " ===");
                pw.println("Hologram: " + item.hologram);
                pw.println("EntityName: " + item.name);
                pw.println("UUID: " + item.uuid);
                pw.println(String.format("Distance: %.2fm", item.distance));
                pw.println("SkinURL: " + item.skinUrl);
                pw.println("Base64: " + item.textureBase64);
                pw.println();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
