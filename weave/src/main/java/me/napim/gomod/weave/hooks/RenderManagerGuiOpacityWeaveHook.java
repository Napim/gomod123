package me.napim.gomod.weave.hooks;

import net.weavemc.api.Hook;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/** Native Weave fallback for inventory player-preview opacity. */
public class RenderManagerGuiOpacityWeaveHook extends Hook {
    public static volatile boolean lastPatchApplied = false;
    public static volatile String lastPatchedMethod = "<none>";

    public RenderManagerGuiOpacityWeaveHook() {
        super("net/minecraft/client/renderer/entity/RenderManager");
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        MethodNode method = findRenderEntityWithPosYaw(node);
        if (method == null || method.instructions == null || method.instructions.getFirst() == null) {
            System.err.println("[gomod] native Weave inventory opacity hook: renderEntityWithPosYaw not found");
            lastPatchApplied = false;
            lastPatchedMethod = "<not found>";
            return;
        }

        InsnList head = new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD, 1));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "me/napim/gomod/features/VisibleSpecs",
                "beginGuiInventoryEntityRender",
                "(Lnet/minecraft/entity/Entity;)Z",
                false));
        head.add(new InsnNode(Opcodes.POP));
        head.add(new VarInsnNode(Opcodes.ALOAD, 1));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "me/napim/gomod/features/SelfOpacity",
                "beginGuiInventoryEntityRender",
                "(Lnet/minecraft/entity/Entity;)Z",
                false));
        head.add(new InsnNode(Opcodes.POP));
        method.instructions.insertBefore(method.instructions.getFirst(), head);

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN || insn.getOpcode() == Opcodes.IRETURN) {
                InsnList tail = new InsnList();
                tail.add(new VarInsnNode(Opcodes.ALOAD, 1));
                tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/SelfOpacity",
                        "endGuiInventoryEntityRender",
                        "(Lnet/minecraft/entity/Entity;)V",
                        false));
                tail.add(new VarInsnNode(Opcodes.ALOAD, 1));
                tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/VisibleSpecs",
                        "endGuiInventoryEntityRender",
                        "(Lnet/minecraft/entity/Entity;)V",
                        false));
                method.instructions.insertBefore(insn, tail);
            }
        }
        lastPatchApplied = true;
        lastPatchedMethod = method.name + method.desc;
        System.out.println("[gomod] native Weave inventory opacity hook installed");
    }

    private static MethodNode findRenderEntityWithPosYaw(ClassNode node) {
        for (MethodNode method : (List<MethodNode>) node.methods) {
            if (("renderEntityWithPosYaw".equals(method.name) || "func_147940_a".equals(method.name))
                    && isRenderEntityDescriptor(method.desc)) {
                return method;
            }
        }
        // Lunar may rename the method while retaining its unique vanilla descriptor.
        for (MethodNode method : (List<MethodNode>) node.methods) {
            if (isRenderEntityDescriptor(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static boolean isRenderEntityDescriptor(String desc) {
        return desc != null && desc.matches("\\(L[^;]+;DDDFF\\)[ZV]");
    }
}
