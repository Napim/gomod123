package me.napim.gomod.weave;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.ClassReader;

/** Signed, data-only mappings for an exact native Lunar artifact. No URLs or executable code. */
public final class LunarCompatibilityManifest {
    static final int MAX_DOCUMENT_BYTES = 65_536;
    private LunarCompatibilityManifest() {}

    public static final class Registry {
        public final long revision;
        public final List<Mapping> mappings;
        public final String fingerprint;
        final byte[] envelope;
        Registry(long revision,List<Mapping> mappings,byte[] envelope,byte[] payload) {
            this.revision=revision;this.mappings=Collections.unmodifiableList(mappings);
            this.envelope=envelope.clone();this.fingerprint=sha256(payload);
        }
        public Mapping forBuild(String digest) {
            for(Mapping mapping:mappings)if(mapping.lunarSha256.equals(digest))return mapping;
            return null;
        }
    }
    public static final class Mapping {
        private final JsonObject data;
        public final String id,lunarSha256,serviceClass,serviceSha256,api,authenticator,assets,styngr;
        public final String managerClass,managerSha256,getter,connect,infoClass,infoSha256;
        Mapping(JsonObject entry)throws IOException {
            data=new JsonParser().parse(entry.toString()).getAsJsonObject();
            keys(entry,"id","lunarSha256","service","assets");
            id=text(entry,"id","[a-z0-9][a-z0-9.-]{0,63}");lunarSha256=digest(entry,"lunarSha256");
            JsonObject service=object(entry.get("service"));
            keys(service,"className","sha256","api","authenticator","assets","styngr");
            serviceClass=className(service,"className");serviceSha256=digest(service,"sha256");
            api=member(service,"api");authenticator=member(service,"authenticator");
            assets=member(service,"assets");styngr=member(service,"styngr");
            if(new HashSet<String>(Arrays.asList(api,authenticator,assets,styngr)).size()!=4)throw invalid("duplicate-service-field");
            JsonObject manager=object(entry.get("assets"));
            keys(manager,"className","sha256","getter","connect","infoClass","infoSha256");
            managerClass=className(manager,"className");managerSha256=digest(manager,"sha256");
            getter=member(manager,"getter");connect=member(manager,"connect");
            infoClass=className(manager,"infoClass");infoSha256=digest(manager,"infoSha256");
            if(serviceClass.equals(managerClass)||managerClass.equals(infoClass)||serviceClass.equals(infoClass))throw invalid("duplicate-native-class");
        }
        public JsonObject toJson(){return new JsonParser().parse(data.toString()).getAsJsonObject();}
        /** Validate both native fingerprints and the meaning/shape of the selected members. */
        public void validateNative(File nativeJar,String actualDigest)throws IOException {
            if(!lunarSha256.equals(actualDigest))throw invalid("wrong-native-build");
            try(JarFile jar=new JarFile(nativeJar)) {
                ClassNode service=readClass(jar,serviceClass,serviceSha256);
                for(String name:new String[]{api,authenticator,assets,styngr}) {
                    boolean found=false;
                    for(FieldNode field:service.fields)if(field.name.equals(name)&&field.desc.equals("Ljava/lang/String;")
                            &&(field.access&Opcodes.ACC_STATIC)!=0&&field.value==null)found=true;
                    if(!found)throw invalid("service-field-shape");
                }
                Map<String,String> labels=new HashMap<String,String>();
                for(MethodNode method:service.methods)if(method.name.equals("<clinit>")) {
                    for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof LdcInsnNode) {
                        Object value=((LdcInsnNode)instruction).cst;
                        if(!Arrays.asList("Api","Authenticator","AssetServer","Styngr").contains(value))continue;
                        int remaining=40;
                        for(AbstractInsnNode next=instruction.getNext();next!=null&&remaining-->0;next=next.getNext()) {
                            if(next instanceof FieldInsnNode) {
                                FieldInsnNode write=(FieldInsnNode)next;
                                if(write.getOpcode()==Opcodes.PUTSTATIC&&write.owner.equals(serviceClass)&&write.desc.equals("Ljava/lang/String;")) {
                                    String previous=labels.put((String)value,write.name);
                                    if(previous!=null&&!previous.equals(write.name))throw invalid("ambiguous-service-label");
                                    break;
                                }
                            }
                        }
                    }
                }
                if(!api.equals(labels.get("Api"))||!authenticator.equals(labels.get("Authenticator"))
                        ||!assets.equals(labels.get("AssetServer"))||!styngr.equals(labels.get("Styngr")))throw invalid("service-label-mismatch");
                ClassNode manager=readClass(jar,managerClass,managerSha256);
                readClass(jar,infoClass,infoSha256);
                MethodNode getterMethod=null,connectMethod=null;
                for(MethodNode method:manager.methods) {
                    if(method.name.equals(getter)&&method.desc.equals("()L"+managerClass+";")
                            &&(method.access&(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))==(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC))getterMethod=method;
                    if(method.name.equals(connect)&&method.desc.equals("(L"+infoClass+";)V")
                            &&(method.access&Opcodes.ACC_PUBLIC)!=0&&(method.access&Opcodes.ACC_STATIC)==0)connectMethod=method;
                }
                if(getterMethod==null||connectMethod==null)throw invalid("reconnect-signature");
                List<AbstractInsnNode> getterCode=new ArrayList<AbstractInsnNode>();
                for(AbstractInsnNode instruction:getterMethod.instructions)if(instruction.getOpcode()>=0)getterCode.add(instruction);
                if(getterCode.size()!=2||!(getterCode.get(0) instanceof FieldInsnNode)
                        ||getterCode.get(0).getOpcode()!=Opcodes.GETSTATIC||getterCode.get(1).getOpcode()!=Opcodes.ARETURN)throw invalid("singleton-getter-shape");
                FieldInsnNode singleton=(FieldInsnNode)getterCode.get(0);
                if(!singleton.owner.equals(managerClass)||!singleton.desc.equals("L"+managerClass+";"))throw invalid("singleton-getter-owner");
                boolean handshake=false,backgroundConnect=false;
                for(AbstractInsnNode instruction:connectMethod.instructions)if(instruction instanceof MethodInsnNode) {
                    MethodInsnNode call=(MethodInsnNode)instruction;
                    handshake|=call.owner.equals(managerClass)&&call.desc.equals("()Lcom/lunarclient/websocket/handshake/v1/Handshake$Builder;");
                    backgroundConnect|=call.owner.equals("java/lang/Thread")&&call.name.equals("start")&&call.desc.equals("()V");
                }
                if(!handshake||!backgroundConnect)throw invalid("native-reconnect-flow");
            }
        }
    }

    public static Registry verify(byte[] envelope)throws IOException {
        try(InputStream input=LunarCompatibilityManifest.class.getResourceAsStream("/gomod/lunar-compat-public-key.pem")) {
            if(input==null)throw invalid("missing-verification-key");
            String pem=new String(readBounded(input,4096),StandardCharsets.US_ASCII)
                    .replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
            PublicKey key=KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
            return verify(envelope,key);
        }catch(GeneralSecurityException|IllegalArgumentException invalid){throw invalid("verification-key");}
    }
    static Registry verify(byte[] bytes,PublicKey key)throws IOException {
        if(bytes==null||bytes.length==0||bytes.length>MAX_DOCUMENT_BYTES)throw invalid("document-size");
        try {
            JsonObject envelope=object(new JsonParser().parse(new String(bytes,StandardCharsets.UTF_8)));
            keys(envelope,"keyId","payload","signature");
            if(!"gomod-lunar-compat-1".equals(text(envelope,"keyId","[a-z0-9-]{1,64}")))throw invalid("unknown-signing-key");
            byte[] payload=Base64.getDecoder().decode(text(envelope,"payload","[A-Za-z0-9+/=]{1,65536}"));
            byte[] signature=Base64.getDecoder().decode(text(envelope,"signature","[A-Za-z0-9+/=]{1,1024}"));
            if(payload.length>49_152||signature.length<256||signature.length>512)throw invalid("signed-payload-size");
            Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update(payload);
            if(!verifier.verify(signature))throw invalid("bad-signature");
            JsonObject root=object(new JsonParser().parse(new String(payload,StandardCharsets.UTF_8)));
            keys(root,"schema","revision","mappings");
            if(number(root,"schema")!=1)throw invalid("unsupported-schema");
            long revision=number(root,"revision");
            if(revision<1||!root.get("mappings").isJsonArray())throw invalid("invalid-registry");
            JsonArray mappings=root.getAsJsonArray("mappings");if(mappings.size()>32)throw invalid("too-many-mappings");
            List<Mapping> entries=new ArrayList<Mapping>();Set<String> hashes=new HashSet<String>(),ids=new HashSet<String>();
            for(JsonElement entry:mappings) {
                Mapping mapping=new Mapping(object(entry));
                if(!hashes.add(mapping.lunarSha256)||!ids.add(mapping.id))throw invalid("duplicate-build");
                entries.add(mapping);
            }
            return new Registry(revision,entries,bytes,payload);
        }catch(GeneralSecurityException|RuntimeException invalid){throw invalid("invalid-envelope");}
    }
    private static ClassNode readClass(JarFile jar,String name,String expected)throws IOException {
        JarEntry entry=jar.getJarEntry(name+".class");if(entry==null||entry.getSize()>4_194_304L)throw invalid("native-class-missing-or-large");
        byte[] bytes;try(InputStream input=jar.getInputStream(entry)){bytes=readBounded(input,4_194_304);}
        if(!expected.equals(sha256(bytes)))throw invalid("native-class-fingerprint");
        ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        if(!name.equals(node.name))throw invalid("native-class-name");return node;
    }
    static byte[] readBounded(InputStream input,int limit)throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int read;
        while((read=input.read(buffer))!=-1){if(output.size()+read>limit)throw invalid("body-too-large");output.write(buffer,0,read);}return output.toByteArray();
    }
    static String sha256(byte[] bytes) {
        try{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static String hashFile(File file)throws IOException {
        try(InputStream input=new FileInputStream(file)) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int read;
            while((read=input.read(buffer))!=-1)digest.update(buffer,0,read);return hex(digest.digest());
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    private static JsonObject object(JsonElement value)throws IOException{if(value==null||!value.isJsonObject())throw invalid("expected-object");return value.getAsJsonObject();}
    private static void keys(JsonObject object,String...expected)throws IOException{Set<String> actual=new HashSet<String>();for(Map.Entry<String,JsonElement> entry:object.entrySet())actual.add(entry.getKey());if(!actual.equals(new HashSet<String>(Arrays.asList(expected))))throw invalid("unexpected-fields");}
    private static String text(JsonObject object,String name,String pattern)throws IOException{JsonElement value=object.get(name);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString()||!value.getAsString().matches(pattern))throw invalid("invalid-"+name);return value.getAsString();}
    private static String member(JsonObject object,String name)throws IOException{return text(object,name,"[A-Za-z_$][A-Za-z0-9_$]{0,95}");}
    private static String className(JsonObject object,String name)throws IOException{String value=text(object,name,"com/moonsworth/lunar/client/(?:[A-Za-z_$][A-Za-z0-9_$]*/)*[A-Za-z_$][A-Za-z0-9_$]*");if(value.length()>256)throw invalid("class-name-too-long");return value;}
    private static String digest(JsonObject object,String name)throws IOException{return text(object,name,"[0-9a-fA-F]{64}").toLowerCase(Locale.ROOT);}
    private static long number(JsonObject object,String name)throws IOException{JsonElement value=object.get(name);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber()||!value.getAsString().matches("[0-9]{1,13}"))throw invalid("invalid-"+name);return value.getAsLong();}
    private static IOException invalid(String reason){return new IOException("lunar-compatibility-"+reason);}
}
