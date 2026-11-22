package me.napim.gomod.weave;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Passive final-class input taps. No schema changes, native hooks, cancellation or input writes. */
public final class LunarMenuDebugTransform {
    private static final String MC = "net/minecraft/client/Minecraft";
    private static final String MOVEMENT = "net/minecraft/util/MovementInputFromOptions";
    private static final String KEYS = "net/minecraft/client/settings/KeyBinding";
    private static final String BROWSER = "com/moonsworth/webosr/wrappers/Browser";
    public static final String UI = "com/moonsworth/lunar/client/COOHHRRCORRHIRIHRORIHRHICCIOIH/"
            + "IOCIHHIRIROCOIROOCORCCIOOIIIRR/IOCIHHIRIROCOIROOCORCCIOOIIIRR";
    private static final String HELPER = "me/napim/gomod/features/LunarMenuDebugCapture";
    private LunarMenuDebugTransform() { }

    public static boolean isTarget(String name) {
        return MC.equals(name) || MOVEMENT.equals(name) || KEYS.equals(name) || BROWSER.equals(name) || UI.equals(name);
    }

    public static byte[] transform(String name, byte[] bytes) {
        if (GomodReleaseBuild.PUBLIC_RELEASE || !isTarget(name) || bytes == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes); ClassNode node = new ClassNode(); reader.accept(node, 0);
            int patched = 0;
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || alreadyPatched(method)) continue;
                if (MC.equals(name) && "runTick".equals(method.name) && "()V".equals(method.desc)) {
                    method.instructions.insert(sample(false, false));
                    for (AbstractInsnNode insn : method.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN)
                        method.instructions.insertBefore(insn, sample(false, true));
                    patched++;
                } else if (MOVEMENT.equals(name) && "updatePlayerMoveState".equals(method.name) && "()V".equals(method.desc)) {
                    method.instructions.insert(sample(true, false));
                    for (AbstractInsnNode insn : method.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN)
                        method.instructions.insertBefore(insn, sample(true, true));
                    patched++;
                } else if (KEYS.equals(name) && "setKeyBindState".equals(method.name) && "(IZ)V".equals(method.desc)
                        && (method.access & Opcodes.ACC_STATIC) != 0) {
                    InsnList tap = new InsnList(); tap.add(new VarInsnNode(Opcodes.ILOAD, 0)); tap.add(new VarInsnNode(Opcodes.ILOAD, 1));
                    tap.add(call("recordKeyWrite", "(IZ)V")); method.instructions.insert(tap); patched++;
                } else if (BROWSER.equals(name) && "triggerKeyEvent".equals(method.name)
                        && "(Ljava/lang/String;ILjava/lang/String;ILcom/moonsworth/webosr/input/Keyboard$Action;I)V".equals(method.desc)) {
                    // Ignore both String arguments, codes, modifiers and the Browser reference.
                    InsnList tap = new InsnList(); tap.add(new VarInsnNode(Opcodes.ALOAD, 5));
                    tap.add(call("recordBrowserKey", "(Ljava/lang/Object;)V")); method.instructions.insert(tap); patched++;
                } else if (BROWSER.equals(name) && "triggerMouseEvent".equals(method.name)
                        && "(Lcom/moonsworth/webosr/input/Mouse$Button;Lcom/moonsworth/webosr/input/Mouse$Action;III)V".equals(method.desc)) {
                    InsnList tap = new InsnList(); tap.add(new VarInsnNode(Opcodes.ALOAD, 2));
                    tap.add(call("recordBrowserMouse", "(Ljava/lang/Object;)V")); method.instructions.insert(tap); patched++;
                } else if (UI.equals(name)) {
                    boolean tapped = false;
                    for (AbstractInsnNode insn : method.instructions.toArray()) {
                        if (insn instanceof MethodInsnNode) {
                            MethodInsnNode invoke = (MethodInsnNode) insn;
                            if ("bridge$onTextInputFocusChange".equals(invoke.name) && "(Ljava/lang/Object;Z)V".equals(invoke.desc)) {
                                InsnList tap = new InsnList(); tap.add(new InsnNode(Opcodes.DUP)); tap.add(call("recordBridgeCapture", "(Z)V"));
                                method.instructions.insertBefore(insn, tap); tapped = true;
                            }
                        }
                        if ("COICHORHCOOIORORIHORCROHIOOCIR".equals(method.name) && "()Z".equals(method.desc)
                                && insn.getOpcode() == Opcodes.IRETURN) {
                            InsnList tap = new InsnList(); tap.add(new InsnNode(Opcodes.DUP)); tap.add(call("recordJavaCapture", "(Z)V"));
                            method.instructions.insertBefore(insn, tap); tapped = true;
                        }
                    }
                    if (tapped) patched++;
                }
            }
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.menu_debug.transform", name,
                    "target=" + name + " tappedMethods=" + patched + " diagnosticOnly=true noNativeQueries=true");
            if (patched == 0) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); node.accept(writer); return writer.toByteArray();
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.menu_debug.transform_failed", name, "exception=" + error.getClass().getSimpleName());
            return null;
        }
    }
    private static InsnList sample(boolean movement, boolean completed) {
        InsnList tap = new InsnList();
        if (movement) tap.add(new VarInsnNode(Opcodes.ALOAD, 0));
        tap.add(new InsnNode(completed ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
        tap.add(call(movement ? "sampleMovement" : "sampleTick", movement ? "(Ljava/lang/Object;Z)V" : "(Z)V")); return tap;
    }
    private static MethodInsnNode call(String method, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, method, descriptor, false);
    }
    private static boolean alreadyPatched(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode && HELPER.equals(((MethodInsnNode) insn).owner)) return true;
        return false;
    }
}
