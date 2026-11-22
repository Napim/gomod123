package me.napim.gomod.features;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import static org.junit.Assert.*;

/** Regression checks for the Lunar official-profile socket handoff used by FullAccess. */
public final class LunarPeerLogoHandoffTest {
    private static final String BOOTSTRAP =
            "me.napim.gomod.weave.LunarRelayWeaveBootstrap$NativeLunarDebugTransformer";
    private static final String HELPER = "me/napim/gomod/features/LunarSpectatorCosmetics";
    private static final String HELPER_DESC = "(Ljava/lang/Object;)V";
    private static final String METHOD_DESC =
            "(Lcom/moonsworth/lunar/client/Socket;Lcom/lunarclient/websocket/handshake/v1/Handshake$Builder;)V";

    @Test
    public void routeGuardRetainsOnlyTheLiveOfficialSocketWithNativeBridge() {
        URI official = URI.create("wss://websocket.lunarclientprod.com/game");
        assertTrue(LunarSpectatorCosmetics.shouldRetainOfficialProfileSocket(true, true, true, official, true));
        assertFalse(LunarSpectatorCosmetics.shouldRetainOfficialProfileSocket(false, true, true, official, true));
        assertFalse(LunarSpectatorCosmetics.shouldRetainOfficialProfileSocket(true, false, true, official, true));
        assertFalse(LunarSpectatorCosmetics.shouldRetainOfficialProfileSocket(true, true, false, official, true));
        assertFalse(LunarSpectatorCosmetics.shouldRetainOfficialProfileSocket(true, true, true,
                URI.create("wss://relay.example/game"), true));
        assertFalse(LunarSpectatorCosmetics.shouldRetainOfficialProfileSocket(true, true, true, official, false));
        assertFalse(LunarSpectatorCosmetics.shouldRetainOfficialProfileSocket(true, true, true, null, true));
    }

    @Test
    public void reconnectTransformerReplacesOnlyOneNativeSocketClose() throws Exception {
        byte[] original = reconnectClass(1);
        byte[] transformed = patch(original);
        assertNotNull("Lunar reconnect close site must be recognized", transformed);
        final int[] helperCalls = {0};
        final int[] blockingCloses = {0};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String methodName,
                            String methodDescriptor, boolean isInterface) {
                        if (HELPER.equals(owner) && "closeForRouteChange".equals(methodName)) {
                            assertEquals(Opcodes.INVOKESTATIC, opcode);
                            assertEquals(HELPER_DESC, methodDescriptor);
                            helperCalls[0]++;
                        }
                        if ("closeBlocking".equals(methodName)) blockingCloses[0]++;
                    }
                };
            }
        }, 0);
        assertEquals("Exactly one close site must route through the profile handoff", 1, helperCalls[0]);
        assertEquals("The original blocking close must be removed", 0, blockingCloses[0]);
    }

    @Test
    public void reconnectTransformerFailsClosedForMissingOrAmbiguousNativeSites() throws Exception {
        assertNull(patch(reconnectClass(0)));
        assertNull(patch(reconnectClass(2)));
    }

    @Test
    public void peerLogoBatchCoversLargeLobbyWithoutStarvation() {
        List<UUID> lobby = new ArrayList<UUID>();
        for (int i = 0; i < 120; i++) {
            lobby.add(UUID.randomUUID());
        }
        Map<UUID, Long> retryAt = new LinkedHashMap<UUID, Long>();
        Set<UUID> queried = new LinkedHashSet<UUID>();
        long now = 1000L;
        int batches = 0;
        while (queried.size() < lobby.size() && batches < 10) {
            Set<UUID> batch = LunarSpectatorCosmetics.selectPeerLogoBatch(
                    lobby, retryAt, now, LunarSpectatorCosmetics.PEER_LOGO_BATCH_SIZE);
            assertFalse("Expected non-empty batch while unqueried players remain", batch.isEmpty());
            assertTrue(batch.size() <= LunarSpectatorCosmetics.PEER_LOGO_BATCH_SIZE);
            for (UUID id : batch) {
                assertTrue("Player should not be re-queried before all unqueried players are covered", queried.add(id));
                retryAt.put(id, Long.valueOf(now + LunarSpectatorCosmetics.PEER_LOGO_RETRY_MS));
            }
            now += LunarSpectatorCosmetics.PEER_LOGO_BATCH_INTERVAL_MS;
            batches++;
        }
        assertEquals("All 120 lobby players must be queried", 120, queried.size());
        assertEquals("120 players at batch size 50 must complete in 3 batches", 3, batches);
    }

    @Test
    public void peerLogoBatchPrioritizesUnqueriedV4PlayersAndFairOldestRetries() {
        UUID npc1 = new UUID(0x0000000000002000L, 1L); // version 2 NPC
        UUID npc2 = new UUID(0x0000000000002000L, 2L); // version 2 NPC
        UUID player1 = UUID.randomUUID();
        UUID player2 = UUID.randomUUID();
        UUID expiredOlder = UUID.randomUUID();
        UUID expiredNewer = UUID.randomUUID();
        UUID notDueYet = UUID.randomUUID();

        List<UUID> candidates = new ArrayList<UUID>();
        candidates.add(expiredNewer);
        candidates.add(expiredOlder);
        candidates.add(notDueYet);
        candidates.add(npc1);
        candidates.add(npc2);
        candidates.add(player1);
        candidates.add(player2);

        Map<UUID, Long> retryAt = new LinkedHashMap<UUID, Long>();
        retryAt.put(expiredNewer, Long.valueOf(500L));
        retryAt.put(expiredOlder, Long.valueOf(200L));
        retryAt.put(notDueYet, Long.valueOf(9000L));

        // Batch size 3: must pick both unqueried v4 players first, then first unqueried NPC (never expired retries yet)
        Set<UUID> first = LunarSpectatorCosmetics.selectPeerLogoBatch(candidates, retryAt, 1000L, 3);
        List<UUID> firstList = new ArrayList<UUID>(first);
        assertEquals(3, firstList.size());
        assertEquals(player1, firstList.get(0));
        assertEquals(player2, firstList.get(1));
        assertEquals(npc1, firstList.get(2));
        for (UUID id : first) retryAt.put(id, Long.valueOf(1000L + LunarSpectatorCosmetics.PEER_LOGO_RETRY_MS));

        // Next batch size 3: must pick remaining unqueried npc2, then oldest expired retry, then newer expired retry
        Set<UUID> second = LunarSpectatorCosmetics.selectPeerLogoBatch(candidates, retryAt, 1000L, 3);
        List<UUID> secondList = new ArrayList<UUID>(second);
        assertEquals(3, secondList.size());
        assertEquals(npc2, secondList.get(0));
        assertEquals(expiredOlder, secondList.get(1));
        assertEquals(expiredNewer, secondList.get(2));
        assertFalse(second.contains(notDueYet));
    }

    private static byte[] patch(byte[] original) throws Exception {
        Class<?> type = Class.forName(BOOTSTRAP);
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object transformer = constructor.newInstance();
        Method method = type.getDeclaredMethod("patchOfficialProfileHandoff", byte[].class);
        method.setAccessible(true);
        return (byte[]) method.invoke(transformer, (Object) original);
    }

    private static byte[] reconnectClass(int closeCalls) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                "com/moonsworth/lunar/client/AssetsReconnectFixture", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "reconnect", METHOD_DESC, null, null);
        method.visitCode();
        for (int i = 0; i < closeCalls; i++) {
            method.visitVarInsn(Opcodes.ALOAD, 1);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/moonsworth/lunar/client/Socket",
                    "closeBlocking", "()V", false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 3);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
