package me.napim.gomod.weave;

import com.google.gson.JsonObject;
import java.io.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Derives data from installed native bytecode; never executes discovery candidates. */
final class LunarNativeLayoutDiscovery {
    private static final String PREFIX="com/moonsworth/lunar/client/";
    private static final String BUILDER="Lcom/lunarclient/websocket/handshake/v1/Handshake$Builder;";
    private LunarNativeLayoutDiscovery() {}

    static LunarCompatibilityManifest.Mapping discover(File file,String digest)throws IOException {
        ClassNode service=null,manager=null;
        byte[] serviceBytes=null,managerBytes=null;
        Map<String,String> labels=null;
        MethodNode getter=null,connect=null;
        try(JarFile jar=new JarFile(file)) {
            Enumeration<JarEntry> entries=jar.entries();
            long total=0;int count=0;
            while(entries.hasMoreElements()) {
                JarEntry entry=entries.nextElement();String name=entry.getName();
                if(entry.isDirectory()||!name.startsWith(PREFIX)||!name.endsWith(".class"))continue;
                if(++count>50_000||entry.getSize()>4_194_304L)throw invalid("scan-limit");
                byte[] bytes;
                try(InputStream input=jar.getInputStream(entry)){bytes=LunarCompatibilityManifest.readBounded(input,4_194_304);}
                total+=bytes.length;if(total>268_435_456L)throw invalid("scan-limit");
                boolean endpoints=contains(bytes,"Authenticator")&&contains(bytes,"AssetServer")&&contains(bytes,"Styngr");
                boolean assets=contains(bytes,"com/lunarclient/websocket/handshake/v1/Handshake$Builder")
                        &&contains(bytes,"java/lang/Thread")&&contains(bytes,"Connected to the AssetServer");
                if(!endpoints&&!assets)continue;
                ClassNode node=node(bytes);
                if(!name.equals(node.name+".class"))throw invalid("entry-name");
                if(endpoints) {
                    Map<String,String> fields=endpointFields(node);
                    if(fields!=null) {
                        if(service!=null)throw invalid("ambiguous-service");
                        service=node;serviceBytes=bytes;labels=fields;
                    }
                }
                if(assets) {
                    MethodNode singleton=singletonGetter(node);
                    MethodNode reconnect=singleton==null?null:reconnectMethod(node);
                    if(reconnect!=null) {
                        if(manager!=null)throw invalid("ambiguous-manager");
                        manager=node;managerBytes=bytes;getter=singleton;connect=reconnect;
                    }
                }
            }
            if(service==null||manager==null)throw invalid("unsupported-connection-flow");
            String info=Type.getArgumentTypes(connect.desc)[0].getInternalName();
            JarEntry infoEntry=jar.getJarEntry(info+".class");
            if(infoEntry==null)throw invalid("missing-reconnect-info");
            byte[] infoBytes;
            try(InputStream input=jar.getInputStream(infoEntry)){infoBytes=LunarCompatibilityManifest.readBounded(input,4_194_304);}
            JsonObject row=new JsonObject();row.addProperty("id","local-"+digest.substring(0,16));row.addProperty("lunarSha256",digest);
            JsonObject endpoint=new JsonObject();endpoint.addProperty("className",service.name);
            endpoint.addProperty("sha256",LunarCompatibilityManifest.sha256(serviceBytes));
            endpoint.addProperty("api",labels.get("Api"));endpoint.addProperty("authenticator",labels.get("Authenticator"));
            endpoint.addProperty("assets",labels.get("AssetServer"));endpoint.addProperty("styngr",labels.get("Styngr"));
            JsonObject asset=new JsonObject();asset.addProperty("className",manager.name);
            asset.addProperty("sha256",LunarCompatibilityManifest.sha256(managerBytes));
            asset.addProperty("getter",getter.name);asset.addProperty("connect",connect.name);
            asset.addProperty("infoClass",info);asset.addProperty("infoSha256",LunarCompatibilityManifest.sha256(infoBytes));
            row.add("service",endpoint);row.add("assets",asset);
            LunarCompatibilityManifest.Mapping mapping=new LunarCompatibilityManifest.Mapping(row);
            mapping.validateNative(file,digest);
            // Detect a concurrent launcher replacement before any runtime handoff.
            if(!digest.equals(LunarCompatibilityManifest.hashFile(file)))throw invalid("artifact-changed");
            return mapping;
        }
    }

