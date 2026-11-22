package me.napim.gomod.weave;

import me.napim.gomod.features.LunarConnectionDiagnostics;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.lang.instrument.*;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.jar.*;

/** Discovers existing cosmetic render parameters by their constructor/bridge roles, without native aliases. */
public final class LunarCosmeticAppearanceTransform implements ClassFileTransformer {
    private static final String HELPER="me/napim/gomod/features/LunarCosmeticAppearanceRenderer";
    private static final String FIT_APPEARANCE="me/napim/gomod/features/LunarFitAppearance";
    private static final String UPLOADER="net/minecraft/client/renderer/WorldVertexBufferUploader";
    public static final String MODEL_DRAW="com/moonsworth/lunar/client/ICCOCOHOCCOROROIRHCCRHICOOOHHC/HHROHORCRHHRHCHHOCOCIHHCIORHIH/ICCOCOHOCCOROROIRHCCRHICOOOHHC";
    /** Renderable entry types used by recent Lunar builds; keep prior aliases for older profiles. */
    private static final String[] CURRENT_MODEL_DRAWS={
            "com/moonsworth/lunar/client/CCCRIROROCORHRRIIOCIOHRCROIOHH/CRRCORCHIOCRROCCCHRORCOORCCRCI/CCCRIROROCORHRRIIOCIOHRCROIOHH",
            "com/moonsworth/lunar/client/CCCRIROROCORHRRIIOCIOHRCROIOHH/CRRCORCHIOCRROCCCHRORCOORCCRCI/RHHIHCHCOHORHOCHRHCIIHRCIRIOOR",
            // Lunar's November 14 update moved retained cosmetic draws to new obfuscated classes.
            "com/moonsworth/lunar/client/HROHOHORHOCICRICCOCROCRHHOIICR/RRIHHIROHRHCRICCIIRHCRCRIRCRHC/OICICOCOOCICCHOOOORIRICIIIICCI/HOOCCRIRRIOOOHCCORHCIORIHIOCOR/RHIHIRHRRHRRICIHOCOCCIOCIHROIR$HCHORRIICIOCIIHOOCCCICROOIOOOH",
            "com/moonsworth/lunar/client/RRIHHIROHRHCRICCIIRHCRCRIRCRHC/IIOCRIIHHCIIOROIRRCOROCIHCOHCO/IHCIHCRRICRROOOCCCIOOIHHRCIRCC",
            "com/moonsworth/lunar/client/RRIHHIROHRHCRICCIIRHCRCRIRCRHC/IIOCRIIHHCIIOROIRRCOROCIHCOHCO/RRIHHIROHRHCRICCIIRHCRCRIRCRHC"
    };
    private final Map<String,ClassNode> classes=new HashMap<>();
    private final Map<String,Boolean> definitions=new HashMap<>(),players=new HashMap<>(),modelDraws=new HashMap<>();
    private File sourceFile;
    private JarFile sourceArchive;
    private final Set<String> rejectedSources=new HashSet<>();
    private static boolean installed;

    public LunarCosmeticAppearanceTransform() {
        this(new File(System.getProperty("user.home","."),".lunarclient/offline/multiver/lunar.jar"));
    }
    public LunarCosmeticAppearanceTransform(File sourceFile) { this.sourceFile=sourceFile; }

