package me.napim.gomod.weave;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/** Discovers native actor/frame delivery and the equip resolver including its removal lambdas. */
public final class LunarCompanionAndConflictTransform {
    private static final String SYNC = "me/napim/gomod/features/GomodCompanionPositionSync";
    private static final String REACTION = "me/napim/gomod/features/GoparkyBlitzReactions";
    private static final String CONFLICT = "me/napim/gomod/mixins/LunarMultiSameTypeCosmeticSupport";
    private static final String COMPANION = "me/napim/gomod/mixins/LunarCompanionMultiTypeSupport";
    private LunarCompanionAndConflictTransform() { }
    public static boolean mayContain(String name, byte[] bytes) {
        if (name == null || bytes == null || !name.startsWith("com/moonsworth/lunar/")) return false;
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        return pool.contains("bridge$getPosX")
                || (pool.contains("java/util/List") && pool.contains("anyMatch"))
                || (pool.contains("lastBlockPos") && pool.contains("bridge$getBlockPos"))
                || (pool.contains("LookAtTargetTask") && pool.contains("org/joml/Vector3d"))
                || (pool.contains("COMPANION") && pool.contains("bridge$getUniqueID")
                && pool.contains("java/util/Iterator") && pool.contains("computeIfAbsent"));
    }
    public static byte[] transform(String name, byte[] bytes) {
        if (!mayContain(name, bytes)) return null;
        ClassNode node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
        boolean actor = false, frame = false, conflict = false;
        for (MethodNode method : node.methods) for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode) {
            String owner = ((MethodInsnNode) insn).owner;
            if (owner.equals(SYNC)) frame = true;
            if (owner.equals(REACTION) && ((MethodInsnNode) insn).name.equals("rememberGoparkyActor")) actor = true;
            if (owner.equals(CONFLICT)) conflict = true;
        }
        int actors = 0, frames = 0, resolvers = 0, removals = 0, conflictConsumers = 0, previews=0;
        boolean actorShape = false;
        for (MethodNode method : node.methods) if (method.name.equals("bridge$getPosX") && method.desc.equals("()D")) actorShape = true;
        for (MethodNode method : node.methods) {
            Type[] args = Type.getArgumentTypes(method.desc);
            if (!actor && actorShape && method.name.equals("<init>") && args.length == 5
                    && allObjects(args)) {
                for (AbstractInsnNode insn : method.instructions.toArray()) if (insn.getOpcode() == Opcodes.RETURN) {
                    InsnList patch = new InsnList();
                    for (String hook : new String[]{REACTION, SYNC}) {
                        patch.add(new VarInsnNode(Opcodes.ALOAD, 0)); patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        patch.add(new VarInsnNode(Opcodes.ALOAD, 3));
                        patch.add(call(hook, hook.equals(SYNC) ? "rememberActor" : "rememberGoparkyActor", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"));
                    }
                    method.instructions.insertBefore(insn, patch);
                }
                actors++;
            }
            if (!frame && method.desc.equals("(F)V") && contains(method, "bridge$getPosX")) {
                try {
                    Frame<SourceValue>[] analyzed = new Analyzer<SourceValue>(new SourceInterpreter()).analyze(node.name, method);
                    Map<AbstractInsnNode, Frame<SourceValue>> originalFrames = new IdentityHashMap<AbstractInsnNode, Frame<SourceValue>>();
                    AbstractInsnNode[] original = method.instructions.toArray();
                    for (int i = 0; i < original.length; i++) originalFrames.put(original[i], analyzed[i]);
                    for (AbstractInsnNode insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                        MethodInsnNode ctor = (MethodInsnNode) insn; Type[] p = Type.getArgumentTypes(ctor.desc);
                        if (!ctor.name.equals("<init>") || p.length != 7 || p[0].getSort() != Type.OBJECT || p[1].getSort() != Type.OBJECT
                                || !ctor.desc.endsWith(";DDDFD)V")) continue;
                        Frame<SourceValue> f = originalFrames.get(insn);
                        if (f == null) continue;
                        SourceValue source = f.getStack(f.getStackSize() - p.length);
                        if (source.insns.size() != 1) continue;
                        AbstractInsnNode load = source.insns.iterator().next();
                        if (!(load instanceof VarInsnNode) || load.getOpcode() != Opcodes.ALOAD) continue;
                        InsnList patch = new InsnList(); patch.add(new VarInsnNode(Opcodes.ALOAD, ((VarInsnNode) load).var));
                        patch.add(new VarInsnNode(Opcodes.FLOAD, 1));
                        patch.add(call(SYNC, "adjustRenderedFrame", "(Ljava/lang/Object;Ljava/lang/Object;F)Ljava/lang/Object;"));
                        patch.add(new TypeInsnNode(Opcodes.CHECKCAST, ctor.owner)); method.instructions.insert(insn, patch); frames++;
                    }
                } catch (AnalyzerException error) {
                    System.out.println("[gomod] Companion frame layout skipped: analysis failed");
                }
            }
        }
        if (!conflict) {
            List<MethodNode> roots = new ArrayList<MethodNode>();
            for (MethodNode method : node.methods) {
                Type[] p = Type.getArgumentTypes(method.desc);
                if ((method.access & Opcodes.ACC_STATIC) != 0 && p.length == 3 && p[0].getSort() == Type.OBJECT
                        && p[0].getInternalName().startsWith("com/moonsworth/lunar/")
                        && method.desc.endsWith(";Ljava/util/List;Z)Ljava/util/List;")
                        && contains(method, "anyMatch") && contains(method, "forEach")) roots.add(method);
            }
            if (roots.size() == 1) {
                MethodNode root = roots.get(0); Set<MethodNode> reachable = new HashSet<MethodNode>(); visit(node, root, reachable);
                for (MethodNode method : reachable) for (AbstractInsnNode insn : method.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                    MethodInsnNode invocation = (MethodInsnNode) insn;
                    if (!invocation.owner.equals("java/util/List") || !invocation.name.equals("removeIf")) continue;
                    method.instructions.insertBefore(insn, call(CONFLICT, "preserveSameCategoryRemovalPredicate", "(Ljava/util/function/Predicate;)Ljava/util/function/Predicate;")); removals++;
                }
                for (AbstractInsnNode insn : root.instructions.toArray()) if (insn instanceof MethodInsnNode) {
                    MethodInsnNode invocation = (MethodInsnNode) insn;
                    if (!invocation.owner.equals("java/util/List") || !invocation.name.equals("forEach")
                            || !invocation.desc.equals("(Ljava/util/function/Consumer;)V")) continue;
                    AbstractInsnNode producer = previousOpcode(insn);
                    if (!(producer instanceof InvokeDynamicInsnNode)
                            || !invokesRemovalLambda(node, (InvokeDynamicInsnNode) producer)) continue;
                    root.instructions.insertBefore(insn, call(CONFLICT, "preserveEquippedSameCategory", "(Ljava/util/function/Consumer;)Ljava/util/function/Consumer;"));
                    conflictConsumers++;
                }
                InsnList head = new InsnList(); head.add(new VarInsnNode(Opcodes.ALOAD, 0)); head.add(new VarInsnNode(Opcodes.ILOAD, 2));
                head.add(call(CONFLICT, "beginEquipResolution", "(Ljava/lang/Object;Z)V")); LabelNode start = new LabelNode(); head.add(start); root.instructions.insert(head);
                for (AbstractInsnNode insn : root.instructions.toArray()) if (insn.getOpcode() == Opcodes.ARETURN) {
                    InsnList patch = new InsnList(); patch.add(new VarInsnNode(Opcodes.ALOAD, 0)); patch.add(new InsnNode(Opcodes.SWAP));
                    patch.add(new VarInsnNode(Opcodes.ILOAD, 2)); patch.add(call(CONFLICT, "preserveSameCategory", "(Ljava/lang/Object;Ljava/util/List;Z)Ljava/util/List;"));
                    patch.add(call(CONFLICT, "endEquipResolution", "()V")); root.instructions.insertBefore(insn, patch);
                }
                LabelNode end = new LabelNode(), handler = new LabelNode(); root.instructions.add(end); root.instructions.add(handler);
                root.instructions.add(new FrameNode(Opcodes.F_FULL, 3, new Object[]{Type.getArgumentTypes(root.desc)[0].getInternalName(), "java/util/List", Opcodes.INTEGER}, 1, new Object[]{"java/lang/Throwable"}));
                root.instructions.add(call(CONFLICT, "endEquipResolution", "()V")); root.instructions.add(new InsnNode(Opcodes.ATHROW));
                root.tryCatchBlocks.add(new TryCatchBlockNode(start, end, handler, null)); resolvers++;
            }
        }
        for(MethodNode method:node.methods){
            boolean preview=false;
            if(method.visibleAnnotations!=null)for(AnnotationNode annotation:method.visibleAnnotations)
                if(annotation.desc.endsWith("/CallbackJS;")&&annotation.values!=null&&annotation.values.contains("previewCosmetics"))preview=true;
            if(!preview||containsCall(method,"preservePreviewConflicts"))continue;
            for(AbstractInsnNode insn:method.instructions.toArray())if(insn instanceof MethodInsnNode){
                MethodInsnNode resolver=(MethodInsnNode)insn;
                if(!resolver.owner.equals(node.name)||!resolver.desc.endsWith(";Ljava/util/List;Z)Ljava/util/List;"))continue;
                AbstractInsnNode flag=previousOpcode(insn),list=previousOpcode(flag),selected=previousOpcode(list);
                if(flag==null||flag.getOpcode()!=Opcodes.ICONST_0||!(selected instanceof VarInsnNode)||selected.getOpcode()!=Opcodes.ALOAD)continue;
                InsnList filter=new InsnList();filter.add(new VarInsnNode(Opcodes.ALOAD,((VarInsnNode)selected).var));filter.add(new InsnNode(Opcodes.SWAP));
                filter.add(call(CONFLICT,"preservePreviewConflicts","(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;"));
                method.instructions.insert(insn,filter);previews++;
            }
        }
        int nativeMoveTargets = patchNativeMoveTargets(node);
        int nativeLookTargets = patchNativeLookTargets(node);
        int nativeArrivalDistances = patchNativeArrivalDistances(node);
        int companionRenderers = patchCompanionRenderer(node);
        if (actors + frames + resolvers + previews + nativeMoveTargets + nativeLookTargets + companionRenderers
                + nativeArrivalDistances == 0) return null;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS); node.accept(writer);
        System.out.println("[gomod] Native companion/conflict hooks: actors=" + actors + " frames=" + frames
                + " resolvers=" + resolvers + " removals=" + removals + " conflictConsumers=" + conflictConsumers
                + " previewConflicts="+previews
                + " multiCompanionRendererHooks=" + companionRenderers
                + " goparkyMoveTargets=" + nativeMoveTargets + " goparkyLookTargets=" + nativeLookTargets
                + " goparkyArrivalDistances=" + nativeArrivalDistances);
        return writer.toByteArray();
    }

