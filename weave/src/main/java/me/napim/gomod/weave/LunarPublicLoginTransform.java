package me.napim.gomod.weave;

import java.nio.charset.StandardCharsets;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Derives the Assets frame and RPC delivery boundaries from stable protobuf operations. */
public final class LunarPublicLoginTransform {
    private static final String HOOK = "me/napim/gomod/features/LunarPublicLoginGuard";
    private LunarPublicLoginTransform() { }
    public static boolean mayContain(String name, byte[] bytes) {
        if (name == null || bytes == null || !name.startsWith("com/moonsworth/lunar/")) return false;
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        return pool.contains("protocol/v1/ClientboundWebSocketMessage") || pool.contains("protocol/v1/WebSocketRpcResponse");
    }
    public static byte[] transform(String name, byte[] bytes) {
        if (!mayContain(name, bytes)) return null;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions)
            if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).owner.equals(HOOK)) return null;
        boolean changed = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("onOpen") && method.desc.equals("(Lorg/java_websocket/handshake/ServerHandshake;)V")) {
                InsnList open = new InsnList(); open.add(new VarInsnNode(Opcodes.ALOAD, 0));
                open.add(call("rememberSocketAccount", "(Ljava/lang/Object;)V")); method.instructions.insert(open); changed = true;
            }
            if (method.name.equals("onMessage") && method.desc.equals("(Ljava/nio/ByteBuffer;)V")
                    && contains(method, "com/lunarclient/websocket/protocol/v1/ClientboundWebSocketMessage", "parseFrom")) {
                InsnList start = new InsnList(); start.add(new VarInsnNode(Opcodes.ALOAD, 0));
                start.add(call("beginFrame", "(Ljava/lang/Object;)V"));
                LabelNode begin = new LabelNode(); start.add(begin); method.instructions.insert(start);
                for (AbstractInsnNode insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                    MethodInsnNode invocation = (MethodInsnNode) insn;
                    if (!invocation.owner.equals("com/google/protobuf/Any") || !invocation.name.equals("unpack")) continue;
                    InsnList patch = new InsnList(); patch.add(call("filterPush", "(Ljava/lang/Object;)Ljava/lang/Object;"));
                    patch.add(new TypeInsnNode(Opcodes.CHECKCAST, "com/google/protobuf/Message")); method.instructions.insert(insn, patch);
                }
                for (AbstractInsnNode insn : method.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN)
                    method.instructions.insertBefore(insn, call("endFrame", "()V"));
                LabelNode end = new LabelNode(), handler = new LabelNode(); method.instructions.add(end); method.instructions.add(handler);
                method.instructions.add(new FrameNode(Opcodes.F_FULL, 2, new Object[]{node.name, "java/nio/ByteBuffer"},
                        1, new Object[]{"java/lang/Throwable"}));
                method.instructions.add(call("endFrame", "()V")); method.instructions.add(new InsnNode(Opcodes.ATHROW));
                method.tryCatchBlocks.add(new TryCatchBlockNode(begin, end, handler, null)); changed = true;
            }
            if (method.desc.equals("(Lcom/lunarclient/websocket/protocol/v1/WebSocketRpcResponse;)V")
                    && contains(method, "com/lunarclient/websocket/protocol/v1/WebSocketRpcResponse", "getOutput")) {
                int builds = 0;
                for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode) {
                    MethodInsnNode invocation = (MethodInsnNode) insn;
                    if (invocation.owner.equals("com/google/protobuf/Message$Builder") && invocation.name.equals("build")) builds++;
                }
                if (builds != 1) continue;
                for (AbstractInsnNode insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                    MethodInsnNode invocation = (MethodInsnNode) insn;
                    if (!invocation.owner.equals("com/google/protobuf/Message$Builder") || !invocation.name.equals("build")) continue;
                    InsnList patch = new InsnList(); patch.add(call("filterLogin", "(Ljava/lang/Object;)Ljava/lang/Object;"));
                    patch.add(new TypeInsnNode(Opcodes.CHECKCAST, "com/google/protobuf/Message")); method.instructions.insert(insn, patch); changed = true;
                }
            }
        }
        if (!changed) return null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
        System.out.println("[gomod] Public Locker official-ownership boundary installed: " + name);
        return writer.toByteArray();
    }
    private static boolean contains(MethodNode method, String owner, String name) {
        for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) insn; if (call.owner.equals(owner) && call.name.equals(name)) return true;
        }
        return false;
    }
    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, name, descriptor, false);
    }
}