    /** Four distinct static String assignments must each follow their native service label. */
    static Map<String,String> endpointFields(ClassNode node) {
        Set<String> fields=new HashSet<String>();
        for(FieldNode field:node.fields)if(field.desc.equals("Ljava/lang/String;")
                &&(field.access&Opcodes.ACC_STATIC)!=0&&field.value==null)fields.add(field.name);
        Set<String> expected=new HashSet<String>(Arrays.asList("Api","Authenticator","AssetServer","Styngr"));
        Map<String,String> labels=new HashMap<String,String>();
        for(MethodNode method:node.methods)if(method.name.equals("<clinit>")) {
            for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof LdcInsnNode) {
                Object value=((LdcInsnNode)instruction).cst;
                if(!expected.contains(value))continue;
                int remaining=40;
                for(AbstractInsnNode next=instruction.getNext();next!=null&&remaining-->0;next=next.getNext()) {
                    if(next instanceof FieldInsnNode&&next.getOpcode()==Opcodes.PUTSTATIC) {
                        FieldInsnNode write=(FieldInsnNode)next;
                        if(write.owner.equals(node.name)&&write.desc.equals("Ljava/lang/String;")&&fields.contains(write.name)) {
                            String prior=labels.put((String)value,write.name);
                            if(prior!=null&&!prior.equals(write.name))return null;
                        }
                        break;
                    }
                }
            }
        }
        return labels.keySet().equals(expected)&&new HashSet<String>(labels.values()).size()==4?labels:null;
    }

    private static MethodNode singletonGetter(ClassNode node)throws IOException {
        MethodNode found=null;
        for(MethodNode method:node.methods) {
            if((method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))!=(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC)
                    ||!method.desc.equals("()L"+node.name+";"))continue;
            AbstractInsnNode a=next(method.instructions.getFirst()),b=a==null?null:next(a.getNext());
            if(!(a instanceof FieldInsnNode)||a.getOpcode()!=Opcodes.GETSTATIC||b==null
                    ||b.getOpcode()!=Opcodes.ARETURN||next(b.getNext())!=null)continue;
            FieldInsnNode field=(FieldInsnNode)a;
            if(!field.owner.equals(node.name)||!field.desc.equals("L"+node.name+";"))continue;
            if(found!=null)throw invalid("ambiguous-singleton");found=method;
        }
        return found;
    }

    private static MethodNode reconnectMethod(ClassNode node)throws IOException {
        MethodNode found=null;
        for(MethodNode method:node.methods) {
            Type[] args=Type.getArgumentTypes(method.desc);
            if((method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))!=Opcodes.ACC_PUBLIC
                    ||Type.getReturnType(method.desc).getSort()!=Type.VOID||args.length!=1
                    ||args[0].getSort()!=Type.OBJECT||!args[0].getInternalName().startsWith(PREFIX))continue;
            boolean handshake=false,thread=false,background=false;
            for(AbstractInsnNode instruction:method.instructions) {
                if(instruction instanceof MethodInsnNode) {
                    MethodInsnNode call=(MethodInsnNode)instruction;
                    handshake|=call.owner.equals(node.name)&&call.desc.equals("()"+BUILDER);
                    thread|=call.owner.equals("java/lang/Thread")&&call.name.equals("start")&&call.desc.equals("()V");
                } else if(instruction instanceof InvokeDynamicInsnNode) {
                    InvokeDynamicInsnNode lambda=(InvokeDynamicInsnNode)instruction;
                    if(!lambda.name.equals("run")||!lambda.desc.equals("(L"+node.name+";"+args[0].getDescriptor()+BUILDER+")Ljava/lang/Runnable;")
                            ||!lambda.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory"))continue;
                    for(Object value:lambda.bsmArgs)if(value instanceof Handle) {
                        Handle target=(Handle)value;
                        if(target.getOwner().equals(node.name)&&target.getDesc().equals("("+args[0].getDescriptor()+BUILDER+")V")) {
                            MethodNode body=find(node,target.getName(),target.getDesc());
                            background=body!=null&&freshAuthenticationBranch(node,body);
                        }
                    }
                }
            }
            if(handshake&&thread&&background){if(found!=null)throw invalid("ambiguous-reconnect");found=method;}
        }
        return found;
    }

    private static boolean freshAuthenticationBranch(ClassNode owner,MethodNode method) {
        // Follow the nullable reconnect-info branch, not just a matching method signature.
        for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof JumpInsnNode
                &&instruction.getOpcode()==Opcodes.IFNULL) {
            AbstractInsnNode previous=instruction.getPrevious();while(previous!=null&&previous.getOpcode()<0)previous=previous.getPrevious();
            if(!(previous instanceof VarInsnNode)||previous.getOpcode()!=Opcodes.ALOAD||((VarInsnNode)previous).var!=1)continue;
            int remaining=80;
            for(AbstractInsnNode branch=((JumpInsnNode)instruction).label;branch!=null&&remaining-->0;branch=branch.getNext()) {
                if(branch.getOpcode()==Opcodes.RETURN||branch.getOpcode()==Opcodes.GOTO)break;
                if(!(branch instanceof MethodInsnNode))continue;
                MethodInsnNode call=(MethodInsnNode)branch;
                if(!call.owner.equals(owner.name)||!call.desc.equals("("+BUILDER+")V"))continue;
                MethodNode auth=find(owner,call.name,call.desc);
                if(auth!=null&&hasText(auth,"GAME_WEBSOCKET")&&hasCall(auth,owner.name,"(Ljava/lang/String;Ljava/util/function/Consumer;)V")) {
                    // The same manager must finish the native identity/Assets handshake.
                    for(AbstractInsnNode n:auth.instructions)if(n instanceof InvokeDynamicInsnNode) {
                        InvokeDynamicInsnNode lambda=(InvokeDynamicInsnNode)n;
                        if(!lambda.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory"))continue;
                        for(Object value:lambda.bsmArgs)if(value instanceof Handle) {
                            Handle handle=(Handle)value;
                            MethodNode callback=handle.getOwner().equals(owner.name)?find(owner,handle.getName(),handle.getDesc()):null;
                            if(callback!=null&&assetsCallback(owner,callback,1))return true;
                        }
                    }
                }
            }
        }
        return false;
    }
    private static boolean assetsCallback(ClassNode owner,MethodNode method,int depth) {
        if(!method.desc.equals("(Ljava/lang/String;"+BUILDER+")V")&&!method.desc.equals("("+BUILDER+"Ljava/lang/String;)V"))return false;
        if(hasText(method,"Connected to the AssetServer")
                &&hasNamedCall(method,"com/lunarclient/websocket/handshake/v1/MinecraftIdentity$Builder","setAuthenticatorJwt")
                &&hasNamedCall(method,"com/lunarclient/websocket/handshake/v1/Handshake$Builder","build"))return true;
        if(depth>0)for(AbstractInsnNode n:method.instructions)if(n instanceof MethodInsnNode) {
            MethodInsnNode call=(MethodInsnNode)n;
            MethodNode target=call.owner.equals(owner.name)?find(owner,call.name,call.desc):null;
            if(target!=null&&assetsCallback(owner,target,depth-1))return true;
        }
        return false;
    }
    /** Stable protobuf boundary used before a build-specific layout has been discovered. */
    static String authSuccessHandlerName(byte[] bytes) {
        if(!contains(bytes,"getEncryptedJwt")||!contains(bytes,"com/lunarclient/authenticator/v1/AuthSuccessMessage"))return null;
        ClassNode node=node(bytes);if(!node.name.startsWith(PREFIX))return null;
        String found=null;
        for(MethodNode method:node.methods) {
            if((method.access&Opcodes.ACC_STATIC)!=0||!method.desc.equals("(Lcom/lunarclient/authenticator/v1/AuthSuccessMessage;)V")
                    ||!hasNamedCall(method,"com/lunarclient/authenticator/v1/AuthSuccessMessage","getEncryptedJwt"))continue;
            if(found!=null)return null;found=method.name;
        }
        boolean consumer=false,flag=false;
        for(FieldNode field:node.fields)if((field.access&Opcodes.ACC_STATIC)==0) {
            consumer|=field.desc.equals("Ljava/util/function/Consumer;");flag|=field.desc.equals("Z");
        }
        return consumer&&flag?found:null;
    }
    private static boolean hasText(MethodNode method,String text){for(AbstractInsnNode n:method.instructions)if(n instanceof LdcInsnNode&&text.equals(((LdcInsnNode)n).cst))return true;return false;}
    private static boolean hasCall(MethodNode method,String owner,String desc){for(AbstractInsnNode n:method.instructions)if(n instanceof MethodInsnNode){MethodInsnNode c=(MethodInsnNode)n;if(c.owner.equals(owner)&&c.desc.equals(desc))return true;}return false;}
    private static boolean hasNamedCall(MethodNode method,String owner,String name){for(AbstractInsnNode n:method.instructions)if(n instanceof MethodInsnNode){MethodInsnNode c=(MethodInsnNode)n;if(c.owner.equals(owner)&&c.name.equals(name))return true;}return false;}
    private static MethodNode find(ClassNode node,String name,String desc){for(MethodNode m:node.methods)if(m.name.equals(name)&&m.desc.equals(desc))return m;return null;}
    private static AbstractInsnNode next(AbstractInsnNode n){while(n!=null&&n.getOpcode()<0)n=n.getNext();return n;}
    private static ClassNode node(byte[] bytes){ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);return n;}
    private static boolean contains(byte[] bytes,String text){byte[] token=text.getBytes(java.nio.charset.StandardCharsets.US_ASCII);outer:for(int i=0;i<=bytes.length-token.length;i++){for(int j=0;j<token.length;j++)if(bytes[i+j]!=token[j])continue outer;return true;}return false;}
    private static IOException invalid(String reason){return new IOException("lunar-discovery-"+reason);}
}
