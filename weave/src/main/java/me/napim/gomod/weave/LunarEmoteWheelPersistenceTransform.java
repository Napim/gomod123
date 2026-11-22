package me.napim.gomod.weave;

import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Finds native user commits and RPC delivery from protobuf operations, independent of Lunar's renamed managers. */
public final class LunarEmoteWheelPersistenceTransform {
    private static final String HELPER = "me/napim/gomod/features/LunarEmoteWheelPersistence";
    private static final String EMOTE = "com/lunarclient/websocket/emote/v1/EquippedEmote";
    private static final String REQUEST = "com/lunarclient/websocket/emote/v1/UpdateEquippedEmotesRequest";
    private static final String SPRAY_REQUEST = "com/lunarclient/websocket/spray/v1/UpdateEquippedSpraysRequest";
    private static final String RPC_RESPONSE = "com/lunarclient/websocket/protocol/v1/WebSocketRpcResponse";
    private LunarEmoteWheelPersistenceTransform() { }

    public static boolean mayContain(String name, byte[] bytes) {
        if (name == null || bytes == null
                || !name.startsWith("com/moonsworth/lunar/")) return false;
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        return pool.contains(REQUEST) || pool.contains(SPRAY_REQUEST) || pool.contains(RPC_RESPONSE);
    }

    /** Read class bytes without initializing an already-loaded native manager. */
    public static boolean isLoadedTarget(Class<?> type) {
        if (type == null) return false;
        String name = type.getName().replace('.', '/');
        if (!name.startsWith("com/moonsworth/lunar/")) return false;
        try (InputStream input = type.getResourceAsStream("/" + name + ".class")) {
            if (input == null) return false;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return mayContain(name, output.toByteArray());
        } catch (Exception ignored) { return false; }
    }

