package me.napim.gomod.weave;

import me.napim.gomod.features.LunarCosmeticAppearanceRenderer;
import me.napim.gomod.mixins.LunarMultiSameTypeCosmeticSupport;
import org.junit.After;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

public class LunarMultiSuitAndConflictTransformTest {
    private static final String CONFLICT = "me/napim/gomod/mixins/LunarMultiSameTypeCosmeticSupport";
    private static final File REAL_LUNAR_JAR = new File(
            System.getProperty("user.home"), ".lunarclient/offline/multiver/lunar.jar");

    @After
    public void cleanup() {
        LunarMultiSameTypeCosmeticSupport.endEquipResolution();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void conflictConsumerSkipsIncompatibleToastsAndRemovalsForSuitsAndHats() throws Exception {
        FakeCosmetic alienSuit = new FakeCosmetic(9001L, FakeCategory.SUITS);
        FakeCosmetic angelSuit = new FakeCosmetic(9002L, FakeCategory.SUITS);
        FakeCosmetic blackBow = new FakeCosmetic(1001L, FakeCategory.HAT);
        FakeCosmetic bucketHat = new FakeCosmetic(1002L, FakeCategory.HAT);
        FakeCosmetic cloak = new FakeCosmetic(2001L, FakeCategory.CLOAK);

        Field activeSelectionField = LunarMultiSameTypeCosmeticSupport.class.getDeclaredField("ACTIVE_EQUIP_SELECTION");
        activeSelectionField.setAccessible(true);
        ((ThreadLocal<Object>) activeSelectionField.get(null)).set(alienSuit);

        List<Long> executed = new ArrayList<Long>();
        Consumer<Object> wrapped = (Consumer<Object>) LunarMultiSameTypeCosmeticSupport.preserveEquippedSameCategory(
                item -> executed.add(((FakeCosmetic) item).getId()));

        wrapped.accept(blackBow);
        wrapped.accept(bucketHat);
        wrapped.accept(angelSuit);
        assertTrue("Different hats and suits must skip toast + removal lambda", executed.isEmpty());

        // Same cosmetic unequip and CLOAK conflicts must still execute natively.
        wrapped.accept(alienSuit);
        wrapped.accept(cloak);
        assertEquals(Arrays.asList(9001L, 2001L), executed);
    }

    @Test
    public void wearableSlotKeysAndCapacitiesCoverSuitsAndAllWearables() throws Exception {
        assertEquals("SUITS", invokeSlotKey("SUITS"));
        assertEquals(1, invokeSlotCapacity("SUITS"));
        assertEquals("HAT", invokeSlotKey("HAT"));
        assertEquals("HAT", invokeSlotKey("HEADWEAR"));
        assertEquals("BODYWEAR", invokeSlotKey("BODYWEAR"));
        assertEquals("BODYWEAR", invokeSlotKey("BODYWEAR_UI"));
        assertEquals("WINGS", invokeSlotKey("WINGS"));
        assertEquals("BACKPACK", invokeSlotKey("BACKPACK"));
        assertEquals("PET", invokeSlotKey("PET"));
        assertEquals(2, invokeSlotCapacity("PET"));
        assertNull(invokeSlotKey("CLOAK"));
        assertNull(invokeSlotKey("COMPANION"));
        assertNull(invokeSlotKey("SWORD"));
        assertNull(invokeSlotKey("ITEM"));
        assertNull(invokeSlotKey("EMOTE"));
        assertNull(invokeSlotKey("BADGE"));
    }

    @Test
    public void hatIdAndNativeEntryConversionWorkForSuits() throws Exception {
        FakeCosmetic alienSuit = new FakeCosmetic(9001L, FakeCategory.SUITS);
        FakeCosmetic angelSuit = new FakeCosmetic(9002L, FakeCategory.SUITS);
        FakeCosmetic cloak = new FakeCosmetic(2001L, FakeCategory.CLOAK);
        UUID owner = UUID.randomUUID();

        Method nativeEntry = LunarCosmeticAppearanceRenderer.class.getDeclaredMethod(
                "nativeRendererEntry", Class.class, Class.class, Object.class, UUID.class, long.class);
        nativeEntry.setAccessible(true);
        Object alienRender = nativeEntry.invoke(null, FakeRenderEntry.class, FakeProfile.class, alienSuit, owner, 9001L);
        Object angelRender = nativeEntry.invoke(null, FakeRenderEntry.class, FakeProfile.class, angelSuit, owner, 9002L);
        assertTrue(alienRender instanceof FakeRenderEntry);
        assertTrue(angelRender instanceof FakeRenderEntry);

        Method hatId = LunarCosmeticAppearanceRenderer.class.getDeclaredMethod("hatId", Object.class);
        hatId.setAccessible(true);
        assertEquals(9001L, ((Long) hatId.invoke(null, alienRender)).longValue());
        assertEquals(9002L, ((Long) hatId.invoke(null, angelRender)).longValue());
        assertEquals(0L, ((Long) hatId.invoke(null, cloak)).longValue());
    }

    @Test
    public void realLunarJarWrapsConflictConsumerAndIsIdempotent() throws Exception {
        if (!REAL_LUNAR_JAR.isFile()) return;
        String matchedTarget = null;
        byte[] matchedTransformed = null;
        int matchedCount = 0;
        try (ZipFile jar = new ZipFile(REAL_LUNAR_JAR)) {
            Enumeration<? extends ZipEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.startsWith("com/moonsworth/lunar/client/ui/osr/") || !entryName.endsWith(".class")) {
                    continue;
                }
                String className = entryName.substring(0, entryName.length() - ".class".length());
                byte[] raw = readAll(jar.getInputStream(entry));
                if (!LunarCompanionAndConflictTransform.mayContain(className, raw)) continue;
                byte[] transformed = LunarCompanionAndConflictTransform.transform(className, raw);
                if (transformed == null) continue;
                ClassNode candidate = new ClassNode();
                new ClassReader(transformed).accept(candidate, ClassReader.EXPAND_FRAMES);
                if (countCalls(candidate, CONFLICT, "beginEquipResolution") > 0) {
                    matchedTarget = className;
                    matchedTransformed = transformed;
                    matchedCount++;
                }
            }
        }

