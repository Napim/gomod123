package me.napim.gomod.weave;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.ArrayList;
import java.util.List;

/** Corrects only LWJGL 2's seven-int attribute builder with a missing rewind. */
public final class LunarOpenALAttributeTransform {
    public static final String TARGET = "org/lwjgl/openal/ALCcontext";
    private LunarOpenALAttributeTransform() { }

    public static byte[] transform(String name, byte[] bytes) {
        if (!TARGET.equals(name) || bytes == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            if (!TARGET.equals(node.name)) return null;
            MethodNode selected = null;
            for (MethodNode method : node.methods) {
                if (!"createAttributeList".equals(method.name)
                        || !"(III)Ljava/nio/IntBuffer;".equals(method.desc)
                        || (method.access & Opcodes.ACC_STATIC) == 0) continue;
                if (!matchesLegacyBuilder(method)) return null;
                if (selected != null) return null;
                selected = method;
            }
            if (selected == null) return null;
            // Keep the original reference/return type; Buffer.rewind is available
            // on Java 8 as well as Lunar's Java 17. No new native call or helper.
            AbstractInsnNode end = selected.instructions.getLast();
            while (end != null && end.getOpcode() < 0) end = end.getPrevious();
            InsnList rewind = new InsnList();
            rewind.add(new InsnNode(Opcodes.DUP));
            rewind.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/nio/Buffer",
                    "rewind", "()Ljava/nio/Buffer;", false));
            rewind.add(new InsnNode(Opcodes.POP));
            selected.instructions.insertBefore(end, rewind);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            LunarConnectionDiagnostics.recordOnceAfterStart("audio.openal_attribute_buffer_patch", name,
                    "legacyBuilderMatches=1 expected=1 position=0 nativeLibraryUnchanged=true");
            return writer.toByteArray();
        } catch (Throwable failure) {
            LunarConnectionDiagnostics.recordOnceAfterStart("audio.openal_attribute_buffer_patch_failed", name,
                    "exception=" + failure.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean matchesLegacyBuilder(MethodNode method) {
        List<AbstractInsnNode> instructions = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) instructions.add(instruction);
        }
        if (instructions.size() != 33 || !constant(instructions.get(0), 7)
                || !call(instructions.get(1), Opcodes.INVOKESTATIC, "org/lwjgl/BufferUtils",
                    "createIntBuffer", "(I)Ljava/nio/IntBuffer;")
                || !variable(instructions.get(2), Opcodes.ASTORE, 3)) return false;
        // Frequency, refresh and synchronous mode, followed by a zero terminator.
        int[] values = {4103, 0, 4104, 1, 4105, 2, 0};
        for (int pair = 0; pair < values.length; pair++) {
            int offset = 3 + pair * 4;
            boolean argument = pair == 1 || pair == 3 || pair == 5;
            if (!variable(instructions.get(offset), Opcodes.ALOAD, 3)
                    || !(argument ? variable(instructions.get(offset + 1), Opcodes.ILOAD, values[pair])
                                  : constant(instructions.get(offset + 1), values[pair]))
                    || !call(instructions.get(offset + 2), Opcodes.INVOKEVIRTUAL, "java/nio/IntBuffer",
                        "put", "(I)Ljava/nio/IntBuffer;")
                    || instructions.get(offset + 3).getOpcode() != Opcodes.POP) return false;
        }
        return variable(instructions.get(31), Opcodes.ALOAD, 3)
                && instructions.get(32).getOpcode() == Opcodes.ARETURN;
    }

    private static boolean constant(AbstractInsnNode instruction, int value) {
        if (instruction instanceof IntInsnNode)
            return (instruction.getOpcode() == Opcodes.BIPUSH || instruction.getOpcode() == Opcodes.SIPUSH)
                    && ((IntInsnNode) instruction).operand == value;
        return value >= -1 && value <= 5 && instruction.getOpcode() == Opcodes.ICONST_0 + value;
    }
    private static boolean variable(AbstractInsnNode instruction, int opcode, int index) {
        return instruction instanceof VarInsnNode && instruction.getOpcode() == opcode
                && ((VarInsnNode) instruction).var == index;
    }
    private static boolean call(AbstractInsnNode instruction, int opcode, String owner, String name, String descriptor) {
        if (!(instruction instanceof MethodInsnNode) || instruction.getOpcode() != opcode) return false;
        MethodInsnNode call = (MethodInsnNode) instruction;
        return owner.equals(call.owner) && name.equals(call.name) && descriptor.equals(call.desc);
    }
}
