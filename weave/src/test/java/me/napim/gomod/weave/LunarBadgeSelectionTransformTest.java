package me.napim.gomod.weave;

import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.Assert.*;

/**
 * Regression tests for structural Lunar badge-selection capture (BadgeManager + OutfitManager)
 * and local PlayerLogoProfile / TAB cache badge preservation.
 */
public final class LunarBadgeSelectionTransformTest {
    private static final String LOGO_SYNC = "me/napim/gomod/features/GomodLogoColorSync";
    private static final String BADGE_CATALOG = "me/napim/gomod/features/LunarCustomBadgeCatalog";
    private static final File ACTIVE_LUNAR_JAR =
            new File(System.getProperty("user.home"), ".lunarclient/offline/multiver/lunar.jar");

    @Test
    public void structuralBadgeSelectionHookMatchesArbitrarilyObfuscatedBadgeManager() {
        String managerName = "com/moonsworth/lunar/client/RANDOM_PKG/RANDOM_BADGE_MGR";
        String badgeType = "com/moonsworth/lunar/client/RANDOM_BADGE_PKG/RANDOM_BADGE";
        String resourceType = "com/moonsworth/lunar/client/RANDOM_RES_PKG/RANDOM_RES";
        byte[] raw = syntheticBadgeManager(managerName, badgeType, resourceType, false);

        assertTrue(LunarCatalogCompatibilityTransform.mayContain(managerName, raw));
        byte[] transformed = LunarCatalogCompatibilityTransform.transform(managerName, raw);
        assertNotNull("BadgeManager transform must succeed for arbitrary obfuscated names", transformed);

        ClassNode node = parseExpanded(transformed);
        int catalogHooks = 0;
        int setterReturnHooks = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn;
                    if (BADGE_CATALOG.equals(call.owner) && "appendToCatalog".equals(call.name)) {
                        catalogHooks++;
                    }
                    if (LOGO_SYNC.equals(call.owner) && "rememberNativeBadgeSelection".equals(call.name)) {
                        assertEquals("(Ljava/lang/Object;)V", call.desc);
                        AbstractInsnNode prev = previousReal(insn);
                        assertTrue("Badge argument must be loaded from local 1", prev instanceof VarInsnNode);
                        assertEquals(Opcodes.ALOAD, prev.getOpcode());
                        assertEquals(1, ((VarInsnNode) prev).var);
                        AbstractInsnNode next = nextReal(insn);
                        assertNotNull(next);
                        assertEquals("Hook must sit right before every RETURN", Opcodes.RETURN, next.getOpcode());
                        setterReturnHooks++;
                    }
                }
            }
        }
        assertEquals("Catalog hook must be injected once", 1, catalogHooks);
        assertEquals("All 3 RETURN paths (including early returns) must capture badge selection", 3, setterReturnHooks);

        // Second pass must be a no-op (idempotent).
        assertNull("Transform must be idempotent", LunarCatalogCompatibilityTransform.transform(managerName, transformed));
    }

    @Test
    public void structuralBadgeSelectionHookFailsClosedOnAmbiguousSetters() {
        String managerName = "com/moonsworth/lunar/client/RANDOM_PKG/AMBIGUOUS_BADGE_MGR";
        String badgeType = "com/moonsworth/lunar/client/RANDOM_BADGE_PKG/RANDOM_BADGE";
        String resourceType = "com/moonsworth/lunar/client/RANDOM_RES_PKG/RANDOM_RES";
        byte[] raw = syntheticBadgeManager(managerName, badgeType, resourceType, true);

        byte[] transformed = LunarCatalogCompatibilityTransform.transform(managerName, raw);
        assertNotNull("Catalog hook still runs when setter is ambiguous", transformed);
        ClassNode node = parseExpanded(transformed);
        int selectionHooks = countCalls(node, LOGO_SYNC, "rememberNativeBadgeSelection");
        assertEquals("Ambiguous badge setters must fail closed with 0 selection hooks", 0, selectionHooks);
    }

    @Test
    public void structuralOutfitSelectBadgeHookMatchesArbitrarilyObfuscatedOutfitManager() {
        String managerName = "com/moonsworth/lunar/client/RANDOM_PKG/RANDOM_OUTFIT_MGR";
        String outfitType = "com/moonsworth/lunar/client/RANDOM_OUTFIT_PKG/RANDOM_OUTFIT";
        String badgeType = "com/moonsworth/lunar/client/RANDOM_BADGE_PKG/RANDOM_BADGE";
        byte[] raw = syntheticOutfitManager(managerName, outfitType, badgeType);

        assertTrue(LunarColorCompatibilityTransform.mayContain(managerName, raw));
        byte[] transformed = LunarColorCompatibilityTransform.transform(managerName, raw);
        assertNotNull("OutfitManager transform must succeed for arbitrary obfuscated names", transformed);

        ClassNode node = parseExpanded(transformed);
        int outfitCaptureHooks = countCalls(node, "me/napim/gomod/features/LunarFullDisguise", "onOutfitManagerAvailable");
        int badgeSelectionHooks = countCalls(node, LOGO_SYNC, "rememberNativeBadgeSelection");
        assertEquals("OutfitManager availability hook must be installed", 1, outfitCaptureHooks);
        assertEquals("SelectOutfitResponse badge sync hook must be installed once", 1, badgeSelectionHooks);

        // Verify DUP + rememberNativeBadgeSelection sits between Outfit.<getBadge> and IFNONNULL.
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode
                        && LOGO_SYNC.equals(((MethodInsnNode) insn).owner)
                        && "rememberNativeBadgeSelection".equals(((MethodInsnNode) insn).name)) {
                    AbstractInsnNode prev = previousReal(insn);
                    assertTrue(prev instanceof InsnNode && prev.getOpcode() == Opcodes.DUP);
                    AbstractInsnNode next = nextReal(insn);
                    assertTrue(next instanceof JumpInsnNode && next.getOpcode() == Opcodes.IFNONNULL);
                }
            }
        }

        // Second pass must be a no-op (idempotent).
        assertNull("OutfitManager transform must be idempotent",
                LunarColorCompatibilityTransform.transform(managerName, transformed));
    }

    @Test
    public void transformsRealLunarJarBadgeManagerAndOutfitManagerCleanly() throws Exception {
        Assume.assumeTrue("Installed lunar.jar not present on this machine", ACTIVE_LUNAR_JAR.isFile());

        try (ZipFile zip = new ZipFile(ACTIVE_LUNAR_JAR)) {
            int badgeManagersFound = 0;
            int badgeManagersHooked = 0;
            int outfitManagersFound = 0;
            int outfitSelectHooks = 0;

            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (!entryName.startsWith("com/moonsworth/lunar/") || !entryName.endsWith(".class")) continue;
                String internalName = entryName.substring(0, entryName.length() - 6);
                byte[] bytes = readAll(zip.getInputStream(entry));

                if (LunarCatalogCompatibilityTransform.mayContain(internalName, bytes)) {
                    String pool = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                    if (pool.contains("badges.json") && pool.contains("Failed to parse badge:")) {
                        badgeManagersFound++;
                        byte[] transformed = LunarCatalogCompatibilityTransform.transform(internalName, bytes);
                        assertNotNull("Real BadgeManager must transform: " + internalName, transformed);
                        ClassNode expanded = parseExpanded(transformed);
                        int selectionCalls = countCalls(expanded, LOGO_SYNC, "rememberNativeBadgeSelection");
                        assertTrue("Real BadgeManager setter must hook all RETURN paths (expected >= 3, got "
                                + selectionCalls + ")", selectionCalls >= 3);
                        assertEquals("Real BadgeManager catalog hook must remain present",
                                1, countCalls(expanded, BADGE_CATALOG, "appendToCatalog"));
                        assertNull("Second pass on real BadgeManager must be idempotent",
                                LunarCatalogCompatibilityTransform.transform(internalName, transformed));
                        badgeManagersHooked++;
                    }
                }

                if (LunarColorCompatibilityTransform.mayContain(internalName, bytes)) {
                    String pool = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
                    if (pool.contains("SelectOutfitResponse") && pool.contains("EquipBadgeRequest")) {
                        outfitManagersFound++;
                        byte[] transformed = LunarColorCompatibilityTransform.transform(internalName, bytes);
                        assertNotNull("Real OutfitManager must transform: " + internalName, transformed);
                        ClassNode expanded = parseExpanded(transformed);
                        int selectBadgeCalls = countCalls(expanded, LOGO_SYNC, "rememberNativeBadgeSelection");
                        assertEquals("Real OutfitManager SelectOutfitResponse must hook badge sync once",
                                1, selectBadgeCalls);
                        assertNull("Second pass on real OutfitManager must be idempotent",
                                LunarColorCompatibilityTransform.transform(internalName, transformed));
                        outfitSelectHooks++;
                    }
                }
            }

            assertEquals("Expected exactly 1 BadgeManager in active lunar.jar", 1, badgeManagersFound);
            assertEquals("Expected real BadgeManager to be hooked", 1, badgeManagersHooked);
            assertEquals("Expected exactly 1 OutfitManager with SelectOutfitResponse in active lunar.jar",
                    1, outfitManagersFound);
            assertEquals("Expected real OutfitManager outfit-switch badge sync to be hooked", 1, outfitSelectHooks);
        }
    }

    @Test
    public void rememberNativeBadgeSelectionImmediatelyRepairsProfileAndTabCache() throws Exception {
        UUID localId = UUID.randomUUID();
        BadgeRuntimeSandboxLoader loader = new BadgeRuntimeSandboxLoader(localId);

        Class<?> syncClass = loader.loadClass("me.napim.gomod.features.GomodLogoColorSync");
        Class<?> badgeClass = loader.loadClass("test.fake.FakeBadge");
        Class<?> profileClass = loader.loadClass("test.fake.FakePlayerLogoProfile");
        Class<?> tabRecordClass = loader.loadClass("test.fake.FakeTabLogoRecord");
        Class<?> profileMgrClass = loader.loadClass("test.fake.FakeProfileManager");
        Class<?> tabMgrClass = loader.loadClass("test.fake.FakeTabLogoManager");
        Class<?> cacheInterface = loader.loadClass("com.google.common.cache.Cache");

        final Map<Object, Object> backingTabCache = new HashMap<Object, Object>();
        Object cacheProxy = Proxy.newProxyInstance(loader, new Class<?>[]{cacheInterface}, new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("getIfPresent".equals(method.getName())) return backingTabCache.get(args[0]);
                if ("put".equals(method.getName())) {
                    backingTabCache.put(args[0], args[1]);
                    return null;
                }
                return null;
            }
        });

        Object profileMgr = profileMgrClass.getDeclaredConstructor().newInstance();
        @SuppressWarnings("unchecked")
        Map<UUID, Object> profilesMap = (Map<UUID, Object>) profileMgrClass.getField("profiles").get(profileMgr);
        Object tabMgr = tabMgrClass.getDeclaredConstructor(profileMgrClass, cacheInterface)
                .newInstance(profileMgr, cacheProxy);

        Object badge42 = badgeClass.getDeclaredConstructor(int.class).newInstance(42);
        Object badge77 = badgeClass.getDeclaredConstructor(int.class).newInstance(77);

        // Initial state: profile & TAB cache wiped to badge=null by a badgeId=0 push.
        Object emptyProfile = profileClass.getDeclaredConstructor(
                float.class, float.class, float.class, boolean.class, int.class, boolean.class, badgeClass)
                .newInstance(1F, 0.5F, 0.25F, true, 0xFF00FF00, false, null);
        Object emptyTab = tabRecordClass.getDeclaredConstructor(int.class, int.class, badgeClass)
                .newInstance(0xFFFF8040, 0xFF00FF00, null);
        profilesMap.put(localId, emptyProfile);
        backingTabCache.put(localId, emptyTab);

        setStaticField(syncClass, "playerLogoProfileManager", profileMgr);
        setStaticField(syncClass, "tabLogoManager", tabMgr);

        Method rememberBadge = syncClass.getMethod("rememberNativeBadgeSelection", Object.class);
        Method preserveAfterPush = syncClass.getMethod("preserveSelectedLocalBadgeAfterProfileUpdate",
                Object.class, UUID.class);
        Field profileBadgeField = profileClass.getField("badge");
        Field tabBadgeField = tabRecordClass.getField("badge");

        // 1. Capturing badge 42 (including after an early return in BadgeManager) must immediately
        //    repair BOTH PlayerLogoProfile (Locker preview + nametag + TAB) AND the TAB Guava cache.
        rememberBadge.invoke(null, badge42);
        assertSame("PlayerLogoProfile must immediately receive selected badge 42",
                badge42, profileBadgeField.get(profilesMap.get(localId)));
        assertSame("TAB cache must immediately receive selected badge 42",
                badge42, tabBadgeField.get(backingTabCache.get(localId)));

        // 2. Stale PlayerCosmeticsPushV2 with badgeId=0 wipes PlayerLogoProfile -> repair restores badge 42.
        profilesMap.put(localId, emptyProfile);
        preserveAfterPush.invoke(null, profileMgr, localId);
        assertSame("Stale push must be repaired back to selected badge 42",
                badge42, profileBadgeField.get(profilesMap.get(localId)));

        // 3. Switching outfits / selecting badge 77 immediately updates both caches.
        rememberBadge.invoke(null, badge77);
        assertSame("PlayerLogoProfile must update to badge 77",
                badge77, profileBadgeField.get(profilesMap.get(localId)));
        assertSame("TAB cache must update to badge 77",
                badge77, tabBadgeField.get(backingTabCache.get(localId)));

        // 4. Not-loaded sentinel (-1, -1, null) in TAB cache must NEVER be overwritten.
        Object sentinel = tabRecordClass.getDeclaredConstructor(int.class, int.class, badgeClass)
                .newInstance(-1, -1, null);
        backingTabCache.put(localId, sentinel);
        rememberBadge.invoke(null, badge77);
        assertSame("Not-loaded sentinel in TAB cache must remain untouched",
                sentinel, backingTabCache.get(localId));

        // 5. Selecting None (null) must clear the badge from both PlayerLogoProfile and TAB cache.
        backingTabCache.put(localId, tabRecordClass.getDeclaredConstructor(int.class, int.class, badgeClass)
                .newInstance(0xFFFF8040, 0xFF00FF00, badge77));
        rememberBadge.invoke(null, new Object[]{null});
        assertNull("Selecting None must clear PlayerLogoProfile badge",
                profileBadgeField.get(profilesMap.get(localId)));
        assertNull("Selecting None must clear TAB cache badge",
                tabBadgeField.get(backingTabCache.get(localId)));
    }

    /** Isolated test ClassLoader supplying minimal Minecraft/Session/Guava Cache + fake Lunar records. */
    private static final class BadgeRuntimeSandboxLoader extends ClassLoader {
        private final Map<String, byte[]> synthetic = new HashMap<String, byte[]>();

        BadgeRuntimeSandboxLoader(UUID localId) {
            super(LunarBadgeSelectionTransformTest.class.getClassLoader());
            synthetic.put("com.google.common.cache.Cache", buildCacheInterface());
            synthetic.put("net.minecraft.util.Session", buildSessionClass(localId.toString().replace("-", "")));
            synthetic.put("net.minecraft.client.Minecraft", buildMinecraftClass());
            synthetic.put("me.napim.gomod.features.LunarFullDisguise", buildFullDisguiseStub());
            synthetic.put("me.napim.gomod.features.LunarTabLogoCompatibility", buildTabCompatStub());
            synthetic.put("test.fake.FakeBadge", buildFakeBadgeClass());
            synthetic.put("test.fake.FakePlayerLogoProfile", buildFakeProfileClass());
            synthetic.put("test.fake.FakeTabLogoRecord", buildFakeTabRecordClass());
            synthetic.put("test.fake.FakeProfileManager", buildFakeProfileManagerClass());
            synthetic.put("test.fake.FakeTabLogoManager", buildFakeTabManagerClass());
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (name != null && name.endsWith(".class")) {
                String normalized = name.startsWith("/") ? name.substring(1) : name;
                String cls = normalized.substring(0, normalized.length() - 6).replace('/', '.');
                byte[] bytes = synthetic.get(cls);
                if (bytes != null) return new java.io.ByteArrayInputStream(bytes);
            }
            return super.getResourceAsStream(name);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded != null) return loaded;
                byte[] stub = synthetic.get(name);
                if (stub != null) {
                    Class<?> defined = defineClass(name, stub, 0, stub.length);
                    if (resolve) resolveClass(defined);
                    return defined;
                }
                if (name.startsWith("me.napim.gomod.features.GomodLogoColorSync")
                        || name.startsWith("me.napim.gomod.features.LunarColorRuntime")) {
                    String res = name.replace('.', '/') + ".class";
                    try (InputStream in = getParent().getResourceAsStream(res)) {
                        if (in == null) throw new ClassNotFoundException(name);
                        byte[] bytes = readAll(in);
                        Class<?> defined = defineClass(name, bytes, 0, bytes.length);
                        if (resolve) resolveClass(defined);
                        return defined;
                    } catch (Exception e) {
                        throw new ClassNotFoundException(name, e);
                    }
                }
                return super.loadClass(name, resolve);
            }
        }

        private static byte[] buildCacheInterface() {
            ClassWriter cw = new ClassWriter(0);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                    "com/google/common/cache/Cache", "<K:Ljava/lang/Object;V:Ljava/lang/Object;>Ljava/lang/Object;",
                    "java/lang/Object", null);
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getIfPresent",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", null, null).visitEnd();
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "put",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null).visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] buildSessionClass(String rawId) {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "net/minecraft/util/Session", null, "java/lang/Object", null);
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            MethodVisitor getId = cw.visitMethod(Opcodes.ACC_PUBLIC, "getPlayerID", "()Ljava/lang/String;", null, null);
            getId.visitCode();
            getId.visitLdcInsn(rawId);
            getId.visitInsn(Opcodes.ARETURN);
            getId.visitMaxs(0, 0);
            getId.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] buildMinecraftClass() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "net/minecraft/client/Minecraft", null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                    "INSTANCE", "Lnet/minecraft/client/Minecraft;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                    "session", "Lnet/minecraft/util/Session;", null, null).visitEnd();
            MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            clinit.visitCode();
            clinit.visitTypeInsn(Opcodes.NEW, "net/minecraft/client/Minecraft");
            clinit.visitInsn(Opcodes.DUP);
            clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/client/Minecraft", "<init>", "()V", false);
            clinit.visitFieldInsn(Opcodes.PUTSTATIC, "net/minecraft/client/Minecraft", "INSTANCE", "Lnet/minecraft/client/Minecraft;");
            clinit.visitInsn(Opcodes.RETURN);
            clinit.visitMaxs(0, 0);
            clinit.visitEnd();
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitTypeInsn(Opcodes.NEW, "net/minecraft/util/Session");
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/util/Session", "<init>", "()V", false);
            init.visitFieldInsn(Opcodes.PUTFIELD, "net/minecraft/client/Minecraft", "session", "Lnet/minecraft/util/Session;");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            MethodVisitor getMc = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "getMinecraft", "()Lnet/minecraft/client/Minecraft;", null, null);
            getMc.visitCode();
            getMc.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/client/Minecraft", "INSTANCE", "Lnet/minecraft/client/Minecraft;");
            getMc.visitInsn(Opcodes.ARETURN);
            getMc.visitMaxs(0, 0);
            getMc.visitEnd();
            MethodVisitor getSession = cw.visitMethod(Opcodes.ACC_PUBLIC,
                    "getSession", "()Lnet/minecraft/util/Session;", null, null);
            getSession.visitCode();
            getSession.visitVarInsn(Opcodes.ALOAD, 0);
            getSession.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/client/Minecraft", "session", "Lnet/minecraft/util/Session;");
            getSession.visitInsn(Opcodes.ARETURN);
            getSession.visitMaxs(0, 0);
            getSession.visitEnd();
            MethodVisitor isThread = cw.visitMethod(Opcodes.ACC_PUBLIC, "isCallingFromMinecraftThread", "()Z", null, null);
            isThread.visitCode();
            isThread.visitInsn(Opcodes.ICONST_1);
            isThread.visitInsn(Opcodes.IRETURN);
            isThread.visitMaxs(0, 0);
            isThread.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] buildFullDisguiseStub() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "me/napim/gomod/features/LunarFullDisguise",
                    null, "java/lang/Object", null);
            MethodVisitor isActive = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "isActive", "()Z", null, null);
            isActive.visitCode();
            isActive.visitInsn(Opcodes.ICONST_0);
            isActive.visitInsn(Opcodes.IRETURN);
            isActive.visitMaxs(0, 0);
            isActive.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] buildTabCompatStub() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "me/napim/gomod/features/LunarTabLogoCompatibility",
                    null, "java/lang/Object", null);
            MethodVisitor inv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "invalidateProfileLogoWidth", "(Ljava/util/UUID;)V", null, null);
            inv.visitCode();
            inv.visitInsn(Opcodes.RETURN);
            inv.visitMaxs(0, 0);
            inv.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] buildFakeBadgeClass() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/fake/FakeBadge", null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "id", "I", null, null).visitEnd();
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitVarInsn(Opcodes.ILOAD, 1);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakeBadge", "id", "I");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            MethodVisitor id = cw.visitMethod(Opcodes.ACC_PUBLIC, "id", "()I", null, null);
            id.visitCode();
            id.visitVarInsn(Opcodes.ALOAD, 0);
            id.visitFieldInsn(Opcodes.GETFIELD, "test/fake/FakeBadge", "id", "I");
            id.visitInsn(Opcodes.IRETURN);
            id.visitMaxs(0, 0);
            id.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] buildFakeProfileClass() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/fake/FakePlayerLogoProfile", null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "r", "F", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "g", "F", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "b", "F", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "alwaysShow", "Z", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "plusRgb", "I", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "extraFlag", "Z", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "badge", "Ltest/fake/FakeBadge;", null, null).visitEnd();
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                    "(FFFZIZLtest/fake/FakeBadge;)V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.FLOAD, 1);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakePlayerLogoProfile", "r", "F");
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.FLOAD, 2);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakePlayerLogoProfile", "g", "F");
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.FLOAD, 3);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakePlayerLogoProfile", "b", "F");
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.ILOAD, 4);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakePlayerLogoProfile", "alwaysShow", "Z");
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.ILOAD, 5);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakePlayerLogoProfile", "plusRgb", "I");
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.ILOAD, 6);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakePlayerLogoProfile", "extraFlag", "Z");
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.ALOAD, 7);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakePlayerLogoProfile", "badge", "Ltest/fake/FakeBadge;");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] buildFakeTabRecordClass() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/fake/FakeTabLogoRecord", null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "logoRgb", "I", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "plusRgb", "I", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "badge", "Ltest/fake/FakeBadge;", null, null).visitEnd();
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(IILtest/fake/FakeBadge;)V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.ILOAD, 1);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakeTabLogoRecord", "logoRgb", "I");
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.ILOAD, 2);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakeTabLogoRecord", "plusRgb", "I");
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.ALOAD, 3);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakeTabLogoRecord", "badge", "Ltest/fake/FakeBadge;");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] buildFakeProfileManagerClass() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/fake/FakeProfileManager", null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "profiles", "Ljava/util/Map;",
                    "Ljava/util/Map<Ljava/util/UUID;Ltest/fake/FakePlayerLogoProfile;>;", null).visitEnd();
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
            init.visitInsn(Opcodes.DUP);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakeProfileManager", "profiles", "Ljava/util/Map;");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }

        private static byte[] buildFakeTabManagerClass() {
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "test/fake/FakeTabLogoManager", null, "java/lang/Object", null);
            cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "profileMgr", "Ltest/fake/FakeProfileManager;", null, null).visitEnd();
            cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, "cache", "Lcom/google/common/cache/Cache;",
                    "Lcom/google/common/cache/Cache<Ljava/util/UUID;Ltest/fake/FakeTabLogoRecord;>;", null).visitEnd();
            MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                    "(Ltest/fake/FakeProfileManager;Lcom/google/common/cache/Cache;)V", null, null);
            init.visitCode();
            init.visitVarInsn(Opcodes.ALOAD, 0);
            init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.ALOAD, 1);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakeTabLogoManager", "profileMgr", "Ltest/fake/FakeProfileManager;");
            init.visitVarInsn(Opcodes.ALOAD, 0); init.visitVarInsn(Opcodes.ALOAD, 2);
            init.visitFieldInsn(Opcodes.PUTFIELD, "test/fake/FakeTabLogoManager", "cache", "Lcom/google/common/cache/Cache;");
            init.visitInsn(Opcodes.RETURN);
            init.visitMaxs(0, 0);
            init.visitEnd();
            // lookup(UUID) returns a projected record from profileMgr.profiles (matching Lunar's TabLogoManager)
            MethodVisitor lookup = cw.visitMethod(Opcodes.ACC_PUBLIC, "lookup",
                    "(Ljava/util/UUID;)Ltest/fake/FakeTabLogoRecord;", null, null);
            lookup.visitCode();
            Label fallback = new Label();
            lookup.visitVarInsn(Opcodes.ALOAD, 0);
            lookup.visitFieldInsn(Opcodes.GETFIELD, "test/fake/FakeTabLogoManager", "profileMgr", "Ltest/fake/FakeProfileManager;");
            lookup.visitFieldInsn(Opcodes.GETFIELD, "test/fake/FakeProfileManager", "profiles", "Ljava/util/Map;");
            lookup.visitVarInsn(Opcodes.ALOAD, 1);
            lookup.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            lookup.visitTypeInsn(Opcodes.CHECKCAST, "test/fake/FakePlayerLogoProfile");
            lookup.visitVarInsn(Opcodes.ASTORE, 2);
            lookup.visitVarInsn(Opcodes.ALOAD, 2);
            lookup.visitJumpInsn(Opcodes.IFNULL, fallback);
            lookup.visitTypeInsn(Opcodes.NEW, "test/fake/FakeTabLogoRecord");
            lookup.visitInsn(Opcodes.DUP);
            lookup.visitLdcInsn(0xFFFF8040);
            lookup.visitVarInsn(Opcodes.ALOAD, 2);
            lookup.visitFieldInsn(Opcodes.GETFIELD, "test/fake/FakePlayerLogoProfile", "plusRgb", "I");
            lookup.visitVarInsn(Opcodes.ALOAD, 2);
            lookup.visitFieldInsn(Opcodes.GETFIELD, "test/fake/FakePlayerLogoProfile", "badge", "Ltest/fake/FakeBadge;");
            lookup.visitMethodInsn(Opcodes.INVOKESPECIAL, "test/fake/FakeTabLogoRecord", "<init>",
                    "(IILtest/fake/FakeBadge;)V", false);
            lookup.visitInsn(Opcodes.ARETURN);
            lookup.visitLabel(fallback);
            lookup.visitVarInsn(Opcodes.ALOAD, 0);
            lookup.visitFieldInsn(Opcodes.GETFIELD, "test/fake/FakeTabLogoManager", "cache", "Lcom/google/common/cache/Cache;");
            lookup.visitVarInsn(Opcodes.ALOAD, 1);
            lookup.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/google/common/cache/Cache", "getIfPresent",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            lookup.visitTypeInsn(Opcodes.CHECKCAST, "test/fake/FakeTabLogoRecord");
            lookup.visitInsn(Opcodes.ARETURN);
            lookup.visitMaxs(0, 0);
            lookup.visitEnd();
            cw.visitEnd();
            return cw.toByteArray();
        }
    }

    private static void setStaticField(Class<?> type, String name, Object value) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }

    // ---- Synthetic bytecode builders ----

    private static byte[] syntheticBadgeManager(String managerName, String badgeType,
                                                String resourceType, boolean duplicateSetter) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, managerName, null, "java/lang/Object", null);

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor parse = cw.visitMethod(Opcodes.ACC_PRIVATE, "loadBadgesObf", "()V", null, null);
        parse.visitCode();
        parse.visitLdcInsn("badges.json");
        parse.visitInsn(Opcodes.POP);
        parse.visitLdcInsn("Failed to parse badge:");
        parse.visitInsn(Opcodes.POP);
        parse.visitLdcInsn("lunar");
        parse.visitLdcInsn("badges/test.png");
        parse.visitMethodInsn(Opcodes.INVOKESTATIC, resourceType, "makeRes",
                "(Ljava/lang/String;Ljava/lang/String;)L" + resourceType + ";", false);
        parse.visitVarInsn(Opcodes.ASTORE, 1);
        parse.visitTypeInsn(Opcodes.NEW, badgeType);
        parse.visitInsn(Opcodes.DUP);
        parse.visitInsn(Opcodes.ICONST_1);
        parse.visitLdcInsn("Test");
        parse.visitLdcInsn("Desc");
        parse.visitVarInsn(Opcodes.ALOAD, 1);
        parse.visitVarInsn(Opcodes.ALOAD, 1);
        parse.visitInsn(Opcodes.ACONST_NULL);
        parse.visitInsn(Opcodes.ICONST_0);
        parse.visitMethodInsn(Opcodes.INVOKESPECIAL, badgeType, "<init>",
                "(ILjava/lang/String;Ljava/lang/String;L" + resourceType + ";L" + resourceType
                        + ";Ljava/time/Instant;Z)V", false);
        parse.visitInsn(Opcodes.POP);
        parse.visitInsn(Opcodes.RETURN);
        parse.visitMaxs(0, 0);
        parse.visitEnd();

        emitBadgeSetter(cw, "setBadgeObf", badgeType);
        if (duplicateSetter) {
            emitBadgeSetter(cw, "setBadgeObfSecond", badgeType);
        }

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitBadgeSetter(ClassWriter cw, String methodName, String badgeType) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "(L" + badgeType + ";)V", null, null);
        mv.visitCode();
        Label notNull = new Label();
        Label applyProfile = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitJumpInsn(Opcodes.IFNONNULL, notNull);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(notNull);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitJumpInsn(Opcodes.IFNONNULL, applyProfile);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitLabel(applyProfile);
        mv.visitTypeInsn(Opcodes.NEW, "java/util/HashMap");
        mv.visitInsn(Opcodes.DUP);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "computeIfPresent",
                "(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;", true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static byte[] syntheticOutfitManager(String managerName, String outfitType, String badgeType) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, managerName, null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "outfitsByPlayer", "Ljava/util/Map;",
                "Ljava/util/Map<Ljava/util/UUID;Ljava/lang/Object;>;", null).visitEnd();

        MethodVisitor push = cw.visitMethod(Opcodes.ACC_PUBLIC, "onPushObf",
                "(Lcom/lunarclient/websocket/cosmetic/v2/PlayerCosmeticsPushV2;)V", null, null);
        push.visitCode();
        push.visitVarInsn(Opcodes.ALOAD, 1);
        push.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/lunarclient/websocket/cosmetic/v2/PlayerCosmeticsPushV2",
                "getPlayerUuid", "()Lcom/lunarclient/common/v1/Uuid;", false);
        push.visitInsn(Opcodes.POP);
        push.visitVarInsn(Opcodes.ALOAD, 1);
        push.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/lunarclient/websocket/cosmetic/v2/PlayerCosmeticsPushV2",
                "getDefaultCosmeticsList", "()Ljava/util/List;", false);
        push.visitInsn(Opcodes.POP);
        push.visitVarInsn(Opcodes.ALOAD, 1);
        push.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/lunarclient/websocket/cosmetic/v2/PlayerCosmeticsPushV2",
                "getBadgeId", "()I", false);
        push.visitInsn(Opcodes.POP);
        push.visitVarInsn(Opcodes.ALOAD, 0);
        push.visitFieldInsn(Opcodes.GETFIELD, managerName, "outfitsByPlayer", "Ljava/util/Map;");
        push.visitInsn(Opcodes.ACONST_NULL);
        push.visitInsn(Opcodes.ACONST_NULL);
        push.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Map", "put",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true);
        push.visitInsn(Opcodes.POP);
        push.visitInsn(Opcodes.RETURN);
        push.visitMaxs(0, 0);
        push.visitEnd();

        MethodVisitor select = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "onSelectOutfitObf",
                "(L" + outfitType + ";Lcom/lunarclient/websocket/cosmetic/v2/SelectOutfitResponse;)V", null, null);
        select.visitCode();
        Label hasBadge = new Label();
        Label setId = new Label();
        select.visitInsn(Opcodes.ACONST_NULL);
        select.visitInsn(Opcodes.ACONST_NULL);
        select.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lunarclient/websocket/badge/v1/EquipBadgeRequest",
                "newBuilder", "()Lcom/lunarclient/websocket/badge/v1/EquipBadgeRequest$Builder;", false);
        select.visitVarInsn(Opcodes.ALOAD, 0);
        select.visitMethodInsn(Opcodes.INVOKEVIRTUAL, outfitType, "getBadgeObf", "()L" + badgeType + ";", false);
        select.visitJumpInsn(Opcodes.IFNONNULL, hasBadge);
        select.visitInsn(Opcodes.ICONST_M1);
        select.visitJumpInsn(Opcodes.GOTO, setId);
        select.visitLabel(hasBadge);
        select.visitVarInsn(Opcodes.ALOAD, 0);
        select.visitMethodInsn(Opcodes.INVOKEVIRTUAL, outfitType, "getBadgeObf", "()L" + badgeType + ";", false);
        select.visitMethodInsn(Opcodes.INVOKEVIRTUAL, badgeType, "id", "()I", false);
        select.visitLabel(setId);
        select.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/lunarclient/websocket/badge/v1/EquipBadgeRequest$Builder",
                "setBadgeId", "(I)Lcom/lunarclient/websocket/badge/v1/EquipBadgeRequest$Builder;", false);
        select.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/lunarclient/websocket/badge/v1/EquipBadgeRequest$Builder",
                "build", "()Lcom/lunarclient/websocket/badge/v1/EquipBadgeRequest;", false);
        select.visitInsn(Opcodes.ACONST_NULL);
        select.visitMethodInsn(Opcodes.INVOKEINTERFACE, "com/lunarclient/websocket/badge/v1/BadgeService$Interface",
                "equipBadge", "(Lcom/google/protobuf/RpcController;Lcom/lunarclient/websocket/badge/v1/EquipBadgeRequest;Lcom/google/protobuf/RpcCallback;)V", true);
        select.visitInsn(Opcodes.RETURN);
        select.visitMaxs(0, 0);
        select.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static ClassNode parseExpanded(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        return node;
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

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        do { insn = insn.getPrevious(); } while (insn != null && insn.getOpcode() < 0);
        return insn;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        do { insn = insn.getNext(); } while (insn != null && insn.getOpcode() < 0);
        return insn;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream closeable = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = closeable.read(buf)) != -1) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
