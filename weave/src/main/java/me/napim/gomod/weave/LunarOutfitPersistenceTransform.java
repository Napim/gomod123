package me.napim.gomod.weave;

import me.napim.gomod.GomodReleaseBuild;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Finds the native Fits initializer/publisher by protobuf types and state keys. */
public final class LunarOutfitPersistenceTransform {
    private static final String HOOK="me/napim/gomod/features/LunarOutfitPersistence";
    private static final String INIT="(Ljava/util/List;Lcom/lunarclient/websocket/cosmetic/v2/OutfitTree;)V";
    private LunarOutfitPersistenceTransform(){}
    public static boolean mayContain(String name,byte[] bytes){
        if(name==null||bytes==null||!name.startsWith("com/moonsworth/lunar/"))return false;
        String pool=new String(bytes,StandardCharsets.ISO_8859_1);
        return pool.contains("defaultOutfitId")&&pool.contains("outfits")&&pool.contains("cosmetic/v2/OutfitTree");
    }
    public static boolean isLoadedTarget(Class<?> type){
        if(type==null)return false;
        String name=type.getName().replace('.','/');
        if(!name.startsWith("com/moonsworth/lunar/"))return false;
        try(InputStream in=type.getResourceAsStream("/"+name+".class")){
            if(in==null)return false;ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[4096];int n;
            while((n=in.read(block))!=-1)out.write(block,0,n);return mayContain(name,out.toByteArray());
        }catch(Exception ignored){return false;}
    }
    public static byte[] transform(String name,byte[] bytes){
        if(!mayContain(name,bytes))return null;
        ClassReader reader=new ClassReader(bytes);ClassNode node=new ClassNode();reader.accept(node,0);
        MethodNode init=null,publisher=null;int inits=0,publishers=0;
        for(MethodNode method:node.methods){
            boolean fits=false,selected=false;
            for(AbstractInsnNode instruction:method.instructions){
                if(instruction instanceof MethodInsnNode&&((MethodInsnNode)instruction).owner.equals(HOOK))return null;
                if(instruction instanceof LdcInsnNode){Object value=((LdcInsnNode)instruction).cst;fits|="outfits".equals(value);selected|="defaultOutfitId".equals(value);}
            }
            if((method.access&Opcodes.ACC_STATIC)!=0)continue;
            if(method.desc.equals(INIT)){init=method;inits++;}
            if(method.desc.equals("()V")&&fits&&selected){publisher=method;publishers++;}
        }
        if(inits!=1||publishers!=1)return null;
        LabelNode start=new LabelNode(),end=new LabelNode(),handler=new LabelNode();
        InsnList head=new InsnList();head.add(new VarInsnNode(Opcodes.ALOAD,0));head.add(new VarInsnNode(Opcodes.ALOAD,1));head.add(new VarInsnNode(Opcodes.ALOAD,2));head.add(call("beginLogin","(Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;)V"));head.add(start);init.instructions.insert(head);
        for(AbstractInsnNode instruction:init.instructions.toArray())if(instruction.getOpcode()==Opcodes.RETURN)init.instructions.insertBefore(instruction,call("endLogin","()V"));
        init.instructions.add(end);init.instructions.add(handler);
        init.instructions.add(new FrameNode(Opcodes.F_FULL,3,new Object[]{node.name,"java/util/List","com/lunarclient/websocket/cosmetic/v2/OutfitTree"},1,new Object[]{"java/lang/Throwable"}));
        init.instructions.add(call("abortLogin","()V"));init.instructions.add(new InsnNode(Opcodes.ATHROW));
        init.tryCatchBlocks.add(new TryCatchBlockNode(start,end,handler,null));
        for(AbstractInsnNode instruction:publisher.instructions.toArray())if(instruction.getOpcode()==Opcodes.RETURN){InsnList tail=new InsnList();tail.add(new VarInsnNode(Opcodes.ALOAD,0));tail.add(call("rememberNative","(Ljava/lang/Object;)V"));publisher.instructions.insertBefore(instruction,tail);}
        ClassWriter writer=new ClassWriter(reader,ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
    }
    private static MethodInsnNode call(String method,String desc){return new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,method,desc,false);}
}
