package me.napim.gomod.features;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Screenshots from the vanilla {@code screenshots} folder (F2 captures). */
public final class PlayerScreenshotFiles {

    private PlayerScreenshotFiles() {}

    public static File getScreenshotsDir(Minecraft mc) {
        if (mc == null || mc.mcDataDir == null) {
            return null;
        }
        return new File(mc.mcDataDir, "screenshots");
    }

    public static File findLatest(Minecraft mc) {
        List<File> list = listRecent(mc, 1, null);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Newest-first list of image files in the screenshots folder.
     */
    public static List<File> listRecent(Minecraft mc, int maxCount, String nameFilterLower) {
        File dir = getScreenshotsDir(mc);
        if (dir == null || !dir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> images = new ArrayList<File>();
        for (File f : files) {
            if (f == null || !f.isFile() || !isImageFile(f)) {
                continue;
            }
            if (nameFilterLower != null && !nameFilterLower.isEmpty()) {
                if (!f.getName().toLowerCase().contains(nameFilterLower)) {
                    continue;
                }
            }
            images.add(f);
        }
        Collections.sort(images, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        if (maxCount > 0 && images.size() > maxCount) {
            return new ArrayList<File>(images.subList(0, maxCount));
        }
        return images;
    }

    public static boolean isImageFile(File f) {
        if (f == null) {
            return false;
        }
        String n = f.getName().toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".webp");
    }

    /** Incoming friend screenshots saved under {@code gomod123/shared_screenshots}. */
    public static File getSharedScreenshotsDir(Minecraft mc) {
        if (mc == null || mc.mcDataDir == null) {
            return null;
        }
        return new File(new File(mc.mcDataDir, "gomod123"), "shared_screenshots");
    }

    /** Newest-first shared screenshots whose filename contains the friend's name. */
    public static List<File> listSharedForFriend(Minecraft mc, String friendName, int maxCount) {
        File dir = getSharedScreenshotsDir(mc);
        if (dir == null || !dir.isDirectory() || friendName == null || friendName.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String filter = friendName.trim().toLowerCase();
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<File> images = new ArrayList<File>();
        for (File f : files) {
            if (f == null || !f.isFile() || !isImageFile(f)) {
                continue;
            }
            if (!f.getName().toLowerCase().contains(filter)) {
                continue;
            }
            images.add(f);
        }
        Collections.sort(images, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        if (maxCount > 0 && images.size() > maxCount) {
            return new ArrayList<File>(images.subList(0, maxCount));
        }
        return images;
    }

    private static final long CHAT_SCREENSHOT_TIME_WINDOW_MS = 180_000L;

    /**
     * Resolves the image file for a friend-chat {@code screenshot} message (outgoing from
     * {@code screenshots/}, incoming from {@code gomod123/shared_screenshots/}).
     */
    public static File resolveChatScreenshotFile(Minecraft mc, String chatPeer, GomodSocialChat.Message message) {
        if (mc == null || message == null || !"screenshot".equalsIgnoreCase(message.kind)) {
            return null;
        }
        String fileName = message.text == null ? "" : message.text.trim();
        boolean self = isLocalPlayerName(mc, message.from);
        if (self) {
            return resolveInDir(getScreenshotsDir(mc), fileName, message.ts, null);
        }
        if (chatPeer == null || chatPeer.trim().isEmpty()) {
            return null;
        }
        return resolveInDir(getSharedScreenshotsDir(mc), fileName, message.ts, chatPeer.trim().toLowerCase());
    }

    private static boolean isLocalPlayerName(Minecraft mc, String from) {
        return mc != null && mc.thePlayer != null && from != null
                && from.equalsIgnoreCase(mc.thePlayer.getName());
    }

    private static File resolveInDir(File dir, String fileName, long messageTs, String nameFilterLower) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        if (!fileName.isEmpty()) {
            File exact = new File(dir, fileName);
            if (exact.isFile() && isImageFile(exact)) {
                return exact;
            }
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f != null && f.isFile() && isImageFile(f)
                            && f.getName().equalsIgnoreCase(fileName)) {
                        return f;
                    }
                }
            }
        }
        return findClosestByTime(dir, messageTs, nameFilterLower);
    }

    private static File findClosestByTime(File dir, long messageTs, String nameFilterLower) {
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        long anchor = messageTs > 0L ? messageTs : System.currentTimeMillis();
        File best = null;
        long bestDelta = Long.MAX_VALUE;
        for (File f : files) {
            if (f == null || !f.isFile() || !isImageFile(f)) {
                continue;
            }
            if (nameFilterLower != null && !nameFilterLower.isEmpty()
                    && !f.getName().toLowerCase().contains(nameFilterLower)) {
                continue;
            }
            long delta = Math.abs(f.lastModified() - anchor);
            if (delta <= CHAT_SCREENSHOT_TIME_WINDOW_MS && delta < bestDelta) {
                bestDelta = delta;
                best = f;
            }
        }
        return best;
    }
}
