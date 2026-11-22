package me.napim.gomod.weave;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Fixes Quickplay per-game keybind and favorite persistence in Lunar using 100% structural
 * bytecode matching:
 * <ul>
 *   <li>Registers each Quickplay instance + keybinds map with {@link LunarQuickplayPersistenceBridge}</li>
 *   <li>Appends missing {@code qpKeyBinds}/{@code qpFavorites} in Quickplay's save method even when
 *       {@code gameList} is null or empty</li>
 *   <li>Extends Lunar's recursive mod-option dirty check in {@code SettingsManager} to include
 *       Quickplay's keybind map options</li>
 * </ul>
 */
public final class LunarQuickplayPersistenceTransform {
    private static final String HELPER = "me/napim/gomod/weave/LunarQuickplayPersistenceBridge";
    private static final String JSON_OBJ_METHOD_DESC = "(Lcom/google/gson/JsonObject;)V";

    private LunarQuickplayPersistenceTransform() { }

    public static boolean mayContain(String className, byte[] bytes) {
        if (className == null || bytes == null || bytes.length < 32) return false;
        if (!className.startsWith("com/moonsworth/lunar/") && !className.startsWith("me/napim/")) {
            return false;
        }
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        return isQuickplayBytes(pool) || isSettingsManagerBytes(pool);
    }

