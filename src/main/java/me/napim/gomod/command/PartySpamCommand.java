package me.napim.gomod.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.weavemc.loader.api.command.Command;
import net.weavemc.loader.api.event.ChatReceivedEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Command to repeatedly send party invites to one or more players and disband,
 * while suppressing Hypixel's party-spam chat replies and showing a single
 * updating chat status line with a clickable [STOP] button.
 */
public class PartySpamCommand extends Command {
    static final int STATUS_CHAT_LINE_ID = 745912;
    private static final int DELAY = 210; // ms between party commands

    private static final PartySpamState STATE = new PartySpamState();
    private static final Object SCHEDULER_LOCK = new Object();
    private static ScheduledExecutorService scheduler = newDaemonScheduler();
    private static ScheduledFuture<?> currentTask = null;

    private static ScheduledExecutorService newDaemonScheduler() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "GoMod-PartySpam");
            t.setDaemon(true);
            return t;
        });
    }

    public PartySpamCommand() {
        super("pspam");
    }

    @Override
    public void handle(String[] args) {
        PartySpamState.CommandResult result = STATE.handleCommandArgs(args, System.currentTimeMillis());

        if (result.shouldStopScheduler) {
            stopScheduler();
        } else if (result.shouldStartScheduler) {
            startScheduler();
        }

        switch (result.outcome) {
            case USAGE:
                sendSimpleMessage(EnumChatFormatting.RED + "Usage: /pspam <player> [player2 ...] | /pspam stop");
                break;
            case NOT_RUNNING:
                sendSimpleMessage(EnumChatFormatting.YELLOW + "[pspam] Not currently running.");
                break;
            case RUNNING:
                updateStatusLine(buildRunningStatus(result.targets, result.sentCount));
                break;
            case STOPPED:
                updateStatusLine(buildStoppedStatus(result.targets, result.sentCount, null));
                break;
        }
    }

    @SubscribeEvent
    public void onChatReceived(ChatReceivedEvent event) {
        if (event == null || event.getMessage() == null) {
            return;
        }
        String raw = event.getMessage().getUnformattedText();
        PartySpamState.ChatDecision decision = STATE.handleIncomingChat(raw, System.currentTimeMillis());
        if (decision.suppress) {
            event.setCancelled(true);
        }
        if (decision.shouldStopScheduler) {
            stopScheduler();
        }
        if (decision.offlineRemovedTarget != null) {
            sendSimpleMessage(EnumChatFormatting.YELLOW + "[pspam] Stopped "
                    + EnumChatFormatting.GOLD + decision.offlineRemovedTarget
                    + EnumChatFormatting.GRAY + " (offline)");
        }
        if (decision.stoppedTargets != null) {
            updateStatusLine(buildStoppedStatus(decision.stoppedTargets, decision.sentCount, decision.stoppedReason));
        } else if (decision.runningTargets != null) {
            updateStatusLine(buildRunningStatus(decision.runningTargets, decision.sentCount));
        }
    }

    private static void stopScheduler() {
        synchronized (SCHEDULER_LOCK) {
            if (currentTask != null && !currentTask.isDone() && !currentTask.isCancelled()) {
                currentTask.cancel(false);
                currentTask = null;
            }
            scheduler.shutdown();
            scheduler = newDaemonScheduler();
        }
    }

    private static void startScheduler() {
        synchronized (SCHEDULER_LOCK) {
            if (currentTask != null && !currentTask.isDone() && !currentTask.isCancelled()) {
                return;
            }
            if (scheduler.isShutdown() || scheduler.isTerminated()) {
                scheduler = newDaemonScheduler();
            }
            currentTask = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    if (!STATE.isRunning()) {
                        stopScheduler();
                        return;
                    }

                    Minecraft mc = Minecraft.getMinecraft();
                    if (mc == null || mc.thePlayer == null || mc.theWorld == null) {
                        STATE.stopOnDisconnect(System.currentTimeMillis());
                        stopScheduler();
                        return;
                    }

                    final PartySpamState.DispatchStep step = STATE.nextDispatchStep(System.currentTimeMillis());
                    if (step == null) {
                        stopScheduler();
                        return;
                    }

                    mc.addScheduledTask(() -> {
                        Minecraft client = Minecraft.getMinecraft();
                        EntityPlayerSP player = client == null ? null : client.thePlayer;
                        if (player == null || client.theWorld == null) {
                            STATE.stopOnDisconnect(System.currentTimeMillis());
                            stopScheduler();
                            return;
                        }
                        if (!STATE.isRunning()) {
                            return;
                        }
                        if (step.sendInvite) {
                            player.sendChatMessage("/p invite " + step.target);
                            printStatusLineOnClientThread(
                                    client,
                                    buildRunningStatus(step.snapshotTargets, step.snapshotSent));
                        } else {
                            player.sendChatMessage("/p disband");
                        }
                    });
                } catch (Exception e) {
                    System.err.println("Error in party spam task: " + e.getMessage());
                    e.printStackTrace();
                }
            }, 0, DELAY, TimeUnit.MILLISECONDS);
        }
    }

    private static IChatComponent buildRunningStatus(List<String> targets, int sentCount) {
        ChatComponentText root = new ChatComponentText(
                EnumChatFormatting.GREEN + "[pspam] "
                        + EnumChatFormatting.GOLD + joinNames(targets)
                        + EnumChatFormatting.GRAY + " (" + sentCount + " sent) ");

        ChatComponentText stopButton = new ChatComponentText(
                EnumChatFormatting.RED + "" + EnumChatFormatting.BOLD + "[STOP]");
        ChatStyle style = new ChatStyle();
        style.setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/pspam stop"));
        style.setChatHoverEvent(new HoverEvent(
                HoverEvent.Action.SHOW_TEXT,
                new ChatComponentText(EnumChatFormatting.RED + "Click to stop party spam")));
        stopButton.setChatStyle(style);
        root.appendSibling(stopButton);
        return root;
    }

    private static IChatComponent buildStoppedStatus(List<String> targets, int sentCount, String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append(EnumChatFormatting.RED).append("[pspam] Stopped ")
                .append(EnumChatFormatting.GOLD).append(joinNames(targets))
                .append(EnumChatFormatting.GRAY).append(" (");
        if (reason != null && !reason.isEmpty()) {
            sb.append(reason).append(", ");
        }
        sb.append(sentCount).append(" sent)");
        return new ChatComponentText(sb.toString());
    }

    private static String joinNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                joined.append(EnumChatFormatting.GRAY).append(", ").append(EnumChatFormatting.GOLD);
            }
            joined.append(names.get(i));
        }
        return joined.toString();
    }

    private static void updateStatusLine(final IChatComponent component) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(() -> printStatusLineOnClientThread(Minecraft.getMinecraft(), component));
    }

    private static void printStatusLineOnClientThread(Minecraft mc, IChatComponent component) {
        if (mc != null && mc.ingameGUI != null && mc.ingameGUI.getChatGUI() != null && component != null) {
            mc.ingameGUI.getChatGUI().printChatMessageWithOptionalDeletion(component, STATUS_CHAT_LINE_ID);
        }
    }

    private static void sendSimpleMessage(final String message) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(() -> {
            Minecraft client = Minecraft.getMinecraft();
            if (client != null && client.thePlayer != null) {
                client.thePlayer.addChatMessage(new ChatComponentText(message));
            }
        });
    }
}
