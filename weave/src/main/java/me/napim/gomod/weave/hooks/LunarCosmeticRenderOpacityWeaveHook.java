package me.napim.gomod.weave.hooks;

import net.weavemc.api.Hook;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/**
 * Native Weave fallback for Lunar's shared cosmetic render helper.
 *
 * <p>The older cloak-renderer hook targets one concrete cloak layer class. Current Lunar builds can route cloth
 * cloaks through this static helper instead, so hook every static cosmetic render overload that receives Lunar's
 * player bridge as argument #1.</p>
 */
public class LunarCosmeticRenderOpacityWeaveHook extends Hook {
    private static final String TARGET =
            "com/moonsworth/lunar/client/CCHCHHCHHROIRICOOOCORIIHHOHCCR/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/RRHORRHIIIOHHCICIORHHOHHOHIIHR";
    private static final String PLAYER_BRIDGE_DESC =
            "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/ICHRRCIICOIIRHCIOOICORICHOOCHI/RRHORRHIIIOHHCICIORHHOHHOHIIHR;";

    public static volatile int lastPatchedMethods = -1;
    public static volatile String lastTargetName = TARGET;

    public LunarCosmeticRenderOpacityWeaveHook() {
        super(TARGET);
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        int patched = 0;
        for (MethodNode method : (List<MethodNode>) node.methods) {
            if (shouldPatch(method)) {
                patchStaticMethod(method);
                patched++;
            }
        }
        lastPatchedMethods = patched;
        if (patched == 0) {
            System.err.println("[gomod] native Weave Lunar cosmetic opacity hook: no matching helper methods");
        } else {
            System.out.println("[gomod] native Weave Lunar cosmetic opacity hook installed (" + patched + " methods)");
        }
    }

    private static boolean shouldPatch(MethodNode method) {
        return method != null
                && "RRHORRHIIIOHHCICIORHHOHHOHIIHR".equals(method.name)
                && method.desc != null
                && method.desc.endsWith(")V")
                && method.desc.contains(PLAYER_BRIDGE_DESC)
                && (method.access & Opcodes.ACC_STATIC) != 0
                && method.instructions != null
                && method.instructions.getFirst() != null;
    }

    private static void patchStaticMethod(MethodNode method) {
        LabelNode continueRender = new LabelNode();
        InsnList head = new InsnList();
        // In every matched static overload, arg0 is matrix/buffer and arg1 is Lunar's player bridge.
        head.add(new VarInsnNode(Opcodes.ALOAD, 1));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "me/napim/gomod/features/LunarExternalOpacityBridge",
                "beginLunarCloak",
                "(Ljava/lang/Object;)Z",
                false));
        head.add(new JumpInsnNode(Opcodes.IFEQ, continueRender));
        head.add(new InsnNode(Opcodes.RETURN));
        head.add(continueRender);
        method.instructions.insertBefore(method.instructions.getFirst(), head);

        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList tail = new InsnList();
                tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "me/napim/gomod/features/LunarExternalOpacityBridge",
                        "endLunarCloak",
                        "()V",
                        false));
                method.instructions.insertBefore(insn, tail);
            }
        }
    }
}