    public static byte[] transform(String name, byte[] bytes) {
        if (!mayContain(name, bytes)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode(); reader.accept(node, 0);
            for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions)
                if (insn instanceof MethodInsnNode && HELPER.equals(((MethodInsnNode) insn).owner)) return null;

            Set<String> converters = new HashSet<String>(), senders = new HashSet<String>();
            Set<String> spraySenders = new HashSet<String>();
            FieldInsnNode spraySlots = null;
            int sprayFields = 0;
            for (MethodNode method : node.methods) {
                if ((method.access & Opcodes.ACC_STATIC) != 0 && method.desc.endsWith("L" + EMOTE + ";")
                        && contains(method, EMOTE, "newBuilder") && contains(method, EMOTE + "$Builder", "setEmoteId")
                        && contains(method, EMOTE + "$Builder", "setSlotNumber")
                        && contains(method, EMOTE + "$Builder", "build")) converters.add(method.name + method.desc);
                if (contains(method, REQUEST, "newBuilder")
                        && contains(method, REQUEST + "$Builder", "addAllEquippedEmotes")
                        && contains(method, "com/lunarclient/websocket/emote/v1/EmoteService$Interface", "updateEquippedEmotes"))
                    senders.add(method.name + method.desc);
                if ((method.access & Opcodes.ACC_STATIC) == 0 && contains(method, SPRAY_REQUEST, "newBuilder")
                        && contains(method, SPRAY_REQUEST + "$Builder", "addAllEquippedSprays")
                        && contains(method, "com/lunarclient/websocket/spray/v1/SprayService$Interface", "updateEquippedSprays")) {
                    for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) insn;
                        AbstractInsnNode field = previous(insn), receiver = previous(field);
                        if (call.owner.equals(SPRAY_REQUEST + "$Builder") && call.name.equals("addAllEquippedSprays")
                                && field instanceof FieldInsnNode && field.getOpcode() == Opcodes.GETFIELD
                                && ((FieldInsnNode) field).owner.equals(node.name) && ((FieldInsnNode) field).desc.equals("Ljava/util/Set;")
                                && receiver instanceof VarInsnNode && receiver.getOpcode() == Opcodes.ALOAD
                                && ((VarInsnNode) receiver).var == 0) {
                            spraySlots = (FieldInsnNode) field; sprayFields++;
                            spraySenders.add(method.name + method.desc);
                        }
                    }
                }
            }

            MethodNode commit = null, sprayCommit = null, delivery = null;
            VarInsnNode slotStore = null;
            MethodInsnNode responseBuild = null;
            int commits = 0, sprayCommits = 0, deliveries = 0;
            for (MethodNode method : node.methods) {
                if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0
                        && method.desc.equals("()V")) {
                    VarInsnNode store = null; int stores = 0;
                    boolean sends = false, sendsSprays = false, connection = false, connectionAfterSlots = false, optionalSend = false;
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn instanceof InvokeDynamicInsnNode
                                && references((InvokeDynamicInsnNode) insn, node.name, senders)) sends = true;
                        if (insn instanceof InvokeDynamicInsnNode
                                && references((InvokeDynamicInsnNode) insn, node.name, spraySenders)) sendsSprays = true;
                        if (!(insn instanceof MethodInsnNode)) continue;
                        MethodInsnNode call = (MethodInsnNode) insn;
                        AbstractInsnNode map = previous(insn), converter = previous(map), next = next(insn);
                        if (call.owner.equals("java/util/stream/Stream") && call.name.equals("toList")
                                && call.desc.equals("()Ljava/util/List;") && map instanceof MethodInsnNode
                                && ((MethodInsnNode) map).owner.equals("java/util/stream/Stream")
                                && ((MethodInsnNode) map).name.equals("map") && converter instanceof InvokeDynamicInsnNode
                                && references((InvokeDynamicInsnNode) converter, node.name, converters)
                                && next instanceof VarInsnNode && next.getOpcode() == Opcodes.ASTORE) {
                            store = (VarInsnNode) next; stores++;
                        }
                        if (store != null && call.desc.equals("()Ljava/util/Optional;")) connectionAfterSlots = true;
                        if (call.desc.equals("()Ljava/util/Optional;")) connection = true;
                        if (call.owner.equals("java/util/Optional") && call.name.equals("ifPresent")
                                && call.desc.equals("(Ljava/util/function/Consumer;)V")) optionalSend = true;
                    }
                    if (stores == 1 && sends && connectionAfterSlots && optionalSend) {
                        commit = method; slotStore = store; commits++;
                    }
                    if (sendsSprays && connection && optionalSend) { sprayCommit = method; sprayCommits++; }
                }
                if (method.desc.equals("(L" + RPC_RESPONSE + ";)V") && contains(method, RPC_RESPONSE, "getOutput")) {
                    MethodInsnNode build = null; int builds = 0;
                    for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) insn;
                        if (call.owner.equals("com/google/protobuf/Message$Builder") && call.name.equals("build")
                                && call.desc.equals("()Lcom/google/protobuf/Message;")) { build = call; builds++; }
                    }
                    if (builds == 1) { delivery = method; responseBuild = build; deliveries++; }
                }
            }

            if (commits == 1) {
                InsnList tap = new InsnList(); tap.add(new VarInsnNode(Opcodes.ALOAD, 0));
                tap.add(new VarInsnNode(Opcodes.ALOAD, slotStore.var));
                tap.add(call("rememberUserWheel", "(Ljava/lang/Object;Ljava/util/List;)V"));
                commit.instructions.insert(slotStore, tap);
            }
            if (sprayFields == 1 && sprayCommits == 1) {
                InsnList tap = new InsnList(); tap.add(new VarInsnNode(Opcodes.ALOAD, 0));
                tap.add(new VarInsnNode(Opcodes.ALOAD, 0));
                tap.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, spraySlots.name, spraySlots.desc));
                tap.add(call("rememberUserSprayWheel", "(Ljava/lang/Object;Ljava/lang/Iterable;)V"));
                sprayCommit.instructions.insert(tap);
            }
            if (deliveries == 1) {
                InsnList tap = new InsnList(); tap.add(call("restoreLogin", "(Ljava/lang/Object;)Ljava/lang/Object;"));
                tap.add(new TypeInsnNode(Opcodes.CHECKCAST, "com/google/protobuf/Message"));
                delivery.instructions.insert(responseBuild, tap);
            }
            LunarConnectionDiagnostics.recordOnceAfterStart("emote_wheel.native_persistence_transform", name,
                    "localCommitMatches=" + commits + " sprayCommitMatches=" + sprayCommits
                            + " sprayFieldMatches=" + sprayFields + " loginDeliveryMatches=" + deliveries
                            + " saveBeforeConnection=true responsePayload=omitted");
            if (commits != 1 && !(sprayFields == 1 && sprayCommits == 1) && deliveries != 1) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer); return writer.toByteArray();
        } catch (Throwable failure) {
            LunarConnectionDiagnostics.recordOnceAfterStart("emote_wheel.native_persistence_transform_failed", name,
                    "exception=" + failure.getClass().getSimpleName()); return null;
        }
    }

    private static boolean references(InvokeDynamicInsnNode invocation, String owner, Set<String> methods) {
        for (Object argument : invocation.bsmArgs) if (argument instanceof Handle) {
            Handle handle = (Handle) argument;
            if (owner.equals(handle.getOwner()) && methods.contains(handle.getName() + handle.getDesc())) return true;
        }
        return false;
    }
    private static boolean contains(MethodNode method, String owner, String name) {
        for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) insn;
            if (owner.equals(call.owner) && name.equals(call.name)) return true;
        }
        return false;
    }
    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        if (insn == null) return null;
        do { insn = insn.getPrevious(); } while (insn != null && insn.getOpcode() < 0);
        return insn;
    }
    private static AbstractInsnNode next(AbstractInsnNode insn) {
        do { insn = insn.getNext(); } while (insn != null && insn.getOpcode() < 0);
        return insn;
    }
    private static MethodInsnNode call(String name, String descriptor) {
        return new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, name, descriptor, false);
    }
}