        assertEquals("Exactly one OSR cosmetics class must be transformed", 1, matchedCount);
        assertNotNull("OSR cosmetics class must be transformed", matchedTransformed);

        ClassNode node = new ClassNode();
        new ClassReader(matchedTransformed).accept(node, ClassReader.EXPAND_FRAMES);
        assertEquals(1, countCalls(node, CONFLICT, "beginEquipResolution"));
        assertEquals(1, countCalls(node, CONFLICT, "preserveSameCategoryRemovalPredicate"));
        assertEquals("conflicts.forEach removal/toast consumer must be wrapped",
                1, countCalls(node, CONFLICT, "preserveEquippedSameCategory"));
        assertEquals(1, countCalls(node, CONFLICT, "preserveSameCategory"));
        assertEquals(1, countCalls(node, CONFLICT, "preservePreviewConflicts"));

        assertNull("Second transform pass must be idempotent",
                LunarCompanionAndConflictTransform.transform(matchedTarget, matchedTransformed));
    }

    private static String invokeSlotKey(String category) throws Exception {
        Method m = LunarCosmeticAppearanceRenderer.class.getDeclaredMethod("wearableSlotKey", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, category);
    }

    private static int invokeSlotCapacity(String slotKey) throws Exception {
        Method m = LunarCosmeticAppearanceRenderer.class.getDeclaredMethod("wearableSlotCapacity", String.class);
        m.setAccessible(true);
        return ((Integer) m.invoke(null, slotKey)).intValue();
    }

    private static int countCalls(ClassNode node, String owner, String name) {
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (owner.equals(call.owner) && name.equals(call.name)) count++;
                }
            }
        }
        return count;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    public enum FakeCategory {
        HAT, SUITS, CLOAK
    }

    public static final class FakeCosmetic {
        private final long id;
        private final String name;
        private final Object resource;
        private final FakeCategory type;
        private final boolean f4;
        private final long f5;
        private final java.time.Instant f6;
        private final List<?> f7;
        private final List<?> f8;
        private final boolean f9;
        private final Object f10;
        private final Object f11;
        private final boolean f12;
        public FakeCosmetic(long id, FakeCategory type) {
            this(id, "cosmetic-" + id, null, type, false, 0L, null, null, null, false, null, null, false);
        }
        public FakeCosmetic(long id, String name, Object resource, FakeCategory type, boolean f4, long f5,
                java.time.Instant f6, List<?> f7, List<?> f8, boolean f9, Object f10, Object f11, boolean f12) {
            this.id = id; this.name = name; this.resource = resource; this.type = type;
            this.f4 = f4; this.f5 = f5; this.f6 = f6; this.f7 = f7; this.f8 = f8;
            this.f9 = f9; this.f10 = f10; this.f11 = f11; this.f12 = f12;
        }
        public long getId() { return id; }
        public FakeCategory getType() { return type; }
    }

    public static final class FakeRenderEntry {
        private final FakeCosmetic model;
        private final UUID owner;
        public FakeRenderEntry(FakeCosmetic model, UUID owner) { this.model = model; this.owner = owner; }
        public FakeCosmetic getModel() { return model; }
    }

    public static final class FakeProfile {
        public static FakeRenderEntry create(FakeCosmetic model, UUID owner) {
            return new FakeRenderEntry(model, owner);
        }
    }
}
