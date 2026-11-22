package me.napim.gomod.features;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.weavemc.loader.api.event.RenderGameOverlayEvent;
import net.weavemc.loader.api.event.SubscribeEvent;

/**
 * Failsafe renderer for the Blitz sidebar {@code KE: ...} row.
 *
 * <p>Some client/render paths don't hit our FontRenderer redirects reliably. This draws the line directly using the
 * vanilla scoreboard layout when we know an effect and can find the Kit row in the current sidebar.</p>
 *
 * <p>Primary paint is {@link BlitzFlameKillEffectTracker#drawSidebarKillEffectAfterVanillaScoreboard} at
 * {@code renderScoreboard} RETURN (same idea as CustomScoreboard: one coherent pass). This Post handler only runs
 * when that path did not run (e.g. scoreboard render cancelled early).</p>
 */
public final class SidebarKeOverlay {

    /** Failsafe layout scan is expensive; refresh at most this often unless invalidated. */
    private static final long KIT_ROW_LAYOUT_MIN_INTERVAL_MS = 200L;
    private static long keFailsafeLayoutAtMs;
    private static int keFailsafeKeY = -1;
    private static float keFailsafeLeft;
    private static int keFailsafeRightEdge;
    /** Detect sidebar swaps (title / line count) without hashing every line each frame. */
    private static String keFailsafeLayoutSig;

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
                return;
            }
            if (BlitzFlameKillEffectTracker.wasSidebarKeInjectedThisScoreboardPass()) {
                return;
            }
            if (BlitzFlameKillEffectTracker.isSidebarRealRowRewriteActive()) {
                return;
            }
            if (!BlitzFlameKillEffectTracker.shouldInjectSidebarKillEffectLine()) {
                return;
            }
            if (BlitzFlameKillEffectTracker.shouldSkipSidebarKeBecauseAlreadyPainted()) {
                return;
            }

            Scoreboard sb = mc.theWorld.getScoreboard();
            if (sb == null) {
                return;
            }
            ScoreObjective obj = sb.getObjectiveInDisplaySlot(1);
            if (obj == null) {
                return;
            }

            FontRenderer fr = mc.fontRendererObj;
            if (fr == null) {
                return;
            }
            long now = System.currentTimeMillis();
            String quickSig = sidebarQuickSignature(sb, obj, fr);
            boolean needLayout = keFailsafeKeY < 0
                    || now - keFailsafeLayoutAtMs >= KIT_ROW_LAYOUT_MIN_INTERVAL_MS
                    || quickSig == null
                    || !quickSig.equals(keFailsafeLayoutSig);
            if (needLayout) {
                keFailsafeLayoutSig = quickSig;
                keFailsafeLayoutAtMs = now;
                recomputeKeFailsafeLayout(mc, fr, sb, obj);
            }
            if (keFailsafeKeY < 0) {
                return;
            }
            BlitzFlameKillEffectTracker.drawSidebarKillEffectLineAt(fr, keFailsafeLeft, keFailsafeKeY, keFailsafeRightEdge);
        } catch (Throwable ignored) {
        }
    }

    private static String sidebarQuickSignature(Scoreboard sb, ScoreObjective obj, FontRenderer fr) {
        try {
            if (sb == null || obj == null) {
                return "";
            }
            String title = obj.getDisplayName();
            int n = SidebarScoreboardLayout.visibleScoresForSidebarLayout(sb, obj).size();
            int w = fr != null ? fr.getStringWidth(title == null ? "" : title) : 0;
            return (title == null ? "" : title) + "|" + n + "|" + w;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void recomputeKeFailsafeLayout(Minecraft mc, FontRenderer fr, Scoreboard sb, ScoreObjective obj) {
        keFailsafeKeY = -1;
        try {
            ScaledResolution sr = new ScaledResolution(mc);
            List<Score> visibleScores = SidebarScoreboardLayout.visibleScoresForSidebarLayout(sb, obj);
            if (visibleScores.isEmpty()) {
                return;
            }

            int width = fr.getStringWidth(obj.getDisplayName());
            ArrayList<Score> drawOrder = new ArrayList<>(visibleScores);
            Collections.reverse(drawOrder);
            int kitJ = -1;
            for (int j = 0; j < drawOrder.size(); j++) {
                Score sc = drawOrder.get(j);
                ScorePlayerTeam team = sb.getPlayersTeam(sc.getPlayerName());
                String line = ScorePlayerTeam.formatPlayerName(team, sc.getPlayerName());
                if (line == null) {
                    line = "";
                }
                width = Math.max(width, fr.getStringWidth(line));
                if (ScoreboardDateHider.isKitSidebarTextOrFragment(line)) {
                    kitJ = j;
                }
            }

            int padding = 3;
            keFailsafeLeft = sr.getScaledWidth() - width - padding;
            keFailsafeRightEdge = sr.getScaledWidth() - 2;

            int kitYTracked = BlitzFlameKillEffectTracker.getSidebarLastKitAnchorDrawY();
            if (kitYTracked >= 0 && kitYTracked < sr.getScaledHeight()) {
                keFailsafeKeY = kitYTracked + fr.FONT_HEIGHT;
                return;
            }

            if (kitJ < 0) {
                return;
            }

            int n = drawOrder.size();
            int fh = fr.FONT_HEIGHT;
            int m = sr.getScaledHeight() / 2 + n * fh / 3;
            int kitY = m - (n - kitJ) * fh;
            keFailsafeKeY = kitY + fh;
        } catch (Throwable ignored) {
            keFailsafeKeY = -1;
        }
    }
}

