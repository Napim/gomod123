package me.napim.gomod.weave.hooks;

import net.weavemc.api.Hook;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

/** Multiplies Lunar cloth-cloak packed vertex colours by gomod's active local-player opacity. */
public class LunarCurrentClothVertexAlphaWeaveHook extends Hook {
    private static final String TARGET =
            "com/moonsworth/lunar/client/ORCOIHHHROORRIIIRCHHRRCIICHOCH/HCOCIICRHHRCRIHIIIRRIOIHIIHCOC/"
                    + "HCOCIICRHHRCRIHIIIRRIOIHIIHCOC/ORCRCHHRCHRIIRHIRCIHRHOHRCHROC$"
                    + "HCOCIICRHHRCRIHIIIRRIOIHIIHCOC";
    private static final String FLUSH_METHOD = "CCCOHRROCROHICORIICRICIRRCCHOH";
    private static final String PACKED_COLOR_METHOD = "ORCOIHHHROORRIIIRCHHRRCIICHOCH";

    public static volatile int lastPatchedColorCalls = -1;

    public LunarCurrentClothVertexAlphaWeaveHook() {
        super(TARGET);
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        int patched = 0;
        for (MethodNode method : (List<MethodNode>) node.methods) {
            if (!FLUSH_METHOD.equals(method.name) || !"()V".equals(method.desc)) {
                continue;
            }
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode)) {
                    continue;
                }
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!PACKED_COLOR_METHOD.equals(call.name) || call.desc == null || !call.desc.startsWith("(I)")) {
                    continue;
                }
                // Stack immediately before the interface call is [vertexConsumer, packedArgb].
                InsnList alpha = new InsnList();
                alpha.add(new MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/LunarExternalOpacityBridge",
                        "applyActiveOpacityToPackedColor",
                        "(I)I",
                        false));
                method.instructions.insertBefore(call, alpha);
                patched++;
            }
        }
        lastPatchedColorCalls = patched;
        System.out.println("[gomod] current Lunar cloth vertex-alpha hook installed (" + patched + " color calls)");
    }
}