    /**
     * Lunar changes this renderer's obfuscated class name between client builds. Find the
     * player-companion scan from its stable bytecode shape and keep the multi-companion hooks
     * attached to the live renderer instead of relying on an old pseudo-mixin target.
     */
    private static int patchCompanionRenderer(ClassNode node) {
        List<MethodNode> candidates = new ArrayList<MethodNode>();
        for (MethodNode method : node.methods) {
            if (isCompanionRendererMethod(method)) candidates.add(method);
        }
        if (candidates.size() != 1) return 0;

        MethodNode method = candidates.get(0);
        int patched = 0;
        if (!containsCall(method, "recordRendererInvocation")) {
            InsnList hook = new InsnList();
            hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
            hook.add(call(COMPANION, "recordRendererInvocation", "(Ljava/lang/Object;)V"));
            method.instructions.insert(hook);
            patched++;
        }

        if (!containsCall(method, "isMultiCompanionRenderingEnabled")) {
            LoopExit exit = firstMatchLoopExit(method);
            if (exit == null) return 0;
            InsnList gate = new InsnList();
            gate.add(call(COMPANION, "isMultiCompanionRenderingEnabled", "()Z"));
            gate.add(new JumpInsnNode(Opcodes.IFNE, exit.loopHead));
            method.instructions.insertBefore(exit.returnInsn, gate);
            patched++;
        }

        int playerLocal = companionPlayerLocal(method);
        if (playerLocal >= 0 && !containsCall(method, "shouldSuppressCompanionSpawn")) {
            MethodInsnNode cleanup = companionCleanupCall(node, method, playerLocal);
            if (cleanup != null) {
                InsnList suppression = new InsnList();
                LabelNode continueSpawn = new LabelNode();
                suppression.add(new VarInsnNode(Opcodes.ALOAD, playerLocal));
                suppression.add(call(COMPANION, "shouldSuppressCompanionSpawn", "(Ljava/lang/Object;)Z"));
                suppression.add(new JumpInsnNode(Opcodes.IFEQ, continueSpawn));
                suppression.add(new InsnNode(Opcodes.RETURN));
                suppression.add(continueSpawn);
                suppression.add(companionCleanupFrame(node, method, playerLocal));
                method.instructions.insert(cleanup, suppression);
                patched++;
            }
        }

        if (!containsCall(method, "mergeRetry")) {
            int merged = 0;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode invocation = (MethodInsnNode) instruction;
                if (!"java/util/Map".equals(invocation.owner)
                        || !"computeIfAbsent".equals(invocation.name)
                        || !"(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;".equals(invocation.desc)) {
                    continue;
                }
                method.instructions.set(invocation,
                        call(COMPANION, "mergeRetry",
                                "(Ljava/util/Map;Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"));
                merged++;
            }
            if (merged > 0) patched++;
        }
        return patched;
    }

    private static boolean isCompanionRendererMethod(MethodNode method) {
        Type[] args = Type.getArgumentTypes(method.desc);
        if ((method.access & Opcodes.ACC_STATIC) != 0 || args.length != 1
                || args[0].getSort() != Type.OBJECT || Type.getReturnType(method.desc).getSort() != Type.VOID
                || !containsCall(method, "bridge$getUniqueID")
                || !containsCall(method, "computeIfAbsent")
                || !containsIteratorCall(method)) return false;

        boolean companionCategory = false;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if ("COMPANION".equals(field.name) && field.owner.startsWith("com/moonsworth/lunar/")) {
                    companionCategory = true;
                    break;
                }
            }
        }
        return companionCategory && firstMatchLoopExit(method) != null;
    }

    private static LoopExit firstMatchLoopExit(MethodNode method) {
        LoopExit result = null;
        int matches = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() != Opcodes.RETURN) continue;
            AbstractInsnNode next = nextOpcode(instruction);
            if (!(next instanceof JumpInsnNode) || next.getOpcode() != Opcodes.GOTO) continue;
            JumpInsnNode backEdge = (JumpInsnNode) next;
            if (method.instructions.indexOf(backEdge.label) >= method.instructions.indexOf(instruction)) continue;
            result = new LoopExit(instruction, backEdge.label);
            matches++;
        }
        return matches == 1 ? result : null;
    }

    private static int companionPlayerLocal(MethodNode method) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!"bridge$getUniqueID".equals(call.name) || !"()Ljava/util/UUID;".equals(call.desc)) continue;
            AbstractInsnNode receiver = previousOpcode(call);
            if (receiver instanceof VarInsnNode && receiver.getOpcode() == Opcodes.ALOAD) {
                return ((VarInsnNode) receiver).var;
            }
        }
        return -1;
    }

    private static MethodInsnNode companionCleanupCall(ClassNode node, MethodNode method, int playerLocal) {
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            Type[] args = Type.getArgumentTypes(call.desc);
            if (!node.name.equals(call.owner) || Type.getReturnType(call.desc).getSort() != Type.VOID
                    || args.length != 1 || args[0].getSort() != Type.OBJECT) continue;
            AbstractInsnNode player = previousOpcode(call);
            AbstractInsnNode renderer = previousOpcode(player);
            if (player instanceof VarInsnNode && player.getOpcode() == Opcodes.ALOAD
                    && ((VarInsnNode) player).var == playerLocal
                    && renderer instanceof VarInsnNode && renderer.getOpcode() == Opcodes.ALOAD
                    && ((VarInsnNode) renderer).var == 0) return call;
        }
        return null;
    }

    private static FrameNode companionCleanupFrame(ClassNode node, MethodNode method, int playerLocal) {
        String[] locals = new String[playerLocal + 1];
        locals[0] = node.name;
        Type[] args = Type.getArgumentTypes(method.desc);
        locals[1] = args[0].getInternalName();
        String playerType = args[0].getInternalName();
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof VarInsnNode) || instruction.getOpcode() != Opcodes.ASTORE
                    || ((VarInsnNode) instruction).var != playerLocal) continue;
            AbstractInsnNode previous = previousOpcode(instruction);
            if (previous instanceof TypeInsnNode && previous.getOpcode() == Opcodes.CHECKCAST) {
                playerType = ((TypeInsnNode) previous).desc;
                break;
            }
        }
        locals[playerLocal] = playerType;
        return new FrameNode(Opcodes.F_FULL, locals.length, locals, 0, new Object[0]);
    }

    private static AbstractInsnNode nextOpcode(AbstractInsnNode instruction) {
        for (AbstractInsnNode next = instruction == null ? null : instruction.getNext();
             next != null; next = next.getNext()) {
            if (next.getOpcode() >= 0) return next;
        }
        return null;
    }

    private static final class LoopExit {
        private final AbstractInsnNode returnInsn;
        private final LabelNode loopHead;
        private LoopExit(AbstractInsnNode returnInsn, LabelNode loopHead) {
            this.returnInsn = returnInsn;
            this.loopHead = loopHead;
        }
    }

    /** Feed the saved death/interaction position to Lunar's real move_to_owner pathfinder. */
    private static int patchNativeMoveTargets(ClassNode node) {
        if (node.name == null || !node.name.contains("/OOIOHHHCIICRRRCICOIIIRRRICOCIC/OOIOHHHCIICRRRCICOIIIRRRICOCIC")) {
            return 0;
        }
        int patched = 0;
        for (MethodNode method : node.methods) {
            Type[] args = Type.getArgumentTypes(method.desc);
            if ((method.access & Opcodes.ACC_STATIC) != 0 || args.length < 1
                    || args[0].getSort() != Type.OBJECT || !contains(method, "bridge$getBlockPos")) continue;
            boolean alreadyPatched = containsCall(method, "overrideNativeMoveTarget");
            if (alreadyPatched) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!call.name.equals("bridge$getBlockPos") || !call.desc.startsWith("()L")) continue;
                AbstractInsnNode receiver = previousOpcode(call);
                if (!(receiver instanceof VarInsnNode) || receiver.getOpcode() != Opcodes.ALOAD
                        || ((VarInsnNode) receiver).var != 1) continue;
                String resultType = Type.getReturnType(call.desc).getInternalName();
                InsnList patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
                patch.add(new InsnNode(Opcodes.SWAP));
                patch.add(call("me/napim/gomod/features/GoparkyBlitzReactions",
                        "overrideNativeMoveTarget", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                patch.add(new TypeInsnNode(Opcodes.CHECKCAST, resultType));
                method.instructions.insert(call, patch);
                patched++;
            }
        }
        return patched;
    }

    /** Turn Lunar's target gaze toward a death spot or the selected interaction player. */
    private static int patchNativeLookTargets(ClassNode node) {
        if (node.name == null || !node.name.contains("ICCRRCCIOOIRICIHHCIOROHHROOHHR")) return 0;
        int patched = 0;
        for (MethodNode method : node.methods) {
            Type[] args = Type.getArgumentTypes(method.desc);
            if ((method.access & Opcodes.ACC_STATIC) != 0 || args.length < 1
                    || args[0].getSort() != Type.OBJECT || !contains(method, "org/joml/Vector3d")
                    || containsCall(method, "overrideNativeLookTarget")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (Type.getReturnType(call.desc).getSort() != Type.OBJECT
                        || !"org/joml/Vector3d".equals(Type.getReturnType(call.desc).getInternalName())) continue;
                AbstractInsnNode receiver = previousOpcode(call);
                if (!(receiver instanceof VarInsnNode) || receiver.getOpcode() != Opcodes.ALOAD
                        || ((VarInsnNode) receiver).var != 1) continue;
                InsnList patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
                patch.add(new InsnNode(Opcodes.SWAP));
                patch.add(call("me/napim/gomod/features/GoparkyBlitzReactions",
                        "overrideNativeLookTarget", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"));
                patch.add(new TypeInsnNode(Opcodes.CHECKCAST, "org/joml/Vector3d"));
                method.instructions.insert(call, patch);
                patched++;
            }
        }
        return patched;
    }

    /** Respect the configured arrival radius while retaining Lunar's path creation and movement. */
    private static int patchNativeArrivalDistances(ClassNode node) {
        if (node.name == null || !node.name.contains("/OOIOHHHCIICRRRCICOIIIRRRICOCIC/OOIOHHHCIICRRRCICOIIIRRRICOCIC")) {
            return 0;
        }
        int patched = 0;
        for (MethodNode method : node.methods) {
            Type[] args = Type.getArgumentTypes(method.desc);
            if ((method.access & Opcodes.ACC_STATIC) != 0 || args.length < 1
                    || args[0].getSort() != Type.OBJECT || !contains(method, "bridge$getBlockPos")
                    || containsCall(method, "nativeTauntArrivalDistance")) continue;
            for (AbstractInsnNode insn : method.instructions.toArray()) {
                if (!(insn instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) insn;
                if (!call.owner.equals("java/lang/Math") || !call.name.equals("max")
                        || !call.desc.equals("(II)I")) continue;
                InsnList patch = new InsnList();
                patch.add(new VarInsnNode(Opcodes.ALOAD, 1));
                patch.add(new InsnNode(Opcodes.SWAP));
                patch.add(call("me/napim/gomod/features/GoparkyBlitzReactions",
                        "nativeTauntArrivalDistance", "(Ljava/lang/Object;I)I"));
                method.instructions.insert(call, patch);
                patched++;
            }
        }
        return patched;
    }

    private static boolean containsCall(MethodNode method, String name) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode && name.equals(((MethodInsnNode) insn).name)) return true;
        }
        return false;
    }

    private static boolean containsIteratorCall(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if ("java/util/Iterator".equals(call.owner)
                        && ("hasNext".equals(call.name) || "next".equals(call.name))) return true;
            }
        }
        return false;
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        for (AbstractInsnNode previous = instruction == null ? null : instruction.getPrevious();
             previous != null; previous = previous.getPrevious()) {
            if (previous.getOpcode() >= 0) return previous;
        }
        return null;
    }
    private static void visit(ClassNode node, MethodNode method, Set<MethodNode> seen) {
        if (!seen.add(method)) return;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof InvokeDynamicInsnNode) for (Object arg : ((InvokeDynamicInsnNode) insn).bsmArgs)
                if (arg instanceof Handle) visitHandle(node, (Handle) arg, seen);
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode c = (MethodInsnNode) insn;
                if (c.owner.equals(node.name)) for (MethodNode target : node.methods)
                    if (target.name.equals(c.name) && target.desc.equals(c.desc)) visit(node, target, seen);
            }
        }
    }
    private static boolean invokesRemovalLambda(ClassNode node, InvokeDynamicInsnNode indy) {
        if (indy == null || indy.bsmArgs == null) return false;
        for (Object arg : indy.bsmArgs) {
            if (!(arg instanceof Handle)) continue;
            Handle handle = (Handle) arg;
            if (!node.name.equals(handle.getOwner())) continue;
            for (MethodNode target : node.methods) {
                if (!target.name.equals(handle.getName()) || !target.desc.equals(handle.getDesc())) continue;
                Set<MethodNode> reached = new HashSet<MethodNode>();
                visit(node, target, reached);
                for (MethodNode reachedMethod : reached) {
                    for (AbstractInsnNode insn : reachedMethod.instructions) {
                        if (insn instanceof MethodInsnNode) {
                            MethodInsnNode call = (MethodInsnNode) insn;
                            if ("java/util/List".equals(call.owner) && "removeIf".equals(call.name)) return true;
                        }
                    }
                }
            }
        }
        return false;
    }
    private static void visitHandle(ClassNode node, Handle handle, Set<MethodNode> seen) {
        if (!handle.getOwner().equals(node.name)) return;
        for (MethodNode target : node.methods) if (target.name.equals(handle.getName()) && target.desc.equals(handle.getDesc())) visit(node, target, seen);
    }
    private static boolean contains(MethodNode method, String name) {
        for (AbstractInsnNode insn : method.instructions) if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.equals(name)) return true; return false;
    }
    private static boolean allObjects(Type[] args) { for (Type p : args) if (p.getSort() != Type.OBJECT) return false; return true; }
    private static MethodInsnNode call(String owner, String name, String descriptor) { return new MethodInsnNode(Opcodes.INVOKESTATIC, owner, name, descriptor, false); }
}
