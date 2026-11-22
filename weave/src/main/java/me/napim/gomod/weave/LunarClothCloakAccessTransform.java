package me.napim.gomod.weave;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import me.napim.gomod.features.GomodLunarPlusLocker;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Lets the FullAccess settings force Lunar's native Cloth cloak physics option on or off. */
public final class LunarClothCloakAccessTransform {
    private static final String OPTION = "showClothCloaksForOthers";
    private static final String WARNING = "purchase_lunar_plus";
    private static final String HOOK = "me/napim/gomod/features/GomodLunarPlusLocker";
    private static final String SETTING = "(Ljava/lang/Integer;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";
    private static final String COSMETIC_DATA = "([Ljava/lang/Integer;)Ljava/lang/String;";
    private static final String COSMETIC_DETAIL_DATA = "(Ljava/lang/Integer;)Ljava/lang/String;";
    private static final String DATA_HOOK = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Integer;)Ljava/lang/String;";
    private static final String DETAIL_DATA_HOOK = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;)Ljava/lang/String;";
    private static final String VALUE_HOOK = "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;";

    private LunarClothCloakAccessTransform() { }

    public static boolean mayContain(String name, byte[] bytes) {
        if (name == null || bytes == null || !name.startsWith("com/moonsworth/lunar/")) return false;
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        return pool.contains(OPTION) && pool.contains(WARNING);
    }

    public static boolean isLoadedTarget(Class<?> type) {
        String name = type.getName().replace('.', '/');
        if (!name.startsWith("com/moonsworth/lunar/")) return false;
        try (InputStream input = type.getResourceAsStream("/" + name + ".class");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) return false;
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return mayContain(name, output.toByteArray());
        } catch (Exception ignored) {
            return false;
        }
    }

    public static byte[] transform(String name, byte[] bytes, Class<?> classBeingRedefined) {
        if (!mayContain(name, bytes)) return null;
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (HOOK.equals(call.owner) && ("allowClothCloak".equals(call.name)
                        || "captureNativeClothData".equals(call.name)
                        || "captureNativeClothDetailData".equals(call.name)
                        || "nativeClothOptionValue".equals(call.name))) return null;
            }
        }
        FieldInsnNode option = optionField(node);
        List<MethodInsnNode> gates = new ArrayList<MethodInsnNode>();
        MethodNode target = null;
        List<MethodNode> dataTargets = new ArrayList<MethodNode>();
        List<MethodNode> detailDataTargets = new ArrayList<MethodNode>();
        for (MethodNode method : node.methods) {
            if (COSMETIC_DATA.equals(method.desc) && (method.access & Opcodes.ACC_STATIC) != 0) {
                dataTargets.add(method);
            }
            if (COSMETIC_DETAIL_DATA.equals(method.desc) && (method.access & Opcodes.ACC_STATIC) != 0
                    && hasCallback(method, "requestData")) {
                detailDataTargets.add(method);
            }
        }
        if (option != null) for (MethodNode method : node.methods) {
            if (!SETTING.equals(method.desc) || (method.access & Opcodes.ACC_STATIC) == 0) continue;
            List<AbstractInsnNode> code = code(method);
            for (int i = 0; i + 14 < code.size(); i++) {
                if (matchesGate(node.name, option, code, i)) {
                    gates.add((MethodInsnNode) code.get(i + 6));
                    target = method;
                }
            }
        }
        if (gates.size() != 1 || target == null || dataTargets.size() != 1
                || detailDataTargets.size() != 1) {
            LunarConnectionDiagnostics.record("lunar.cloth_cloak_hook_rejected",
                    "class=" + name + " gates=" + gates.size() + " dataTargets=" + dataTargets.size()
                            + " detailDataTargets=" + detailDataTargets.size());
            return null;
        }
        MethodNode dataTarget = dataTargets.get(0);
        InsnList captureValue = new InsnList();
        captureValue.add(new LdcInsnNode(Type.getObjectType(node.name)));
        captureValue.add(new LdcInsnNode(target.name));
        captureValue.add(new VarInsnNode(Opcodes.ALOAD, 0));
        captureValue.add(new VarInsnNode(Opcodes.ALOAD, 1));
        captureValue.add(new VarInsnNode(Opcodes.ALOAD, 2));
        captureValue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "nativeClothOptionValue",
                VALUE_HOOK, false));
        captureValue.add(new VarInsnNode(Opcodes.ASTORE, 2));
        target.instructions.insert(captureValue);
        target.instructions.insert(gates.get(0), new MethodInsnNode(Opcodes.INVOKESTATIC,
                HOOK, "allowClothCloak", "(Z)Z", false));
        List<AbstractInsnNode> returns = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction : dataTarget.instructions) {
            if (instruction.getOpcode() == Opcodes.ARETURN) returns.add(instruction);
        }
        if (returns.isEmpty()) {
            LunarConnectionDiagnostics.record("lunar.cloth_cloak_hook_rejected",
                    "class=" + name + " reason=no_cosmetic_data_return");
            return null;
        }
        int resultLocal = dataTarget.maxLocals++;
        for (AbstractInsnNode instruction : returns) {
            InsnList captureData = new InsnList();
            captureData.add(new VarInsnNode(Opcodes.ASTORE, resultLocal));
            captureData.add(new LdcInsnNode(Type.getObjectType(node.name)));
            captureData.add(new LdcInsnNode(dataTarget.name));
            captureData.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
            captureData.add(new VarInsnNode(Opcodes.ALOAD, 0));
            captureData.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "captureNativeClothData",
                    DATA_HOOK, false));
            // Leave Lunar's original ARETURN in place. Adding our own ARETURN before it creates
            // an unreachable basic block after the injected return; the original class writer
            // preserves Lunar's stack-map table (COMPUTE_MAXS), so HotSpot rejects that block
            // with VerifyError during startup. The hook result is already on the stack here.
            dataTarget.instructions.insertBefore(instruction, captureData);
        }
        MethodNode detailDataTarget = detailDataTargets.get(0);
        List<AbstractInsnNode> detailReturns = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction : detailDataTarget.instructions) {
            if (instruction.getOpcode() == Opcodes.ARETURN) detailReturns.add(instruction);
        }
        if (detailReturns.isEmpty()) {
            LunarConnectionDiagnostics.record("lunar.cloth_cloak_hook_rejected",
                    "class=" + name + " reason=no_cosmetic_detail_data_return");
            return null;
        }
        int detailResultLocal = detailDataTarget.maxLocals++;
        for (AbstractInsnNode instruction : detailReturns) {
            InsnList captureDetailData = new InsnList();
            captureDetailData.add(new VarInsnNode(Opcodes.ASTORE, detailResultLocal));
            captureDetailData.add(new LdcInsnNode(Type.getObjectType(node.name)));
            captureDetailData.add(new LdcInsnNode(target.name));
            captureDetailData.add(new VarInsnNode(Opcodes.ALOAD, detailResultLocal));
            captureDetailData.add(new VarInsnNode(Opcodes.ALOAD, 0));
            captureDetailData.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                    "captureNativeClothDetailData", DETAIL_DATA_HOOK, false));
            // Keep the original return instruction so the existing stack-map table remains valid.
            detailDataTarget.instructions.insertBefore(instruction, captureDetailData);
        }
        MethodNode initializer = null;
        for (MethodNode method : node.methods) {
            if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                initializer = method;
                break;
            }
        }
        if (initializer == null) {
            initializer = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
            initializer.instructions.add(new InsnNode(Opcodes.RETURN));
            node.methods.add(initializer);
        }
        boolean initializerCapturePresent = false;
        for (AbstractInsnNode instruction : initializer.instructions) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (HOOK.equals(call.owner) && "captureNativeClothCallback".equals(call.name)
                    && "(Ljava/lang/Class;Ljava/lang/String;)V".equals(call.desc)) {
                initializerCapturePresent = true;
                break;
            }
        }
        if (!initializerCapturePresent) {
            for (AbstractInsnNode instruction : initializer.instructions.toArray()) {
                if (instruction.getOpcode() != Opcodes.RETURN) continue;
                InsnList capture = new InsnList();
                capture.add(new LdcInsnNode(Type.getObjectType(node.name)));
                capture.add(new LdcInsnNode(target.name));
                capture.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK,
                        "captureNativeClothCallback", "(Ljava/lang/Class;Ljava/lang/String;)V", false));
                initializer.instructions.insertBefore(instruction, capture);
            }
        }
        if (classBeingRedefined != null) {
            GomodLunarPlusLocker.captureNativeClothCallback(classBeingRedefined, target.name);
        }
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        LunarConnectionDiagnostics.record("lunar.cloth_cloak_hook_applied",
                "class=" + name + " gates=1 update=1 cosmeticDataReturns=" + returns.size()
                        + " cosmeticDetailDataReturns=" + detailReturns.size());
        return writer.toByteArray();
    }

    private static boolean hasCallback(MethodNode method, String callbackName) {
        return hasCallback(method.visibleAnnotations, callbackName)
                || hasCallback(method.invisibleAnnotations, callbackName);
    }

    private static boolean hasCallback(List<AnnotationNode> annotations, String callbackName) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (!"Lcom/moonsworth/webosr/javascript/CallbackJS;".equals(annotation.desc)
                    || annotation.values == null) continue;
            for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
                if ("value".equals(annotation.values.get(i))
                        && callbackName.equals(annotation.values.get(i + 1))) return true;
            }
        }
        return false;
    }

    /** Resolve the option's field from its stable localization key, not obfuscated member names. */
    private static FieldInsnNode optionField(ClassNode node) {
        FieldInsnNode result = null;
        int matches = 0;
        for (MethodNode method : node.methods) {
            if (!"<clinit>".equals(method.name)) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof LdcInsnNode) || !OPTION.equals(((LdcInsnNode) instruction).cst)) continue;
                AbstractInsnNode next = instruction.getNext();
                int remaining = 8;
                while (next != null && remaining-- > 0) {
                    if (next.getOpcode() == Opcodes.PUTSTATIC) {
                        FieldInsnNode field = (FieldInsnNode) next;
                        if (node.name.equals(field.owner) && field.desc.startsWith("L")) {
                            result = field;
                            matches++;
                        }
                        break;
                    }
                    next = next.getNext();
                }
            }
        }
        return matches == 1 ? result : null;
    }

    private static boolean matchesGate(String owner, FieldInsnNode option, List<AbstractInsnNode> c, int i) {
        if (!(c.get(i) instanceof FieldInsnNode)) return false;
        FieldInsnNode field = (FieldInsnNode) c.get(i);
        if (field.getOpcode() != Opcodes.GETSTATIC || !owner.equals(field.owner)
                || !option.name.equals(field.name) || !option.desc.equals(field.desc)) return false;
        if (!call(c.get(i + 1), Opcodes.INVOKEVIRTUAL, null, "getId", "()Ljava/lang/String;")
                || !call(c.get(i + 2), Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z")
                || c.get(i + 3).getOpcode() != Opcodes.IFEQ
                || !nativeCall(c.get(i + 4), Opcodes.INVOKESTATIC, false)
                || !nativeCall(c.get(i + 5), Opcodes.INVOKEVIRTUAL, false)
                || !nativeCall(c.get(i + 6), Opcodes.INVOKEVIRTUAL, true)
                || c.get(i + 7).getOpcode() != Opcodes.IFEQ) return false;
        int warningStart = c.indexOf(nextCode(((JumpInsnNode) c.get(i + 7)).label));
        int optionEnd = c.indexOf(nextCode(((JumpInsnNode) c.get(i + 3)).label));
        if (warningStart != i + 14 || optionEnd <= warningStart || optionEnd - warningStart > 24) return false;
        // Native success branch: get settings, parse requested value, set cloth flag, then join.
        if (!(c.get(i + 8) instanceof VarInsnNode) || c.get(i + 8).getOpcode() != Opcodes.ALOAD
                || !nativeCall(c.get(i + 9), Opcodes.INVOKEVIRTUAL, false)
                || !(c.get(i + 10) instanceof VarInsnNode) || c.get(i + 10).getOpcode() != Opcodes.ALOAD
                || ((VarInsnNode) c.get(i + 10)).var != 2
                || !call(c.get(i + 11), Opcodes.INVOKESTATIC, "java/lang/Boolean", "parseBoolean", "(Ljava/lang/String;)Z")
                || !(c.get(i + 12) instanceof MethodInsnNode)
                || !"(Z)V".equals(((MethodInsnNode) c.get(i + 12)).desc)
                || c.get(i + 13).getOpcode() != Opcodes.GOTO) return false;
        int warnings = 0;
        for (int j = warningStart; j < optionEnd; j++) {
            if (c.get(j) instanceof LdcInsnNode && WARNING.equals(((LdcInsnNode) c.get(j)).cst)) warnings++;
        }
        return warnings == 1;
    }

    private static boolean nativeCall(AbstractInsnNode instruction, int opcode, boolean bool) {
        if (!(instruction instanceof MethodInsnNode)) return false;
        MethodInsnNode call = (MethodInsnNode) instruction;
        return call.getOpcode() == opcode && call.owner.startsWith("com/moonsworth/lunar/")
                && (bool ? "()Z".equals(call.desc) : call.desc.startsWith("()L"));
    }

    private static boolean call(AbstractInsnNode instruction, int opcode, String owner, String name, String desc) {
        if (!(instruction instanceof MethodInsnNode)) return false;
        MethodInsnNode call = (MethodInsnNode) instruction;
        return call.getOpcode() == opcode && (owner == null || owner.equals(call.owner))
                && name.equals(call.name) && desc.equals(call.desc);
    }

    private static List<AbstractInsnNode> code(MethodNode method) {
        List<AbstractInsnNode> code = new ArrayList<AbstractInsnNode>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction.getOpcode() >= 0) code.add(instruction);
        return code;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        while (instruction != null && instruction.getOpcode() < 0) instruction = instruction.getNext();
        return instruction;
    }
}
