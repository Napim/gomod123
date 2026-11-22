package me.napim.gomod.weave;

import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Draws the native scoreboard background as one shape when both sections share a solid color. */
public final class LunarSidebarBackgroundTransform implements Opcodes {
    static final String TARGET = "com/moonsworth/lunar/client/HROHOHORHOCICRICCOCROCRHHOIICR/"
            + "RRIHHIROHRHCRICCIIRHCRCRIRCRHC/HHCRIRROOIRCCORCOIICHCCIOIORRH/"
            + "HCHORRIICIOCIIHOOCCCICROOIOOOH$HCHORRIICIOCIIHOOCCCICROOIOOOH";

    private LunarSidebarBackgroundTransform() {}

    static byte[] transform(String name, byte[] bytes) {
        if (!TARGET.equals(name) || bytes == null) {
            return null;
        }
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        for (MethodNode method : node.methods) {
            if (alignTitleBackground(method)) {
                ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
                node.accept(writer);
                System.out.println("[gomod] Lunar scoreboard background seam fix installed");
                return writer.toByteArray();
            }
        }
        return null;
    }

    private static boolean alignTitleBackground(MethodNode method) {
        List<AbstractInsnNode> code = new ArrayList<>();
        List<MethodInsnNode> backgrounds = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (insn.getOpcode() >= 0) {
                code.add(insn);
            }
            if (insn.getOpcode() == Opcodes.INVOKESTATIC && insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.desc.matches("\\(L[^;]+;FFFFFL[^;]+;ZZZZ\\)V")) {
                    backgrounds.add(call);
                }
            }
        }
        if (backgrounds.size() != 2) {
            return false;
        }
        MethodInsnNode title = backgrounds.get(0);
        MethodInsnNode body = backgrounds.get(1);
        if (!title.owner.equals(body.owner) || !title.name.equals(body.name) || !title.desc.equals(body.desc)) {
            return false;
        }
        if (!matches(code, code.indexOf(title) - 4, ICONST_1, ICONST_1, ICONST_0, ICONST_0)
                || !matches(code, code.indexOf(body) - 4, ICONST_0, ICONST_0, ICONST_1, ICONST_1)
                || !matches(code, code.indexOf(title) + 1, ALOAD, FLOAD, FLOAD, ILOAD)
                || code.indexOf(body) - code.indexOf(title) != 30) {
            return false;
        }

        int titleColor = method.maxLocals++;
        int titleHeight = method.maxLocals++;
        int titleY = method.maxLocals++;
        InsnList saveTitle = discardCorners();
        saveTitle.add(new VarInsnNode(ASTORE, titleColor));
        saveTitle.add(new InsnNode(POP)); // radius (shared with the body)
        saveTitle.add(new VarInsnNode(FSTORE, titleHeight));
        saveTitle.add(new InsnNode(POP)); // width (shared with the body)
        saveTitle.add(new VarInsnNode(FSTORE, titleY));
        saveTitle.add(new InsnNode(POP2)); // x and draw context (shared with the body)
        method.instructions.insertBefore(title, saveTitle);
        method.instructions.remove(title);

        String colorType = Type.getArgumentTypes(body.desc)[6].getInternalName();
        InsnList drawBoth = discardCorners();
        drawBoth.add(new VarInsnNode(ALOAD, titleColor));
        drawBoth.add(new VarInsnNode(FLOAD, titleHeight));
        drawBoth.add(new VarInsnNode(FLOAD, titleY));
        drawBoth.add(new LdcInsnNode(new Handle(H_INVOKESTATIC, body.owner, body.name, body.desc, body.itf)));
        drawBoth.add(new LdcInsnNode(new Handle(H_INVOKEINTERFACE, colorType, "getColor", "()I", true)));
        drawBoth.add(new LdcInsnNode(new Handle(H_INVOKEINTERFACE, colorType,
                "CIHHHCRCOIIHRCHOIRIOOCRHHOIRHC", "()Z", true)));
        drawBoth.add(new MethodInsnNode(INVOKESTATIC, "me/napim/gomod/weave/LunarSidebarBackgroundTransform",
                "drawBackground", "(Ljava/lang/Object;FFFFFLjava/lang/Object;Ljava/lang/Object;FF"
                + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)V", false));
        method.instructions.insertBefore(body, drawBoth);
        method.instructions.remove(body);
        return true;
    }

    private static InsnList discardCorners() {
        InsnList discard = new InsnList();
        discard.add(new InsnNode(POP2));
        discard.add(new InsnNode(POP2));
        return discard;
    }

    public static void drawBackground(Object graphics, float x, float bodyY, float width, float bodyHeight,
                                      float radius, Object bodyColor, Object titleColor, float titleHeight,
                                      float titleY, MethodHandle draw, MethodHandle color, MethodHandle gradient)
            throws Throwable {
        if (!(boolean) gradient.invoke(titleColor) && !(boolean) gradient.invoke(bodyColor)
                && (int) color.invoke(titleColor) == (int) color.invoke(bodyColor)) {
            draw.invoke(graphics, x, titleY, width, (bodyY - titleY) + bodyHeight, radius, bodyColor,
                    true, true, true, true);
            return;
        }
        draw.invoke(graphics, x, titleY, width, titleHeight, radius, titleColor, true, true, false, false);
        draw.invoke(graphics, x, bodyY, width, bodyHeight, radius, bodyColor, false, false, true, true);
    }

    private static boolean matches(List<AbstractInsnNode> code, int offset, int... opcodes) {
        if (offset < 0 || offset + opcodes.length > code.size()) {
            return false;
        }
        for (int i = 0; i < opcodes.length; i++) {
            if (code.get(offset + i).getOpcode() != opcodes[i]) {
                return false;
            }
        }
        return true;
    }
}
