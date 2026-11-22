package me.napim.gomod.features;

import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.weavemc.loader.api.event.ChatReceivedEvent;
import net.weavemc.loader.api.event.SubscribeEvent;
import net.weavemc.loader.api.event.TickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Sounds a short rising three-note alert for incoming Blitz Teams pregame team requests. */
public final class TeamInviteNotifier {

    private static final Pattern TEAM_REQUEST = Pattern.compile(
            "^[A-Za-z0-9_]{1,16} has requested to team with you!$", Pattern.CASE_INSENSITIVE);
    private static final float[] PITCHES = {1.4F, 1.7F, 1.9F};
    private static final int NOTE_GAP_TICKS = 3; // 0.15 seconds at 20 client ticks per second.
    private static final long DUPLICATE_WINDOW_MS = 1500L;

    private final List<PendingNote> pendingNotes = new ArrayList<PendingNote>();
    private String lastInviteMessage = "";
    private long lastInviteMs;

    @SubscribeEvent
    public void onChatReceived(ChatReceivedEvent event) {
        if (!ConfigManager.isPlayerTargetTeamInviteNotifierEnabled() || event == null || event.getMessage() == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null
                || !BlitzTeamsDetector.isBlitzTeamsMapPregame(mc)) {
            return;
        }
        String message = EnumChatFormatting.getTextWithoutFormattingCodes(event.getMessage().getUnformattedText());
        if (message == null) {
            return;
        }
        message = message.replaceAll("\\s+", " ").trim();
        if (!TEAM_REQUEST.matcher(message).matches()) {
            return;
        }

        long now = System.currentTimeMillis();
        String duplicateKey = message.toLowerCase(Locale.ROOT);
        synchronized (this.pendingNotes) {
            if (duplicateKey.equals(this.lastInviteMessage) && now - this.lastInviteMs <= DUPLICATE_WINDOW_MS) {
                return;
            }
            this.lastInviteMessage = duplicateKey;
            this.lastInviteMs = now;
            for (int i = 0; i < PITCHES.length; i++) {
                this.pendingNotes.add(new PendingNote(i * NOTE_GAP_TICKS, PITCHES[i]));
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!ConfigManager.isPlayerTargetTeamInviteNotifierEnabled()
                || mc == null || mc.thePlayer == null || mc.theWorld == null) {
            synchronized (this.pendingNotes) {
                this.pendingNotes.clear();
            }
            return;
        }

        ArrayList<Float> duePitches = new ArrayList<Float>(PITCHES.length);
        synchronized (this.pendingNotes) {
            Iterator<PendingNote> iterator = this.pendingNotes.iterator();
            while (iterator.hasNext()) {
                PendingNote note = iterator.next();
                if (note.ticksRemaining <= 0) {
                    duePitches.add(note.pitch);
                    iterator.remove();
                } else {
                    note.ticksRemaining--;
                }
            }
        }
        for (Float pitch : duePitches) {
            mc.thePlayer.playSound("note.pling", 1.0F, pitch);
        }
    }

    private static final class PendingNote {
        int ticksRemaining;
        final float pitch;

        PendingNote(int ticksRemaining, float pitch) {
            this.ticksRemaining = ticksRemaining;
            this.pitch = pitch;
        }
    }
}
