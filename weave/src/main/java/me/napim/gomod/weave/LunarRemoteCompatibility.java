package me.napim.gomod.weave;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import com.google.gson.JsonObject;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import me.napim.gomod.features.LunarOnlineStatus;
import me.napim.gomod.util.GomodPaths;

/** Background native layout discovery and signed data updates; never downloads executable code. */
public final class LunarRemoteCompatibility {
    private static final String ENDPOINT="https://gomod-service.onrender.com/lunar-compat/v1.json";
    private static final ScheduledExecutorService WORKER=Executors.newSingleThreadScheduledExecutor(task->{
        Thread thread=new Thread(task,"gomod-lunar-compatibility");thread.setDaemon(true);return thread;
    });
    private static boolean started;
    private static Instrumentation instrumentation;
    private static File nativeJar;
    private static volatile String nativeHash;
    private static String etag,installedKey;
    private static long nativeSize,nativeModified,nextFetchMs;
    private static LunarCompatibilityManifest.Registry latest,working,desired,trial;
    private static volatile LunarCompatibilityManifest.Mapping localMapping;
    private static boolean localDiscoveryAttempted,localConfirmed;
    private static long nextLocalDiscoveryMs;
    private static volatile String localDiscoveryFailure="";
    private static final Map<String,NativeArtifactScan> nativeArtifactScans=new HashMap<String,NativeArtifactScan>();
    private static volatile String state="not-started";
    private static volatile long registryRevision,mappingRevision;
    private static volatile String mappingId="",lastFailure="";
    private LunarRemoteCompatibility() {}

