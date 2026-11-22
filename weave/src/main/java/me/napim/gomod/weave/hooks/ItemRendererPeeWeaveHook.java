package me.napim.gomod.weave.hooks;

import net.weavemc.api.Hook;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/** Native Weave fallback for /pee's first-person held-item transform. */
public final class ItemRendererPeeWeaveHook extends Hook {
    public ItemRendererPeeWeaveHook() {
        super("net/minecraft/client/renderer/ItemRenderer");
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        MethodNode target = null;
        for (MethodNode method : (List<MethodNode>) node.methods) {
            if (("transformFirstPersonItem".equals(method.name) || "func_178096_b".equals(method.name))
                    && "(FF)V".equals(method.desc)) {
                target = method;
                break;
            }
        }
        if (target == null) {
            for (MethodNode method : (List<MethodNode>) node.methods) {
                if ("(FF)V".equals(method.desc)) {
                    target = method;
                    break;
                }
            }
        }
        if (target == null || target.instructions == null || target.instructions.getFirst() == null) {
            System.err.println("[gomod] native Weave /pee item hook: transform method not found");
            return;
        }

        LabelNode vanilla = new LabelNode();
        InsnList head = new InsnList();
        head.add(new VarInsnNode(Opcodes.FLOAD, 1));
        head.add(new VarInsnNode(Opcodes.FLOAD, 2));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "me/napim/gomod/features/PeeEffectManager",
                "applyFirstPersonHeldItemTransformIfActive", "(FF)Z", false));
        head.add(new JumpInsnNode(Opcodes.IFEQ, vanilla));
        head.add(new InsnNode(Opcodes.RETURN));
        head.add(vanilla);
        target.instructions.insertBefore(target.instructions.getFirst(), head);
        System.out.println("[gomod] native Weave /pee item hook installed in " + target.name + target.desc);
    }
}
