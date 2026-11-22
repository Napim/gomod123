package me.napim.gomod.weave;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Finds Lunar's spray mesh writer from its catalog and vertex arguments, without obfuscated names. */
public final class LunarSprayLightingTransform {
    private static final String HELPER = "me/napim/gomod/features/LunarSprayLighting";

    private LunarSprayLightingTransform() { }

    public static byte[] transform(String name, byte[] bytes) {
        if (name == null || bytes == null || !name.startsWith("com/moonsworth/lunar/")) return null;
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        if (!pool.contains("Failed to register sprays from json file")
                || !pool.contains("bridge$getPackedLight")) return null;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        Set<String> entries = new HashSet<String>();
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (HELPER.equals(call.owner)) return null;
            if (call.name.equals("<init>") && call.desc.startsWith("(ILjava/lang/String;L")
                    && call.desc.endsWith(";IFFFFZZZI)V")) entries.add(call.owner);
        }
        if (entries.size() != 1) return null;
        String entry = entries.iterator().next();
        MethodNode target = null;
        int lightSlot = -1, matches = 0;
        for (MethodNode method : node.methods) {
            Type[] args = Type.getArgumentTypes(method.desc);
            if (Type.getReturnType(method.desc).getSort() != Type.VOID || args.length != 4
                    || args[0].getSort() != Type.OBJECT || args[1].getSort() != Type.OBJECT
                    || !entry.equals(args[1].getInternalName())
                    || args[2].getSort() != Type.INT || args[3].getSort() != Type.INT) continue;
            int slot = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
            slot += args[0].getSize() + args[1].getSize();
            boolean width = false, height = false;
            int lights = 0, colors = 0;
            Set<String> lightWriters = new HashSet<String>();
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (entry.equals(call.owner) && call.desc.equals("()F")) {
                    width |= call.name.equals("getWidth");
                    height |= call.name.equals("getHeight");
                }
                if (!call.desc.startsWith("(I)L") || !call.owner.startsWith("com/moonsworth/lunar/")) continue;
                AbstractInsnNode value = previous(insn);
                if (!(value instanceof VarInsnNode) || value.getOpcode() != Opcodes.ILOAD) continue;
                int source = ((VarInsnNode) value).var;
                if (source == slot) {
                    lights++;
                    lightWriters.add(call.owner + "." + call.name + call.desc);
                } else if (source == slot + 1) colors++;
            }
            // The base quad reads this light argument four times. An optional emissive quad
            // uses a separate already-bright local, while retaining the same tint argument.
            if (width && height && lightWriters.size() == 1 && lights == 4
                    && (colors == 4 || colors == 8)) {
                target = method; lightSlot = slot; matches++;
            }
        }
        if (matches != 1) {
            LunarConnectionDiagnostics.record("lunar.spray.lighting_hook_rejected",
                    "class=" + name + " meshWriterMatches=" + matches);
            return null;
        }
        AbstractInsnNode[] original = target.instructions.toArray();
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        InsnList patch = new InsnList();
        patch.add(start);
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "begin", "()V", false));
        patch.add(new VarInsnNode(Opcodes.ILOAD, lightSlot));
        patch.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "fullBright", "(I)I", false));
        patch.add(new VarInsnNode(Opcodes.ISTORE, lightSlot));
        target.instructions.insert(patch);
        for (AbstractInsnNode insn : original) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                target.instructions.insertBefore(insn,
                        new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "end", "()V", false));
            }
        }
        target.instructions.add(end);
        target.instructions.add(handler);
        target.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1,
                new Object[] {"java/lang/Throwable"}));
        target.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "end", "()V", false));
        target.instructions.add(new InsnNode(Opcodes.ATHROW));
        target.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        LunarConnectionDiagnostics.record("lunar.spray.lighting_hook_applied",
                "class=" + name + " nativeMeshWriters=1");
        return writer.toByteArray();
    }

    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        do { insn = insn.getPrevious(); } while (insn != null && insn.getOpcode() < 0);
        return insn;
    }
}