    public static boolean isLoadedTarget(Class<?> type) {
        if (type == null) return false;
        String name = type.getName().replace('.', '/');
        if (!name.startsWith("com/moonsworth/lunar/")) return false;
        try (InputStream in = type.getResourceAsStream("/" + name + ".class")) {
            if (in == null) return false;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
            }
            return mayContain(name, out.toByteArray());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static byte[] transform(String className, byte[] bytes) {
        if (!mayContain(className, bytes)) return null;
        try {
            String pool = new String(bytes, StandardCharsets.ISO_8859_1);
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);

            boolean changed = false;
            if (isQuickplayBytes(pool) && isQuickplayNode(node)) {
                changed = patchQuickplay(node);
            } else if (isSettingsManagerBytes(pool)) {
                changed = patchSettingsManager(node);
            }

            if (!changed) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable failure) {
            System.err.println("[gomod] Lunar Quickplay persistence transform failed for " + className + ": "
                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return null;
        }
    }

    private static boolean isQuickplayBytes(String pool) {
        return pool.contains("qpKeyBinds")
                && pool.contains("qpFavorites")
                && pool.contains("quickplayUIKeybind");
    }

    private static boolean isSettingsManagerBytes(String pool) {
        return pool.contains("controls")
                && pool.contains("general")
                && pool.contains("performance");
    }

    private static boolean isQuickplayNode(ClassNode node) {
        return findFieldByDesc(node, "Ljava/util/Map;") != null
                && findSaveMethod(node) != null;
    }

    private static boolean patchQuickplay(ClassNode node) {
        FieldNode mapField = findFieldByDesc(node, "Ljava/util/Map;");
        FieldNode favField = findFieldByDesc(node, "Ljava/util/Set;");
        if (mapField == null || favField == null) return false;

        discoverAndRegisterOptionSaveMethod(node);

        boolean changed = false;
        for (MethodNode method : node.methods) {
            if ("<init>".equals(method.name) && !alreadyCalls(method, "registerQuickplayInstance")) {
                for (AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getOpcode() != Opcodes.RETURN) continue;
                    InsnList reg = new InsnList();
                    reg.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    reg.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    reg.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, mapField.name, mapField.desc));
                    reg.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "registerQuickplayInstance",
                            "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                    method.instructions.insertBefore(insn, reg);
                    changed = true;
                }
            }
        }

        MethodNode load = findLoadMethod(node);
        if (load != null && !alreadyCalls(load, "registerQuickplayInstance")) {
            for (AbstractInsnNode insn : load.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.RETURN) continue;
                InsnList reg = new InsnList();
                reg.add(new VarInsnNode(Opcodes.ALOAD, 0));
                reg.add(new VarInsnNode(Opcodes.ALOAD, 0));
                reg.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, mapField.name, mapField.desc));
                reg.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "registerQuickplayInstance",
                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false));
                load.instructions.insertBefore(insn, reg);
                changed = true;
            }
        }

        MethodNode save = findSaveMethod(node);
        if (save != null && !alreadyCalls(save, "persistExtraQuickplayState")) {
            for (AbstractInsnNode insn : save.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.RETURN) continue;
                InsnList persist = new InsnList();
                persist.add(new VarInsnNode(Opcodes.ALOAD, 0));
                persist.add(new VarInsnNode(Opcodes.ALOAD, 1));
                persist.add(new VarInsnNode(Opcodes.ALOAD, 0));
                persist.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, mapField.name, mapField.desc));
                persist.add(new VarInsnNode(Opcodes.ALOAD, 0));
                persist.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, favField.name, favField.desc));
                persist.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "persistExtraQuickplayState",
                        "(Ljava/lang/Object;Lcom/google/gson/JsonObject;Ljava/lang/Object;Ljava/lang/Object;)V",
                        false));
                save.instructions.insertBefore(insn, persist);
                changed = true;
            }
        }
        if (changed) {
            System.out.println("[gomod] Patched Quickplay per-game keybinds/favorites persistence in " + node.name);
        }
        return changed;
    }

    private static void discoverAndRegisterOptionSaveMethod(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0) continue;
            if (!method.desc.endsWith("Lcom/google/gson/JsonObject;)V")
                    || JSON_OBJ_METHOD_DESC.equals(method.desc)) {
                continue;
            }
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (JSON_OBJ_METHOD_DESC.equals(call.desc)
                        && !node.name.equals(call.owner)
                        && !"load".equals(call.name)
                        && !"<init>".equals(call.name)) {
                    LunarQuickplayPersistenceBridge.registerOptionSaveMethod(call.name);
                    return;
                }
            }
        }
    }

    private static boolean patchSettingsManager(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) != 0 || alreadyCalls(method, "onModOptionDirty")) continue;
            if (!isRecursiveModDirtyCheck(node, method)) continue;

            String configSaveMethod = findConfigFileSaveMethod(method);
            if (configSaveMethod == null) continue;

            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (insn.getOpcode() != Opcodes.IRETURN) continue;
                InsnList hook = new InsnList();
                hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 2));
                hook.add(new VarInsnNode(Opcodes.ALOAD, 3));
                hook.add(new LdcInsnNode(configSaveMethod));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "onModOptionDirty",
                        "(ZLjava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Z", false));
                method.instructions.insertBefore(insn, hook);
            }
            System.out.println("[gomod] Connected Quickplay keybinds to Lunar SettingsManager dirty check in " + node.name);
            return true;
        }
        return false;
    }

    private static boolean isRecursiveModDirtyCheck(ClassNode node, MethodNode method) {
        if (!method.desc.endsWith(")Z") || !method.desc.startsWith("(L")) return false;
        int paramCount = countObjectParams(method.desc);
        if (paramCount != 3) return false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (node.name.equals(call.owner) && method.name.equals(call.name) && method.desc.equals(call.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String findConfigFileSaveMethod(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (!"()V".equals(call.desc)) continue;
            AbstractInsnNode prev = previousReal(call);
            if (prev instanceof VarInsnNode && prev.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) prev).var == 3) {
                return call.name;
            }
        }
        return null;
    }

    private static int countObjectParams(String desc) {
        int end = desc.indexOf(')');
        if (end < 0) return -1;
        int count = 0;
        int idx = 1;
        while (idx < end) {
            if (desc.charAt(idx) != 'L') return -1;
            int semi = desc.indexOf(';', idx);
            if (semi < 0 || semi >= end) return -1;
            count++;
            idx = semi + 1;
        }
        return count;
    }

    private static FieldNode findFieldByDesc(ClassNode node, String desc) {
        for (FieldNode field : node.fields) {
            if ((field.access & Opcodes.ACC_STATIC) == 0 && desc.equals(field.desc)) {
                return field;
            }
        }
        return null;
    }

    private static MethodNode findLoadMethod(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0
                    && "load".equals(method.name)
                    && JSON_OBJ_METHOD_DESC.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static MethodNode findSaveMethod(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ((method.access & Opcodes.ACC_STATIC) == 0
                    && !"load".equals(method.name)
                    && JSON_OBJ_METHOD_DESC.equals(method.desc)) {
                return method;
            }
        }
        return null;
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode insn) {
        AbstractInsnNode previous = insn.getPrevious();
        while (previous != null && (previous.getType() == AbstractInsnNode.LABEL
                || previous.getType() == AbstractInsnNode.LINE || previous.getType() == AbstractInsnNode.FRAME)) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static boolean alreadyCalls(MethodNode method, String helperName) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn instanceof MethodInsnNode && HELPER.equals(((MethodInsnNode) insn).owner)
                    && helperName.equals(((MethodInsnNode) insn).name)) {
                return true;
            }
        }
        return false;
    }
}
