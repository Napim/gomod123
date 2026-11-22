package me.napim.gomod.weave.hooks;

import net.weavemc.api.Hook;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.ArrayList;
import java.util.List;

/** Coordinates gomod's replay nameplate stack with Lunar Hypixel's native multi-line nametag event. */
public final class LunarHypixelNametagWeaveHook extends Hook {
    private static final String TARGET =
            "com/moonsworth/lunar/client/CCRCOICHHIIRHHIROIOICIRCIIIOII/CCHCHHCHHROIRICOOOCORIIHHOHCCR/"
                    + "IIHCHHCHRHORCRHRRHCCCRCICORROR/IIHOCIRHRCIRHIIOOCHCOCHROHORCO";
    private static final String EVENT_DESC =
            "Lcom/moonsworth/lunar/client/ICHRRCIICOIIRHCIOOICORICHOOCHI/RRHORRHIIIOHHCICIORHHOHHOHIIHR/"
                    + "ICHRRCIICOIIRHCIOOICORICHOOCHI/CIIIICCIIICHCHRHIRCOOHHCOHRORH;";
    private static final String CURRENT_TARGET =
            "com/moonsworth/lunar/client/HRCIHRROICRHRHOHIRORRCCHOICRRI/CIIHHRHHHIOIIHHRROOHRHICIHOOOC/"
                    + "HRCCCHOHRIROHHIHIHOIOCRHRRIHIO/ROOCCIHRCCHORHCCOIIRIHHHIIHHHO";
    private static final String CURRENT_EVENT_DESC =
            "Lcom/moonsworth/lunar/client/OCCRIICCOCOIORRORHIRHCHCOHHHHC/IOHOHIHHHHORRCCRHCRCHCRIOIHRHH/"
                    + "OCCRIICCOCOIORRORHIRHCHCOHHHHC/OICRICHRROHOCOOHIOIRIIHIICIOCC;";
    // Current Lunar Level Head appends its row from the Hypixel module's native entity-nametag
    // handler. These names are verified against the installed lunar.jar.
    private static final String LUNAR_223_TARGET =
            "com/moonsworth/lunar/client/RROCHCCOCCROCCCHOROHCIHOOHCRHC/"
                    + "ROROOCIIRHIOOCHORIOHIHHHOCHRIO/CIHIORCRHRCICRIHRORCCOHCRICRHR/"
                    + "CRHRHHCRROICOORCRCOIIIHOICICCI";
    private static final String LUNAR_223_EVENT_DESC =
            "(Lcom/moonsworth/lunar/client/IOCRHHIIICRROOCIRRRRCOCRIRCHIR/"
                    + "CRHOIHOORHICOCOCCCCRRIRICCIRCR/IOCRHHIIICRROOCIRRRRCOCRIRCHIR/"
                    + "RHHHCOROIRCHIHIHCHHRCHOICHRRCR;)V";
    // Other native modules share this event and may append rows after HYPIXEL_MOD handles it.
    private static final String LUNAR_223_CLIENT_NAMETAG_TARGET =
            "com/moonsworth/lunar/client/CRRHRIRIHOHRHRCCHRCOICHOOIHOIC/"
                    + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC/CRRHRIRIHOHRHRCCHRCOICHOOIHOIC/"
                    + "IIICIHORHCRHRHIRCHRCRHRICCROOC";
    private static final String LUNAR_223_SERVER_CUSTOM_NAMEPLATE_TARGET =
            "com/moonsworth/lunar/client/RCHORCICHCHOCCCROHOOHICROIOHRI/"
                    + "IHIRRHORRRRHICCOHOHORCHIHHHIOO/ORCOIHCCICICIHRCCCCHHHRICHHICI/"
                    + "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC";
    private static final String LUNAR_223_PING_NAMETAG_TARGET =
            "com/moonsworth/lunar/client/RCHORCICHCHOCCCROHOOHICROIOHRI/"
                    + "RCHICCCIHRCRCHCIRRIIRRHICORHHO/CCHHCORHHICIOOHIHROOROCHOROHRR/"
                    + "IHHHICHHRICRCIOHCIOHHOHIOIORRH";
    private static final String LUNAR_223_TOTEM_COUNTER_TARGET =
            "com/moonsworth/lunar/client/RCHORCICHCHOCCCROHOOHICROIOHRI/"
                    + "RCHICCCIHRCRCHCIRRIIRRHICORHHO/ROCOCOCOOCHCHHOCORROOCCRCHCRIO/"
                    + "CICOHIIRRHHIIHRROHOICOCRHHHHOO";
    private static final String[] LUNAR_223_FINALIZER_TARGETS = {
            LUNAR_223_CLIENT_NAMETAG_TARGET,
            LUNAR_223_SERVER_CUSTOM_NAMEPLATE_TARGET,
            LUNAR_223_PING_NAMETAG_TARGET,
            LUNAR_223_TOTEM_COUNTER_TARGET
    };

