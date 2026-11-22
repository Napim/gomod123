package me.napim.gomod.weave;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Binds color integration to native protobuf operations and Locker keys, never remapped names. */
public final class LunarColorCompatibilityTransform {
    private static final String HOOK = "me/napim/gomod/features/LunarColorHooks";
    private static final String RUNTIME = "me/napim/gomod/features/LunarColorRuntime";
    private static final String SERVICE = "Lcom/lunarclient/websocket/cosmetic/v2/CosmeticService$Interface;";
    private static final String PUSH = "(Ljava/util/UUID;Lcom/lunarclient/websocket/cosmetic/v2/PlayerCosmeticsPushV2;I)V";
    private LunarColorCompatibilityTransform() { }

    public static boolean mayContain(String name, byte[] bytes) {
        if (name == null || bytes == null || !name.startsWith("com/moonsworth/lunar/")) return false;
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        return pool.contains("availableColors") || pool.contains("cosmetic/v2/CosmeticService")
                || pool.contains("cosmetic/v2/PlayerCosmeticsPushV2") || pool.contains("cosmetic/v2/LoadTabLogosResponse");
    }
    public static boolean isLoadedTarget(Class<?> type) {
        String name = type.getName().replace('.', '/');
        if (!name.startsWith("com/moonsworth/lunar/")) return false;
        try (InputStream input = type.getResourceAsStream("/" + name + ".class")) {
            if (input == null) return false;
            ClassReader reader = new ClassReader(input);
            ClassNode node = new ClassNode(); reader.accept(node, 0);
            return hasCandidate(node);
        } catch (Throwable ignored) { return false; }
    }
    private static boolean hasCandidate(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (method.desc.equals(PUSH) || outfitHandler(node, method) || method.desc.equals("(Lcom/lunarclient/websocket/cosmetic/v2/LoadTabLogosResponse;)V")
                    || publisher(method) || selection(method)) return true;
        }
        for (FieldNode field : node.fields) if (field.desc.equals(SERVICE)) return true;
        return false;
    }
    public static byte[] transform(String name, byte[] bytes) {
        if (!mayContain(name, bytes)) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode(); reader.accept(node, 0);
            boolean changed = false;
            int publishers = 0, selections = 0, pushes = 0, outfits = 0, outfitSelects = 0, outfitBadgeSyncs = 0;
            for (MethodNode method : node.methods) {
                if (publisher(method)) publishers++;
                if (selection(method)) selections++;
                if (profileHandler(method)) pushes++;
                if (outfitHandler(node, method)) outfits++;
                if (outfitSelectBadgeHandler(method)) outfitSelects++;
            }
            for (MethodNode method : node.methods) {
                if (alreadyHooked(method)) continue;
                boolean instance = (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) == 0;
                if (instance && outfits == 1 && (outfitHandler(node, method)
                        || method.desc.equals("(Ljava/util/List;Lcom/lunarclient/websocket/cosmetic/v2/OutfitTree;)V"))) {
                    InsnList capture = new InsnList(); capture.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    capture.add(call("me/napim/gomod/features/LunarFullDisguise", "onOutfitManagerAvailable", "(Ljava/lang/Object;)V"));
                    method.instructions.insert(capture); changed = true;
                }
                if (outfits == 1 && outfitSelects == 1 && outfitSelectBadgeHandler(method)) {
                    if (hookOutfitSelectBadge(method)) {
                        outfitBadgeSyncs++;
                        changed = true;
                    }
                }
                if (instance && method.name.equals("onOpen") && method.desc.equals("(Lorg/java_websocket/handshake/ServerHandshake;)V")
                        && serviceFields(node) == 1) {
                    InsnList hook = new InsnList(); hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    hook.add(call(RUNTIME, "captureSocket", "(Ljava/lang/Object;)V"));
                    method.instructions.insert(hook); changed = true;
                }
                if (publishers == 1 && publisher(method)) {
                    List<MethodInsnNode> setters = setters(method);
                    Set<String> signatures = new HashSet<String>();
                    for (MethodInsnNode setter : setters) signatures.add(setter.owner + "." + setter.name);
                    if (setters.size() != 3 || signatures.size() != 1) continue;
                    for (MethodInsnNode setter : setters) {
                        method.instructions.insertBefore(setter, new LdcInsnNode(setter.name));
                        method.instructions.set(setter, call("me/napim/gomod/mixins/LunarLockerStateBridge", "publish",
                                "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/String;)V"));
                    }
                    InsnList capture = new InsnList(); capture.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    capture.add(new LdcInsnNode(method.name));
                    capture.add(call(HOOK, "captureLocker", "(Ljava/lang/Object;Ljava/lang/String;)V"));
                    method.instructions.insert(capture); changed = true;
                    // Capture the preference receiver used by the same native active-color getter.
                    Set<String> nativeColorReads = new HashSet<String>();
                    for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode read = (MethodInsnNode) instruction;
                        if (read.owner.equals(node.name) && read.desc.equals("()I")) nativeColorReads.add(read.name);
                    }
                    for (MethodNode colorRead : node.methods) {
                        if (!nativeColorReads.contains(colorRead.name) || !colorRead.desc.equals("()I")) continue;
                        List<MethodInsnNode> stores = new ArrayList<MethodInsnNode>();
                        for (AbstractInsnNode instruction : colorRead.instructions) if (instruction instanceof MethodInsnNode) {
                            MethodInsnNode read = (MethodInsnNode) instruction;
                            if (read.getOpcode() == Opcodes.INVOKEVIRTUAL && read.desc.equals("(Ljava/util/UUID;)I")
                                    && read.owner.startsWith("com/moonsworth/lunar/")) stores.add(read);
                        }
                        if (stores.size() != 1 || alreadyHooked(colorRead)) continue;
                        InsnList captureStore = new InsnList();
                        captureStore.add(new InsnNode(Opcodes.DUP2)); captureStore.add(new InsnNode(Opcodes.POP));
                        captureStore.add(call(RUNTIME, "capturePlusStore", "(Ljava/lang/Object;)V"));
                        colorRead.instructions.insertBefore(stores.get(0), captureStore);
                    }
                }
                if (selections == 1 && selection(method)) {
                    AbstractInsnNode[] originalInstructions = method.instructions.toArray();
                    InsnList head = new InsnList(); LabelNode nativeSelection = new LabelNode();
                    head.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    head.add(call(HOOK, "selectPlus", "(Ljava/lang/Integer;)Z"));
                    head.add(new JumpInsnNode(Opcodes.IFEQ, nativeSelection)); head.add(new InsnNode(Opcodes.RETURN));
                    head.add(nativeSelection); head.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
                    method.instructions.insert(head);
                    for (AbstractInsnNode instruction : originalInstructions) {
                        if (instruction.getOpcode() != Opcodes.RETURN) continue;
                        InsnList tail = new InsnList(); tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        tail.add(call(HOOK, "selectedPlus", "(Ljava/lang/Integer;)V"));
                        method.instructions.insertBefore(instruction, tail);
                    }
                    changed = true;
                }
                if (instance && pushes == 1 && profileHandler(method)) {
                    addAtReturns(method, HOOK, "profilePush", "(Ljava/lang/Object;Ljava/util/UUID;Ljava/lang/Object;)V", 3);
                    changed = true;
                }
                if (instance && pushes == 1 && method.desc.equals("(Lcom/lunarclient/websocket/cosmetic/v2/LoginResponse;)V")) {
                    addAtReturns(method, HOOK, "profileLogin", "(Ljava/lang/Object;Ljava/lang/Object;)V", 2);
                    changed = true;
                }
                if (instance && method.desc.equals("(Lcom/lunarclient/websocket/cosmetic/v2/LoadTabLogosResponse;)V")
                        && hasUuidCache(node)) {
                    addAtReturns(method, "me/napim/gomod/features/GomodLogoColorSync", "onTabLogoCacheLoaded", "(Ljava/lang/Object;)V", 1);
                    changed = true;
                }
            }
            if (!changed) return null;
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); node.accept(writer);
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar_color.structural_hooks", name,
                    "publishers=" + publishers + " selections=" + selections + " profileHandlers=" + pushes
                            + " outfitHandlers=" + outfits + " outfitBadgeSyncs=" + outfitBadgeSyncs);
            return writer.toByteArray();
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar_color.structural_hooks_failed", name,
                    "errorType=" + error.getClass().getSimpleName());
            return null;
        }
    }
    private static int serviceFields(ClassNode node) {
        int count = 0;
        for (FieldNode field : node.fields) if ((field.access & Opcodes.ACC_STATIC) == 0 && field.desc.equals(SERVICE)) count++;
        return count;
    }
    private static boolean profileHandler(MethodNode method) {
        if (!method.desc.equals(PUSH) || (method.access & Opcodes.ACC_STATIC) != 0) return false;
        Set<String> reads = new HashSet<String>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals("com/lunarclient/websocket/cosmetic/v2/PlayerCosmeticsPushV2")) reads.add(call.name);
        }
        return reads.containsAll(Arrays.asList("getLogoColor", "getPlusColor", "getLogoAlwaysShow"));
    }
    /** Native outfit stores consume the complete cosmetics list and write a UUID-keyed outfit map. */
    private static boolean outfitHandler(ClassNode node, MethodNode method) {
        if (!method.desc.equals("(Lcom/lunarclient/websocket/cosmetic/v2/PlayerCosmeticsPushV2;)V")
                || (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        boolean uuidMap = false, mapWrite = false;
        for (FieldNode field : node.fields) if ((field.access & Opcodes.ACC_STATIC) == 0
                && field.desc.equals("Ljava/util/Map;") && field.signature != null
                && field.signature.startsWith("Ljava/util/Map<Ljava/util/UUID;")) uuidMap = true;
        Set<String> reads = new HashSet<String>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode invocation = (MethodInsnNode) instruction;
            if (invocation.owner.equals("com/lunarclient/websocket/cosmetic/v2/PlayerCosmeticsPushV2")) reads.add(invocation.name);
            if (invocation.owner.equals("java/util/Map") && invocation.name.equals("put")) mapWrite = true;
        }
        return uuidMap && mapWrite && reads.containsAll(Arrays.asList("getPlayerUuid", "getDefaultCosmeticsList", "getBadgeId"));
    }
    private static boolean hasUuidCache(ClassNode node) {
        int count = 0;
        for (FieldNode field : node.fields) if ((field.access & Opcodes.ACC_STATIC) == 0
                && field.desc.equals("Lcom/google/common/cache/Cache;") && field.signature != null
                && field.signature.startsWith("Lcom/google/common/cache/Cache<Ljava/util/UUID;L")) count++;
        return count == 1;
    }
    private static boolean publisher(MethodNode method) {
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || !method.desc.equals("()V")) return false;
        Set<String> keys = new HashSet<String>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof LdcInsnNode
                && ((LdcInsnNode) instruction).cst instanceof String) keys.add((String) ((LdcInsnNode) instruction).cst);
        return keys.containsAll(Arrays.asList("availableColors", "active", "lunarPlus", "originalLunarPlus", "plusColor"));
    }
    private static List<MethodInsnNode> setters(MethodNode method) {
        List<MethodInsnNode> found = new ArrayList<MethodInsnNode>();
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ((call.getOpcode() == Opcodes.INVOKEVIRTUAL || call.getOpcode() == Opcodes.INVOKEINTERFACE)
                    && call.owner.startsWith("com/moonsworth/lunar/") && call.desc.equals("(Ljava/lang/String;Ljava/lang/Object;)V")) found.add(call);
        }
        return found;
    }
    private static boolean selection(MethodNode method) {
        if ((method.access & Opcodes.ACC_STATIC) == 0 || !method.desc.equals("(Ljava/lang/Integer;)V")) return false;
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.owner.equals("com/lunarclient/websocket/cosmetic/v2/CosmeticService$Interface") && call.name.equals("updateLunarPlusColor")) return true;
        }
        return false;
    }
    private static boolean outfitSelectBadgeHandler(MethodNode method) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) return false;
        Type[] args = Type.getArgumentTypes(method.desc);
        if (Type.getReturnType(method.desc) != Type.VOID_TYPE || args.length != 2) return false;
        if (args[0].getSort() != Type.OBJECT || !args[0].getInternalName().startsWith("com/moonsworth/lunar/")) return false;
        if (!args[1].getInternalName().equals("com/lunarclient/websocket/cosmetic/v2/SelectOutfitResponse")) return false;
        boolean setsBadge = false, callsEquip = false;
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode) {
            MethodInsnNode invocation = (MethodInsnNode) instruction;
            if ("com/lunarclient/websocket/badge/v1/EquipBadgeRequest$Builder".equals(invocation.owner)
                    && "setBadgeId".equals(invocation.name)) setsBadge = true;
            if ("com/lunarclient/websocket/badge/v1/BadgeService$Interface".equals(invocation.owner)
                    && "equipBadge".equals(invocation.name)) callsEquip = true;
        }
        return setsBadge && callsEquip;
    }
    private static boolean hookOutfitSelectBadge(MethodNode method) {
        String outfitType = Type.getArgumentTypes(method.desc)[0].getInternalName();
        MethodInsnNode badgeGetterPattern = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode idCall = (MethodInsnNode) instruction;
            if (!"()I".equals(idCall.desc) || !("id".equals(idCall.name) || "getId".equals(idCall.name)
                    || "getBadgeId".equals(idCall.name))) continue;
            AbstractInsnNode prev = previous(instruction);
            if (!(prev instanceof MethodInsnNode)) continue;
            MethodInsnNode getter = (MethodInsnNode) prev;
            if (getter.getOpcode() == Opcodes.INVOKEVIRTUAL && outfitType.equals(getter.owner)
                    && getter.desc.equals("()L" + idCall.owner + ";")) {
                if (badgeGetterPattern != null && (!badgeGetterPattern.name.equals(getter.name)
                        || !badgeGetterPattern.desc.equals(getter.desc))) return false;
                badgeGetterPattern = getter;
            }
        }
        if (badgeGetterPattern == null) return false;
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && outfitType.equals(call.owner)
                    && badgeGetterPattern.name.equals(call.name) && badgeGetterPattern.desc.equals(call.desc)) {
                AbstractInsnNode next = nextReal(instruction);
                if (next instanceof JumpInsnNode
                        && (next.getOpcode() == Opcodes.IFNONNULL || next.getOpcode() == Opcodes.IFNULL)) {
                    InsnList hook = new InsnList();
                    hook.add(new InsnNode(Opcodes.DUP));
                    hook.add(call("me/napim/gomod/features/GomodLogoColorSync",
                            "rememberNativeBadgeSelection", "(Ljava/lang/Object;)V"));
                    method.instructions.insert(instruction, hook);
                    return true;
                }
            }
        }
        return false;
    }
    private static boolean alreadyHooked(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) if (instruction instanceof MethodInsnNode) {
            String owner = ((MethodInsnNode) instruction).owner;
            String methodName = ((MethodInsnNode) instruction).name;
            if (owner.equals(HOOK) || owner.equals(RUNTIME) || owner.equals("me/napim/gomod/features/LunarFullDisguise")
                    || owner.equals("me/napim/gomod/mixins/LunarLockerStateBridge")
                    || owner.equals("me/napim/gomod/features/GomodLogoColorSync")
                    && ("onTabLogoCacheLoaded".equals(methodName) || "rememberNativeBadgeSelection".equals(methodName))) return true;
        }
        return false;
    }
    private static void addAtReturns(MethodNode method, String owner, String name, String desc, int arguments) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) if (instruction.getOpcode() == Opcodes.RETURN) {
            InsnList hook = new InsnList();
            for (int i = 0; i < arguments; i++) hook.add(new VarInsnNode(Opcodes.ALOAD, i));
            hook.add(call(owner, name, desc)); method.instructions.insertBefore(instruction, hook);
        }
    }
    private static MethodInsnNode call(String owner, String name, String desc) { return new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, desc, false); }
    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
    private static AbstractInsnNode nextReal(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
}
