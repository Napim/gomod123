package me.napim.gomod.weave;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import me.napim.gomod.features.LunarNametagDebugCapture;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Display-only profile reads in the final vanilla and Lunar-font nametag paths. */
public final class LunarNametagLogoTransform {
    public static final String TARGET = "net/minecraft/client/renderer/entity/RendererLivingEntity";
    public static final String VANILLA_LABEL = "net/minecraft/client/renderer/entity/Render";
    private static final String VANILLA_LABEL_ARGS = "(Lnet/minecraft/entity/Entity;Ljava/lang/String;DDDI";
    private static final String CALLBACK_SUFFIX = "spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
    private static final String WEAVE_SHADE = "net/weavemc/loader/impl/shaded/";
    private static final String LABEL_STATE = "me/napim/gomod/render/NametagRenderState";
    public static final String WRAPPER = "com/moonsworth/lunar/legacy/wrapper/IROCHCCHCRCCOCOIIHRRHORIIIICCI/IHCCRCCOHRCCIORHHCROORROCICRHI";
    public static final String CURRENT_WRAPPER = "com/moonsworth/lunar/legacy/wrapper/RCHOIOIIRCHIRRHIIHHRHOORCCIHCH/IHHHICHHRICRCIOHCIOHHOHIOIORRH";
    public static final String PROFILE = "com/moonsworth/lunar/client/CRICRCRHRRICCICRHICIIIOHRIRHRH/HHICICROOCIIROOHOORRCIHOICHOIH/"
            + "OIRIRHRCCIHOHRROCOHICIRIHIIHCH$IOHOHIHHHHORRCCRHCRCHCRIOIHRHH";
    private static final String CURRENT_PROFILE = "com/moonsworth/lunar/client/OCOHHCCOROOHIHHRRIIHRHCIRIHIRI/"
            + "OIHCRCHCIHORRCCIHOCIOHHHHCCRCH/IORRHRCOOIOROOIIOROHHCIHRCCOIC"
            + "$CRHOIHOORHICOCOCCCCRRIRICCIRCR";
    private static final String HELPER = "me/napim/gomod/features/LunarDisguiserBadge";
    private static final String SELF_OPACITY = "me/napim/gomod/features/SelfOpacity";
    private static final String COMPONENT = "com/moonsworth/lunar/lib/adventure/text/Component";
    private static final String CACHE = "com/moonsworth/lunar/client/util/ORIIOCOORHRRCRCIROICOOICRIRIRO/"
            + "ROOCCIHRCCHORHCCOIIRIHHHIIHHHO";
    private static final String COMPILED_LIST_DISPATCH_OWNER =
            "com/moonsworth/lunar/IHCCRCCOHRCCIORHHCROORROCICRHI/"
                    + "CIIHHRHHHIOIIHHRROOHRHICIHOOOC/COHCCRCCIOORIRIIRIRCIHHCHIRCOH/"
                    + "HOICRCCCCRCRRHOIOIIRCCIRRHRHRH$IOCIHHIRIROCOIROOCORCCIOOIIIRR";
    private static final String COMPILED_LIST_DISPATCH_METHOD =
            "HOICRCCCCRCRRHOIOIIRCCIRRHRHRH";
    private LunarNametagLogoTransform() { }

    public static boolean isTarget(String className) {
        return TARGET.equals(className) || VANILLA_LABEL.equals(className) || className != null
                && className.startsWith("com/moonsworth/lunar/legacy/wrapper/");
    }

