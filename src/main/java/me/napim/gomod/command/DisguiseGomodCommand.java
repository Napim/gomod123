package me.napim.gomod.command;

/** Opt-in variant that shares the local name/skin disguise with Gomod peers on this server. */
public final class DisguiseGomodCommand extends DisguiseCommand {
    public DisguiseGomodCommand() {
        super("disguisegomod", true);
    }
}
