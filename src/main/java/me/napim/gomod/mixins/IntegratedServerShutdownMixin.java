package me.napim.gomod.mixins;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.Uninterruptibles;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounds the integrated-server shutdown wait when Minecraft's queued stop task races with server termination.
 */
@Mixin(IntegratedServer.class)
public abstract class IntegratedServerShutdownMixin extends MinecraftServer {

    public IntegratedServerShutdownMixin() {
        super(null, null);
    }

    @Redirect(
            method = "initiateShutdown",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/util/concurrent/Futures;getUnchecked(Ljava/util/concurrent/Future;)Ljava/lang/Object;",
                    remap = false
            ),
            require = 0
    )
    private <V> V gomod$waitForShutdownTaskOrServerStop(Future<V> task) {
        while (!isServerStopped()) {
            try {
                return Uninterruptibles.getUninterruptibly(task, 1L, TimeUnit.SECONDS);
            } catch (ExecutionException completedWithFailure) {
                return Futures.getUnchecked(task);
            } catch (TimeoutException stillRunning) {
                // Recheck whether the server has stopped before waiting another interval.
            }
        }
        return null;
    }
}
