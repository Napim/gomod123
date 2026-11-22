package me.napim.gomod.weave;

import net.minecraft.util.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/** Adapts gomod's shared command contract to Weave 1.3.4's command contract. */
final class WeaveCommandAdapter extends net.weavemc.api.command.Command {
    private final net.weavemc.loader.api.command.Command delegate;

    WeaveCommandAdapter(net.weavemc.loader.api.command.Command delegate) {
        super(delegate.name, delegate.aliases.toArray(new String[delegate.aliases.size()]));
        this.delegate = delegate;
    }

    @Override
    public void execute(@NotNull String[] args) {
        // Weave 1.3.4 includes the command name at index 0. Shared gomod handlers do not.
        String[] commandArgs = args.length <= 1
                ? new String[0]
                : Arrays.copyOfRange(args, 1, args.length);
        delegate.handle(commandArgs);
    }

    @Override
    public @Nullable String[] getSuggestions(@NotNull String[] args, @Nullable BlockPos targetPos) {
        return null;
    }
}
