package me.napim.gomod.mixins;

import me.napim.gomod.util.SidebarSnapshot;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.Scoreboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Scoreboard.class)
public abstract class ScoreboardSidebarSnapshotMixin implements SidebarSnapshot.Access {
    @Unique private volatile long gomod$sidebarRevision;
    @Unique private volatile SidebarSnapshot gomod$latestSidebar;
    @Unique private volatile SidebarSnapshot gomod$otherSidebar;

    @Override
    public long gomod$sidebarRevision() {
        return gomod$sidebarRevision;
    }

    @Override
    public void gomod$invalidateSidebar() {
        gomod$sidebarRevision++;
    }

    @Override
    public SidebarSnapshot gomod$sidebarSnapshot(ScoreObjective objective) {
        long revision = gomod$sidebarRevision;
        SidebarSnapshot latest = gomod$latestSidebar;
        if (latest != null && latest.matches(objective, revision)) {
            return latest;
        }
        // The team-color sidebar can coexist with display slot 1.
        SidebarSnapshot other = gomod$otherSidebar;
        if (other != null && other.matches(objective, revision)) {
            return other;
        }
        SidebarSnapshot fresh = new SidebarSnapshot((Scoreboard) (Object) this, objective, revision);
        gomod$otherSidebar = latest;
        gomod$latestSidebar = fresh;
        return fresh;
    }

    @Inject(method = {"func_96536_a", "removeObjectiveFromEntity", "removeObjective", "setObjectiveInDisplaySlot",
            "onObjectiveDisplayNameChanged", "sendTeamUpdate", "removePlayerFromTeam", "removeTeam"},
            at = @At("RETURN"))
    private void gomod$sidebarChanged(CallbackInfo ci) {
        gomod$invalidateSidebar();
    }

    @Inject(method = "addPlayerToTeam", at = @At("RETURN"))
    private void gomod$sidebarTeamJoined(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            gomod$invalidateSidebar();
        }
    }

    @Inject(method = "getValueFromObjective", at = @At(value = "NEW", target = "net/minecraft/scoreboard/Score"))
    private void gomod$sidebarScoreCreated(CallbackInfoReturnable<Score> cir) {
        gomod$invalidateSidebar();
    }
}
