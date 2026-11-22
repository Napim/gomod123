package me.napim.gomod.weave;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.lang.instrument.*;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.*;
import me.napim.gomod.features.LunarConnectionDiagnostics;

/** Schema-preserving bindings to the inspected badge draw and native emote clock. */
public final class LunarStudioTransform implements ClassFileTransformer {
    public static final String ROOT="com/moonsworth/lunar/client/OICROIOCCHRCHRIRIRRIHOIRRCCRCH/RHOICRHRRHCICCRORRHHIRIIHHIIHI/IOOCCRIRHHROHIHRCHHRRIOICHIOIR/";
    public static final String CONTROLLER=ROOT+"RHOICRHRRHCICCRORRHHIRIIHHIIHI/IOOCCRIRHHROHIHRCHHRRIOICHIOIR/IOOCCRIRHHROHIHRCHHRRIOICHIOIR";
    public static final String DEFINITION="com/moonsworth/lunar/client/IOIOOCICIIHCIRIRRHHOIOOOICIRRC/CIOHOIORRIOCHOHCIIIRRRCCOIOIHC/IOOCCRIRHHROHIHRCHHRRIOICHIOIR";
    public static final String BADGE_MANAGER="com/moonsworth/lunar/client/OROHROORCHIHCRHOROOHOHORCHHOII/ICCOIIRCOIOHHHHCIRHHIRICRCROOO/COIOIRHRRORRHHOIIORORRHOOCRHII";
    public static final String PARTICLE_RENDERER_LEGACY="com/moonsworth/lunar/client/CHCHIOIIICRIOOHRHIOHCRROIRRROI/COIOIRHRRORRHHOIIORORRHOOCRHII/RHOICRHRRHCICCRORRHHIRIIHHIIHI/IOOCCRIRHHROHIHRCHHRRIOICHIOIR";
    /** Current Lunar build moved the same native particle renderer under a new client package. */
    public static final String PARTICLE_RENDERER="com/moonsworth/lunar/client/CHHRHIORHRRICOCOIROOIIIORCORCH/COIOIRHRRORRHHOIIORORRHOOCRHII/RHOICRHRRHCICCRORRHHIRIIHHIIHI/IOOCCRIRHHROHIHRCHHRRIOICHIOIR";
    /** Current Lunar's live emote-particle manager, identified by its particle config and render dispatch. */
    public static final String EMOTE_PARTICLE_MANAGER_CURRENT="com/moonsworth/lunar/client/HRHRHOIHHCROROIOIRHOCRRIROCIRH/RHHIHCHCOHORHOCHRHCIIHRCIRIOOR/HORHHICRICRIRORRRICCCOOHOHHIHI";
    private static final String PARTICLE_RUNTIME="com/moonsworth/lunar/client/RCOORCCHCORROOCRICIICHHOIOROOI/";
    private static final String SNOWSTORM_RUNTIME="com/moonsworth/lunar/client/IIRHIHOICCCRRRICICCCRCROHIHCII/";
    private static final String SNOWSTORM_BATCH_RENDERER=
            "com/moonsworth/lunar/client/CIOICRHOOIHORICHROHICOICOOOHIH/"
            +"IICHIHHCCCCCHORIHHOHCIOHOORIIC/HOOCCRIRRIOOOHCCORHCIORIHIOCOR/HCHORRIICIOCIIHOOCCCICROOIOOOH";
    /** Known Snowstorm list renderers, also eligible if already loaded at install time. */
    private static final String PARTICLE_EFFECT_RENDERERS[]={
            PARTICLE_RUNTIME+"OCHHHRRCORHOOIICIROCOCIORICCOH",
            PARTICLE_RUNTIME+"RORCHCRHRCIRHCHROIRCOCCICRHOIR",
            SNOWSTORM_RUNTIME+"ICHHIHCOIHHIHIHRHRCCCRICOOCORI",
            SNOWSTORM_RUNTIME+"RROIRIRRRCOHRCRCCCHIROROOCRCCO"
    };
    private static final String MATRIX_DESCRIPTOR="Ljavax/vecmath/Matrix4f;";
    private static final String CONSUMER_LOOP_DESCRIPTOR="(Ljava/util/function/Consumer;)V";
    private static final Type CONSUMER_TYPE=Type.getObjectType("java/util/function/Consumer");
    private static final byte[] MATRIX_BYTES=MATRIX_DESCRIPTOR.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CONSUMER_LOOP_BYTES=CONSUMER_LOOP_DESCRIPTOR.getBytes(StandardCharsets.US_ASCII);
    private static final String PARTICLE_POSE_DESCRIPTOR="(Ljavax/vecmath/Vector4f;Ljavax/vecmath/Matrix3f;)V";
    private static final byte[] PARTICLE_POSE_BYTES=PARTICLE_POSE_DESCRIPTOR.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BATCHED_BYTES="BATCHED".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LEGACY_DISPATCH_BYTES=(PARTICLE_RUNTIME+"CRROROCORRCIRRHIIORHICIRIHICIH")
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] LEGACY_PARTICLE_CONFIG_BYTES="lunar:particles/configuration.json"
            .getBytes(StandardCharsets.US_ASCII);
    private static final String MODEL_CONTEXT_DESCRIPTOR="(Ljava/util/function/Consumer;Ljava/util/function/Consumer;)V";
    private static final byte[] MODEL_CONTEXT_CONSUMER_BYTES=MODEL_CONTEXT_DESCRIPTOR.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BADGES_JSON_BYTES="badges.json".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PARSE_BADGE_BYTES="Failed to parse badge:".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PUSH_BYTES="push".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] POP_BYTES="pop".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] SCALE_BYTES="scale".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PLAYER_VIEW_X_BYTES="playerViewX".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PLAYER_VIEW_Y_BYTES="playerViewY".getBytes(StandardCharsets.US_ASCII);
    public static final String MODEL_CONTEXT="com/moonsworth/lunar/CIOHOIORRIOCHOHCIIIRRRCCOIOIHC/ICCOCOHOCCOROROIRHCCRHICOOOHHC/HHROHORCRHHRHCHHOCOCIHHCIORHIH/RCHRROHORRHCRRRHOCIHCOIHOOOORR";
    private static final String SIZE="me/napim/gomod/features/LunarCosmeticSizeRenderer";
    private static final String PARTICLE_LIGHTING="me/napim/gomod/features/LunarEmoteParticleLighting";
    private static final String MODEL_ROOT="com/moonsworth/lunar/client/RROHOCCHCHCCCICORCCIRCIHHRCICC/RHOICRHRRHCICCRORRHHIRIIHHIIHI/RCHORRIOHHIRIHRRRHHIHRHHOHRIHH/";
    public static final String MODEL_RENDERER=MODEL_ROOT+"CIOHOIORRIOCHOHCIIIRRRCCOIOIHC/CIOHOIORRIOCHOHCIIIRRRCCOIOIHC";
    private static final String PLAYER_MODEL=MODEL_ROOT+"RHOICRHRRHCICCRORRHHIRIIHHIIHI/IOOCCRIRHHROHIHRCHHRRIOICHIOIR";
    private static final String FIT_APPEARANCE="me/napim/gomod/features/LunarFitAppearance";
    private static final String BADGE="com/moonsworth/lunar/client/CIOHOIORRIOCHOHCIIIRRRCCOIOIHC/IOOCCRIRHHROHIHRCHHRRIOICHIOIR";
    private static final String RUNTIME="me/napim/gomod/features/LunarEmoteStudioRuntime",RENDER="me/napim/gomod/features/LunarCosmeticAppearanceRenderer";
    private static boolean installed;
    private static boolean target(String name){return CONTROLLER.equals(name)||DEFINITION.equals(name)||BADGE_MANAGER.equals(name)||MODEL_RENDERER.equals(name)||PARTICLE_RENDERER.equals(name)||PARTICLE_RENDERER_LEGACY.equals(name)||EMOTE_PARTICLE_MANAGER_CURRENT.equals(name)||MODEL_CONTEXT.equals(name)||SNOWSTORM_BATCH_RENDERER.equals(name)||isCurrentParticleEffectRenderer(name);}
    private static boolean isCurrentParticleEffectRenderer(String name){for(String renderer:PARTICLE_EFFECT_RENDERERS)if(renderer.equals(name))return true;return false;}
    public static boolean isLoadedTarget(Class<?> type){
        if(type==null)return false;
        String name=type.getName().replace('.','/');
        if(target(name))return true;
        if(!name.startsWith("com/moonsworth/lunar/"))return false;
        try(java.io.InputStream input=type.getResourceAsStream("/"+name+".class")){
            if(input==null)return false;
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[8192];int read;
            while((read=input.read(buffer))!=-1)output.write(buffer,0,read);
            return mayContainTarget(name,output.toByteArray());
        }catch(Throwable ignored){return false;}
    }
    private static boolean mayContainTarget(String name,byte[] bytes){
        if(name==null||bytes==null)return false;
        if(target(name)||isEmoteParticleManager(name,bytes))return true;
        if(!name.startsWith("com/moonsworth/lunar/"))return false;
        boolean lunarClient=name.startsWith("com/moonsworth/lunar/client/");
        boolean particleEffect=lunarClient&&containsBytes(bytes,MATRIX_BYTES)&&containsBytes(bytes,CONSUMER_LOOP_BYTES);
        boolean particleBatch=lunarClient&&containsBytes(bytes,PARTICLE_POSE_BYTES)&&containsBytes(bytes,BATCHED_BYTES);
        return particleEffect||particleBatch||isStructuralBadgeManager(name,bytes)
                ||isStructuralModelContext(name,bytes)||isStructuralModelRenderer(name,bytes);
    }
    public static synchronized void install(Instrumentation instrumentation,boolean retransforms){
        if(installed||instrumentation==null)return;installed=true;instrumentation.addTransformer(new LunarStudioTransform(),retransforms);
        if(retransforms)for(Class<?> type:instrumentation.getAllLoadedClasses())if(isLoadedTarget(type)&&instrumentation.isModifiableClass(type))try{instrumentation.retransformClasses(type);}catch(Throwable ignored){}
    }
    @Override public byte[] transform(ClassLoader loader,String name,Class<?> redef,ProtectionDomain domain,byte[] bytes){return apply(name,bytes);}
    public static byte[] apply(String name,byte[] bytes){
        if(name==null||bytes==null)return null;
        boolean knownParticleEffectRenderer=isCurrentParticleEffectRenderer(name);
        boolean knownParticleBatchRenderer=SNOWSTORM_BATCH_RENDERER.equals(name);
        boolean lunarClient=name.startsWith("com/moonsworth/lunar/client/");
        boolean particleEffectRenderer=knownParticleEffectRenderer
                ||lunarClient&&containsBytes(bytes,MATRIX_BYTES)
                &&containsBytes(bytes,CONSUMER_LOOP_BYTES);
        boolean particleBatchRenderer=knownParticleBatchRenderer
                ||lunarClient&&containsBytes(bytes,PARTICLE_POSE_BYTES)&&containsBytes(bytes,BATCHED_BYTES);
        boolean emoteParticleManager=isEmoteParticleManager(name,bytes);
        boolean structuralBadgeManager=isStructuralBadgeManager(name,bytes);
        boolean structuralModelContext=isStructuralModelContext(name,bytes);
        boolean structuralModelRenderer=isStructuralModelRenderer(name,bytes);
        if(!target(name)&&!particleEffectRenderer&&!particleBatchRenderer&&!emoteParticleManager
                &&!structuralBadgeManager&&!structuralModelContext&&!structuralModelRenderer)return null;
        try{
            ClassReader reader=new ClassReader(bytes);ClassNode node=new ClassNode();reader.accept(node,0);
            for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode){MethodInsnNode call=(MethodInsnNode)instruction;if(call.owner.equals(RUNTIME)||call.owner.equals(FIT_APPEARANCE)||call.owner.equals(PARTICLE_LIGHTING)||call.owner.equals(SIZE)||call.owner.equals(RENDER)&&call.name.equals("beginBadge"))return null;}
            MethodNode emoteParticleMethod=null;
            MethodNode particleEffectRendererMethod=null;
            boolean batchDraw=false;
            if(emoteParticleManager){
                int matches=0;
                for(MethodNode method:node.methods)if(matchesCurrentEmoteParticleDispatch(method)){
                    emoteParticleMethod=method;
                    matches++;
                }
                if(matches!=1){
                    LunarConnectionDiagnostics.recordOnceAfterStart("studio.particle_lighting_hook_rejected",name,
                            "emoteParticleDispatches="+matches+" expected=1");
                    return null;
                }
            }
            if(particleEffectRenderer||particleBatchRenderer){
                int matches=0;
                boolean emitterPose=particleBatchRenderer&&node.methods.stream().anyMatch(method ->
                        (method.access&Opcodes.ACC_STATIC)==0&&PARTICLE_POSE_DESCRIPTOR.equals(method.desc));
                for(MethodNode method:node.methods){
                    boolean batch=emitterPose&&matchesParticleBatchDraw(method);
                    if(batch||particleEffectRenderer&&matchesCurrentParticleEffectRenderer(node,method)){
                        particleEffectRendererMethod=method;
                        batchDraw=batch;
                        matches++;
                    }
                }
                if(matches!=1){
                    if(knownParticleEffectRenderer||knownParticleBatchRenderer||matches>1) {
                        LunarConnectionDiagnostics.recordOnceAfterStart("studio.particle_lighting_hook_rejected",name,
                                "particleEffectRendererMatches="+matches+" expected=1");
                        return null;
                    }
                    if(!target(name)&&!emoteParticleManager)return null;
                }
            }
            String badgeType=null;
            if(name.equals(BADGE_MANAGER)||structuralBadgeManager){
                badgeType=discoverBadgeType(node);
                if(badgeType==null&&name.equals(BADGE_MANAGER))badgeType=BADGE;
                if(badgeType==null){
                    if(name.equals(BADGE_MANAGER))LunarConnectionDiagnostics.recordOnceAfterStart("studio.binding_rejected",name,"badge type not uniquely identified");
                    return null;
                }
            }
            boolean modelContextTarget=name.equals(MODEL_CONTEXT)||structuralModelContext&&hasModelContextMethods(node);
            boolean modelRendererTarget=name.equals(MODEL_RENDERER)||structuralModelRenderer&&hasPlayerViewFields(node);
            if(!target(name)&&emoteParticleMethod==null&&particleEffectRendererMethod==null
                    &&badgeType==null&&!modelContextTarget&&!modelRendererTarget){
                return null;
            }
            String tickField=name.equals(CONTROLLER)?findTickField(node):null;
            if(name.equals(CONTROLLER)&&tickField==null){LunarConnectionDiagnostics.recordOnceAfterStart("studio.binding_rejected",name,"native tick increment/reset field not uniquely identified");return null;}
            int changes=0,resets=0,stops=0;
            for(MethodNode method:node.methods){
                if(modelContextTarget&&method.desc.equals(MODEL_CONTEXT_DESCRIPTOR)){
                    wrapSize(method);changes++;
                }
                boolean legacyParticleMethod=(name.equals(PARTICLE_RENDERER)||name.equals(PARTICLE_RENDERER_LEGACY))
                        &&method.name.equals("IOOCCRIRHHROHIHRCHHRRIOICHIOIR")
                        &&method.desc.equals("(Lcom/moonsworth/lunar/CIOHOIORRIOCHOHCIIIRRRCCOIOIHC/ICCOCOHOCCOROROIRHCCRHICOOOHHC/HHROHORCRHHRHCHHOCOCIHHCIORHIH/RCHRROHORRHCRRRHOCIHCOIHOOOORR;Lcom/moonsworth/lunar/CIOHOIORRIOCHOHCIIIRRRCCOIOIHC/ICCOCOHOCCOROROIRHCCRHICOOOHHC/IOOCCRIRHHROHIHRCHHRRIOICHIOIR;)V");
                if(legacyParticleMethod||method==emoteParticleMethod||method==particleEffectRendererMethod){
                    wrapParticles(method);changes++;
                }
                if(name.equals(CONTROLLER)&&method.name.equals("OIIROOCOHHIHCCOCHRROOOOCCHOOHO")&&method.desc.equals("()V")){
                    InsnList stop=new InsnList();stop.add(new VarInsnNode(Opcodes.ALOAD,0));stop.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"stopped","(Ljava/lang/Object;)V",false));method.instructions.insert(stop);stops++;
                }
                if(name.equals(DEFINITION)&&method.name.equals("provide")&&method.desc.equals("()Lcom/google/gson/JsonElement;")){
                    InsnList head=new InsnList();head.add(new VarInsnNode(Opcodes.ALOAD,0));head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"definition","(Ljava/lang/Object;)V",false));method.instructions.insert(head);changes++;
                }
                if(name.equals(CONTROLLER))for(AbstractInsnNode instruction:method.instructions.toArray()){
                    if(!(instruction instanceof FieldInsnNode))continue;FieldInsnNode field=(FieldInsnNode)instruction;
                    if(field.getOpcode()!=Opcodes.PUTFIELD||!field.owner.equals(name)||!field.name.equals(tickField)||!field.desc.equals("I"))continue;
                    AbstractInsnNode previous=field.getPrevious();
                    if(previous!=null&&previous.getOpcode()==Opcodes.IADD){AbstractInsnNode one=previous.getPrevious();
                        if(one==null||one.getOpcode()!=Opcodes.ICONST_1||Type.getArgumentTypes(method.desc).length!=1)continue;
                        InsnList step=new InsnList();step.add(new VarInsnNode(Opcodes.ALOAD,0));step.add(new VarInsnNode(Opcodes.ALOAD,1));step.add(new InsnNode(Opcodes.ICONST_1));step.add(new LdcInsnNode(tickField));step.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"step","(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/String;)I",false));method.instructions.insertBefore(one,step);method.instructions.remove(one);changes++;
                    }else if(previous!=null&&previous.getOpcode()==Opcodes.ICONST_0&&Type.getArgumentTypes(method.desc).length==2){InsnList reset=new InsnList();reset.add(new VarInsnNode(Opcodes.ALOAD,0));reset.add(new VarInsnNode(Opcodes.ALOAD,2));reset.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RUNTIME,"reset","(Ljava/lang/Object;Ljava/lang/Object;)V",false));method.instructions.insert(field,reset);resets++;}
                }
                if(badgeType!=null&&(method.access&Opcodes.ACC_STATIC)!=0&&Type.getReturnType(method.desc)==Type.VOID_TYPE){
                    Type[] args=Type.getArgumentTypes(method.desc);if(args.length==8&&args[1].getSort()==Type.OBJECT&&args[1].getInternalName().equals(badgeType)&&args[2]==Type.FLOAT_TYPE&&args[6]==Type.INT_TYPE){wrapBadge(method);changes++;}
                }
                if(modelRendererTarget&&(method.access&Opcodes.ACC_PUBLIC)!=0
                        &&(method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_BRIDGE|Opcodes.ACC_SYNTHETIC))==0
                        &&Type.getReturnType(method.desc)==Type.VOID_TYPE){
                    Type[] args=Type.getArgumentTypes(method.desc);
                    if(args.length==3&&args[1].getSort()==Type.OBJECT
                            &&(name.equals(MODEL_RENDERER)?args[1].equals(Type.getObjectType(PLAYER_MODEL))
                            :args[1].getInternalName().startsWith("com/moonsworth/lunar/"))){
                        wrapModel(method);changes++;
                    }
                }
            }
            int expectedChanges=badgeType!=null?2:1;
            if(changes!=expectedChanges||name.equals(CONTROLLER)&&(resets!=1||stops!=1)){LunarConnectionDiagnostics.recordOnceAfterStart("studio.binding_rejected",name,"matches="+changes+" expected="+expectedChanges+" resets="+resets+" stops="+stops);return null;}
            ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);node.accept(writer);
            if(emoteParticleMethod!=null)LunarConnectionDiagnostics.recordOnceAfterStart("studio.particle_lighting_dispatch_hook_applied",name,
                    "method="+emoteParticleMethod.name+emoteParticleMethod.desc+" scope=emote-particle-manager");
            if(particleEffectRendererMethod!=null)LunarConnectionDiagnostics.recordOnceAfterStart("studio.particle_lighting_renderer_hook_applied",name,
                    "method="+particleEffectRendererMethod.name+particleEffectRendererMethod.desc
                            +" scope="+(batchDraw?"particle-batch-draw":"particle-list-draw"));
            LunarConnectionDiagnostics.recordOnceAfterStart("studio.binding",name,"matches="+changes+" resets="+resets);return writer.toByteArray();
        }catch(Throwable error){LunarConnectionDiagnostics.recordOnceAfterStart("studio.binding_failed",name,"exception="+error.getClass().getSimpleName());return null;}
    }
    private static boolean isStructuralBadgeManager(String name,byte[] bytes){
        return name.startsWith("com/moonsworth/lunar/")
                &&containsBytes(bytes,BADGES_JSON_BYTES)&&containsBytes(bytes,PARSE_BADGE_BYTES);
    }
    private static String discoverBadgeType(ClassNode node){
        String found=null;
        for(MethodNode method:node.methods){
            if((method.access&Opcodes.ACC_STATIC)!=0)continue;
            for(AbstractInsnNode insn:method.instructions){
                if(!(insn instanceof MethodInsnNode))continue;
                MethodInsnNode call=(MethodInsnNode)insn;
                if(!"<init>".equals(call.name)||!call.desc.startsWith("(ILjava/lang/String;Ljava/lang/String;L")
                        ||!call.desc.endsWith(";Ljava/time/Instant;Z)V"))continue;
                Type[] args=Type.getArgumentTypes(call.desc);
                if(args.length!=7||!args[3].equals(args[4]))continue;
                if(found==null)found=call.owner;
                else if(!found.equals(call.owner))return null;
            }
        }
        return found;
    }
    private static boolean isStructuralModelContext(String name,byte[] bytes){
        return name.startsWith("com/moonsworth/lunar/")
                &&containsBytes(bytes,MODEL_CONTEXT_CONSUMER_BYTES)
                &&containsBytes(bytes,PUSH_BYTES)&&containsBytes(bytes,POP_BYTES)&&containsBytes(bytes,SCALE_BYTES);
    }
    private static boolean hasModelContextMethods(ClassNode node){
        boolean push=false,pop=false,scale=false;
        int consumers=0;
        for(MethodNode method:node.methods){
            if((method.access&Opcodes.ACC_STATIC)!=0)continue;
            if("push".equals(method.name)&&"()V".equals(method.desc))push=true;
            else if("pop".equals(method.name)&&"()V".equals(method.desc))pop=true;
            else if("scale".equals(method.name)&&"(FFF)V".equals(method.desc))scale=true;
            else if(MODEL_CONTEXT_DESCRIPTOR.equals(method.desc))consumers++;
        }
        return push&&pop&&scale&&consumers==1;
    }
    private static boolean isStructuralModelRenderer(String name,byte[] bytes){
        return name.startsWith("com/moonsworth/lunar/client/ui/osr/")
                &&containsBytes(bytes,PLAYER_VIEW_X_BYTES)&&containsBytes(bytes,PLAYER_VIEW_Y_BYTES);
    }
    private static boolean hasPlayerViewFields(ClassNode node){
        boolean viewX=false,viewY=false;
        for(FieldNode field:node.fields){
            if((field.access&Opcodes.ACC_STATIC)==0||!"F".equals(field.desc))continue;
            if("playerViewX".equals(field.name))viewX=true;
            else if("playerViewY".equals(field.name))viewY=true;
        }
        return viewX&&viewY;
    }
    /** Lunar renames the native tick counter between builds; identify it by its increment and reset sites. */
    private static String findTickField(ClassNode node){
        Set<String> increments=new HashSet<>(),resets=new HashSet<>();
        for(MethodNode method:node.methods){int arguments=Type.getArgumentTypes(method.desc).length;
            for(AbstractInsnNode instruction:method.instructions){if(!(instruction instanceof FieldInsnNode))continue;FieldInsnNode field=(FieldInsnNode)instruction;
                if(field.getOpcode()!=Opcodes.PUTFIELD||!field.owner.equals(node.name)||!field.desc.equals("I"))continue;AbstractInsnNode value=field.getPrevious();
                if(arguments==1&&value!=null&&value.getOpcode()==Opcodes.IADD&&value.getPrevious()!=null&&value.getPrevious().getOpcode()==Opcodes.ICONST_1)increments.add(field.name);
                if(arguments==2&&value!=null&&value.getOpcode()==Opcodes.ICONST_0)resets.add(field.name);
            }
        }
        increments.retainAll(resets);return increments.size()==1?increments.iterator().next():null;
    }
    private static boolean isEmoteParticleManager(String name,byte[] bytes){
        return EMOTE_PARTICLE_MANAGER_CURRENT.equals(name)||name.startsWith("com/moonsworth/lunar/client/")
                &&containsBytes(bytes,LEGACY_PARTICLE_CONFIG_BYTES)&&containsBytes(bytes,LEGACY_DISPATCH_BYTES);
    }
    private static boolean containsBytes(byte[] bytes,byte[] marker){
        int limit=bytes.length-marker.length;
        for(int i=0;i<=limit;i++){
            if(bytes[i]!=marker[0])continue;
            int j=1;
            while(j<marker.length&&bytes[i+j]==marker[j])j++;
            if(j==marker.length)return true;
        }
        return false;
    }
    /**
     * Current Lunar dispatches each live emote effect through its per-player particle manager.
     * Match that exact render-shaped call, rather than an unrelated generic spray billboard.
     */
    private static boolean matchesCurrentEmoteParticleDispatch(MethodNode method){
        Type[] args=Type.getArgumentTypes(method.desc);
        if((method.access&Opcodes.ACC_STATIC)!=0||Type.getReturnType(method.desc).getSort()!=Type.VOID
                ||args.length!=4||args[0].getSort()!=Type.OBJECT
                ||!args[1].getDescriptor().equals("Ljavax/vecmath/Matrix4f;")
                ||args[2].getSort()!=Type.OBJECT||!args[2].getInternalName().startsWith(PARTICLE_RUNTIME)
                ||args[3].getSort()!=Type.FLOAT)return false;
        boolean uniqueId=false,mapLookup=false,dispatch=false;
        for(AbstractInsnNode instruction:method.instructions){
            if(instruction instanceof MethodInsnNode){
                MethodInsnNode call=(MethodInsnNode)instruction;
                uniqueId|=call.name.equals("bridge$getUniqueID")&&call.desc.equals("()Ljava/util/UUID;");
                mapLookup|=call.owner.equals("java/util/Map")&&call.name.equals("get")&&call.desc.equals("(Ljava/lang/Object;)Ljava/lang/Object;");
                Type[] callArgs=Type.getArgumentTypes(call.desc);
                dispatch|=call.owner.startsWith(PARTICLE_RUNTIME+"CRROROCORRCIRRHIIORHICIRIHICIH")
                        &&Type.getReturnType(call.desc)==Type.VOID_TYPE&&callArgs.length==4
                        &&callArgs[0].equals(args[0])&&callArgs[1].equals(args[1])
                        &&callArgs[2].equals(args[2])&&callArgs[3].equals(args[3]);
            }
        }
        return uniqueId&&mapLookup&&dispatch;
    }
    /** Snowstorm creates a billboard callback and immediately passes it to its own particle list loop. */
    private static boolean matchesCurrentParticleEffectRenderer(ClassNode owner,MethodNode method){
        Type[] args=Type.getArgumentTypes(method.desc);
        if((method.access&Opcodes.ACC_STATIC)!=0||Type.getReturnType(method.desc)!=Type.VOID_TYPE
                ||args.length!=4||args[0].getSort()!=Type.OBJECT
                ||!args[1].getDescriptor().equals(MATRIX_DESCRIPTOR)
                ||args[2].getSort()!=Type.OBJECT||args[3].getSort()!=Type.FLOAT)return false;
        String particlePackage=owner.name.substring(0,owner.name.lastIndexOf('/')+1);
        if(!args[2].getInternalName().startsWith(particlePackage))return false;

        String factory=Type.getMethodDescriptor(CONSUMER_TYPE,args[1],args[0],args[2],args[3]);
        for(AbstractInsnNode instruction:method.instructions){
            if(!(instruction instanceof InvokeDynamicInsnNode))continue;
            InvokeDynamicInsnNode lambda=(InvokeDynamicInsnNode)instruction;
            if(!"accept".equals(lambda.name)||!factory.equals(lambda.desc)
                    ||!"java/lang/invoke/LambdaMetafactory".equals(lambda.bsm.getOwner()))continue;
            if(lambda.bsmArgs.length!=3||!(lambda.bsmArgs[1] instanceof Handle))continue;
            Handle draw=(Handle)lambda.bsmArgs[1];
            if(draw.getTag()!=Opcodes.H_INVOKESTATIC||!owner.name.equals(draw.getOwner()))continue;
            AbstractInsnNode next=instruction.getNext();
            while(next!=null&&next.getOpcode()<0)next=next.getNext();
            if(next instanceof MethodInsnNode){
                MethodInsnNode loop=(MethodInsnNode)next;
                if(owner.name.equals(loop.owner)&&CONSUMER_LOOP_DESCRIPTOR.equals(loop.desc))return true;
            }
        }
        return false;
    }

    /** The emitter draws after the player callbacks have updated its pose. Keep lighting off through its batch flush. */
    private static boolean matchesParticleBatchDraw(MethodNode method){
        Type[] args=Type.getArgumentTypes(method.desc);
        if((method.access&Opcodes.ACC_STATIC)!=0||Type.getReturnType(method.desc)!=Type.VOID_TYPE
                ||args.length!=2||args[0].getSort()!=Type.OBJECT||args[1].getSort()!=Type.OBJECT)return false;

        boolean iteratesParticles=false;
        int flushes=0;
        for(AbstractInsnNode instruction:method.instructions){
            if(instruction instanceof MethodInsnNode){
                MethodInsnNode call=(MethodInsnNode)instruction;
                if(call.owner.equals("java/util/List")&&call.name.equals("iterator"))iteratesParticles=true;
            }
            if(!(instruction instanceof FieldInsnNode)||instruction.getOpcode()!=Opcodes.GETSTATIC)continue;
            FieldInsnNode field=(FieldInsnNode)instruction;
            if(!field.name.equals("BATCHED")||!field.desc.equals("L"+field.owner+";"))continue;
            AbstractInsnNode next=field.getNext();
            while(next!=null&&next.getOpcode()<0)next=next.getNext();
            if(next instanceof MethodInsnNode&&next.getOpcode()==Opcodes.INVOKEINTERFACE
                    &&((MethodInsnNode)next).desc.equals("("+field.desc+")V"))flushes++;
        }
        return iteratesParticles&&flushes==1;
    }
    private static void wrapBadge(MethodNode method){
        AbstractInsnNode[] original=method.instructions.toArray();LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();InsnList head=new InsnList();head.add(new VarInsnNode(Opcodes.ALOAD,1));head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RENDER,"beginBadge","(Ljava/lang/Object;)V",false));head.add(start);method.instructions.insert(head);
        for(AbstractInsnNode instruction:original)if(instruction.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(instruction,new MethodInsnNode(Opcodes.INVOKESTATIC,RENDER,"end","()V",false));
        method.instructions.add(end);method.instructions.add(handler);method.instructions.add(new FrameNode(Opcodes.F_FULL,0,new Object[0],1,new Object[]{"java/lang/Throwable"}));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,RENDER,"end","()V",false));method.instructions.add(new InsnNode(Opcodes.ATHROW));method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));
    }
    private static void wrapModel(MethodNode method){
        AbstractInsnNode[] original=method.instructions.toArray();LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();InsnList head=new InsnList();head.add(new VarInsnNode(Opcodes.ALOAD,2));head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,FIT_APPEARANCE,"beginModel","(Ljava/lang/Object;)V",false));head.add(start);method.instructions.insert(head);
        for(AbstractInsnNode instruction:original)if(instruction.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(instruction,new MethodInsnNode(Opcodes.INVOKESTATIC,FIT_APPEARANCE,"endModel","()V",false));
        method.instructions.add(end);method.instructions.add(handler);method.instructions.add(new FrameNode(Opcodes.F_FULL,0,new Object[0],1,new Object[]{"java/lang/Throwable"}));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,FIT_APPEARANCE,"endModel","()V",false));method.instructions.add(new InsnNode(Opcodes.ATHROW));method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));
    }
    private static void wrapParticles(MethodNode method){
        AbstractInsnNode[] original=method.instructions.toArray();LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();
        InsnList head=new InsnList();head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,PARTICLE_LIGHTING,"begin","()V",false));head.add(start);method.instructions.insert(head);
        for(AbstractInsnNode instruction:original)if(instruction.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(instruction,new MethodInsnNode(Opcodes.INVOKESTATIC,PARTICLE_LIGHTING,"end","()V",false));
        method.instructions.add(end);method.instructions.add(handler);method.instructions.add(new FrameNode(Opcodes.F_FULL,0,new Object[0],1,new Object[]{"java/lang/Throwable"}));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,PARTICLE_LIGHTING,"end","()V",false));method.instructions.add(new InsnNode(Opcodes.ATHROW));method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));
    }
    private static void wrapSize(MethodNode method){
        for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode&&((MethodInsnNode)instruction).owner.equals(SIZE))return;
        AbstractInsnNode[] original=method.instructions.toArray();LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();InsnList head=new InsnList();head.add(new VarInsnNode(Opcodes.ALOAD,0));head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SIZE,"begin","(Ljava/lang/Object;)V",false));head.add(start);method.instructions.insert(head);
        for(AbstractInsnNode instruction:original)if(instruction.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(instruction,new MethodInsnNode(Opcodes.INVOKESTATIC,SIZE,"end","()V",false));
        method.instructions.add(end);method.instructions.add(handler);method.instructions.add(new FrameNode(Opcodes.F_FULL,0,new Object[0],1,new Object[]{"java/lang/Throwable"}));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,SIZE,"end","()V",false));method.instructions.add(new InsnNode(Opcodes.ATHROW));method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));
    }
}