    public static volatile int lastPatchedReturns = -1;
    public static volatile int lastLevelHeadRowFilterHooked = -1;

    public LunarHypixelNametagWeaveHook() {
        super(TARGET, CURRENT_TARGET, LUNAR_223_TARGET,
                LUNAR_223_CLIENT_NAMETAG_TARGET,
                LUNAR_223_SERVER_CUSTOM_NAMEPLATE_TARGET,
                LUNAR_223_PING_NAMETAG_TARGET,
                LUNAR_223_TOTEM_COUNTER_TARGET);
    }

    public static boolean isTarget(String className) {
        if (TARGET.equals(className) || CURRENT_TARGET.equals(className)
                || LUNAR_223_TARGET.equals(className)) return true;
        for (String target : LUNAR_223_FINALIZER_TARGETS) {
            if (target.equals(className)) return true;
        }
        return false;
    }

    /** Runs the same hook from the retransformation-capable native transformer. */
    public static byte[] transformBytes(String className, byte[] bytes, final ClassLoader loader) {
        if (!isTarget(className) || bytes == null) return null;
        try {
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            boolean finalizerOnly = isCurrentFinalizerTarget(className);
            int patched = instrument(node, finalizerOnly);
            if (patched > 0 && !finalizerOnly) lastPatchedReturns = patched;
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    finalizerOnly ? "lunar.spectator_nametag_finalizer" : "disguise.lunar_level_head_hook",
                    className, "patchedReturns=" + patched + " finalizerOnly=" + finalizerOnly);
            if (patched == 0) return null;
            org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(reader,
                    org.objectweb.asm.ClassWriter.COMPUTE_FRAMES | org.objectweb.asm.ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String left, String right) {
                    try {
                        Class<?> leftClass = Class.forName(left.replace('/', '.'), false, loader);
                        Class<?> rightClass = Class.forName(right.replace('/', '.'), false, loader);
                        if (leftClass.isAssignableFrom(rightClass)) return left;
                        if (rightClass.isAssignableFrom(leftClass)) return right;
                        if (leftClass.isInterface() || rightClass.isInterface()) return "java/lang/Object";
                        do { leftClass = leftClass.getSuperclass(); }
                        while (leftClass != null && !leftClass.isAssignableFrom(rightClass));
                        return leftClass == null ? "java/lang/Object" : leftClass.getName().replace('.', '/');
                    } catch (Throwable unavailableDuringTransform) {
                        return "java/lang/Object";
                    }
                }
            };
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable error) {
            System.err.println("[gomod] Lunar Hypixel nametag hook failed: "
                    + error.getClass().getSimpleName());
            return null;
        }
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        boolean finalizerOnly = isCurrentFinalizerTarget(node.name);
        int patched = instrument(node, finalizerOnly);
        // Do not erase a successful dynamic-transform count when another hook pass sees already-patched bytes.
        if (patched > 0 && !finalizerOnly) lastPatchedReturns = patched;
        if (patched > 0) cfg.computeFrames();
        if (patched == 0) {
            System.err.println(finalizerOnly
                    ? "[gomod] Lunar spectator nametag finalizer: event handler not found"
                    : "[gomod] Lunar Hypixel nametag hook: event handler not found");
        } else {
            System.out.println(finalizerOnly
                    ? "[gomod] Lunar spectator nametag finalizer installed (" + patched + " returns)"
                    : "[gomod] Lunar Hypixel nametag hook installed (" + patched + " returns)");
        }
    }

    private static boolean isCurrentFinalizerTarget(String className) {
        if (className == null) return false;
        String internalName = className.replace('.', '/');
        for (String target : LUNAR_223_FINALIZER_TARGETS) {
            if (target.equals(internalName)) return true;
        }
        return false;
    }

    private static int instrument(ClassNode node, boolean finalizerOnly) {
        int patched = 0;
        for (MethodNode method : (List<MethodNode>) node.methods) {
            if (method == null || method.instructions == null || method.desc == null
                    || !(method.desc.equals("(" + EVENT_DESC + ")V")
                    || method.desc.equals("(" + CURRENT_EVENT_DESC + ")V")
                    || method.desc.equals(LUNAR_223_EVENT_DESC))
                    || (method.access & Opcodes.ACC_STATIC) != 0) {
                continue;
            }
            if (finalizerOnly) {
                if (alreadyFinalized(method)) continue;
                List<AbstractInsnNode> originalReturns = new ArrayList<AbstractInsnNode>();
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.RETURN) originalReturns.add(insn);
                }
                for (AbstractInsnNode originalReturn : originalReturns) {
                    InsnList call = new InsnList();
                    call.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "me/napim/gomod/features/LunarNametagCompatibility",
                            "finalizeLiveSpectatorNametagEvent", "(Ljava/lang/Object;)V", false));
                    method.instructions.insertBefore(originalReturn, call);
                    patched++;
                }
                continue;
            }
            if (alreadyPatched(method)) continue;
            List<AbstractInsnNode> originalReturns = new ArrayList<AbstractInsnNode>();
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.RETURN) originalReturns.add(insn);
            }
            boolean levelHeadHandler = LUNAR_223_TARGET.equals(node.name)
                    && LUNAR_223_EVENT_DESC.equals(method.desc);
            LabelNode nativeEventAllowed = new LabelNode();
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "me/napim/gomod/features/LunarNametagCompatibility",
                    "shouldSuppressNativeLunarEvent", "(Ljava/lang/Object;)Z", false));
            guard.add(new JumpInsnNode(Opcodes.IFEQ, nativeEventAllowed));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(nativeEventAllowed);
            guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
            guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "me/napim/gomod/features/LunarNametagCompatibility",
                    "beginDisguisedLevelHeadEvent", "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
            LabelNode nativeStart = new LabelNode();
            guard.add(nativeStart);
            if (levelHeadHandler) {
                LabelNode runNativeLevelHeadHandler = new LabelNode();
                guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
                guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/LunarNametagCompatibility",
                        "skipNativeLevelHeadCallback", "(Ljava/lang/Object;)Z", false));
                guard.add(new JumpInsnNode(Opcodes.IFEQ, runNativeLevelHeadHandler));
                guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/LunarNametagCompatibility",
                        "endDisguisedLevelHeadEvent", "()V", false));
                guard.add(new InsnNode(Opcodes.RETURN));
                guard.add(runNativeLevelHeadHandler);
                guard.add(new VarInsnNode(Opcodes.ALOAD, 1));
                guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/LunarNametagCompatibility",
                        "beginNativeLevelHeadRowFilter", "(Ljava/lang/Object;)V", false));
                lastLevelHeadRowFilterHooked = 1;
            }
            method.instructions.insert(guard);
            for (AbstractInsnNode originalReturn : originalReturns) {
                InsnList call = new InsnList();
                call.add(new VarInsnNode(Opcodes.ALOAD, 1));
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/LunarNametagCompatibility",
                        "addDenickLineToLunarEvent", "(Ljava/lang/Object;)V", false));
                if (levelHeadHandler) {
                    call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "me/napim/gomod/features/LunarNametagCompatibility",
                            "endNativeLevelHeadRowFilter", "()V", false));
                }
                call.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/LunarNametagCompatibility",
                        "endDisguisedLevelHeadEvent", "()V", false));
                method.instructions.insertBefore(originalReturn, call);
                patched++;
            }
            LabelNode nativeEnd = new LabelNode();
            LabelNode restoreOnFailure = new LabelNode();
            method.instructions.add(nativeEnd);
            method.instructions.add(restoreOnFailure);
            if (levelHeadHandler) {
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/LunarNametagCompatibility",
                        "endNativeLevelHeadRowFilter", "()V", false));
            }
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "me/napim/gomod/features/LunarNametagCompatibility",
                    "endDisguisedLevelHeadEvent", "()V", false));
            method.instructions.add(new InsnNode(Opcodes.ATHROW));
            method.tryCatchBlocks.add(new TryCatchBlockNode(nativeStart, nativeEnd, restoreOnFailure,
                    "java/lang/Throwable"));
        }
        if (patched > 0 && !finalizerOnly) lastPatchedReturns = patched;
        return patched;
    }

    private static boolean alreadyPatched(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if ("me/napim/gomod/features/LunarNametagCompatibility".equals(call.owner)
                    && "beginDisguisedLevelHeadEvent".equals(call.name)) return true;
        }
        return false;
    }

    private static boolean alreadyFinalized(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if ("me/napim/gomod/features/LunarNametagCompatibility".equals(call.owner)
                    && "finalizeLiveSpectatorNametagEvent".equals(call.name)) return true;
        }
        return false;
    }
}
