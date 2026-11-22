package me.napim.gomod.hooks;

import net.weavemc.loader.api.Hook;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/** Native Weave fallback for Lunar's cloth-cloak renderer, used when /clothcapes is enabled. */
public class LunarClothCloakOpacityHook extends Hook {
    private static final String TARGET =
            "com/moonsworth/lunar/client/COROIRIHIIRCIIIOOCHROCOIOHCHIC/CIIOHCCOCRHORIRROROICHHRRRHHCC/CIIOHCCOCRHORIRROROICHHRRRHHCC/CCHCHHCHHROIRICOOOCORIIHHOHCCR";

    private static final String MAIN_DESC =
            "(Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/HOIHCCRHORIHIHHCHHIHHCCROHOHOR/HOOOIIIOCRHCIRRIHCIHOHROIHRCHH;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/ICHRRCIICOIIRHCIOOICORICHOOCHI/RRHORRHIIIOHHCICIORHHOHHOHIIHR;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/IORHHRRIHIRRIRRHIHCIRCRORIRHRI;"
                    + "FFFFFFF)V";

    private static final String ALT_DESC =
            "(Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/HOIHCCRHORIHIHHCHHIHHCCROHOHOR/CCHHIRHRCOIHHHIOOICRIHORHCIHIC;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/HOIHCCRHORIHIHHCHHIHHCCROHOHOR/ICHRRCIICOIIRHCIOOICORICHOOCHI/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/ICHRRCIICOIIRHCIOOICORICHOOCHI;"
                    + "Lcom/moonsworth/lunar/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/CCHCHHCHHROIRICOOOCORIIHHOHCCR/IORHHRRIHIRRIRRHIHCIRCRORIRHRI/IORHHRRIHIRRIRRHIHCIRCRORIRHRI;"
                    + "II)V";

    public LunarClothCloakOpacityHook() {
        super(TARGET);
    }

    @Override
    public void transform(@NotNull ClassNode node, @NotNull AssemblerConfig cfg) {
        int patched = 0;
        for (MethodNode method : (List<MethodNode>) node.methods) {
            if ("RRHORRHIIIOHHCICIORHHOHHOHIIHR".equals(method.name)
                    && (MAIN_DESC.equals(method.desc) || ALT_DESC.equals(method.desc))) {
                patchMethod(method);
                patched++;
            }
        }
        if (patched == 0) {
            System.err.println("[gomod] native Weave cloth cloak opacity hook: no matching Lunar cloth methods");
        } else {
            System.out.println("[gomod] native Weave cloth cloak opacity hook installed (" + patched + " methods)");
        }
    }

    private static void patchMethod(MethodNode method) {
        LabelNode continueRender = new LabelNode();
        InsnList head = new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD, 2));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "me/napim/gomod/features/LunarExternalOpacityBridge",
                "beginLunarCloak",
                "(Ljava/lang/Object;)Z",
                false));
        head.add(new JumpInsnNode(Opcodes.IFEQ, continueRender));
        head.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
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
