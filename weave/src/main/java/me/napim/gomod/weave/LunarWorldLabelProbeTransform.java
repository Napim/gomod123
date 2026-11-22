package me.napim.gomod.weave;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Scopes the actual Lunar billboard row, before both its background and bridge$drawString3D. */
public final class LunarWorldLabelProbeTransform {
    private static final String CLIENT_PREFIX = "com/moonsworth/lunar/client/";
    private static final String MODULE_ID = "SERVER_HOLOGRAMS";
    private static final String COMPONENT = "Lcom/moonsworth/lunar/lib/adventure/text/Component;";
    private static final String DRAW_STRING = "bridge$drawString3D";
    private static final String HELPER = "me/napim/gomod/features/LunarWorldLabelProbe";
    private LunarWorldLabelProbeTransform() { }

    public static boolean isLoadedTarget(Class<?> type) {
        String name = type.getName().replace('.', '/');
        if (!name.startsWith(CLIENT_PREFIX)) return false;
        try (InputStream input = type.getResourceAsStream("/" + name + ".class")) {
            if (input == null) return false;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return mayContainTarget(bytes.toByteArray());
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean mayContainTarget(byte[] bytes) {
        String constants = new String(bytes, StandardCharsets.ISO_8859_1);
        return constants.contains(MODULE_ID) && constants.contains(DRAW_STRING);
    }

    public static byte[] transform(String name, byte[] bytes) {
        if (name == null || !name.startsWith(CLIENT_PREFIX) || bytes == null || !mayContainTarget(bytes)) {
            return null;
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        if (!name.equals(node.name) || !isHologramModule(node)) return null;

        MethodNode row = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode && HELPER.equals(((MethodInsnNode) insn).owner)) return null;
            }
            Type[] args = Type.getArgumentTypes(method.desc);
            if (args.length != 8 || args[0].getSort() != Type.OBJECT
                    || !method.desc.equals("(" + args[0].getDescriptor() + COMPONENT + "FFFZZZ)V")) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode draw = (MethodInsnNode) insn;
                if (DRAW_STRING.equals(draw.name)
                        && draw.desc.equals("(" + args[0].getDescriptor() + COMPONENT + "FFIZ)F")) {
                    row = method;
                    matches++;
                }
            }
        }
        if (matches != 1) {
            LunarConnectionDiagnostics.record("lunar.world_label.hook",
                    "matched=false class=" + name + " drawSites=" + matches);
            return null;
        }
        AbstractInsnNode[] original = row.instructions.toArray();
        LabelNode start = new LabelNode(), end = new LabelNode(), handler = new LabelNode();
        InsnList enter = new InsnList();
        enter.add(call("begin")); enter.add(start);
        row.instructions.insert(enter);
        for (AbstractInsnNode insn : original) if (insn.getOpcode() == Opcodes.RETURN) row.instructions.insertBefore(insn, call("end"));
        row.instructions.add(end); row.instructions.add(handler);
        row.instructions.add(new FrameNode(Opcodes.F_FULL, 0, new Object[0], 1, new Object[]{"java/lang/Throwable"}));
        row.instructions.add(call("end")); row.instructions.add(new InsnNode(Opcodes.ATHROW));
        row.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] transformed = writer.toByteArray();
        LunarConnectionDiagnostics.record("lunar.world_label.hook", "matched=true class=" + name);
        return transformed;
    }

    private static boolean isHologramModule(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!"getId".equals(method.name) || !"()Ljava/lang/String;".equals(method.desc)) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LdcInsnNode && MODULE_ID.equals(((LdcInsnNode) insn).cst)) return true;
            }
        }
        return false;
    }

    private static MethodInsnNode call(String method) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, method, "()V", false);
    }
}
