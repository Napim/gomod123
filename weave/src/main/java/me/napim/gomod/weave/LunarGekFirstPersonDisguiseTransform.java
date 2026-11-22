package me.napim.gomod.weave;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Guards the GEK first-person cosmetic event before it draws outside RenderPlayer's arm scope. */
public final class LunarGekFirstPersonDisguiseTransform {
    public static final String TARGET = "com/moonsworth/lunar/client/COOHIICHOIRRHOIHHOCOICHRRHHIIC/"
            + "IOCIHHIRIROCOIROOCORCCIOOIIIRR/IHCCRCCOHRCCIORHHCROORROCICRHI/ROOCCIHRCCHORHCCOIIRIHHHIIHHHO";
    // Lunar 2.23.1 moved the GEK held-item decision to this renderer. Keep the
    // legacy target too because older supported Lunar builds still use it.
    public static final String CURRENT_TARGET = "com/moonsworth/lunar/client/"
            + "HIRCHOCCIROORHOHOOHOIOHOOIHRCR/CICOHIIRRHHIIHRROHOICOCRHHHHOO/"
            + "IHHHICHHRICRCIOHCIOHHOHIOIORRH/ORCOIHCCICICIHRCCCCHHHRICHHICI";
    private static final String BRIDGE = "com/moonsworth/lunar/IHCCRCCOHRCCIORHHCROORROCICRHI/"
            + "CIIHHRHHHIOIIHHRROOHRHICIHOOOC/COHCCRCCIOORIRIIRIRCIHHCHIRCOH/";
    public static final String MATRIX = BRIDGE + "IROCHCCHCRCCOCOIIHRRHORIIIICCI";
    public static final String PLAYER = BRIDGE + "OCCRIICCOCOIORRORHIRHCHCOHHHHC/"
            + "IHCCRCCOHRCCIORHHCROORROCICRHI/OCCRIICCOCOIORRORHIRHCHCOHHHHC";
    public static final String CONTEXT = "com/moonsworth/lunar/IHCCRCCOHRCCIORHHCROORROCICRHI/"
            + "OICRICHRROHOCOOHIOIRIIHIICIOCC/COOHIICHOIRRHOIHHOCOICHRRHHIIC$IOCIHHIRIROCOIROOCORCCIOOIIIRR";
    public static final String METHOD = "IOHOHIHHHHORRCCRHCRCHCRIOIHRHH";
    public static final String DESCRIPTOR = "(L" + MATRIX + ";L" + PLAYER + ";Z)Z";
    private static final String HELPER = "me/napim/gomod/features/LunarDisguiseCosmeticVisibility";
    private static final String GUARD = "shouldHideFirstPersonGekCosmetics";
    private static final String CURRENT_METHOD = "CRRHRIRIHOHRHRCCHRCOICHOOIHOIC";
    private static final String CURRENT_DESCRIPTOR = "(Lcom/moonsworth/lunar/"
            + "IHHHICHHRICRCIOHCIOHHOHIOIORRH/RCHICCCIHRCRCHCIRRIIRRHICORHHO/"
            + "ROHIOOCICRORHRCIHHHHHHRICCCRRC/RCHOIOIIRCHIRRHIIHHRHOORCCIHCH;"
            + "Lcom/moonsworth/lunar/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
            + "RCHICCCIHRCRCHCIRRIIRRHICORHHO/ROHIOOCICRORHRCIHHHHHHRICCCRRC/"
            + "ICCRRCCIOOIRICIHHCIOROHHROOHHR/IHHHICHHRICRCIOHCIOHHOHIOIORRH/"
            + "ICCRRCCIOOIRICIHHCIOROHHROOHHR;Z)Z";

    private LunarGekFirstPersonDisguiseTransform() { }

    public static boolean isTarget(String name) {
        return TARGET.equals(name) || CURRENT_TARGET.equals(name);
    }

    public static byte[] transform(String name, byte[] bytes) {
        if (GomodReleaseBuild.PUBLIC_RELEASE || !isTarget(name) || bytes == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode(); reader.accept(node, 0);
            int matched = 0, patched = 0;
            for (MethodNode method : node.methods) {
                if (!matches(method)) continue;
                matched++;
                if (alreadyPatched(method)) continue;
                LabelNode nativePass = new LabelNode();
                InsnList gate = new InsnList();
                gate.add(new VarInsnNode(Opcodes.ALOAD, 1));
                gate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, GUARD, "(Ljava/lang/Object;)Z", false));
                gate.add(new JumpInsnNode(Opcodes.IFEQ, nativePass));
                // False means the GEK event did not replace the vanilla hand/held item.
                gate.add(new InsnNode(Opcodes.ICONST_0));
                gate.add(new InsnNode(Opcodes.IRETURN));
                gate.add(nativePass);
                gate.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                method.instructions.insert(gate);
                method.maxStack = Math.max(1, method.maxStack);
                patched++;
            }
            LunarConnectionDiagnostics.recordOnceAfterStart("disguise.lunar_gek_hand_patch", name,
                    "stage=class-definition firstPersonMethods=" + matched + " patched=" + patched
                            + " expected=1 vanillaHandPreserved=true thirdPersonUntouched=true");
            if (patched == 0) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer); return writer.toByteArray();
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("disguise.lunar_gek_hand_patch_failed", name,
                    "exception=" + error.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean matches(MethodNode method) {
        if (CURRENT_METHOD.equals(method.name) && CURRENT_DESCRIPTOR.equals(method.desc)
                && (method.access & Opcodes.ACC_STATIC) != 0
                && (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
            boolean firstPersonContext = false, heldItemPolicy = false;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                firstPersonContext |= "firstPerson".equals(call.name)
                        && call.desc.startsWith("(Z)Lcom/moonsworth/lunar/");
                heldItemPolicy |= "isHeldItemCosmetic".equals(call.name) && "()Z".equals(call.desc);
            }
            return firstPersonContext && heldItemPolicy;
        }
        if (!METHOD.equals(method.name) || !DESCRIPTOR.equals(method.desc)
                || (method.access & Opcodes.ACC_STATIC) == 0
                || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        boolean firstPersonContext = false, heldItemPolicy = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            firstPersonContext |= CONTEXT.equals(call.owner) && "firstPerson".equals(call.name)
                    && ("(Z)L" + CONTEXT + ";").equals(call.desc);
            heldItemPolicy |= call.owner.startsWith("com/moonsworth/lunar/client/COOHIICHOIRRHOIHHOCOICHRRHHIIC/")
                    && "isHeldItemCosmetic".equals(call.name) && "()Z".equals(call.desc);
        }
        return firstPersonContext && heldItemPolicy;
    }

    private static boolean alreadyPatched(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (HELPER.equals(call.owner) && GUARD.equals(call.name)) return true;
            }
        }
        return false;
    }
}
