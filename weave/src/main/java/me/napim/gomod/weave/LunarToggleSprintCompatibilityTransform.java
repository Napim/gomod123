package me.napim.gomod.weave;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Extends only Toggle Sprint's native no-screen predicate to movement-enabled screens. */
public final class LunarToggleSprintCompatibilityTransform {
    private static final String HOOK = "me/napim/gomod/features/LunarMenuSprintSupport";

    private LunarToggleSprintCompatibilityTransform() { }

    public static boolean mayContain(String name, byte[] bytes) {
        if (name == null || bytes == null || !name.startsWith("com/moonsworth/lunar/")) {
            return false;
        }
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        return pool.contains("toggleSprint") && pool.contains("keybindSprint")
                && pool.contains("TOGGLE_SNEAK") && pool.contains("bridge$getCurrentScreen");
    }

    public static boolean isLoadedTarget(Class<?> type) {
        String name = type.getName().replace('.', '/');
        if (!name.startsWith("com/moonsworth/lunar/")) {
            return false;
        }
        try (InputStream input = type.getResourceAsStream("/" + name + ".class")) {
            if (input == null) {
                return false;
            }
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return mayContain(name, output.toByteArray());
        } catch (Exception ignored) {
            return false;
        }
    }

    public static byte[] transform(String name, byte[] bytes) {
        if (!mayContain(name, bytes)) {
            return null;
        }
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        List<MethodNode> gates = new ArrayList<MethodNode>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode
                        && ((MethodInsnNode) instruction).owner.equals(HOOK)) {
                    return null;
                }
            }
            if (isNoScreenPredicate(method)) {
                gates.add(method);
            }
        }
        if (gates.size() != 1) {
            LunarConnectionDiagnostics.record("menu.native_sprint_hook_rejected",
                    "class=" + name + " noScreenPredicates=" + gates.size());
            return null;
        }
        MethodNode gate = gates.get(0);
        for (AbstractInsnNode instruction : gate.instructions.toArray()) {
            if (instruction.getOpcode() == Opcodes.IRETURN) {
                gate.instructions.insertBefore(instruction, new MethodInsnNode(Opcodes.INVOKESTATIC,
                        HOOK, "allowNativeSprint", "(Z)Z", false));
            }
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        LunarConnectionDiagnostics.record("menu.native_sprint_hook",
                "class=" + name + " noScreenPredicate=" + gate.name
                        + " sprintStateOwner=lunar collisionHandling=vanilla");
        return writer.toByteArray();
    }

    private static boolean isNoScreenPredicate(MethodNode method) {
        if (!method.desc.equals("()Z") || (method.access & Opcodes.ACC_PRIVATE) == 0
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) {
            return false;
        }
        List<AbstractInsnNode> code = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) {
                code.add(instruction);
            }
        }
        if (code.size() != 8 || code.get(0).getOpcode() != Opcodes.ALOAD
                || ((VarInsnNode) code.get(0)).var != 0 || code.get(1).getOpcode() != Opcodes.GETFIELD
                || !(code.get(2) instanceof MethodInsnNode)
                || code.get(3).getOpcode() != Opcodes.IFNONNULL
                || code.get(4).getOpcode() != Opcodes.ICONST_1
                || code.get(5).getOpcode() != Opcodes.GOTO
                || code.get(6).getOpcode() != Opcodes.ICONST_0
                || code.get(7).getOpcode() != Opcodes.IRETURN) {
            return false;
        }
        MethodInsnNode screen = (MethodInsnNode) code.get(2);
        return screen.name.equals("bridge$getCurrentScreen") && screen.desc.startsWith("()L")
                && nextCode(((JumpInsnNode) code.get(3)).label) == code.get(6)
                && nextCode(((JumpInsnNode) code.get(5)).label) == code.get(7);
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) {
            instruction = instruction.getNext();
        }
        return instruction;
    }
}
