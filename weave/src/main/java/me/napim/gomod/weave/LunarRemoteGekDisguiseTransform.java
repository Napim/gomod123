package me.napim.gomod.weave;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Guards only the inspected GEK body-cosmetic pass; skin/body and Gomod cape draw separately. */
public final class LunarRemoteGekDisguiseTransform {
    public static final String TARGET = "com/moonsworth/lunar/client/COOHIICHOIRRHOIHHOCOICHRRHHIIC/"
            + "IOCIHHIRIROCOIROOCORCCIOOIIIRR/IHCCRCCOHRCCIORHHCROORROCICRHI/IOIRIIRHHCCRIHCOOHHHIIIRCICCRI";
    private static final String ROOT = "com/moonsworth/lunar/client/COOHIICHOIRRHOIHHOCOICHRRHHIIC/IOCIHHIRIROCOIROOCORCCIOOIIIRR/";
    private static final String HELPER = "me/napim/gomod/features/LunarRemoteDisguiseCosmetics";
    private LunarRemoteGekDisguiseTransform() { }

    public static byte[] transform(String name, byte[] bytes) {
        if (!TARGET.equals(name) || bytes == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes); ClassNode node = new ClassNode(); reader.accept(node, 0);
            MethodNode selected = null; int matches = 0;
            for (MethodNode method : node.methods) {
                if (!"IOHOHIHHHHORRCCRHCRCHCRIOIHRHH".equals(method.name) || (method.access & Opcodes.ACC_STATIC) == 0
                        || (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
                Type[] args = Type.getArgumentTypes(method.desc);
                if (args.length != 12 || Type.getReturnType(method.desc) != Type.VOID_TYPE
                        || !args[0].equals(Type.getObjectType(LunarGekFirstPersonDisguiseTransform.MATRIX))
                        || !args[1].equals(Type.getObjectType(ROOT + "CIIHHRHHHIOIIHHRROOHRHICIHOOOC"))
                        || !args[2].equals(Type.getObjectType(ROOT + "IHCCRCCOHRCCIORHHCROORROCICRHI/HHICICROOCIIROOHOORRCIHOICHOIH"))
                        || !args[4].equals(Type.getObjectType(LunarGekFirstPersonDisguiseTransform.PLAYER))
                        || args[6] != Type.BOOLEAN_TYPE || args[7] != Type.FLOAT_TYPE
                        || args[8] != Type.BOOLEAN_TYPE || args[9] != Type.BOOLEAN_TYPE
                        || !args[10].equals(Type.getObjectType(ROOT + "IHCCRCCOHRCCIORHHCROORROCICRHI/CIIHHRHHHIOIIHHRROOHRHICIHOOOC"))
                        || !args[11].equals(Type.getType("Ljava/util/function/Consumer;"))) continue;
                boolean bodyMarker = false;
                for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode)instruction;
                    if (HELPER.equals(call.owner)) return null;
                    if (LunarGekFirstPersonDisguiseTransform.CONTEXT.equals(call.owner)
                            && "thirdPerson".equals(call.name) && call.desc.equals("(Z)L" + call.owner + ";")) bodyMarker = true;
                }
                if (bodyMarker) { selected = method; matches++; }
            }
            LunarConnectionDiagnostics.recordOnceAfterStart("disguiser.lunar_gek_body_patch", name,
                    "bodyPassMatches=" + matches + " expected=1 renderOnly=true");
            if (matches != 1) return null;
            LabelNode nativePass = new LabelNode(); InsnList gate = new InsnList();
            gate.add(new VarInsnNode(Opcodes.ALOAD, 4));
            gate.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "shouldHideBridge", "(Ljava/lang/Object;)Z", false));
            gate.add(new JumpInsnNode(Opcodes.IFEQ, nativePass)); gate.add(new InsnNode(Opcodes.RETURN));
            gate.add(nativePass); gate.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            selected.instructions.insert(gate);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
        } catch (Throwable failure) {
            LunarConnectionDiagnostics.recordOnceAfterStart("disguiser.lunar_gek_body_patch_failed", name,
                    "exception=" + failure.getClass().getSimpleName()); return null;
        }
    }
}