    static synchronized void start(Instrumentation vm) {
        if(started)return;started=true;instrumentation=vm;state="waiting-for-native-client";
        WORKER.scheduleWithFixedDelay(()->{
            try{poll();}catch(Throwable error){
                state="retrying-update";
                lastFailure="compatibility-"+error.getClass().getSimpleName();
                LunarConnectionDiagnostics.record("lunar.compatibility_error","exception="+error.getClass().getSimpleName());
            }
        },1L,5L,TimeUnit.SECONDS);
    }
    public static String describeState(){return state;}
    /** True only after a verified registry confirms this native build has no selected mapping. */
    public static boolean isCurrentBuildMissingFromRegistry() {
        String currentState=state;
        if(!"native-build-not-in-registry".equals(currentState)||localMapping!=null)return false;
        String currentHash=nativeHash;
        LunarCompatibilityManifest.Registry selected=desired;
        return currentHash!=null&&(selected==null||selected.forBuild(currentHash)==null);
    }
    /** Only already-computed metadata; never reads a JAR or contacts the relay on the usage thread. */
    public static JsonObject usageSnapshot() {
        JsonObject result=new JsonObject();
        result.addProperty("compatibilityState",state);
        if(nativeHash!=null)result.addProperty("nativeSha256",nativeHash);
        result.addProperty("registryRevision",registryRevision);
        result.addProperty("mappingRevision",mappingRevision);
        if(!mappingId.isEmpty())result.addProperty("mappingId",mappingId);
        if(!lastFailure.isEmpty())result.addProperty("compatibilityFailure",lastFailure);
        result.addProperty("compatibilitySource",mappingRevision>0L?"signed-registry":localMapping!=null?"local-structure":"bundled");
        if(!localDiscoveryFailure.isEmpty())result.addProperty("nativeDiscoveryFailure",localDiscoveryFailure);
        return result;
    }
    public static String getCompatibilityWaitMessage() {
        if("native-artifact-changed-relaunch-required".equals(state))
            return "Lunar files changed while the game was running; restart Lunar to recheck compatibility.";
        String hash=nativeHash;
        if("native-build-not-in-registry".equals(state)&&hash!=null)
            return "Lunar build "+hash.substring(0,12)+" has an unsupported connection layout; waiting for a compatibility update.";
        return "";
    }
    private static void refreshMappingDetails() {
        registryRevision=latest==null?0L:latest.revision;
        LunarCompatibilityManifest.Mapping selected=desired==null?null:desired.forBuild(nativeHash);
        mappingRevision=selected==null?0L:desired.revision;
        mappingId=selected==null?(localMapping==null?"":localMapping.id):selected.id;
    }
    private static void poll()throws Exception {
        if(!LunarOnlineStatus.isFullAccessPreferenceEnabled()){trial=null;installedKey=null;state="disabled";return;}
        if(nativeJar==null) {
            nativeJar=findNativeJar(instrumentation.getAllLoadedClasses());
            if(nativeJar==null)return;
            nativeSize=nativeJar.length();nativeModified=nativeJar.lastModified();
            nativeHash=LunarCompatibilityManifest.hashFile(nativeJar);
            if(!unchanged())throw new IOException("native-artifact-changed-during-hash");
            LunarConnectionDiagnostics.record("lunar.compatibility_native_artifact_discovered",
                    "sha256="+nativeHash+" size="+nativeSize);
            latest=readCache("seen");working=readCache("working");
            if(working!=null&&(latest==null||working.revision>latest.revision))latest=working;
            desired=working!=null?working:latest;
            refreshMappingDetails();
            state="checking-relay";
        }
        if(!unchanged()){state="native-artifact-changed-relaunch-required";return;}
        // Discover and hand off before contacting the relay: a missing entry, offline relay,
        // or expired registry must not prevent a supported native build from connecting.
        if((desired==null||desired.forBuild(nativeHash)==null)&&localMapping==null
                &&(!localDiscoveryAttempted||System.currentTimeMillis()>=nextLocalDiscoveryMs)) {
            localDiscoveryAttempted=true;state="discovering-native-layout";
            try {
                localMapping=LunarNativeLayoutDiscovery.discover(nativeJar,nativeHash);
                if(!unchanged()){localMapping=null;state="native-artifact-changed-relaunch-required";return;}
                localDiscoveryFailure="";refreshMappingDetails();writeLocalCache(false);
                LunarConnectionDiagnostics.record("lunar.compatibility_discovered",
                        "native="+nativeHash+" source=local-structure endpointLabels=verified reconnectFlow=verified");
            }catch(IOException unsupported) {
                nextLocalDiscoveryMs=System.currentTimeMillis()+60_000L;
                localDiscoveryFailure=unsupported.getMessage();
                LunarConnectionDiagnostics.record("lunar.compatibility_discovery_deferred","reason="+localDiscoveryFailure);
            }
        }
        if(localMapping!=null&&(desired==null||desired.forBuild(nativeHash)==null))applyLocalCompatibility();
        long now=System.currentTimeMillis();
        if(now>=nextFetchMs) {
            nextFetchMs=now+60_000L;
            try {
                Download download=download(new URL(ENDPOINT),etag,5_000);
                if(download.bytes!=null) {
                    LunarCompatibilityManifest.Registry incoming=LunarCompatibilityManifest.verify(download.bytes);
                    LunarCompatibilityManifest.Mapping mapping=incoming.forBuild(nativeHash);
                    if(mapping!=null)mapping.validateNative(nativeJar,nativeHash);
                    if(acceptRevision(latest,incoming)) {
                        latest=incoming;desired=mapping==null?working:incoming;
                        refreshMappingDetails();
                        writeCache("seen",incoming);etag=download.etag;
                        LunarConnectionDiagnostics.record("lunar.compatibility_received","revision="+incoming.revision+" exactBuild="+(mapping!=null));
                    }
                }else if(latest!=null&&latest.forBuild(nativeHash)!=null){desired=latest;refreshMappingDetails();}
                lastFailure="";
            }catch(Exception unavailable){
                lastFailure="compatibility-fetch-"+unavailable.getClass().getSimpleName();
                LunarConnectionDiagnostics.record("lunar.compatibility_fetch_deferred","exception="+unavailable.getClass().getSimpleName()+" cachedWorking="+(working!=null));
            }
        }
        if(trial!=null&&LunarOnlineStatus.isFullAccessRouteActive()&&LunarOnlineStatus.isFullAccessConnected()) {
            working=trial;writeCache("working",working);trial=null;
            state="working revision="+working.revision;
            LunarConnectionDiagnostics.record("lunar.compatibility_confirmed","revision="+working.revision+" source=fresh-fullaccess-response");
        }
        LunarCompatibilityManifest.Mapping mapping=desired==null?null:desired.forBuild(nativeHash);
        if(mapping==null) {
            if(localMapping!=null){applyLocalCompatibility();return;}
            state=LunarOnlineStatus.isFullAccessConnected()?"connected-with-bundled-layout"
                    :latest==null?"waiting-for-compatibility-registry":"native-build-not-in-registry";
            if("native-build-not-in-registry".equals(state))
                LunarRelayWeaveBootstrap.pauseAssetsReconnectForCompatibilityMapping();
            return;
        }
        String key=desired.revision+":"+desired.fingerprint;
        if(key.equals(installedKey)) {
            state=working!=null&&working.fingerprint.equals(desired.fingerprint)?"working revision="+desired.revision
                    :"installed revision="+desired.revision+" awaiting-fullaccess-response";
            return;
        }
        int result=LunarRelayWeaveBootstrap.applyRemoteCompatibility(mapping,nativeJar,instrumentation.getAllLoadedClasses());
        if(result==0){state="available revision="+desired.revision+" waiting-for-safe-handoff";return;}
        installedKey=key;
        if(result==1) {
            working=desired;writeCache("working",working);trial=null;state="working revision="+working.revision;
        }else{trial=desired;state="installed revision="+desired.revision+" awaiting-fullaccess-response";}
    }
    private static void applyLocalCompatibility()throws Exception {
        String key="local:"+nativeHash;
        if(!key.equals(installedKey)) {
            int result=LunarRelayWeaveBootstrap.applyRemoteCompatibility(localMapping,nativeJar,instrumentation.getAllLoadedClasses());
            if(result==0){state="local-layout-available waiting-for-safe-handoff";return;}
            installedKey=key;
        }
        if(!localConfirmed&&LunarOnlineStatus.isFullAccessRouteActive()&&LunarOnlineStatus.isFullAccessConnected()) {
            localConfirmed=true;writeLocalCache(true);
            LunarConnectionDiagnostics.record("lunar.compatibility_confirmed","source=local-structure native="+nativeHash);
        }
        state=localConfirmed?"working local-layout":"installed local-layout awaiting-fullaccess-response";
    }
    private static void writeLocalCache(boolean confirmed) {
        // This file is a data/status record, never a trusted input. Each new session derives
        // its layout from the installed bytecode again, so edits cannot steer reflection.
        try {
            JsonObject record=new JsonObject();record.addProperty("schema",1);
            record.addProperty("source","local-structure");record.addProperty("confirmedWorking",confirmed);
            record.add("mapping",localMapping.toJson());
            Path target=cachePath("local"),lock=target.resolveSibling(nativeHash+".local.lock");
            try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                    FileLock held=channel.tryLock()) {
                if(held==null)return;
                Path temporary=Files.createTempFile(target.getParent(),nativeHash+"-", ".tmp");
                try {
                    Files.write(temporary,record.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                    catch(AtomicMoveNotSupportedException unavailable){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
                }finally{Files.deleteIfExists(temporary);}
            }
        }catch(Exception unavailable){LunarConnectionDiagnostics.record("lunar.compatibility_cache_deferred","source=local-structure");}
    }
    static boolean acceptRevision(LunarCompatibilityManifest.Registry previous,LunarCompatibilityManifest.Registry incoming)throws IOException {
        if(previous==null)return true;
        if(incoming.revision<previous.revision)throw new IOException("older-compatibility-revision");
        if(incoming.revision==previous.revision&&!incoming.fingerprint.equals(previous.fingerprint))throw new IOException("conflicting-compatibility-revision");
        return true;
    }
    private static boolean unchanged(){return nativeJar!=null&&nativeJar.isFile()&&nativeJar.length()==nativeSize&&nativeJar.lastModified()==nativeModified;}
    static File findNativeJar(Class<?>[] loaded) {
        LinkedHashMap<String,File> candidates=new LinkedHashMap<String,File>();
        Set<File> lunarClasspathDirectories=new LinkedHashSet<File>();
        for(Class<?> type:loaded)if(type.getName().startsWith("com.moonsworth.lunar.client.")) {
            try {
                if(type.getProtectionDomain()==null||type.getProtectionDomain().getCodeSource()==null)continue;
                URI location=type.getProtectionDomain().getCodeSource().getLocation().toURI();
                if(!"file".equalsIgnoreCase(location.getScheme()))continue;
                File file=new File(location).getCanonicalFile();
                if(file.isFile()&&file.getName().toLowerCase(Locale.ROOT).endsWith(".jar")
                        &&file.length()<=268_435_456L) {
                    candidates.put(file.getPath(),file);
                    File parent=file.getParentFile();
                    if(parent!=null)lunarClasspathDirectories.add(parent);
                }
            }catch(Exception ignored){}
        }
        // The endpoint-owner JAR may not yet have defined any class, so it might not appear
        // among code sources above. Lunar keeps its runtime artifacts beside one another.
        for(File directory:lunarClasspathDirectories) {
            File[] siblings=directory.listFiles((parent,name)->name.toLowerCase(Locale.ROOT).endsWith(".jar"));
            if(siblings==null)continue;
            for(File file:siblings)try {
                File canonical=file.getCanonicalFile();
                if(canonical.isFile()&&canonical.length()<=268_435_456L)
                    candidates.put(canonical.getPath(),canonical);
            }catch(Exception ignored){}
        }
        File uniqueOwner=null,loadedOwner=null;
        boolean ambiguous=false,ambiguousLoadedOwner=false;
        for(File file:candidates.values()) {
            NativeArtifactScan scan=scanNativeArtifact(file);
            if(scan.serviceClass==null)continue;
            if(uniqueOwner!=null&&!uniqueOwner.equals(file))ambiguous=true;
            else uniqueOwner=file;
            String ownerName=scan.serviceClass.replace('/','.');
            for(Class<?> type:loaded)if(type.getName().equals(ownerName)) {
                try {
                    URI location=type.getProtectionDomain().getCodeSource().getLocation().toURI();
                    if("file".equalsIgnoreCase(location.getScheme())
                            &&file.equals(new File(location).getCanonicalFile())) {
                        if(loadedOwner!=null&&!loadedOwner.equals(file))ambiguousLoadedOwner=true;
                        else loadedOwner=file;
                    }
                }catch(Exception ignored){}
            }
        }
        if(ambiguousLoadedOwner)return null;
        if(loadedOwner!=null)return loadedOwner;
        return ambiguous?null:uniqueOwner;
    }

    /**
     * Lunar may defer loading its endpoint owner until after FullAccess connects. Scan the
     * loaded Lunar code-source JARs and their sibling runtime JARs for the labelled owner so discovery
     * does not depend on the very reconnect that needs the mapping. Runs on the background worker.
     */
    private static synchronized NativeArtifactScan scanNativeArtifact(File file) {
        String key=file.getPath();
        long length=file.length(),modified=file.lastModified();
        NativeArtifactScan cached=nativeArtifactScans.get(key);
        if(cached!=null&&cached.length==length&&cached.modified==modified)return cached;

        String serviceClass=null;
        try(java.util.jar.JarFile jar=new java.util.jar.JarFile(file)) {
            Enumeration<java.util.jar.JarEntry> entries=jar.entries();
            while(entries.hasMoreElements()) {
                java.util.jar.JarEntry entry=entries.nextElement();
                String name=entry.getName();
                if(entry.isDirectory()||!name.startsWith("com/moonsworth/lunar/client/")
                        ||!name.endsWith(".class")||entry.getSize()>4_194_304L)continue;
                String internalName=name.substring(0,name.length()-6);
                try {
                    if(hasServiceEndpointLabels(jar,internalName)) {
                        serviceClass=internalName;
                        break;
                    }
                }catch(Exception malformedClass){}
            }
        }catch(Exception ignored){}
        NativeArtifactScan result=new NativeArtifactScan(length,modified,serviceClass);
        nativeArtifactScans.put(key,result);
        return result;
    }

    private static final class NativeArtifactScan {
        final long length,modified;
        final String serviceClass;
        NativeArtifactScan(long length,long modified,String serviceClass) {
            this.length=length;this.modified=modified;this.serviceClass=serviceClass;
        }
    }
    /**
     * Lunar splits its loaded classes across several JARs. Select the artifact that owns the
     * native service endpoint class rather than whichever Lunar class happens to load first.
     */
    private static boolean hasServiceEndpointLabels(java.util.jar.JarFile jar,String internalName)throws IOException {
        java.util.jar.JarEntry entry=jar.getJarEntry(internalName+".class");
        if(entry==null||entry.getSize()>4_194_304L)return false;
        ClassNode node=new ClassNode();
        try(InputStream input=jar.getInputStream(entry)) {
            new ClassReader(LunarCompatibilityManifest.readBounded(input,4_194_304)).accept(node,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        if(!internalName.equals(node.name))return false;
        Set<String> endpointFields=new HashSet<String>();
        for(FieldNode field:node.fields)if(field.desc.equals("Ljava/lang/String;")
                &&(field.access&Opcodes.ACC_STATIC)!=0&&field.value==null)endpointFields.add(field.name);
        Map<String,String> labels=new HashMap<String,String>();
        Set<String> expected=new HashSet<String>(Arrays.asList("Api","Authenticator","AssetServer","Styngr"));
        for(MethodNode method:node.methods)if(method.name.equals("<clinit>")) {
            for(AbstractInsnNode instruction:method.instructions)if(instruction instanceof LdcInsnNode) {
                Object value=((LdcInsnNode)instruction).cst;
                if(!(value instanceof String)||!expected.contains(value))continue;
                int remaining=40;
                for(AbstractInsnNode next=instruction.getNext();next!=null&&remaining-->0;next=next.getNext()) {
                    if(next instanceof FieldInsnNode) {
                        FieldInsnNode write=(FieldInsnNode)next;
                        if(write.getOpcode()==Opcodes.PUTSTATIC&&write.owner.equals(internalName)
                                &&write.desc.equals("Ljava/lang/String;")&&endpointFields.contains(write.name)) {
                            String previous=labels.put((String)value,write.name);
                            if(previous!=null&&!previous.equals(write.name))return false;
                            break;
                        }
                    }
                }
            }
        }
        if(!labels.keySet().containsAll(expected))return false;
        return new HashSet<String>(labels.values()).size()==expected.size();
    }
    static final class Download {
        final byte[] bytes;final String etag;
        Download(byte[] bytes,String etag){this.bytes=bytes;this.etag=etag;}
    }
    static Download download(URL url,String previousEtag,int timeoutMs)throws IOException {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();
        try {
            connection.setConnectTimeout(timeoutMs);connection.setReadTimeout(timeoutMs);
            connection.setInstanceFollowRedirects(false);connection.setUseCaches(false);
            connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Connection","close");
            if(previousEtag!=null&&previousEtag.matches("(?:W/)?\"[0-9a-f]{64}\""))connection.setRequestProperty("If-None-Match",previousEtag);
            int status=connection.getResponseCode();
            if(status==304)return new Download(null,previousEtag);
            String type=connection.getContentType();
            if(status!=200||type==null||!type.toLowerCase(Locale.ROOT).contains("application/json")
                    ||connection.getContentLengthLong()>LunarCompatibilityManifest.MAX_DOCUMENT_BYTES)throw new IOException("compatibility-http-unavailable");
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            try(InputStream input=connection.getInputStream()) {
                byte[] buffer=new byte[4096];
                while(true) {
                    long remaining=deadline-System.nanoTime();if(remaining<=0L)throw new IOException("compatibility-request-deadline");
                    connection.setReadTimeout((int)Math.max(1L,TimeUnit.NANOSECONDS.toMillis(remaining)));
                    int read=input.read(buffer);if(read<0)break;
                    if(body.size()+read>LunarCompatibilityManifest.MAX_DOCUMENT_BYTES)throw new IOException("compatibility-body-too-large");
                    body.write(buffer,0,read);
                }
            }
            if(body.size()==0||(connection.getContentLengthLong()>=0&&body.size()!=connection.getContentLengthLong()))throw new IOException("compatibility-empty-or-truncated");
            String tag=connection.getHeaderField("ETag");if(tag!=null&&!tag.matches("(?:W/)?\"[0-9a-f]{64}\""))tag=null;
            return new Download(body.toByteArray(),tag);
        }finally{connection.disconnect();}
    }
    private static Path cachePath(String kind)throws IOException {
        Path directory=GomodPaths.getGomod123Dir().toPath().resolve("lunar-compatibility");Files.createDirectories(directory);
        return directory.resolve(nativeHash+"."+kind+".json");
    }
    private static LunarCompatibilityManifest.Registry readCache(String kind) {
        try {
            Path file=cachePath(kind);if(!Files.isRegularFile(file)||Files.size(file)>LunarCompatibilityManifest.MAX_DOCUMENT_BYTES)return null;
            LunarCompatibilityManifest.Registry registry=LunarCompatibilityManifest.verify(Files.readAllBytes(file));
            LunarCompatibilityManifest.Mapping mapping=registry.forBuild(nativeHash);
            if(mapping!=null)mapping.validateNative(nativeJar,nativeHash);
            return registry;
        }catch(Exception invalid){return null;}
    }
    private static void writeCache(String kind,LunarCompatibilityManifest.Registry registry) {
        try {
            persistCache(cachePath(kind),nativeHash,registry);
        }catch(Exception unavailable){
            LunarConnectionDiagnostics.record("lunar.compatibility_cache_deferred","exception="+unavailable.getClass().getSimpleName());
        }
    }
    static boolean persistCache(Path target,String buildHash,LunarCompatibilityManifest.Registry registry)throws IOException {
            Path lock=target.resolveSibling(buildHash+".lock");
            try(FileChannel channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                    FileLock held=channel.tryLock()) {
                if(held==null)return false;
                LunarCompatibilityManifest.Registry existing=null;
                try{if(Files.isRegularFile(target)&&Files.size(target)<=LunarCompatibilityManifest.MAX_DOCUMENT_BYTES)existing=LunarCompatibilityManifest.verify(Files.readAllBytes(target));}catch(Exception invalid){}
                if(existing!=null&&(existing.revision>registry.revision
                        ||(existing.revision==registry.revision&&!existing.fingerprint.equals(registry.fingerprint))))return false;
                Path temporary=Files.createTempFile(target.getParent(),buildHash+"-", ".tmp");
                try {
                    Files.write(temporary,registry.envelope);
                    try{Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                    catch(AtomicMoveNotSupportedException unavailable){Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);}
                }finally{Files.deleteIfExists(temporary);}
                return true;
            }
    }
}