    public static synchronized void install(Instrumentation instrumentation,boolean canRetransform) {
        if (installed || instrumentation==null) return;
        LunarCosmeticAppearanceTransform transformer=new LunarCosmeticAppearanceTransform();
        instrumentation.addTransformer(transformer,canRetransform); installed=true;
        if (canRetransform) for (Class<?> loaded:instrumentation.getAllLoadedClasses()) {
            if ((!loaded.getName().startsWith("com.moonsworth.lunar.client.")
                    &&!loaded.getName().equals(UPLOADER.replace('/','.'))) || !instrumentation.isModifiableClass(loaded)) continue;
            try {
                String name=loaded.getName().replace('.','/');
                boolean selected;
                synchronized (transformer) {
                    if(name.equals(UPLOADER)) selected=true;
                    else {
                        transformer.rememberSource(loaded.getProtectionDomain(),name);
                        ClassNode node=transformer.read(name,loaded.getClassLoader());
                        selected=node!=null && transformer.renderClass(node,loaded.getClassLoader());
                    }
                }
                if (selected) instrumentation.retransformClasses(loaded);
            } catch (Throwable failure) {
                LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.retransform",loaded.getName(),
                        "result=failed exception="+failure.getClass().getSimpleName());
            }
        }
    }
    @Override public synchronized byte[] transform(ClassLoader loader,String name,Class<?> redefined,ProtectionDomain domain,byte[] bytes) {
        if (name!=null && name.startsWith("com/moonsworth/lunar/client/")) rememberSource(domain,name);
        return apply(name,bytes,loader);
    }
    private void rememberSource(ProtectionDomain domain,String name) {
        try {
            if(domain==null||domain.getCodeSource()==null)return;
            java.net.URL location=domain.getCodeSource().getLocation();if(!"file".equals(location.getProtocol()))return;
            File file=new File(location.toURI());String path=file.getAbsolutePath();
            if(file.equals(sourceFile)||rejectedSources.contains(path)||!file.isFile())return;
            try(JarFile jar=new JarFile(file)){
                if(jar.getJarEntry(name+".class")==null){rejectedSources.add(path);return;}
            }
            if(sourceArchive!=null)sourceArchive.close();sourceArchive=null;sourceFile=file;
            classes.clear();definitions.clear();players.clear();modelDraws.clear();
        }catch(Exception ignored){ }
    }
    public synchronized byte[] apply(String name,byte[] bytes,ClassLoader loader) {
        if (name==null || (!name.startsWith("com/moonsworth/lunar/client/")&&!name.equals(UPLOADER))
                || bytes==null || loader==null) return null;
        try {
            // Nearly all native classes have no rendering markers; avoid parsing their metadata.
            String constants=new String(bytes,java.nio.charset.StandardCharsets.ISO_8859_1);
            boolean framePreview=constants.contains("bridge$framebufferClear")&&constants.contains("java/lang/Runnable");
            boolean thumbnailClass=constants.contains("java/lang/AutoCloseable")&&constants.contains("bridge$getUniqueID")
                    &&constants.contains("render");
            boolean scopeClass=constants.contains("getAnimationProcessor")||constants.contains("bridge$bindTexture")||framePreview;
            boolean clothClass=constants.contains("bridge$getClothCloakState");
            boolean fitModelClass=constants.contains("playerViewX")&&constants.contains("playerViewY")&&constants.contains("getId");
            boolean cloakTextureClass=constants.contains("Either renderContext or modelRenderer must not be null!");
            boolean cloakReceiver=constants.contains("bridge$cloak");
            boolean modelDrawCandidate=isModelDrawCandidate(name,constants);
            boolean deferredClass=referencesModelDraw(bytes,constants,loader);
            boolean multiHatRenderCandidate=constants.contains("java/util/function/Consumer")
                    &&constants.contains("bridge$getCosmetics");
            if (!name.equals(UPLOADER) && !scopeClass && !clothClass && !cloakTextureClass && !cloakReceiver && !thumbnailClass && !fitModelClass
                    && !modelDrawCandidate && !deferredClass && !multiHatRenderCandidate && !constants.contains("bridge$setupRenderState")) return null;
            ClassReader reader=new ClassReader(bytes);ClassNode node=new ClassNode();reader.accept(node,0);
            classes.put(name,node);cloakReceiver=cloakReceiver&&definition(name,loader,0);
            if(modelDrawCandidate&&(node.access&(Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT))==0&&hasDeferredDrawShape(node,loader))
                modelDraws.put(name,Boolean.TRUE);
            MultiHatCallsite multiHatDispatch=multiHatRenderDispatch(node,loader);
            if(clothClass) {
                boolean textureDraw=false;
                for(MethodNode method:node.methods)if(cloakTextureDraw(method,loader)||clothCosmeticDraw(method,loader))textureDraw=true;
                clothClass=textureDraw;
            }
            FieldInsnNode thumbnailCosmetic=thumbnailClass?thumbnailCosmetic(node,loader):null;
            int count=0,bindHooks=0,drawHooks=0,sizeDrawHooks=0,bufferDrawHooks=0,bufferInvalidations=0,cloakFrames=0,cloakSelections=0,cloakTextures=0,fitModels=0,deferredDraws=0,deferredCaptures=0,multiHatHooks=0;
            for (MethodNode method:node.methods) {
                if ((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))!=0) continue;
                if(multiHatDispatch!=null&&method==multiHatDispatch.method
                        &&!hasCall(method,HELPER,"wrapMultiSameTypeHatConsumer")) {
                    wrapMultiHatCallsite(method,multiHatDispatch);multiHatHooks++;
                }
                if((node.access&(Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT))==0&&deferredDrawEntry(node,method,loader)
                        &&!hasCall(method,HELPER,"beginDeferredDraw")&&!hasCall(method,HELPER,"beginCurrentDeferredDraw")) {
                    wrapDeferredDraw(method,node.name,highlightEntryGetter(node,loader),!MODEL_DRAW.equals(name));deferredDraws++;
                }
                if(deferredClass&&!method.name.startsWith("<")&&!hasCall(method,HELPER,"rememberDraw")&&!hasCall(method,HELPER,"rememberPreviewDraw")) {
                    for(AbstractInsnNode instruction:method.instructions.toArray()) {
                        if(!(instruction instanceof MethodInsnNode))continue;MethodInsnNode call=(MethodInsnNode)instruction;
                        if(call.getOpcode()!=Opcodes.INVOKESPECIAL||!call.name.equals("<init>")||!isModelDrawType(call.owner,loader))continue;
                        DrawInput input=drawInput(node,method,call,loader);if(input==null)continue;
                        InsnList capture=new InsnList();capture.add(new InsnNode(Opcodes.DUP));capture.add(new VarInsnNode(Opcodes.ALOAD,input.slot));
                        if(input.field!=null)capture.add(new FieldInsnNode(Opcodes.GETFIELD,input.field.owner,input.field.name,input.field.desc));
                        capture.add(input.player<0?new InsnNode(Opcodes.ACONST_NULL):new VarInsnNode(Opcodes.ALOAD,input.player));
                        if(input.playerGetter!=null)capture.add(new MethodInsnNode(input.playerGetter.getOpcode(),input.playerGetter.owner,input.playerGetter.name,input.playerGetter.desc,input.playerGetter.itf));
                        capture.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,previewFactory(node,loader)?"rememberPreviewDraw":"rememberDraw","(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",false));
                        method.instructions.insert(call,capture);deferredCaptures++;
                    }
                }
                boolean hasBindHook=false,hasScope=false;
                for (AbstractInsnNode insn:method.instructions) if(insn instanceof MethodInsnNode) {
                    MethodInsnNode call=(MethodInsnNode)insn;
                    if(HELPER.equals(call.owner)&&call.name.equals("afterTextureBind"))hasBindHook=true;
                    if(HELPER.equals(call.owner)&&(call.name.equals("begin")||call.name.equals("beginCloakRender")
                            ||call.name.equals("beginCloakTexture")||call.name.equals("beginClothTexture")||call.name.equals("beginClothCosmetic")))hasScope=true;
                }
                if(fitModelClass&&fitModelEntry(method,loader)&&!hasCall(method,FIT_APPEARANCE,"beginModel")) {
                    wrapFitModel(method);fitModels++;
                }
                if(!hasScope&&thumbnailCosmetic!=null&&thumbnailEntry(method,loader)) {
                    wrap(method,0,playerSlot(method,loader),"begin",thumbnailCosmetic);count++;hasScope=true;
                }
                if(!hasScope && clothClass && cloakEntry(method,loader)) {
                    int selections=0;
                    for(AbstractInsnNode insn:method.instructions.toArray()) {
                        String result=null;
                        if(insn instanceof MethodInsnNode) {
                            Type type=Type.getReturnType(((MethodInsnNode)insn).desc);
                            if(type.getSort()==Type.OBJECT)result=type.getInternalName();
                        } else if(insn instanceof TypeInsnNode&&insn.getOpcode()==Opcodes.CHECKCAST)
                            result=((TypeInsnNode)insn).desc;
                        if(result==null||!cosmetic(result,loader))continue;
                        InsnList selected=new InsnList();selected.add(new InsnNode(Opcodes.DUP));
                        selected.add(new VarInsnNode(Opcodes.ALOAD,2));
                        selected.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"selectCloak",
                                "(Ljava/lang/Object;Ljava/lang/Object;)V",false));
                        method.instructions.insert(insn,selected);selections++;
                    }
                    if(selections>0){wrapCloak(method,2);cloakFrames++;cloakSelections+=selections;hasScope=true;}
                }
                if(!hasScope && clothClass && cloakTextureDraw(method,loader)) {
                    wrap(method,3,4,"beginClothTexture");cloakTextures++;hasScope=true;
                }
                if(!hasScope && clothClass && clothCosmeticDraw(method,loader)) {
                    wrap(method,4,3,"beginClothCosmetic");cloakTextures++;hasScope=true;
                }
                if(!hasScope && cloakTextureClass && containsText(method,"Either renderContext or modelRenderer must not be null!")
                        && (method.access&Opcodes.ACC_STATIC)!=0 && Type.getArgumentTypes(method.desc).length==6
                        &&Type.getReturnType(method.desc)==Type.VOID_TYPE) {
                    wrap(method,2,4,"beginCloakTexture");cloakTextures++;hasScope=true;
                }
                if(!hasScope && cloakReceiver && (method.access&Opcodes.ACC_STATIC)==0
                        &&Type.getReturnType(method.desc)==Type.VOID_TYPE && usesCloak(method)) {
                    int owner=playerSlot(method,loader);
                    if(owner>=0){wrap(method,0,owner);count++;hasScope=true;}
                }
                if(!hasBindHook) for(AbstractInsnNode insn:method.instructions.toArray()) {
                    if(!(insn instanceof MethodInsnNode))continue;
                    MethodInsnNode call=(MethodInsnNode)insn;
                    if(!call.name.equals("bridge$bindTexture"))continue;
                    method.instructions.insert(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"afterTextureBind","()V",false));
                    bindHooks++;
                }
                for(AbstractInsnNode insn:method.instructions.toArray()) {
                    if(!(insn instanceof MethodInsnNode))continue;
                    MethodInsnNode call=(MethodInsnNode)insn;
                    if("org/lwjgl/opengl/GL15".equals(call.owner)&&"glDeleteBuffers".equals(call.name)&&"(I)V".equals(call.desc)) {
                        AbstractInsnNode before=previousCode(call);
                        if(!(before instanceof MethodInsnNode&&"forgetBuffer".equals(((MethodInsnNode)before).name))) {
                            InsnList forget=new InsnList();forget.add(new InsnNode(Opcodes.DUP));
                            forget.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"me/napim/gomod/features/LunarCosmeticSizeRenderer","forgetBuffer","(I)V",false));
                            method.instructions.insertBefore(call,forget);bufferInvalidations++;
                        }
                    }
                    if(!call.owner.startsWith("org/lwjgl/opengl/")
                            || !(call.name.startsWith("glDrawArrays")||call.name.startsWith("glDrawElements")
                            ||call.name.startsWith("glDrawRangeElements")||call.name.startsWith("glMultiDraw")))continue;
                    AbstractInsnNode previous=previousCode(call);
                    int vertexBuffer=worldRendererSlot(name,method);
                    // Legacy native meshes own an AABB; GPU-skinned/static draws can use the bound VBO fallback.
                    if(vertexBuffer<0&&(method.access&Opcodes.ACC_STATIC)==0)vertexBuffer=0;
                    if(vertexBuffer<0){
                        int slot=0,candidate=-1,objects=0;
                        for(Type arg:Type.getArgumentTypes(method.desc)){
                            if(arg.getSort()==Type.OBJECT){candidate=slot;objects++;}slot+=arg.getSize();
                        }
                        if(objects==1)vertexBuffer=candidate;
                    }
                    String beforeDescriptor=vertexBuffer>=0?"(Ljava/lang/Object;)V":"()V";
                    boolean alreadyHooked=previous instanceof MethodInsnNode&&HELPER.equals(((MethodInsnNode)previous).owner)
                            &&"beforeModelDraw".equals(((MethodInsnNode)previous).name)
                            &&beforeDescriptor.equals(((MethodInsnNode)previous).desc);
                    if(previous instanceof MethodInsnNode&&HELPER.equals(((MethodInsnNode)previous).owner)
                            &&"beforeModelDraw".equals(((MethodInsnNode)previous).name)&&vertexBuffer>=0
                            &&"()V".equals(((MethodInsnNode)previous).desc)) {
                        // Upgrade an older no-argument upload hook so the pivot sees this mesh's vertices.
                        method.instructions.remove(previous);
                        alreadyHooked=false;
                    }
                    if(!alreadyHooked) {
                        InsnList before=new InsnList();
                        if(vertexBuffer>=0)before.add(new VarInsnNode(Opcodes.ALOAD,vertexBuffer));
                        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"beforeModelDraw",beforeDescriptor,false));
                        method.instructions.insertBefore(call,before);
                        if(vertexBuffer>=0)bufferDrawHooks++;else drawHooks++;
                    }
                    AbstractInsnNode next=nextCode(call);
                    if (!(next instanceof MethodInsnNode && HELPER.equals(((MethodInsnNode) next).owner)
                            && "finishNativeMeshDraw".equals(((MethodInsnNode) next).name))) {
                        if (next instanceof MethodInsnNode
                                && "me/napim/gomod/features/LunarCosmeticSizeRenderer".equals(((MethodInsnNode) next).owner)
                                && "endDraw".equals(((MethodInsnNode) next).name)) {
                            AbstractInsnNode oldEndModel = nextCode(next);
                            method.instructions.remove(next);
                            if (oldEndModel instanceof MethodInsnNode && HELPER.equals(((MethodInsnNode) oldEndModel).owner)
                                    && "endModelDraw".equals(((MethodInsnNode) oldEndModel).name)) {
                                method.instructions.remove(oldEndModel);
                            }
                        }
                        method.instructions.insert(call,new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"finishNativeMeshDraw","()V",false));
                        sizeDrawHooks++;
                    }
                }
                if (!scopeClass || hasScope || method.name.startsWith("<") || Type.getReturnType(method.desc)!=Type.VOID_TYPE) continue;
                Type[] args=Type.getArgumentTypes(method.desc);int cosmetic=-1,player=-1,candidates=0,direct=-1,directCount=0;
                int slot=(method.access&Opcodes.ACC_STATIC)==0?1:0;
                for(Type arg:args) {
                    if(arg.getSort()==Type.OBJECT) {
                        if(definition(arg.getInternalName(),loader,0)) { direct=slot;directCount++; }
                        if(cosmetic(arg.getInternalName(),loader)) {cosmetic=slot;candidates++;}
                        if(player(arg.getInternalName(),loader,0)) player=slot;
                    }
                    slot+=arg.getSize();
                }
                if(directCount==1) cosmetic=direct;
                else if(candidates!=1) continue;
                wrap(method,cosmetic,player);count++;
            }
            if(count==0&&bindHooks==0&&drawHooks==0&&sizeDrawHooks==0&&bufferDrawHooks==0&&bufferInvalidations==0&&cloakFrames==0&&cloakTextures==0&&fitModels==0&&deferredDraws==0&&deferredCaptures==0&&multiHatHooks==0) return null;
            ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);node.accept(writer);
            if(count>0)LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.scope",name,
                    "class="+name+" methods="+count+" schemaPreserved=true");
            if(bindHooks>0)LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.texture_bind_hook",name,
                    "class="+name+" calls="+bindHooks+" schemaPreserved=true");
            if(drawHooks>0)LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.final_draw_hook",name,
                    "class="+name+" calls="+drawHooks+" schemaPreserved=true");
            if(bufferDrawHooks>0)LunarConnectionDiagnostics.recordOnce("cosmetics.size.mesh_draw_hook",name,
                    "class="+name+" calls="+bufferDrawHooks+" pivot=geometry_bounds_center schemaPreserved=true");
            if(sizeDrawHooks>0)LunarConnectionDiagnostics.recordOnce("cosmetics.size.final_draw_hook",name,
                    "class="+name+" calls="+sizeDrawHooks+" pivot=post_attachment schemaPreserved=true");
            if(cloakFrames>0||cloakTextures>0)LunarConnectionDiagnostics.recordOnce("cosmetics.cloak.render_hooks",name,
                    "class="+name+" entrypoints="+cloakFrames+" nativeSelections="+cloakSelections
                            +" textureScopes="+cloakTextures+" schemaPreserved=true");
            if(fitModels>0)LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.fit_model_hook",name,
                    "class="+name+" methods="+fitModels+" schemaPreserved=true");
            if(multiHatHooks>0)LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.render_hook",name,
                    "class="+name+" method="+multiHatDispatch.method.name+multiHatDispatch.method.desc
                            +" playerSlot="+multiHatDispatch.playerSlot+" bundle="+multiHatDispatch.call.owner
                            +" strategy=cosmetic_player_bridge_receiver schemaPreserved=true");
            if(deferredDraws>0||deferredCaptures>0)LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.deferred_hook",name,
                    "class="+name+" captures="+deferredCaptures+" draws="+deferredDraws+" schemaPreserved=true");
            return writer.toByteArray();
        } catch(Throwable failure) {
            LunarConnectionDiagnostics.recordOnce("cosmetics.appearance.transform_failed",name,
                    "exception="+failure.getClass().getSimpleName());return null;
        }
    }
    private boolean renderClass(ClassNode node,ClassLoader loader) {
        if((node.access&(Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT))==0&&hasDeferredDrawShape(node,loader))return true;
        for(MethodNode method:node.methods)for(AbstractInsnNode insn:method.instructions)
            if(insn instanceof MethodInsnNode&&((MethodInsnNode)insn).name.equals("<init>")&&isModelDrawType(((MethodInsnNode)insn).owner,loader))return true;
        boolean viewX=false,viewY=false;
        for(FieldNode field:node.fields)if(field.desc.equals("F")&&(field.access&Opcodes.ACC_STATIC)!=0) {
            viewX|=field.name.equals("playerViewX");viewY|=field.name.equals("playerViewY");
        }
        if(viewX&&viewY)return true;
        if(node.interfaces.contains("java/lang/AutoCloseable")) {
            for(MethodNode method:node.methods)for(AbstractInsnNode instruction:method.instructions)
                if(instruction instanceof MethodInsnNode&&"bridge$getUniqueID".equals(((MethodInsnNode)instruction).name))return true;
        }
        for(MethodNode method:node.methods) for(AbstractInsnNode insn:method.instructions) if(insn instanceof MethodInsnNode) {
            String name=((MethodInsnNode)insn).name;
            if(name.equals("getAnimationProcessor")||name.equals("bridge$bindTexture")||name.equals("bridge$setupRenderState")||name.equals("bridge$framebufferClear")
                    ||name.equals("bridge$getClothCloakState")||name.equals("bridge$cloak"))return true;
        }
        return multiHatRenderDispatch(node,loader)!=null;
    }
    private static boolean isLegacyModelDrawType(String name) {
        if(MODEL_DRAW.equals(name))return true;
        for(String current:CURRENT_MODEL_DRAWS)if(current.equals(name))return true;
        return false;
    }
    private static boolean isModelDrawCandidate(String name,String constants) {
        return isLegacyModelDrawType(name)||(constants.contains("translucent")&&constants.contains("highlightEntry"));
    }
    private static boolean referencesLegacyModelDraw(String constants) {
        if(constants.contains(MODEL_DRAW))return true;
        for(String current:CURRENT_MODEL_DRAWS)if(constants.contains(current))return true;
        return false;
    }
    private boolean hasDeferredDrawShape(ClassNode node,ClassLoader loader) {
        if(node==null)return false;
        for(MethodNode method:node.methods) {
            if((method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))==0
                    &&deferredDrawEntry(node,method,loader))return true;
        }
        return false;
    }
    private boolean isModelDrawType(String name,ClassLoader loader) {
        if(name==null||!name.startsWith("com/moonsworth/lunar/client/"))return false;
        if(isLegacyModelDrawType(name))return true;
        Boolean cached=modelDraws.get(name);
        if(cached!=null)return cached.booleanValue();
        modelDraws.put(name,Boolean.FALSE);
        ClassNode node=classes.get(name);
        if(node==null) {
            byte[] raw=classBytes(name,loader);
            if(raw==null)return false;
            String constants=new String(raw,java.nio.charset.StandardCharsets.ISO_8859_1);
            if(!constants.contains("translucent")||!constants.contains("highlightEntry"))return false;
            node=new ClassNode();
            new ClassReader(raw).accept(node,0);
            classes.put(name,node);
        }
        boolean match=(node.access&(Opcodes.ACC_INTERFACE|Opcodes.ACC_ABSTRACT))==0&&hasDeferredDrawShape(node,loader);
        modelDraws.put(name,Boolean.valueOf(match));
        return match;
    }
    private boolean referencesModelDraw(byte[] bytes,String constants,ClassLoader loader) {
        if(referencesLegacyModelDraw(constants))return true;
        if(!constants.contains("<init>")||!constants.contains("com/moonsworth/lunar/client/"))return false;
        ClassReader reader=new ClassReader(bytes);
        char[] buf=new char[reader.getMaxStringLength()];
        int count=reader.getItemCount();
        for(int item=1;item<count;item++) {
            int offset=reader.getItem(item);
            if(offset<=0||(bytes[offset-1]&0xFF)!=7)continue;
            String ref=reader.readUTF8(offset,buf);
            if(ref!=null&&ref.startsWith("com/moonsworth/lunar/client/")&&isModelDrawType(ref,loader))return true;
        }
        return false;
    }
    /** Finds the renderer call that owns the actual player and passes Lunar's native render callback. */
    private MultiHatCallsite multiHatRenderDispatch(ClassNode node,ClassLoader loader) {
        if(node==null||!node.name.startsWith("com/moonsworth/lunar/client/"))return null;
        MultiHatCallsite match=null;
        for(MethodNode method:node.methods) {
            if((method.access&(Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))!=0||method.name.startsWith("<"))continue;
            int player=multiHatPlayerSlot(method,loader);if(player<0)continue;
            boolean readsCosmetics=false;
            for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode) {
                String called=((MethodInsnNode)instruction).name;
                readsCosmetics|=called.equals("bridge$getCosmetics");
            }
            if(!readsCosmetics)continue;
            for(AbstractInsnNode instruction:method.instructions) {
                if(!(instruction instanceof MethodInsnNode))continue;
                MethodInsnNode call=(MethodInsnNode)instruction;
                if(!call.name.equals("forEach")||!call.desc.equals("(Ljava/util/function/Consumer;)V")
                        ||call.getOpcode()==Opcodes.INVOKESTATIC||!isHatCollection(call.owner,loader))continue;
                if(match!=null)return null;
                match=new MultiHatCallsite(method,call,player);
            }
        }
        return match;
    }
    private int multiHatPlayerSlot(MethodNode method,ClassLoader loader) {
        int cosmeticsSlot=-1,spectatorSlot=-1;
        for(AbstractInsnNode instruction:method.instructions) {
            if(!(instruction instanceof MethodInsnNode))continue;
            MethodInsnNode call=(MethodInsnNode)instruction;
            if(!call.name.equals("bridge$getCosmetics")&&!call.name.equals("bridge$isSpectator"))continue;
            AbstractInsnNode receiver=previousOpcode(instruction);
            if(!(receiver instanceof VarInsnNode)||receiver.getOpcode()!=Opcodes.ALOAD)return -1;
            int slot=((VarInsnNode)receiver).var;
            if(call.name.equals("bridge$getCosmetics")) {
                if(cosmeticsSlot>=0&&cosmeticsSlot!=slot)return -1;
                cosmeticsSlot=slot;
            } else {
                if(spectatorSlot>=0&&spectatorSlot!=slot)return -1;
                spectatorSlot=slot;
            }
        }
        if(cosmeticsSlot>=0||spectatorSlot>=0)
            return cosmeticsSlot>=0&&(spectatorSlot<0||cosmeticsSlot==spectatorSlot)?cosmeticsSlot:-1;
        // Some Lunar layouts pass the player directly. Never guess from a lone object argument:
        // in the current renderer that argument is a pose stack, while the player is a local.
        return playerSlot(method,loader);
    }
    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        AbstractInsnNode previous=instruction==null?null:instruction.getPrevious();
        while(previous!=null&&previous.getOpcode()<0)previous=previous.getPrevious();
        return previous;
    }
    private boolean isHatCollection(String name,ClassLoader loader) {
        if(name==null||!name.startsWith("com/moonsworth/lunar/client/"))return false;
        ClassNode bundle=read(name,loader);
        return bundle!=null&&hatCollectionDispatch(bundle,loader)!=null;
    }
    /** Verifies Lunar's immutable cosmetic bundle iterator by its array/consumer bytecode shape. */
    private MethodNode hatCollectionDispatch(ClassNode node,ClassLoader loader) {
        if(node==null||!node.name.startsWith("com/moonsworth/lunar/client/"))return null;
        MethodNode match=null;
        for(MethodNode method:node.methods) {
            if(!method.name.equals("forEach")||!method.desc.equals("(Ljava/util/function/Consumer;)V")
                    ||(method.access&(Opcodes.ACC_STATIC|Opcodes.ACC_ABSTRACT|Opcodes.ACC_NATIVE))!=0)continue;
            boolean cosmeticArray=false,consumerAccept=false,arrayIteration=false;
            for(FieldNode field:node.fields) {
                if((field.access&Opcodes.ACC_STATIC)!=0)continue;
                Type type=Type.getType(field.desc);
                if(type.getSort()==Type.ARRAY&&type.getElementType().getSort()==Type.OBJECT
                        &&type.getElementType().getInternalName().startsWith("com/moonsworth/lunar/client/")
                        &&cosmetic(type.getElementType().getInternalName(),loader))cosmeticArray=true;
            }
            for(AbstractInsnNode instruction:method.instructions) {
                if(instruction.getOpcode()==Opcodes.AALOAD)arrayIteration=true;
                if(instruction instanceof MethodInsnNode) {
                    MethodInsnNode call=(MethodInsnNode)instruction;
                    if(call.owner.equals("java/util/function/Consumer")&&call.name.equals("accept")
                            &&call.desc.equals("(Ljava/lang/Object;)V"))consumerAccept=true;
                }
            }
            if(!cosmeticArray||!consumerAccept||!arrayIteration)continue;
            if(match!=null)return null;
            match=method;
        }
        return match;
    }
    private static void wrapMultiHatCallsite(MethodNode method,MultiHatCallsite callsite) {
        int wrappedConsumer=method.maxLocals++;
        InsnList before=new InsnList();
        // The collection and original consumer are already on the operand stack for forEach.
        // Keep that pair intact while passing duplicate arguments to the wrapper; otherwise
        // Lunar's invokevirtual would receive only the returned Consumer and fail verification.
        before.add(new InsnNode(Opcodes.DUP2));
        before.add(new VarInsnNode(Opcodes.ALOAD,callsite.playerSlot));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"wrapMultiSameTypeHatConsumer",
                "(Ljava/lang/Object;Ljava/util/function/Consumer;Ljava/lang/Object;)Ljava/util/function/Consumer;",false));
        before.add(new InsnNode(Opcodes.SWAP));
        before.add(new InsnNode(Opcodes.POP));
        before.add(new InsnNode(Opcodes.DUP));
        before.add(new VarInsnNode(Opcodes.ASTORE,wrappedConsumer));
        method.instructions.insertBefore(callsite.call,before);
        InsnList after=new InsnList();after.add(new VarInsnNode(Opcodes.ALOAD,wrappedConsumer));
        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"finishMultiSameTypeHatConsumer",
                "(Ljava/util/function/Consumer;)V",false));
        method.instructions.insert(callsite.call,after);
    }
    private static final class MultiHatCallsite {
        final MethodNode method;
        final MethodInsnNode call;
        final int playerSlot;
        MultiHatCallsite(MethodNode method,MethodInsnNode call,int playerSlot) {
            this.method=method;this.call=call;this.playerSlot=playerSlot;
        }
    }
    private boolean deferredDrawEntry(ClassNode node,MethodNode method,ClassLoader loader) {
        if(!method.name.equals("render")||(method.access&Opcodes.ACC_STATIC)!=0||Type.getReturnType(method.desc)!=Type.VOID_TYPE)return false;
        Type[] args=Type.getArgumentTypes(method.desc);
        if(args.length!=3||args[0].getSort()!=Type.OBJECT||args[1]!=Type.BOOLEAN_TYPE||args[2]!=Type.INT_TYPE)return false;
        if(!matrixContext(args[0].getInternalName(),loader))return false;
        boolean translucent=false;
        for(MethodNode other:node.methods)translucent|=other.name.equals("translucent")&&other.desc.equals("()Z");
        return highlightEntryGetter(node,loader)!=null&&translucent;
    }
    private MethodNode highlightEntryGetter(ClassNode node,ClassLoader loader) {
        MethodNode match=null;
        for(MethodNode method:node.methods) {
            Type result=Type.getReturnType(method.desc);
            if(!method.name.equals("highlightEntry")||Type.getArgumentTypes(method.desc).length!=0
                    ||result.getSort()!=Type.OBJECT||!definition(result.getInternalName(),loader,0))continue;
            if(match!=null)return null;
            match=method;
        }
        return match;
    }
    private boolean matrixContext(String name,ClassLoader loader) {
        ClassNode context=read(name,loader);if(context==null)return false;
        boolean push=false,pop=false,scale=false,translate=false;
        for(MethodNode method:context.methods) {
            push|=method.name.equals("push")&&method.desc.equals("()V");
            pop|=method.name.equals("pop")&&method.desc.equals("()V");
            scale|=method.name.equals("scale")&&method.desc.equals("(FFF)V");
            translate|=method.name.equals("translate")&&method.desc.equals("(DDD)V");
        }
        return push&&pop&&scale&&translate;
    }
    private boolean previewFactory(ClassNode node,ClassLoader loader) {
        if(!node.interfaces.contains("java/lang/AutoCloseable"))return false;
        for(MethodNode method:node.methods)if(method.name.equals("<init>")) {
            Type[] args=Type.getArgumentTypes(method.desc);
            if(args.length==1&&args[0].getSort()==Type.OBJECT&&cosmetic(args[0].getInternalName(),loader))return true;
        }
        return false;
    }
    private DrawInput drawInput(ClassNode node,MethodNode method,MethodInsnNode constructor,ClassLoader loader) {
        int slot=(method.access&Opcodes.ACC_STATIC)==0?1:0,selected=-1,count=0,owner=-1,owners=0;
        List<Integer> cosmeticSlots=null,ownerSlots=null;
        List<String> cosmeticTypes=null,ownerTypes=null;
        for(Type arg:Type.getArgumentTypes(method.desc)) {
            if(arg.getSort()==Type.OBJECT) {
                String internal=arg.getInternalName();
                if(cosmetic(internal,loader)){
                    selected=slot;count++;
                    if(cosmeticSlots==null){cosmeticSlots=new ArrayList<>(2);cosmeticTypes=new ArrayList<>(2);}
                    cosmeticSlots.add(slot);cosmeticTypes.add(internal);
                }
                if(player(internal,loader,0)){
                    owner=slot;owners++;
                    if(ownerSlots==null){ownerSlots=new ArrayList<>(2);ownerTypes=new ArrayList<>(2);}
                    ownerSlots.add(slot);ownerTypes.add(internal);
                }
            }
            slot+=arg.getSize();
        }
        if(owners>1) {
            owner=resolveUniquePlayerSlot(method,ownerSlots,ownerTypes,loader);
            if(owner<0)return null;
        }
        DrawInput input=null;
        if(count==1)input=new DrawInput(selected,owner,null);
        if(input==null) {
            // The model's last constructor argument is its exact native cosmetic definition.
            AbstractInsnNode producer=previousCode(constructor);
            if(producer instanceof MethodInsnNode) {
                MethodInsnNode getter=(MethodInsnNode)producer;Type result=Type.getReturnType(getter.desc);AbstractInsnNode receiver=previousCode(getter);
                if(getter.getOpcode()!=Opcodes.INVOKESTATIC&&Type.getArgumentTypes(getter.desc).length==0
                        &&result.getSort()==Type.OBJECT&&definition(result.getInternalName(),loader,0)
                        &&receiver instanceof VarInsnNode&&receiver.getOpcode()==Opcodes.ALOAD&&cosmetic(getter.owner,loader))
                    input=new DrawInput(((VarInsnNode)receiver).var,owner,null);
            }
        }
        if(input==null&&count>1) {
            int uniqueCosmetic=resolveUniqueCosmeticSlot(cosmeticSlots,cosmeticTypes,loader);
            if(uniqueCosmetic<0)return null;
            input=new DrawInput(uniqueCosmetic,owner,null);
        }
        if(input==null&&(method.access&Opcodes.ACC_STATIC)!=0)return null;
        if(input==null&&cosmetic(node.name,loader))input=new DrawInput(0,owner,null);
        FieldInsnNode selectedField=null;
        if(input==null)for(FieldNode field:node.fields)if((field.access&(Opcodes.ACC_STATIC|Opcodes.ACC_FINAL))==Opcodes.ACC_FINAL) {
            Type type=Type.getType(field.desc);
            if(type.getSort()!=Type.OBJECT||!cosmetic(type.getInternalName(),loader))continue;
            if(selectedField!=null)return null;
            selectedField=new FieldInsnNode(Opcodes.GETFIELD,node.name,field.name,field.desc);
        }
        if(input==null&&selectedField!=null)input=new DrawInput(0,owner,selectedField);
        if(input==null||owner>=0)return input;
        for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode) {
            MethodInsnNode getter=(MethodInsnNode)instruction;Type result=Type.getReturnType(getter.desc);AbstractInsnNode receiver=previousCode(getter);
            if(getter.getOpcode()==Opcodes.INVOKESTATIC||Type.getArgumentTypes(getter.desc).length!=0||result.getSort()!=Type.OBJECT
                    ||!player(result.getInternalName(),loader,0)||!(receiver instanceof VarInsnNode)||receiver.getOpcode()!=Opcodes.ALOAD)continue;
            if(input.playerGetter!=null&&(!input.playerGetter.owner.equals(getter.owner)||!input.playerGetter.name.equals(getter.name)||input.player!=((VarInsnNode)receiver).var))return null;
            input.player=((VarInsnNode)receiver).var;input.playerGetter=getter;
        }
        return input;
    }
    private static final class DrawInput {
        final int slot;int player;final FieldInsnNode field;MethodInsnNode playerGetter;
        DrawInput(int slot,int player,FieldInsnNode field){this.slot=slot;this.player=player;this.field=field;}
    }
    private static void wrapDeferredDraw(MethodNode method,String owner,MethodNode entryGetter,boolean suppressStackOffset) {
        if(entryGetter==null)return;
        AbstractInsnNode[] original=method.instructions.toArray();LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();
        int getterOpcode=(entryGetter.access&Opcodes.ACC_PRIVATE)!=0?Opcodes.INVOKESPECIAL:Opcodes.INVOKEVIRTUAL;
        InsnList head=new InsnList();head.add(new VarInsnNode(Opcodes.ALOAD,0));head.add(new VarInsnNode(Opcodes.ALOAD,1));
        head.add(new VarInsnNode(Opcodes.ALOAD,0));head.add(new MethodInsnNode(getterOpcode,owner,entryGetter.name,entryGetter.desc,false));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,
                suppressStackOffset?"beginCurrentDeferredDraw":"beginDeferredDraw",
                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",false));
        head.add(start);method.instructions.insert(head);
        for(AbstractInsnNode instruction:original)if(instruction.getOpcode()==Opcodes.RETURN)method.instructions.insertBefore(instruction,new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"end","()V",false));
        method.instructions.add(end);method.instructions.add(handler);method.instructions.add(new FrameNode(Opcodes.F_FULL,0,new Object[0],1,new Object[]{"java/lang/Throwable"}));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"end","()V",false));method.instructions.add(new InsnNode(Opcodes.ATHROW));method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));
    }
    /** Current Lunar previews keep the equipped wrapper in their sole constructor parameter. */
    private FieldInsnNode thumbnailCosmetic(ClassNode node,ClassLoader loader) {
        FieldInsnNode selected=null;
        for(MethodNode constructor:node.methods) {
            if(!constructor.name.equals("<init>"))continue;
            Type[] args=Type.getArgumentTypes(constructor.desc);
            if(args.length!=1||args[0].getSort()!=Type.OBJECT||!cosmetic(args[0].getInternalName(),loader))continue;
            for(AbstractInsnNode instruction:constructor.instructions) {
                if(!(instruction instanceof FieldInsnNode)||instruction.getOpcode()!=Opcodes.PUTFIELD)continue;
                FieldInsnNode field=(FieldInsnNode)instruction;
                AbstractInsnNode value=previousCode(field),receiver=value==null?null:previousCode(value);
                if(!field.owner.equals(node.name)||!field.desc.equals(args[0].getDescriptor())
                        ||!(value instanceof VarInsnNode)||value.getOpcode()!=Opcodes.ALOAD||((VarInsnNode)value).var!=1
                        ||!(receiver instanceof VarInsnNode)||receiver.getOpcode()!=Opcodes.ALOAD||((VarInsnNode)receiver).var!=0)continue;
                if(selected!=null&&!selected.name.equals(field.name))return null;
                selected=field;
            }
        }
        return selected;
    }
    private boolean thumbnailEntry(MethodNode method,ClassLoader loader) {
        if((method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))!=Opcodes.ACC_PUBLIC
                ||Type.getReturnType(method.desc)!=Type.VOID_TYPE)return false;
        Type[] args=Type.getArgumentTypes(method.desc);
        if(args.length!=6||args[0].getSort()!=Type.OBJECT||args[1].getSort()!=Type.OBJECT)return false;
        for(int i=2;i<6;i++)if(args[i]!=Type.FLOAT_TYPE)return false;
        return playerSlot(method,loader)==1;
    }
    private int resolveUniquePlayerSlot(MethodNode method,List<Integer> slots,List<String> types,ClassLoader loader) {
        int directSlot=-1,directCount=0;
        for(int i=0;i<slots.size();i++) {
            if(directPlayer(types.get(i),loader)) {
                directSlot=slots.get(i);
                directCount++;
            }
        }
        if(directCount==1)return directSlot;
        Set<Integer> candidateSet=new HashSet<>(slots);
        Set<Integer> usedSlots=new HashSet<>();
        for(AbstractInsnNode insn:method.instructions) {
            if(!(insn instanceof MethodInsnNode))continue;
            MethodInsnNode call=(MethodInsnNode)insn;
            if(!call.name.equals("bridge$getUniqueID")&&!call.name.equals("bridge$getCosmetics")
                    &&!call.name.equals("bridge$getWornCosmetics")&&!call.name.equals("bridge$isEmoting")
                    &&!call.name.equals("bridge$isVisiblyCrouching")&&!call.name.equals("getDummyPlayerType"))continue;
            AbstractInsnNode receiver=previousCode(call);
            if(receiver instanceof VarInsnNode&&receiver.getOpcode()==Opcodes.ALOAD
                    &&candidateSet.contains(((VarInsnNode)receiver).var))
                usedSlots.add(((VarInsnNode)receiver).var);
        }
        return usedSlots.size()==1?usedSlots.iterator().next().intValue():-1;
    }
    private boolean directPlayer(String name,ClassLoader loader) {
        ClassNode node=read(name,loader);
        if(node==null)return false;
        for(MethodNode method:node.methods)
            if(method.name.equals("bridge$getUniqueID")&&method.desc.equals("()Ljava/util/UUID;"))return true;
        return false;
    }
    private int resolveUniqueCosmeticSlot(List<Integer> slots,List<String> types,ClassLoader loader) {
        int match=-1,matches=0;
        for(int i=0;i<slots.size();i++) {
            if(hasUuidField(types.get(i),loader)) {
                match=slots.get(i);
                matches++;
            }
        }
        return matches==1?match:-1;
    }
    private boolean hasUuidField(String name,ClassLoader loader) {
        for(int depth=0;name!=null&&depth<12;depth++) {
            ClassNode node=read(name,loader);
            if(node==null)break;
            for(FieldNode field:node.fields)
                if((field.access&Opcodes.ACC_STATIC)==0&&"Ljava/util/UUID;".equals(field.desc))return true;
            name=node.superName;
        }
        return false;
    }
    private byte[] classBytes(String name,ClassLoader loader) {
        try(InputStream input=classStream(name,loader)) {
            if(input==null)return null;
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int n;
            while((n=input.read(buffer))>=0)output.write(buffer,0,n);
            return output.toByteArray();
        } catch(Exception failure) { return null; }
    }
    private ClassNode read(String name,ClassLoader loader) {
        if(name==null||name.startsWith("java/")||name.startsWith("javax/"))return null;
        ClassNode cached=classes.get(name);if(cached!=null)return cached;
        byte[] raw=classBytes(name,loader);
        if(raw==null)return null;
        try {
            ClassNode node=new ClassNode();new ClassReader(raw).accept(node,0);classes.put(name,node);return node;
        }catch(Exception failure){return null;}
    }
    private InputStream classStream(String name,ClassLoader loader)throws IOException {
        try { InputStream input=loader.getResourceAsStream(name+".class");if(input!=null)return input; }
        catch(RuntimeException unavailable){ }
        // Genesis/SandboxedMixinLoader may hide native resources. Read the active native archive without loading classes.
        if(sourceArchive==null&&sourceFile!=null&&sourceFile.isFile())sourceArchive=new JarFile(sourceFile);
        JarEntry entry=sourceArchive==null?null:sourceArchive.getJarEntry(name+".class");
        return entry==null?null:sourceArchive.getInputStream(entry);
    }
    private boolean definition(String name,ClassLoader loader,int depth) {
        if(name==null||depth>12)return false;Boolean cached=definitions.get(name);if(cached!=null)return cached;
        ClassNode node=read(name,loader);if(node==null)return false;
        boolean result=false;
        for(MethodNode method:node.methods)if(method.name.equals("<init>")){
            Type[] p=Type.getArgumentTypes(method.desc);
            if(p.length==13&&p[0]==Type.LONG_TYPE&&p[1].equals(Type.getType(String.class))&&p[4]==Type.BOOLEAN_TYPE
                    &&p[5]==Type.LONG_TYPE&&p[6].equals(Type.getType(java.time.Instant.class))
                    &&p[7].equals(Type.getType(List.class))&&p[8].equals(Type.getType(List.class))
                    &&p[9]==Type.BOOLEAN_TYPE&&p[12]==Type.BOOLEAN_TYPE)result=true;
        }
        if(!result)result=definition(node.superName,loader,depth+1);definitions.put(name,result);return result;
    }
    private boolean cosmetic(String name,ClassLoader loader) {
        if(definition(name,loader,0))return true;
        for(int depth=1;depth<=2;depth++) {
            int count=cosmeticPaths(name,loader,depth,new HashSet<String>());
            if(count>0)return count==1;
        }
        return false;
    }
    private int cosmeticPaths(String name,ClassLoader loader,int depth,Set<String> visiting) {
        if(name==null||!visiting.add(name))return 0;
        String visitedName=name;
        int count=0;
        try {
            for(int parent=0;name!=null&&parent<12;parent++){
                ClassNode node=read(name,loader);if(node==null)break;
                for(FieldNode field:node.fields)if((field.access&Opcodes.ACC_STATIC)==0){
                    Type type=Type.getType(field.desc);if(type.getSort()!=Type.OBJECT)continue;
                    if(depth==1) {if(definition(type.getInternalName(),loader,0))count++;}
                    else if((field.access&Opcodes.ACC_FINAL)!=0&&type.getInternalName().startsWith("com/moonsworth/lunar/client/"))
                        count+=cosmeticPaths(type.getInternalName(),loader,depth-1,visiting);
                }
                name=node.superName;
            }
            return count;
        } finally {visiting.remove(visitedName);}
    }
    private boolean player(String name,ClassLoader loader,int depth) {
        if(name==null||depth>16)return false;Boolean cached=players.get(name);if(cached!=null)return cached;
        ClassNode node=read(name,loader);if(node==null)return false;
        for(MethodNode method:node.methods)if(method.name.equals("bridge$getUniqueID")&&method.desc.equals("()Ljava/util/UUID;")){
            players.put(name,true);return true;
        }
        boolean found=player(node.superName,loader,depth+1);
        if(!found)for(String itf:node.interfaces)if(player(itf,loader,depth+1)){found=true;break;}
        players.put(name,found);return found;
    }
    private int playerSlot(MethodNode method,ClassLoader loader) {
        int slot=(method.access&Opcodes.ACC_STATIC)==0?1:0,result=-1;
        for(Type arg:Type.getArgumentTypes(method.desc)) {
            if(arg.getSort()==Type.OBJECT&&player(arg.getInternalName(),loader,0)) {
                if(result>=0)return -1;result=slot;
            }
            slot+=arg.getSize();
        }
        return result;
    }
    private boolean cloakEntry(MethodNode method,ClassLoader loader) {
        if((method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))!=Opcodes.ACC_PUBLIC
                ||(method.access&(Opcodes.ACC_BRIDGE|Opcodes.ACC_SYNTHETIC))!=0
                ||Type.getReturnType(method.desc)!=Type.VOID_TYPE)return false;
        Type[] args=Type.getArgumentTypes(method.desc);
        if(args.length!=5&&args.length!=10)return false;
        for(int i=0;i<3;i++)if(args[i].getSort()!=Type.OBJECT)return false;
        for(int i=3;i<args.length;i++)if(!args[i].equals(args.length==5?Type.INT_TYPE:Type.FLOAT_TYPE))return false;
        return playerSlot(method,loader)==2;
    }
    private boolean cloakTextureDraw(MethodNode method,ClassLoader loader) {
        if((method.access&(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC))!=Opcodes.ACC_PRIVATE
                ||Type.getReturnType(method.desc)!=Type.VOID_TYPE)return false;
        Type[] args=Type.getArgumentTypes(method.desc);
        return args.length==5&&args[0].getSort()==Type.OBJECT&&args[1].getSort()==Type.OBJECT
                &&args[2].getSort()==Type.OBJECT&&args[3].getSort()==Type.OBJECT&&args[4].equals(Type.INT_TYPE)
                &&playerSlot(method,loader)==3;
    }
    private boolean clothCosmeticDraw(MethodNode method,ClassLoader loader) {
        if((method.access&(Opcodes.ACC_PRIVATE|Opcodes.ACC_STATIC))!=Opcodes.ACC_PRIVATE
                ||Type.getReturnType(method.desc)!=Type.VOID_TYPE)return false;
        Type[] args=Type.getArgumentTypes(method.desc);
        return args.length==6&&args[0].getSort()==Type.OBJECT&&args[1].getSort()==Type.OBJECT
                &&args[2].getSort()==Type.OBJECT&&args[3].getSort()==Type.OBJECT
                &&args[4].equals(Type.BOOLEAN_TYPE)&&args[5].equals(Type.INT_TYPE)
                &&playerSlot(method,loader)==3&&cosmetic(args[3].getInternalName(),loader);
    }
    private boolean fitModelEntry(MethodNode method,ClassLoader loader) {
        if((method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC|Opcodes.ACC_BRIDGE|Opcodes.ACC_SYNTHETIC))!=Opcodes.ACC_PUBLIC
                ||Type.getReturnType(method.desc)!=Type.VOID_TYPE)return false;
        Type[] args=Type.getArgumentTypes(method.desc);
        if(args.length!=3||args[0].getSort()!=Type.OBJECT||args[1].getSort()!=Type.OBJECT||args[2].getSort()!=Type.OBJECT)return false;
        ClassNode model=read(args[1].getInternalName(),loader);if(model==null)return false;
        Set<String> fields=new HashSet<>();boolean id=false;
        for(int depth=0;model!=null&&depth<8;depth++,model=read(model.superName,loader)) {
            for(FieldNode field:model.fields)if((field.access&Opcodes.ACC_STATIC)==0) {
                fields.add(field.name);serializedNames(field.visibleAnnotations,fields);serializedNames(field.invisibleAnnotations,fields);
            }
            for(MethodNode getter:model.methods)if(getter.name.equals("getId")&&getter.desc.equals("()Ljava/lang/String;"))id=true;
        }
        return id&&fields.contains("emoteOnHover")&&fields.contains("renderContext")&&fields.contains("renderNametag");
    }
    private static void serializedNames(List<AnnotationNode> annotations,Set<String> names) {
        if(annotations==null)return;
        for(AnnotationNode annotation:annotations)if(annotation.desc.equals("Lcom/google/gson/annotations/SerializedName;")&&annotation.values!=null)
            for(int i=0;i+1<annotation.values.size();i+=2)if(annotation.values.get(i).equals("value")&&annotation.values.get(i+1) instanceof String)
                names.add((String)annotation.values.get(i+1));
    }
    private static boolean hasCall(MethodNode method,String owner,String name) {
        for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof MethodInsnNode) {
            MethodInsnNode call=(MethodInsnNode)instruction;if(call.owner.equals(owner)&&call.name.equals(name))return true;
        }
        return false;
    }
    private static void wrapFitModel(MethodNode method) {
        AbstractInsnNode[] original=method.instructions.toArray();
        LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();InsnList head=new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD,2));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,FIT_APPEARANCE,"beginModel","(Ljava/lang/Object;)V",false));
        head.add(start);method.instructions.insert(head);
        for(AbstractInsnNode instruction:original)if(instruction.getOpcode()==Opcodes.RETURN)
            method.instructions.insertBefore(instruction,new MethodInsnNode(Opcodes.INVOKESTATIC,FIT_APPEARANCE,"endModel","()V",false));
        method.instructions.add(end);method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_FULL,0,new Object[0],1,new Object[]{"java/lang/Throwable"}));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,FIT_APPEARANCE,"endModel","()V",false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));method.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));
    }
    private static boolean usesCloak(MethodNode method) {
        for(AbstractInsnNode insn:method.instructions)if(insn instanceof FieldInsnNode
                &&"CLOAK".equals(((FieldInsnNode)insn).name))return true;
        return false;
    }
    private static boolean containsText(MethodNode method,String text) {
        for(AbstractInsnNode insn:method.instructions)if(insn instanceof LdcInsnNode
                &&text.equals(((LdcInsnNode)insn).cst))return true;
        return false;
    }
    private static AbstractInsnNode previousCode(AbstractInsnNode instruction) {
        AbstractInsnNode current=instruction.getPrevious();
        while(current instanceof LabelNode||current instanceof LineNumberNode||current instanceof FrameNode)current=current.getPrevious();
        return current;
    }
    private static AbstractInsnNode nextCode(AbstractInsnNode instruction) {
        AbstractInsnNode current=instruction.getNext();
        while(current instanceof LabelNode||current instanceof LineNumberNode||current instanceof FrameNode)current=current.getNext();
        return current;
    }
    private static int worldRendererSlot(String className,MethodNode method) {
        if(!UPLOADER.equals(className)||!"draw".equals(method.name)
                ||Type.getReturnType(method.desc)!=Type.VOID_TYPE)return -1;
        int slot=(method.access&Opcodes.ACC_STATIC)==0?1:0,result=-1;
        for(Type argument:Type.getArgumentTypes(method.desc)) {
            if(argument.getSort()==Type.OBJECT
                    &&"net/minecraft/client/renderer/WorldRenderer".equals(argument.getInternalName())) {
                if(result>=0)return -1;
                result=slot;
            }
            slot+=argument.getSize();
        }
        return result;
    }
    private static void wrap(MethodNode method,int cosmetic,int player) {
        wrap(method,cosmetic,player,"begin");
    }
    private static void wrap(MethodNode method,int cosmetic,int player,String beginMethod) {
        wrap(method,cosmetic,player,beginMethod,null);
    }
    private static void wrap(MethodNode method,int cosmetic,int player,String beginMethod,FieldInsnNode cosmeticField) {
        AbstractInsnNode[] original=method.instructions.toArray();
        LabelNode begin=new LabelNode(),end=new LabelNode(),handler=new LabelNode();InsnList head=new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD,cosmetic));
        if(cosmeticField!=null)head.add(new FieldInsnNode(Opcodes.GETFIELD,cosmeticField.owner,cosmeticField.name,cosmeticField.desc));
        head.add(player<0?new InsnNode(Opcodes.ACONST_NULL):new VarInsnNode(Opcodes.ALOAD,player));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,beginMethod,"(Ljava/lang/Object;Ljava/lang/Object;)V",false));
        head.add(begin);method.instructions.insert(head);
        for(AbstractInsnNode insn:original)if(insn.getOpcode()==Opcodes.RETURN){
            InsnList tail=new InsnList();tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"end","()V",false));
            method.instructions.insertBefore(insn,tail);
        }
        method.instructions.add(end);method.instructions.add(handler);
        // The handler uses no locals, so existing parameters may safely merge to TOP.
        method.instructions.add(new FrameNode(Opcodes.F_FULL,0,new Object[0],1,new Object[]{"java/lang/Throwable"}));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"end","()V",false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(begin,end,handler,null));
    }
    private static void wrapCloak(MethodNode method,int player) {
        AbstractInsnNode[] original=method.instructions.toArray();
        LabelNode proceed=new LabelNode(),end=new LabelNode(),handler=new LabelNode();InsnList head=new InsnList();
        head.add(new VarInsnNode(Opcodes.ALOAD,player));
        head.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"beginCloakRender","(Ljava/lang/Object;)Z",false));
        head.add(new JumpInsnNode(Opcodes.IFEQ,proceed));head.add(new InsnNode(Opcodes.RETURN));
        head.add(proceed);head.add(new FrameNode(Opcodes.F_SAME,0,null,0,null));method.instructions.insert(head);
        for(AbstractInsnNode insn:original)if(insn.getOpcode()==Opcodes.RETURN)
            method.instructions.insertBefore(insn,new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"endCloakRender","()V",false));
        method.instructions.add(end);method.instructions.add(handler);
        method.instructions.add(new FrameNode(Opcodes.F_FULL,0,new Object[0],1,new Object[]{"java/lang/Throwable"}));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"endCloakRender","()V",false));
        method.instructions.add(new InsnNode(Opcodes.ATHROW));
        method.tryCatchBlocks.add(new TryCatchBlockNode(proceed,end,handler,null));
    }
}