    public static byte[] transform(String className, byte[] bytes) {
        if(VANILLA_LABEL.equals(className))return vanillaLabelState(bytes);
        boolean renderer = TARGET.equals(className);
        if (!isTarget(className) || bytes == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode(); reader.accept(node, 0);
            MethodNode method = null;
            List<MethodInsnNode> getters = new ArrayList<>(), presence = new ArrayList<>();
            Map<MethodInsnNode, String> getterTypes = new IdentityHashMap<>();
            for (MethodNode candidate : node.methods) {
                if (!isLabelMethod(candidate, renderer)) continue;
                for (AbstractInsnNode instruction : candidate.instructions) {
                    if (!(instruction instanceof MethodInsnNode)) continue;
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    if (HELPER.equals(call.owner) && "getNametagLogo".equals(call.name)) return null;
                    if (!"java/util/Map".equals(call.owner) || call.getOpcode() != Opcodes.INVOKEINTERFACE
                            || !"get".equals(call.name) || !"(Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc)) continue;
                    AbstractInsnNode next = nextCode(call);
                    if (!(next instanceof TypeInsnNode) || next.getOpcode() != Opcodes.CHECKCAST) continue;
                    String castType = ((TypeInsnNode) next).desc;
                    if (isNativeProfile(castType) && profileMapAccessor(call, castType) != null) {
                        if (method != null && method != candidate) return null;
                        method = candidate; getters.add(call); getterTypes.put(call, castType);
                    }
                }
            }
            if (method == null || getters.size() != 1) return null;
            String profileType = getterTypes.get(getters.get(0));
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if ("java/util/Map".equals(call.owner) && "containsKey".equals(call.name)
                        && "(Ljava/lang/Object;)Z".equals(call.desc)
                        && profileMapAccessor(call, profileType) != null) presence.add(call);
            }
            if (presence.size() > 1) return null;
            AbstractInsnNode profileStore = nextCode(nextCode(getters.get(0)));
            int profileLocal = profileStore instanceof VarInsnNode && profileStore.getOpcode() == Opcodes.ASTORE
                    ? ((VarInsnNode) profileStore).var : -1;
            FieldInsnNode cachedProfile = findCachedProfile(method, profileType, profileLocal);
            FieldInsnNode cachedComponent = renderer || cachedProfile != null ? null : findCachedComponent(method, getters.get(0));
            MethodInsnNode compileColor = renderer ? null : findCompiler(node.name, method, profileType);
            MethodInsnNode drawColor = renderer ? null : findCompiledListDraw(method);
            int labelReturnHooks = 0;
            if (compileColor != null && cachedProfile == null && cachedComponent == null) {
                LunarConnectionDiagnostics.recordOnceAfterStart("disguiser.nametag_color_cache_patch", className,
                        "cacheHooks=0 reason=unknown-layout");
                return null;
            }
            for (MethodInsnNode call : getters) rewrite(method, call, "getNametagLogo", "Ljava/lang/Object;",
                    getterTypes.get(call));
            for (MethodInsnNode call : presence) {
                rewrite(method, call, "containsNametagLogo", "Z", profileType);
            }
            if (cachedProfile != null) {
                InsnList comparison = new InsnList();
                comparison.add(new VarInsnNode(Opcodes.ALOAD, profileLocal));
                comparison.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "getCachedNametagProfile",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                comparison.add(new TypeInsnNode(Opcodes.CHECKCAST, profileType));
                method.instructions.insert(cachedProfile, comparison);
            }
            if (cachedComponent != null) {
                InsnList cacheCheck = new InsnList();
                cacheCheck.add(new VarInsnNode(Opcodes.ALOAD, 16));
                cacheCheck.add(new VarInsnNode(Opcodes.ALOAD, 12));
                cacheCheck.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "getCachedNametagComponent",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
                cacheCheck.add(new TypeInsnNode(Opcodes.CHECKCAST, COMPONENT));
                method.instructions.insert(cachedComponent, cacheCheck);
                LunarConnectionDiagnostics.recordOnceAfterStart("disguiser.nametag_color_cache_patch", className,
                        "cacheHooks=1 expected=1 nativeListLifecyclePreserved=true");
            }
            if (compileColor != null && profileLocal >= 0) {
                InsnList trace = new InsnList();
                trace.add(new LdcInsnNode("compile"));
                trace.add(new VarInsnNode(Opcodes.ALOAD, profileLocal));
                trace.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "recordNametagNativeColorStage",
                        "(Ljava/lang/String;Ljava/lang/Object;)V", false));
                method.instructions.insert(compileColor, trace);
            }
            if (drawColor != null && profileLocal >= 0) {
                InsnList trace = new InsnList();
                trace.add(new InsnNode(Opcodes.DUP));
                trace.add(new LdcInsnNode("draw"));
                trace.add(new VarInsnNode(Opcodes.ALOAD, profileLocal));
                trace.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "recordNametagNativeColorStage",
                        "(ILjava/lang/String;Ljava/lang/Object;)V", false));
                method.instructions.insertBefore(drawColor, trace);
            }
            int opacityBypassHooks = 0;
            if (compileColor != null) {
                bracketSelfOpacityAlpha(method, compileColor);
                opacityBypassHooks++;
            }
            if (drawColor != null) {
                bracketSelfOpacityAlpha(method, drawColor);
                opacityBypassHooks++;
            }
            if (renderer && compileColor != null) {
                boolean afterCompile = false;
                AbstractInsnNode[] instructions = method.instructions.toArray();
                for (AbstractInsnNode instruction : instructions) {
                    if (instruction == compileColor) {
                        afterCompile = true;
                        continue;
                    }
                    if (!afterCompile || instruction.getOpcode() != Opcodes.DRETURN) continue;
                    InsnList trace = new InsnList();
                    trace.add(new LdcInsnNode("label_return"));
                    trace.add(new VarInsnNode(Opcodes.ALOAD, profileLocal));
                    trace.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "recordNametagNativeColorStage",
                            "(Ljava/lang/String;Ljava/lang/Object;)V", false));
                    method.instructions.insertBefore(instruction, trace);
                    labelReturnHooks++;
                }
            }
            if (compileColor != null || drawColor != null) {
                LunarConnectionDiagnostics.recordOnceAfterStart("disguiser.nametag_native_color_trace", className,
                        "compileHooks=" + (compileColor == null ? 0 : 1) + " drawHooks="
                                + (drawColor == null ? 0 : 1) + " labelReturnHooks=" + labelReturnHooks
                                + " opacityBypassHooks=" + opacityBypassHooks
                                + " identityAndHandlesOmitted=true");
            }
            LunarNametagDebugCapture.reportNametagInstrumentation(
                    compileColor == null ? 0 : 1, drawColor == null ? 0 : 1, labelReturnHooks);
            int compiledQuads = renderer ? 0 : patchCompiledQuads(node);
            wrapLabelState(method,className);
            LunarConnectionDiagnostics.recordOnceAfterStart("nametag.render_state_hook",className,"source="+className+" methods=1 scopedAlphaAndDepth=true");
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.connection_logo_compiled_hook",className,
                    "quadWriters="+compiledQuads+" profileMarkerOnly=true");
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS); node.accept(writer);
            LunarConnectionDiagnostics.recordOnceAfterStart("disguiser.nametag_logo_patch", className,
                    "lookupMatches=" + getters.size() + " expected=1 nativeWrapper=" + !renderer
                            + " presenceHooks=" + presence.size() + " profileCacheHooks=" + (cachedProfile == null ? 0 : 1)
                            + " savedProfilesPreserved=true");
            return writer.toByteArray();
        } catch (Throwable error) {
            LunarConnectionDiagnostics.recordOnceAfterStart("disguiser.nametag_logo_patch_failed", className,
                    "exception=" + error.getClass().getSimpleName()); return null;
        }
    }

    private static byte[] vanillaLabelState(byte[] bytes) {
        if (bytes == null) return null;
        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, 0);
            MethodNode label = null;
            for (MethodNode method : node.methods) {
                if (!(method.name.equals("renderLivingLabel") || method.name.equals("func_147906_a"))
                        || !method.desc.equals(VANILLA_LABEL_ARGS + ")V")) continue;
                if (label != null) return null;
                label = method;
            }
            if (label == null || !wrapLabelState(label, "vanilla-label")) return null;
            boolean removedDuplicate = removeMergedVanillaLabelScopes(node, label);
            LunarConnectionDiagnostics.recordOnceAfterStart("nametag.render_state_hook", VANILLA_LABEL,
                    "source=vanilla-label methods=1 scopedAlphaAndDepth=true removedDuplicate=" + removedDuplicate);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            return writer.toByteArray();
        } catch (Throwable failure) {
            return null;
        }
    }

    private static boolean removeMergedVanillaLabelScopes(ClassNode owner, MethodNode label) {
        MethodNode head = findMergedLabelHandler(owner, label, "$gomod$markMobNametagRendered");
        MethodNode tail = findMergedLabelHandler(owner, label, "$gomod$recordLivingLabelReturn");
        if (head == null || tail == null) return false;
        MethodInsnNode begin = findLabelStateCall(head, "begin", "(Ljava/lang/String;)V");
        MethodInsnNode end = findLabelStateCall(tail, "end", "()V");
        if (begin == null || end == null) return false;
        AbstractInsnNode source = begin.getPrevious();
        if (!(source instanceof LdcInsnNode)
                || !"native-render-living-label".equals(((LdcInsnNode) source).cst)) return false;

        // The outer wrapper covers early cancellation and exceptions. A merged HEAD scope
        // would otherwise outlive calls whose control flow never reaches the Mixin TAIL.
        head.instructions.remove(source);
        head.instructions.remove(begin);
        tail.instructions.remove(end);
        return true;
    }

    private static MethodNode findMergedLabelHandler(ClassNode owner, MethodNode label, String suffix) {
        MethodInsnNode reference = null;
        for (AbstractInsnNode instruction : label.instructions) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!owner.name.equals(call.owner) || !call.name.startsWith("handler$")
                    || !call.name.endsWith(suffix)) continue;
            if (reference != null || call.getOpcode() != Opcodes.INVOKESPECIAL
                    || !(call.desc.equals(VANILLA_LABEL_ARGS + "Lorg/" + CALLBACK_SUFFIX)
                    || call.desc.equals(VANILLA_LABEL_ARGS + "L" + WEAVE_SHADE + CALLBACK_SUFFIX))) return null;
            reference = call;
        }
        if (reference == null) return null;

        MethodNode handler = null;
        for (MethodNode method : owner.methods) {
            if (method.name.equals(reference.name) && method.desc.equals(reference.desc)) {
                if (handler != null || (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC))
                        != Opcodes.ACC_PRIVATE) return null;
                handler = method;
            }
            if (method == label) continue;
            for (AbstractInsnNode instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (owner.name.equals(call.owner) && reference.name.equals(call.name)
                        && reference.desc.equals(call.desc)) return null;
            }
        }
        return handler;
    }

    private static MethodInsnNode findLabelStateCall(MethodNode handler, String name, String descriptor) {
        MethodInsnNode found = null;
        for (AbstractInsnNode instruction : handler.instructions) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (!LABEL_STATE.equals(call.owner)) continue;
            if (found != null || call.getOpcode() != Opcodes.INVOKESTATIC || !name.equals(call.name)
                    || !descriptor.equals(call.desc)) return null;
            found = call;
        }
        return found;
    }
    private static boolean wrapLabelState(MethodNode method,String source) {
        for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode&&((MethodInsnNode)instruction).owner.equals(LABEL_STATE))return false;
        AbstractInsnNode[] original=method.instructions.toArray();LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();
        InsnList head=new InsnList();head.add(new LdcInsnNode(source));head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,LABEL_STATE,"begin","(Ljava/lang/String;)V",false));head.add(start);method.instructions.insert(head);
        for(AbstractInsnNode instruction:original)if(instruction.getOpcode()>=Opcodes.IRETURN&&instruction.getOpcode()<=Opcodes.RETURN)
            method.instructions.insertBefore(instruction,new MethodInsnNode(Opcodes.INVOKESTATIC,LABEL_STATE,"end","()V",false));
        method.instructions.add(end);method.instructions.add(handler);method.instructions.add(new FrameNode(Opcodes.F_FULL,0,new Object[0],1,new Object[]{"java/lang/Throwable"}));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,LABEL_STATE,"end","()V",false));method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));return true;
    }

    private static boolean isLabelMethod(MethodNode method, boolean renderer) {
        if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT)) != 0) return false;
        if (renderer && !"impl$drawLabel".equals(method.name)) return false;
        int doubles = 0, components = 0;
        for (Type argument : Type.getArgumentTypes(method.desc)) {
            if (argument.equals(Type.DOUBLE_TYPE)) doubles++;
            if (argument.equals(Type.getObjectType(COMPONENT))
                    || argument.equals(Type.getObjectType("com/moonsworth/lunar/lib/adventure/text/TextComponent"))) components++;
        }
        Type result = Type.getReturnType(method.desc);
        return doubles == 3 && components == 1 && (result.equals(Type.BOOLEAN_TYPE)
                || result.equals(Type.VOID_TYPE) || result.equals(Type.DOUBLE_TYPE));
    }

    private static int patchCompiledQuads(ClassNode node) {
        int count=0;
        for(MethodNode method:node.methods){
            if((method.access&Opcodes.ACC_STATIC)!=0||!method.desc.equals("(Ljava/nio/FloatBuffer;FFFFFI)V"))continue;
            int vertices=0;
            for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode){
                MethodInsnNode call=(MethodInsnNode)instruction;
                if(call.owner.equals(node.name)&&call.desc.equals("(Ljava/nio/FloatBuffer;FFFFFF)V"))vertices++;
            }
            if(vertices!=4)continue;
            for(AbstractInsnNode instruction:method.instructions.toArray())if(instruction.getOpcode()==Opcodes.RETURN){
                InsnList hook=new InsnList();hook.add(new VarInsnNode(Opcodes.ALOAD,1));
                for(int slot:new int[]{2,3,5,6})hook.add(new VarInsnNode(Opcodes.FLOAD,slot));
                hook.add(new VarInsnNode(Opcodes.ILOAD,7));
                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"me/napim/gomod/features/LunarConnectionLogo",
                        "rotateCompiledQuad","(Ljava/nio/FloatBuffer;FFFFI)V",false));
                method.instructions.insertBefore(instruction,hook);
            }
            count++;
        }
        return count;
    }

    private static void bracketSelfOpacityAlpha(MethodNode method, MethodInsnNode call) {
        InsnList begin = new InsnList();
        begin.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF_OPACITY,
                "beginNametagLogoAlphaBypass", "()V", false));
        method.instructions.insertBefore(call, begin);

        InsnList end = new InsnList();
        end.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF_OPACITY,
                "endNametagLogoAlphaBypass", "()V", false));
        method.instructions.insert(call, end);
    }

    /** Read a renamed native value's constructor without initializing Lunar bridge classes. */
    private static boolean isNativeProfile(String type) {
        if (PROFILE.equals(type) || CURRENT_PROFILE.equals(type)) return true;
        if (!type.startsWith("com/moonsworth/lunar/") || type.indexOf('$') < 0) return false;
        try (java.io.InputStream input = LunarNametagLogoTransform.class.getClassLoader().getResourceAsStream(type + ".class")) {
            if (input == null) return false;
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            int constructors = 0;
            for (MethodNode method : node.methods) {
                if (!"<init>".equals(method.name)) continue;
                Type[] args = Type.getArgumentTypes(method.desc);
                if (args.length == 7 && method.desc.startsWith("(FFFZIZL")
                        && args[6].getSort() == Type.OBJECT) constructors++;
            }
            return constructors == 1;
        } catch (Exception ignored) { return false; }
    }

    private static MethodInsnNode profileMapAccessor(MethodInsnNode call, String profileType) {
        // Renderer: map, entity, getUniqueID(); wrapper: map, saved UUID local.
        AbstractInsnNode key = previousCode(call);
        if (key instanceof MethodInsnNode && "()Ljava/util/UUID;".equals(((MethodInsnNode) key).desc))
            key = previousCode(key);
        if (!(key instanceof VarInsnNode) || key.getOpcode() != Opcodes.ALOAD) return null;
        AbstractInsnNode receiver = previousCode(key);
        if (!(receiver instanceof MethodInsnNode)) return null;
        MethodInsnNode map = (MethodInsnNode) receiver;
        if (!"()Ljava/util/Map;".equals(map.desc)) return null;
        int inner = profileType.lastIndexOf('$');
        return inner > 0 && map.owner.equals(profileType.substring(0, inner)) ? map : null;
    }

    private static FieldInsnNode findCachedProfile(MethodNode method, String profileType, int profileLocal) {
        if (profileLocal < 0) return null;
        FieldInsnNode match = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof FieldInsnNode)) continue;
            FieldInsnNode field = (FieldInsnNode) instruction;
            if (field.getOpcode() != Opcodes.GETFIELD || !("L" + profileType + ";").equals(field.desc)) continue;
            AbstractInsnNode load = previousCode(field), profile = nextCode(field), branch = nextCode(profile);
            if (!(load instanceof VarInsnNode) || load.getOpcode() != Opcodes.ALOAD
                    || !(profile instanceof VarInsnNode) || profile.getOpcode() != Opcodes.ALOAD
                    || ((VarInsnNode) profile).var != profileLocal || branch == null
                    || (branch.getOpcode() != Opcodes.IF_ACMPEQ && branch.getOpcode() != Opcodes.IF_ACMPNE)
                    || match != null) return null;
            match = field;
        }
        return match;
    }

    private static MethodInsnNode findCompiler(String owner, MethodNode method, String profileType) {
        MethodInsnNode match = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            Type[] args = Type.getArgumentTypes(call.desc);
            if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !owner.equals(call.owner)
                    || args.length != 6 || !args[0].equals(Type.getObjectType(COMPONENT))
                    || !args[1].equals(Type.getObjectType(profileType)) || args[2].getSort() != Type.OBJECT
                    || !args[3].equals(Type.INT_TYPE) || !args[4].equals(Type.BOOLEAN_TYPE)
                    || !args[5].equals(Type.BOOLEAN_TYPE) || !Type.getReturnType(call.desc).equals(Type.INT_TYPE)) continue;
            if (match != null) return null;
            match = call;
        }
        return match;
    }

    private static MethodInsnNode findCompiledListDraw(MethodNode method) {
        // Lunar renames this display-list interface and method between builds. Match its
        // stable invocation shape instead: an interface accepting the compiled list ID,
        // with a local receiver directly below the int on the operand stack.
        MethodInsnNode match = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (call.getOpcode() != Opcodes.INVOKEINTERFACE
                    || !"(I)V".equals(call.desc)) continue;
            AbstractInsnNode listId = previousCode(call);
            if (!(listId instanceof VarInsnNode) || listId.getOpcode() != Opcodes.ILOAD) continue;
            AbstractInsnNode listOwner = previousCode(listId);
            if (!(listOwner instanceof VarInsnNode) || listOwner.getOpcode() != Opcodes.ALOAD) continue;
            if (match != null) return null;
            match = call;
        }
        return match;
    }

    /** Match only the inspected profile/cache locals and native component reuse branch. */
    private static FieldInsnNode findCachedComponent(MethodNode method, MethodInsnNode profileLookup) {
        AbstractInsnNode profileCast = nextCode(profileLookup);
        AbstractInsnNode profileStore = nextCode(profileCast);
        if (!(profileStore instanceof VarInsnNode) || profileStore.getOpcode() != Opcodes.ASTORE
                || ((VarInsnNode) profileStore).var != 12) return null;
        FieldInsnNode match = null;
        int cacheStores = 0, compilers = 0, handleReads = 0;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) instruction;
                if ("lunar$getCache".equals(call.name) && ("()L" + CACHE + ";").equals(call.desc)) {
                    AbstractInsnNode store = nextCode(call);
                    if (store instanceof VarInsnNode && store.getOpcode() == Opcodes.ASTORE
                            && ((VarInsnNode) store).var == 16) cacheStores++;
                }
                if (WRAPPER.equals(call.owner) && "IOHOHIHHHHORRCCRHCRCHCRIOIHRHH".equals(call.name)
                        && ("(L" + COMPONENT + ";L" + PROFILE + ";L" + CACHE + ";IZZ)I").equals(call.desc)) compilers++;
            }
            if (!(instruction instanceof FieldInsnNode)) continue;
            FieldInsnNode field = (FieldInsnNode) instruction;
            if (field.getOpcode() != Opcodes.GETFIELD || !CACHE.equals(field.owner)) continue;
            if ("HRICCOOIIHRCHOOOOOHIICRRHIICHR".equals(field.name) && "I".equals(field.desc)) handleReads++;
            if (!"CHRRIOROIRHIIHCOOHHOHCRIROIRHO".equals(field.name)
                    || !("L" + COMPONENT + ";").equals(field.desc)) continue;
            AbstractInsnNode load = previousCode(field), component = nextCode(field), branch = nextCode(component);
            if (!(load instanceof VarInsnNode) || load.getOpcode() != Opcodes.ALOAD
                    || ((VarInsnNode) load).var != 16 || !(component instanceof VarInsnNode)
                    || component.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) component).var != 1
                    || !(branch instanceof JumpInsnNode) || branch.getOpcode() != Opcodes.IF_ACMPNE
                    || match != null) return null;
            match = field;
        }
        return cacheStores == 1 && compilers == 1 && handleReads == 2 ? match : null;
    }

    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static void rewrite(MethodNode method, MethodInsnNode call, String name, String result) {
        rewrite(method, call, name, result, PROFILE);
    }

    private static void rewrite(MethodNode method, MethodInsnNode call, String name, String result,
                                String profileType) {
        method.instructions.insertBefore(call, new LdcInsnNode(Type.getObjectType(profileType)));
        method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, name,
                "(Ljava/util/Map;Ljava/lang/Object;Ljava/lang/Class;)" + result, false));
    }

    /** Match the live Lunar profile map by its UUID key instead of a release-specific obfuscated class name. */
    private static boolean lookupKeyIsUuid(MethodNode method, MethodInsnNode mapGet) {
        int inspected = 0;
        for (AbstractInsnNode instruction = mapGet.getPrevious(); instruction != null && inspected < 28;
             instruction = instruction.getPrevious()) {
            if (instruction.getOpcode() < 0) continue;
            inspected++;
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if ("bridge$getUniqueID".equals(call.name)
                    && "()Ljava/util/UUID;".equals(call.desc)) return true;
            if ("java/util/Map".equals(call.owner) && "get".equals(call.name)) return false;
        }
        return false;
    }
}
