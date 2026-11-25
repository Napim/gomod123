package me.napim.gomod.weave;

import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/** Regression tests for Lunar Hypixel Stats Blitz display name, structural discovery, and session accounting. */
public final class LunarHypixelBlitzStatsTest {
    private static final String HELPER = "me/napim/gomod/weave/LunarHypixelBlitzStatsBridge";
    private static final String ROOT = "com/moonsworth/lunar/client/synthetic/";
    private static final String OPTIONS_TYPE = ROOT + "SyntheticModDetails";
    private static final String OPTIONS_BUILDER_TYPE = OPTIONS_TYPE + "$Builder";
    private static final String MODULE_BASE = ROOT + "SyntheticAbstractMod";
    private static final String DATA_MODEL = ROOT + "SyntheticBedWarsModel";
    private static final String STATS_CHILD_CLASS = ROOT + "SyntheticStatsChild";
    private static final String MESSAGE_PARSER = ROOT + "SyntheticParser";
    private static final String EVENT_ENUM = MESSAGE_PARSER + "$Event";

    @Before
    public void resetBridge() {
        LunarHypixelBlitzStatsBridge.resetTestState();
    }

    @Test
    public void detailsBuilderTransformAndNameSupplierFixFallbackName() throws Exception {
        byte[] transformed = LunarHypixelBlitzStatsTransform.transform(MODULE_BASE, syntheticModuleBaseClass());
        assertNotNull("MODULE_BASE transform must succeed", transformed);

        final int[] configureCalls = {0};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (!"HCICROIRHCHHORCCHIOHCICRRIORRO".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc,
                            boolean isInterface) {
                        if (HELPER.equals(owner) && "configureBlitzDetailsBuilder".equals(methodName)) {
                            assertEquals(Opcodes.INVOKESTATIC, opcode);
                            assertEquals("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", methodDesc);
                            configureCalls[0]++;
                        }
                    }
                };
            }
        }, 0);
        assertEquals("Details factory must configure the Blitz display name supplier", 1, configureCalls[0]);

        // Verify configureBlitzDetailsBuilder leaves unrelated modules untouched and configures a real Blitz child.
        FakeDetailsBuilder bedwarsBuilder = new FakeDetailsBuilder();
        LunarHypixelBlitzStatsBridge.configureBlitzDetailsBuilder(bedwarsBuilder, new Object());
        assertNull("Non-Blitz module must keep its native details name lookup", bedwarsBuilder.nameSupplier);

        Object blitzChild = newSyntheticBlitzChild();
        assertTrue("Synthetic native child with Blitz ID must be recognized",
                LunarHypixelBlitzStatsBridge.isBlitzChild(blitzChild));
        FakeDetailsBuilder blitzBuilder = new FakeDetailsBuilder();
        assertSame(blitzBuilder, LunarHypixelBlitzStatsBridge.configureBlitzDetailsBuilder(blitzBuilder, blitzChild));
        assertNotNull("Blitz child must receive a native display name supplier", blitzBuilder.nameSupplier);
        assertEquals("Blitz Survival Games", blitzBuilder.nameSupplier.get());
    }

    @Test
    public void dataModelTransformSkipsBedWarsArmorAutoStartForBlitz() {
        byte[] transformed = LunarHypixelBlitzStatsTransform.transform(
                DATA_MODEL, syntheticDataModelClass());
        assertNotNull("DATA_MODEL transform must succeed", transformed);

        final int[] guardCalls = {0};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                if (!"armorTick".equals(name)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc,
                            boolean isInterface) {
                        if (HELPER.equals(owner) && "isBlitzModel".equals(methodName)) {
                            assertEquals(Opcodes.INVOKESTATIC, opcode);
                            assertEquals("(Ljava/lang/Object;)Z", methodDesc);
                            guardCalls[0]++;
                        }
                    }
                };
            }
        }, 0);
        assertEquals("Bed Wars armor auto-start tick must be guarded for Blitz", 1, guardCalls[0]);
    }

    @Test
    public void realLunarJarTransformsCleanlyWhenPresent() throws Exception {
        File lunarJar = new File("C:/Users/admin0/.lunarclient/offline/multiver/lunar.jar");
        if (!lunarJar.isFile()) return;

        List<String> transformedNames = new ArrayList<>();
        final boolean[] sawDetailsHook = {false};
        final boolean[] sawArmorGuard = {false};
        final boolean[] sawBlitzCoinDispatch = {false};
        final boolean[] sawGamePresence = {false};
        final boolean[] sawGameStart = {false};
        final boolean[] sawSoloLoss = {false};
        final boolean[] sawHudSections = {false};
        final boolean[] sawClassifyBlitz = {false};

        try (ZipFile zip = new ZipFile(lunarJar)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.endsWith(".class") || !entryName.startsWith("com/moonsworth/lunar/")) continue;
                String className = entryName.substring(0, entryName.length() - ".class".length());
                byte[] original = readAll(zip.getInputStream(entry));
                if (!LunarHypixelBlitzStatsTransform.mayContain(className, original)) continue;

                byte[] patched = LunarHypixelBlitzStatsTransform.transform(className, original);
                if (patched == null) continue;
                transformedNames.add(className);

                // Re-parse every modified class with EXPAND_FRAMES to verify StackMapTable frame integrity.
                new ClassReader(patched).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                            String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            private boolean hasPlusLdc;
                            private boolean hasElimLdc;
                            private Label afterCoinReturn;
                            private boolean blitzGuard;

                            @Override
                            public void visitLdcInsn(Object value) {
                                if ("+".equals(value)) hasPlusLdc = true;
                                if ("You have been eliminated!".equals(value)) hasElimLdc = true;
                            }

                            @Override
                            public void visitJumpInsn(int opcode, Label label) {
                                if (hasPlusLdc && opcode == Opcodes.IFEQ) {
                                    afterCoinReturn = label;
                                    hasPlusLdc = false;
                                }
                                if (blitzGuard && opcode == Opcodes.IFNE && label == afterCoinReturn) {
                                    sawBlitzCoinDispatch[0] = true;
                                }
                                blitzGuard = false;
                            }

                            @Override
                            public void visitMethodInsn(int opcode, String owner, String methodName,
                                    String methodDesc, boolean isInterface) {
                                if (HELPER.equals(owner) && "configureBlitzDetailsBuilder".equals(methodName)) {
                                    sawDetailsHook[0] = true;
                                }
                                if (HELPER.equals(owner) && "blitzGamePresence".equals(methodName)) {
                                    sawGamePresence[0] = true;
                                }
                                if (HELPER.equals(owner) && "shouldStartBlitzSession".equals(methodName)) {
                                    sawGameStart[0] = true;
                                }
                                if (HELPER.equals(owner) && "takeBlitzSoloLoss".equals(methodName)) {
                                    sawSoloLoss[0] = true;
                                }
                                if (HELPER.equals(owner) && "blitzHudSections".equals(methodName)) {
                                    sawHudSections[0] = true;
                                }
                                if (HELPER.equals(owner) && "classifyBlitz".equals(methodName)) {
                                    sawClassifyBlitz[0] = true;
                                }
                                if (HELPER.equals(owner) && "isBlitzModel".equals(methodName)) {
                                    if ("()V".equals(descriptor)) {
                                        sawArmorGuard[0] = true;
                                    } else {
                                        blitzGuard = true;
                                    }
                                }
                            }

                            @Override
                            public void visitEnd() {
                                if (!hasElimLdc) afterCoinReturn = null;
                            }
                        };
                    }
                }, ClassReader.EXPAND_FRAMES);
            }
        }

        assertEquals("Expected all 8 structural Hypixel Stats target classes in lunar.jar to transform: "
                + transformedNames, 8, transformedNames.size());
        assertTrue("Real Lunar AbstractMod must contain configureBlitzDetailsBuilder hook", sawDetailsHook[0]);
        assertTrue("Real Lunar BedWarsDataModel must contain isBlitzModel armor-start guard", sawArmorGuard[0]);
        assertTrue("Real Lunar BedWarsDataModel must route Blitz coin rewards into chat dispatcher",
                sawBlitzCoinDispatch[0]);
        assertTrue("Real Lunar ModelBase must contain blitzGamePresence hook", sawGamePresence[0]);
        assertTrue("Real Lunar ModelBase must contain shouldStartBlitzSession hook", sawGameStart[0]);
        assertTrue("Real Lunar BedWarsDataModel must contain takeBlitzSoloLoss hook", sawSoloLoss[0]);
        assertTrue("Real Lunar HypixelStatsChild must contain blitzHudSections hook", sawHudSections[0]);
        assertTrue("Real Lunar MessageParser must contain classifyBlitz hook", sawClassifyBlitz[0]);

        assertNotNull("detailsNameSetterMethod must be resolved structurally",
                readStaticString("detailsNameSetterMethod"));
        assertNotNull("hypixelStatsInLobbyField must be resolved structurally",
                readStaticString("hypixelStatsInLobbyField"));
        assertNotNull("hypixelStatsGameIdField must be resolved structurally",
                readStaticString("hypixelStatsGameIdField"));
        assertNotNull("hypixelStatsChildParentMethod must be resolved structurally",
                readStaticString("hypixelStatsChildParentMethod"));
        assertNotNull("modelBaseModeGetterMethod must be resolved structurally",
                readStaticString("modelBaseModeGetterMethod"));
        assertNotNull("modelBaseGameIdField must be resolved structurally",
                readStaticString("modelBaseGameIdField"));
        assertNotNull("modelBaseChildField must be resolved structurally",
                readStaticString("modelBaseChildField"));
        assertNotNull("eventEnumClassName must be resolved structurally",
                readStaticString("eventEnumClassName"));
    }

    private static String readStaticString(String fieldName) throws Exception {
        Field field = LunarHypixelBlitzStatsBridge.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void optionIdPrefersBaseIdFieldOverSubclassFormatMethod() throws Exception {
        abstract class BaseStatItem {
            @SuppressWarnings("unused")
            private final String id;

            BaseStatItem(String id) {
                this.id = id;
            }
        }
        class CounterStatItem extends BaseStatItem {
            CounterStatItem(String id) {
                super(id);
            }

            @SuppressWarnings("unused")
            public String formatValue() {
                return "0";
            }
        }

        Object kills = new CounterStatItem("kills");
        Object wins = new CounterStatItem("wins");
        Object bedBreaks = new CounterStatItem("bedBreaks");

        assertEquals("kills", LunarHypixelBlitzStatsBridge.optionId(kills));
        assertEquals("wins", LunarHypixelBlitzStatsBridge.optionId(wins));
        assertEquals("bedBreaks", LunarHypixelBlitzStatsBridge.optionId(bedBreaks));

        Object sessionGroup = new Object();
        Field groupsField = LunarHypixelBlitzStatsBridge.class.getDeclaredField("blitzStatsGroups");
        groupsField.setAccessible(true);
        ((java.util.Map<Object, String>) groupsField.get(null)).put(sessionGroup, "session");
        try {
            List<?> filtered = LunarHypixelBlitzStatsBridge.filterBlitzStats(
                    java.util.Arrays.asList(kills, wins, bedBreaks), sessionGroup);
            assertEquals("Blitz session stats must keep valid Blitz stat items", 2, filtered.size());
        } finally {
            ((java.util.Map<Object, String>) groupsField.get(null)).remove(sessionGroup);
        }
    }

    @Test
    public void dataModeUsesHypixelModApiSurvivalGamesKey() throws Exception {
        java.lang.reflect.Field flag = LunarHypixelBlitzStatsBridge.class.getDeclaredField("constructingBlitzModel");
        flag.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<Boolean> constructing = (ThreadLocal<Boolean>) flag.get(null);
        assertEquals("BEDWARS", LunarHypixelBlitzStatsBridge.dataMode("BEDWARS"));
        constructing.set(Boolean.TRUE);
        try {
            assertEquals("SURVIVAL_GAMES", LunarHypixelBlitzStatsBridge.dataMode("BEDWARS"));
            assertEquals("SKYWARS", LunarHypixelBlitzStatsBridge.dataMode("SKYWARS"));
        } finally {
            constructing.remove();
        }
    }

    @Test
    public void replaysObservedSessionWithSkyWarsDodgedPregamesAndSpectatedEnds() {
        SessionHarness s = new SessionHarness("OnSeb");
        long t = 1_000_000L;

        // 09:53 Blitz lobby, then 6 SkyWars games -> must contribute 0 Blitz games, 0 losses, 0 deaths.
        s.enterBlitzLobby("blitzlobby3", t += 1000);
        for (int i = 0; i < 6; i++) {
            s.enterNonBlitzGame("miniSW" + i, "SKYWARS", "skywars", t += 5000);
            s.chat("SkyWars", t += 1000);
            s.chat("Teaming is not allowed on Solo mode!", t += 1000);
            s.chat("You died! Want to play again? Click here!", t += 5000);
        }
        assertEquals(0, s.games);
        assertEquals(0, s.wins);
        assertEquals(0, s.losses);
        assertEquals(0, s.deaths);

        // 10:02 Dodged Blitz pregame (Aelin's Tower -> left to SkyWars) -> 0 games, 0 losses.
        s.enterBlitzPregame("mini187CN", "solo_normal", t += 5000);
        s.enterNonBlitzGame("mini70AP", "SKYWARS", "skywars", t += 5000);
        assertEquals(0, s.games);
        assertEquals(0, s.losses);

        // Game 1 (Woodlands): dies BEFORE "Teaming is not allowed on Solo maps!", then spectates until Winner line.
        s.enterBlitzPregame("mini19AW", "solo_normal", t += 5000);
        s.chat("                         Blitz Survival Games", t += 40_000);
        s.chat("OnSeb was killed by Leg1tRobert!", t += 35_000);
        s.chat("You were killed by Leg1tRobert with their Phoenix X kit!", t);
        s.chat("You died, use your compass to spectate players!", t);
        s.chat("Teaming is not allowed on Solo maps! Report teamers using /report!", t += 10_000);
        s.chat("                         Blitz Survival Games", t += 90_000);
        s.chat("                  Winner - Leg1tRobert (Phoenix X)", t);
        assertEquals(1, s.games);
        assertEquals(0, s.wins);
        assertEquals(1, s.losses);
        assertEquals(1, s.deaths);

        // Game 2 (Impact): dies after solo rule, leaves immediately.
        s.enterBlitzPregame("mini89DC", "solo_normal", t += 10_000);
        s.chat("                         Blitz Survival Games", t += 25_000);
        s.chat("Teaming is not allowed on Solo maps! Report teamers using /report!", t += 45_000);
        s.chat("OnSeb was killed by Leg1tRobert!", t += 60_000);
        assertEquals(2, s.games);
        assertEquals(2, s.losses);

        // Game 3 (KTulu Island): 2 kills, dies, leaves.
        s.enterBlitzPregame("mini144BM", "solo_normal", t += 10_000);
        s.chat("                         Blitz Survival Games", t += 60_000);
        s.chat("You damaged Mocha_Cow for 42% before killing them!", t += 30_000);
        s.chat("Mocha_Cow was killed by OnSeb!", t);
        s.chat("You damaged asdioqiwe for 100% before killing them!", t += 60_000);
        s.chat("asdioqiwe was killed by OnSeb!", t);
        s.chat("OnSeb was killed by Leg1tRobert!", t += 30_000);
        assertEquals(3, s.games);
        assertEquals(3, s.losses);
        assertEquals(2, s.kills);

        // Two dodged pregame lobbies (Gulch, Island of Despair -> blitzlobby1) -> 0 games, 0 losses.
        s.enterBlitzPregame("mini16CW", "solo_normal", t += 10_000);
        s.enterBlitzPregame("mini141AR", "solo_normal", t += 6_000);
        s.enterBlitzLobby("blitzlobby1", t += 2_000);
        assertEquals(3, s.games);
        assertEquals(3, s.losses);

        // Game 4 (Aelin's Tower): 3 kills, dies, then 3 seconds later end-of-game summary prints!
        // Previously double-counted as 2 games / 2 losses.
        s.enterBlitzPregame("mini114CA", "solo_normal", t += 30_000);
        s.chat("                         Blitz Survival Games", t += 60_000);
        s.chat("Teaming is not allowed on Solo maps! Report teamers using /report!", t += 45_000);
        s.chat("You damaged Azhy12 for 100% before killing them!", t += 30_000);
        s.chat("You damaged shaaaai for 100% before killing them!", t += 30_000);
        s.chat("You damaged Guhwa for 100% before killing them!", t += 30_000);
        s.chat("OnSeb was killed by Leg1tRobert!", t += 30_000);
        s.chat("                         Blitz Survival Games", t += 3_000);
        s.chat("                  Winner - Leg1tRobert (Phoenix X)", t);
        assertEquals(4, s.games);
        assertEquals(4, s.losses);
        assertEquals(5, s.kills);

        // Game 5 (Woodlands): WIN!
        s.enterBlitzPregame("mini89DU", "solo_normal", t += 5_000);
        s.chat("                         Blitz Survival Games", t += 50_000);
        s.chat("You damaged sordidstew for 100% before killing them!", t += 30_000);
        s.chat("You damaged Guhwa for 100% before killing them!", t += 30_000);
        s.chat("You damaged Leg1tRobert for 86% before killing them!", t += 30_000);
        s.chat("                         Blitz Survival Games", t += 3_000);
        s.chat("                   Winner - [VIP] OnSeb (Paladin X)", t);
        assertEquals(5, s.games);
        assertEquals(1, s.wins);
        assertEquals(4, s.losses);

        // Games 6, 7, 8 (Winter, Woodlands, Citadel): solo losses.
        for (String server : new String[]{"mini30CT", "mini20BR", "mini72BQ"}) {
            s.enterBlitzPregame(server, "solo_normal", t += 10_000);
            s.chat("                         Blitz Survival Games", t += 30_000);
            s.chat("Teaming is not allowed on Solo maps! Report teamers using /report!", t += 45_000);
            s.chat("OnSeb was killed by Th3BigZ3ro!", t += 30_000);
        }
        assertEquals(8, s.games);
        assertEquals(1, s.wins);
        assertEquals(7, s.losses);

        // Game 9 (Gulch): dies, spectates 2.5 minutes until end-of-game summary! Previously double-counted.
        s.enterBlitzPregame("mini16CW", "solo_normal", t += 5_000);
        s.chat("                         Blitz Survival Games", t += 35_000);
        s.chat("Teaming is not allowed on Solo maps! Report teamers using /report!", t += 45_000);
        s.chat("OnSeb was killed by Leg1tRobert!", t += 60_000);
        s.chat("                         Blitz Survival Games", t += 145_000);
        s.chat("                 Winner - [VIP] KrulTepesi (Warrior)", t);
        assertEquals(9, s.games);
        assertEquals(1, s.wins);
        assertEquals(8, s.losses);

        // Game 10 (Hamani): leaves mid-game to lobby, then queues Egypt.
        s.enterBlitzPregame("mini69B", "solo_normal", t += 5_000);
        s.chat("                         Blitz Survival Games", t += 60_000);
        s.enterBlitzLobby("blitzlobby3", t += 120_000);

        // Game 11 (Egypt): kicked/disconnected mid-game, then joins & leaves two pregame lobbies.
        s.enterBlitzPregame("mini147CQ", "solo_normal", t += 3_000);
        assertEquals(10, s.games);
        assertEquals(9, s.losses);
        s.chat("                         Blitz Survival Games", t += 100_000);
        s.disconnect();
        assertEquals(11, s.games);
        assertEquals(10, s.losses);

        // Dodged pregame lobbies after Egypt (mini97DU, mini182DV) -> 0 extra games/losses.
        s.enterBlitzLobby("blitzlobby3", t += 5_000);
        s.enterBlitzPregame("mini97DU", "solo_normal", t += 5_000);
        s.disconnect();
        s.enterBlitzLobby("blitzlobby3", t += 60_000);
        s.enterBlitzPregame("mini182DV", "solo_normal", t += 5_000);
        s.enterBlitzLobby("blitzlobby2", t += 2_000);

        assertEquals("11 real Blitz games must count as exactly 11 games", 11, s.games);
        assertEquals("1 real Blitz win must count as exactly 1 win", 1, s.wins);
        assertEquals("10 real Blitz losses must count as exactly 10 losses", 10, s.losses);
    }

    @Test
    public void teamsWinAfterEarlyLocalDeathCountsAsSingleWin() {
        SessionHarness s = new SessionHarness("OnSeb");
        long t = 2_000_000L;

        s.enterBlitzPregame("mini55T", "teams_normal", t += 1000);
        s.chat("                         Blitz Survival Games", t += 30_000);
        s.chat("OnSeb was killed by Enemy1!", t += 30_000);
        assertEquals(0, s.games);
        assertEquals(0, s.losses);
        assertEquals(1, s.deaths);

        s.chat("                         Blitz Survival Games", t += 90_000);
        s.chat("Winning Team - Teammate1, OnSeb", t);
        s.chat("2nd - [VIP] OnSeb (Paladin X) - 1 Kill", t);

        assertEquals(1, s.games);
        assertEquals(1, s.wins);
        assertEquals(0, s.losses);
        assertEquals(1, s.deaths);
    }

    @Test
    public void persiaWinBonusRecordsWinBeforeAutoQueueTransfersToBastion() {
        SessionHarness s = new SessionHarness("LocalPlayer");
        long t = 3_000_000L;

        s.enterBlitzPregame("mini69D", "solo_normal", t);
        s.chat("                         Blitz Survival Games", t += 264_000L);
        s.chat("You damaged Opponent for 100% before killing them!", t += 268_000L);
        s.chat("+2160 coins! (Win Bonus)", t);
        assertEquals(1, s.wins);

        s.chat("Opponent was killed by LocalPlayer!", t);
        s.chat("+189 coins! (Kill Bonus, Participation Bonus)", t);
        s.enterBlitzPregame("mini117CU", "solo_normal", t += 2_000L);

        assertEquals(1, s.games);
        assertEquals(1, s.wins);
        assertEquals(0, s.losses);
        assertEquals(1, s.kills);
    }

    @Test
    public void teamWinBonusAndLaterSummaryCountAsOneResult() {
        SessionHarness s = new SessionHarness("LocalPlayer");
        long t = 4_000_000L;

        s.enterBlitzPregame("mini42T", "teams_normal", t);
        s.chat("Blitz Survival Games", t += 30_000L);
        s.chat("LocalPlayer was killed by Enemy1!", t += 30_000L);
        s.chat("\u00a76+2,160 coins! \u00a77(Win Bonus)", t += 30_000L);
        assertEquals(1, s.wins);

        s.chat("+2160 coins! (Win Bonus)", t += 1_000L);
        s.chat("Blitz Survival Games", t += 1_000L);
        s.chat("Winning Team - Teammate1, LocalPlayer", t);
        assertEquals(1, s.games);
        assertEquals(1, s.wins);
        assertEquals(0, s.losses);
        assertEquals(1, s.deaths);
    }

    @Test
    public void coinRewardsRequireAnActiveBlitzMatchAndTheWinBonusLine() {
        SessionHarness s = new SessionHarness("LocalPlayer");
        long t = 5_000_000L;

        s.enterNonBlitzGame("mini30S", "SKYWARS", "skywars", t);
        s.chat("+2160 coins! (Win Bonus)", t += 1_000L);
        s.enterBlitzLobby("blitzlobby3", t += 2_000L);
        s.chat("+2160 coins! (Win Bonus)", t += 1_000L);
        s.enterBlitzPregame("mini117CU", "solo_normal", t += 2_000L);
        s.chat("+2160 coins! (Win Bonus)", t += 1_000L);
        assertEquals("WAITING", s.state);
        s.chat("Blitz Survival Games", t += 30_000L);
        s.chat("+189 coins! (Kill Bonus, Participation Bonus)", t);
        s.chat("[VIP] OtherPlayer: +2160 coins! (Win Bonus)", t);

        assertEquals("RUNNING", s.state);
        assertEquals(0, s.games);
        assertEquals(0, s.wins);
    }

    /** Faithful replica of Lunar's MODEL_BASE + DATA_MODEL state machine to test our bridge transitions. */
    private static final class SessionHarness {
        final Object model = new Object();
        final String localName;
        Object world = new Object();
        String scoreboardTitle = "";
        String state = "AWAY";
        String currentGameId = null;
        String mode = "";
        boolean inLobby = false;
        boolean gameDecided = false;
        boolean paused = false;
        int games;
        int wins;
        int losses;
        int kills;
        int deaths;

        SessionHarness(String localName) {
            this.localName = localName;
        }

        void enterBlitzLobby(String lobbyName, long now) {
            this.world = new Object();
            this.scoreboardTitle = "blitzsg";
            this.inLobby = true;
            this.mode = "";
            if ("RUNNING".equals(state)) {
                paused = true;
            } else {
                abortCurrentGame();
            }
            setState("LOBBY");
            chat("{\"server\":\"dynamiclobby24B\",\"gametype\":\"SURVIVAL_GAMES\",\"lobbyname\":\"" + lobbyName + "\"}", now);
        }

        void enterBlitzPregame(String server, String gameMode, long now) {
            this.world = new Object();
            this.scoreboardTitle = "blitzsg";
            this.inLobby = false;
            this.mode = gameMode;
            String normalizedId = server.replaceFirst("^[a-zA-Z]+", "");
            if (!normalizedId.equals(currentGameId)) {
                abortCurrentGame();
                currentGameId = normalizedId;
                setState("WAITING");
            }
            chat("{\"server\":\"" + server + "\",\"gametype\":\"SURVIVAL_GAMES\",\"mode\":\"" + gameMode + "\",\"map\":\"Test\"}", now);
        }

        void enterNonBlitzGame(String server, String gameType, String title, long now) {
            this.world = new Object();
            this.scoreboardTitle = title;
            this.inLobby = false;
            this.mode = "solo_normal";
            abortCurrentGame();
            setState("AWAY");
            chat("{\"server\":\"" + server + "\",\"gametype\":\"" + gameType + "\",\"mode\":\"solo_normal\",\"map\":\"Test\"}", now);
        }

        void disconnect() {
            abortCurrentGame();
            setState("AWAY");
        }

        void chat(String raw, long now) {
            if (LunarHypixelBlitzStatsBridge.shouldStartBlitzSessionInternal(
                    model, raw, state, world, scoreboardTitle, inLobby, inLobby, mode, now)) {
                setState("WAITING");
                gameDecided = false;
                setState("RUNNING");
            }
            if (!"RUNNING".equals(state) && !"ENDED".equals(state)) return;

            String event = LunarHypixelBlitzStatsBridge.classifyBlitzEventInternal(
                    model, raw, world, scoreboardTitle, localName, mode, now);
            switch (event) {
                case "WIN":
                    recordWin();
                    break;
                case "LOSS":
                    recordLoss();
                    break;
                case "KILL":
                    kills++;
                    break;
                case "DEATH":
                    deaths++;
                    break;
                default:
                    break;
            }
            if (LunarHypixelBlitzStatsBridge.takeBlitzSoloLossInternal(model)) {
                recordLoss();
            }
        }

        private void abortCurrentGame() {
            recordLoss();
            paused = false;
            currentGameId = null;
        }

        private void recordWin() {
            if (tryMarkDecided()) wins++;
        }

        private void recordLoss() {
            if (tryMarkDecided()) losses++;
        }

        private boolean tryMarkDecided() {
            if (!gameDecided && ("RUNNING".equals(state) || "ENDED".equals(state) || paused)) {
                gameDecided = true;
                if ("RUNNING".equals(state) || paused) {
                    paused = false;
                    setState("ENDED");
                }
                return true;
            }
            return false;
        }

        private void setState(String next) {
            if (next.equals(state)) return;
            state = next;
            if ("ENDED".equals(next)) games++;
        }
    }

    public static final class FakeDetailsBuilder {
        Supplier<String> nameSupplier;

        public FakeDetailsBuilder IIRHIHOICCCRRRICICCCRCROHIHCII(Supplier<String> supplier) {
            this.nameSupplier = supplier;
            return this;
        }
    }

    private static byte[] syntheticModuleBaseClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
                MODULE_BASE, null, "java/lang/Object", null);

        MethodVisitor marker = writer.visitMethod(Opcodes.ACC_PRIVATE, "marker", "()V", null, null);
        marker.visitCode();
        marker.visitLdcInsn("icons/features/");
        marker.visitLdcInsn("aliases");
        marker.visitLdcInsn("categories");
        marker.visitLdcInsn("Apollo broadcast failed");
        marker.visitInsn(Opcodes.POP2);
        marker.visitInsn(Opcodes.POP2);
        marker.visitInsn(Opcodes.RETURN);
        marker.visitMaxs(4, 1);
        marker.visitEnd();

        MethodVisitor tr = writer.visitMethod(Opcodes.ACC_PUBLIC, "translate",
                "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;", null, null);
        tr.visitCode();
        tr.visitVarInsn(Opcodes.ALOAD, 1);
        tr.visitInsn(Opcodes.ARETURN);
        tr.visitMaxs(1, 3);
        tr.visitEnd();

        MethodVisitor meta = writer.visitMethod(Opcodes.ACC_PUBLIC, "metadata",
                "()Lcom/google/gson/JsonElement;", null, null);
        meta.visitCode();
        meta.visitLdcInsn("name");
        meta.visitInsn(Opcodes.POP);
        meta.visitInsn(Opcodes.ACONST_NULL);
        meta.visitInsn(Opcodes.ARETURN);
        meta.visitMaxs(1, 1);
        meta.visitEnd();

        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PROTECTED, "HCICROIRHCHHORCCHIOHCICRRIORRO",
                "()L" + OPTIONS_TYPE + ";", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, OPTIONS_TYPE, "builder",
                "()L" + OPTIONS_BUILDER_TYPE + ";", true);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, OPTIONS_BUILDER_TYPE, "build",
                "(Ljava/lang/Object;)L" + OPTIONS_TYPE + ";", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 1);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] syntheticDataModelClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, DATA_MODEL, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PRIVATE, "parser", "L" + MESSAGE_PARSER + ";", null, null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitLdcInsn("BEDWARS");
        init.visitLdcInsn("^\\+(\\d+) tokens!");
        init.visitInsn(Opcodes.POP2);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(2, 1);
        init.visitEnd();

        MethodVisitor chat = writer.visitMethod(Opcodes.ACC_PROTECTED, "handleChat",
                "(Ljava/lang/String;)V", null, null);
        chat.visitCode();
        Label lWin = new Label();
        Label lLoss = new Label();
        Label lEnd = new Label();
        chat.visitVarInsn(Opcodes.ALOAD, 1);
        chat.visitLdcInsn("You have been eliminated!");
        chat.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "startsWith",
                "(Ljava/lang/String;)Z", false);
        chat.visitInsn(Opcodes.POP);
        chat.visitVarInsn(Opcodes.ALOAD, 0);
        chat.visitFieldInsn(Opcodes.GETFIELD, DATA_MODEL, "parser", "L" + MESSAGE_PARSER + ";");
        chat.visitVarInsn(Opcodes.ALOAD, 1);
        chat.visitMethodInsn(Opcodes.INVOKEVIRTUAL, MESSAGE_PARSER, "parse",
                "(Ljava/lang/String;)L" + EVENT_ENUM + ";", false);
        chat.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EVENT_ENUM, "ordinal", "()I", false);
        chat.visitTableSwitchInsn(1, 2, lEnd, lWin, lLoss);
        chat.visitLabel(lWin);
        chat.visitVarInsn(Opcodes.ALOAD, 0);
        chat.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DATA_MODEL, "win", "()V", false);
        chat.visitJumpInsn(Opcodes.GOTO, lEnd);
        chat.visitLabel(lLoss);
        chat.visitVarInsn(Opcodes.ALOAD, 0);
        chat.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DATA_MODEL, "loss", "()V", false);
        chat.visitLabel(lEnd);
        chat.visitInsn(Opcodes.RETURN);
        chat.visitMaxs(2, 2);
        chat.visitEnd();

        MethodVisitor hasArmor = writer.visitMethod(Opcodes.ACC_PRIVATE, "hasArmor", "()Z", null, null);
        hasArmor.visitCode();
        hasArmor.visitInsn(Opcodes.ACONST_NULL);
        hasArmor.visitMethodInsn(Opcodes.INVOKEINTERFACE, ROOT + "Inventory", "bridge$getArmorInventory",
                "()Ljava/util/List;", true);
        hasArmor.visitInsn(Opcodes.POP);
        hasArmor.visitInsn(Opcodes.ICONST_0);
        hasArmor.visitInsn(Opcodes.IRETURN);
        hasArmor.visitMaxs(1, 1);
        hasArmor.visitEnd();

        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PROTECTED, "armorTick", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, DATA_MODEL, "hasArmor", "()Z", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Object newSyntheticBlitzChild() throws Exception {
        String internalName = STATS_CHILD_CLASS + "$1";
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();
        MethodVisitor getId = writer.visitMethod(Opcodes.ACC_PUBLIC, "getId", "()Ljava/lang/String;", null, null);
        getId.visitCode();
        getId.visitLdcInsn("Blitz Survival Games");
        getId.visitInsn(Opcodes.ARETURN);
        getId.visitMaxs(1, 1);
        getId.visitEnd();
        writer.visitEnd();
        final byte[] bytes = writer.toByteArray();
        final String binaryName = internalName.replace('/', '.');
        Class<?> defined = new ClassLoader(LunarHypixelBlitzStatsTest.class.getClassLoader()) {
            Class<?> load() {
                return defineClass(binaryName, bytes, 0, bytes.length);
            }
        }.load();
        return defined.getDeclaredConstructor().newInstance();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
