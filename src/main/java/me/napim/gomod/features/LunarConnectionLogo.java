package me.napim.gomod.features;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.*;
import me.napim.gomod.util.MinecraftAccountUuid;
import net.minecraft.util.ResourceLocation;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.lwjgl.opengl.GL11;

/** Local display copies only; the connection mark never enters account, Fit or relay data. */
public final class LunarConnectionLogo {
    public static final int DARK_RED = 0x8B1E24;
    public static final int OFFLINE_MARKER = 0xFE8B1E24;
    private static final Map<Object, Object> COPIES = Collections.synchronizedMap(new WeakHashMap<Object, Object>());
    private static final Map<Object, Object> HIDDEN_COPIES = Collections.synchronizedMap(new WeakHashMap<Object, Object>());
    private static volatile ResourceLocation crossTexture;
    private static final class RotationAccess {
        final java.lang.reflect.Method push,pop,translate,rotate,quaternion;
        final Constructor<?> quaternionConstructor;
        final boolean gui;
        RotationAccess(Class<?> type)throws Exception{
            push=type.getMethod("push");pop=type.getMethod("pop");
            java.lang.reflect.Method turn=null,move=null,factory=null;Constructor<?> ctor=null;boolean isGui=false;
            try {
                move=type.getMethod("translate",double.class,double.class,double.class);
                for(java.lang.reflect.Method method:type.getMethods())if(method.getReturnType()==void.class&&method.getParameterTypes().length==1&&method.getParameterTypes()[0].getName().equals("org.joml.Quaternionf")){turn=method;break;}
                if(turn==null)throw new NoSuchMethodException("Native quaternion rotation");
                Class<?> q=turn.getParameterTypes()[0];ctor=q.getConstructor();factory=q.getMethod("rotationZ",float.class);
            }catch(NoSuchMethodException noModelPose){
                move=type.getMethod("ICROCIHRRCCORCCROHRRCIIRRCROOO",float.class,float.class,float.class);
                turn=type.getMethod("RCOHOHORRROHHHOCRCOIHCICIRRRRC",float.class);isGui=true;
            }
            translate=move;rotate=turn;quaternion=factory;quaternionConstructor=ctor;gui=isGui;
            for(java.lang.reflect.Method method:new java.lang.reflect.Method[]{push,pop,translate,rotate})method.setAccessible(true);
        }
        void move(Object context,float x,float y)throws Exception{
            if(gui)translate.invoke(context,x,y,0f);else translate.invoke(context,(double)x,(double)y,0D);
        }
        void turn(Object context)throws Exception{
            if(gui)rotate.invoke(context,45f);else rotate.invoke(context,quaternion.invoke(quaternionConstructor.newInstance(),(float)Math.toRadians(45)));
        }
    }
    private static final ClassValue<Optional<RotationAccess>> ROTATION_ACCESS=new ClassValue<Optional<RotationAccess>>(){
        protected Optional<RotationAccess> computeValue(Class<?> type){try{return Optional.of(new RotationAccess(type));}catch(Exception unavailable){return Optional.empty();}}
    };
    private static final class RotationFrame {final int mode;final Object context;final RotationAccess access;RotationFrame(int mode,Object context,RotationAccess access){this.mode=mode;this.context=context;this.access=access;}}
    private static final RotationFrame NO_ROTATION=new RotationFrame(-1,null,null);
    private static final ThreadLocal<ArrayDeque<RotationFrame>> ROTATIONS=ThreadLocal.withInitial(ArrayDeque::new);
    private static final ClassValue<Optional<Layout>> LAYOUTS = new ClassValue<Optional<Layout>>() {
        @Override protected Optional<Layout> computeValue(Class<?> type) {
            try { return Optional.of(new Layout(type)); }
            catch (Exception unsupported) { LunarConnectionDiagnostics.recordOnceAfterStart("lunar.connection_logo_layout_unavailable",type.getName(),"error="+unsupported.getClass().getSimpleName());return Optional.empty(); }
        }
    };

    private LunarConnectionLogo() { }

    public static boolean isLocalOffline(UUID playerId) {
        return isLocalAccount(playerId) && !LunarOnlineStatus.isConnected();
    }

    private static boolean isLocalAccount(UUID playerId) {
        return playerId != null && GomodFixes.isWeaveRuntime()
                && playerId.equals(MinecraftAccountUuid.currentFromCompactSessionId());
    }

    public static Object snapshot(Object original, UUID playerId, Class<?> type) {
        if (type == null || (original!=null&&!type.isInstance(original)) || !isLocalAccount(playerId)) return original;
        boolean offline = !LunarOnlineStatus.isConnected();
        if(original==null&&!offline)return null;
        if (!offline && !LunarDisguiserBadge.shouldHideExtraBadge(playerId)) return original;
        Optional<Layout> layout = LAYOUTS.get(type);
        if (!layout.isPresent()) return original;
        try {
            Layout binding = layout.get();
            int desiredPlus = offline ? OFFLINE_MARKER : 0;
            if(original==null){
                if(binding.emptyOffline==null){
                    Object[] values=binding.fields.length==3?new Object[]{0xFFFFFF,OFFLINE_MARKER,null}:new Object[]{1f,1f,1f,true,OFFLINE_MARKER,true,null};
                    binding.emptyOffline=binding.constructor.newInstance(values);
                }
                LunarConnectionDiagnostics.recordOnceAfterStart("lunar.connection_logo_snapshot",type.getName()+":empty","offline=true source=empty-cache mark=present");
                return binding.emptyOffline;
            }
            if (binding.fields[binding.plus].getInt(original) == desiredPlus
                    && (binding.fields.length == 3 || binding.fields[5].getBoolean(original) == offline)) return original;
            Map<Object, Object> copies = offline ? COPIES : HIDDEN_COPIES;
            Object cached = copies.get(original);
            if (cached != null) return cached;
            Object[] values = new Object[binding.fields.length];
            for (int n = 0; n < values.length; n++) values[n] = binding.fields[n].get(original);
            values[binding.plus] = Integer.valueOf(desiredPlus);
            if (values.length == 7) values[5] = Boolean.valueOf(offline);
            Object copy = binding.constructor.newInstance(values);
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.connection_logo_snapshot",type.getName()+":"+offline,"offline="+offline+" source=native-profile mark="+(binding.fields[binding.plus].getInt(copy)==OFFLINE_MARKER));
            if (copies.size() >= 1024) copies.clear();
            copies.put(original, copy);
            return copy;
        } catch (ReflectiveOperationException unsupported) { return original; }
    }

    /** Called at Lunar's own textured icon draw, preserving its matrix and native geometry. */
    public static Object texture(Object original, int color, Class<?> nativeResourceType) {
        if (color != OFFLINE_MARKER) return original;
        ResourceLocation cross = crossTexture;
        if (cross == null) crossTexture = cross = new ResourceLocation("gomod", "textures/gui/lunar_offline_cross.png");
        if (nativeResourceType.isInstance(cross)) {
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.connection_logo_texture",
                    nativeResourceType.getName(), "offlineMarker=true replacement=gomod:textures/gui/lunar_offline_cross.png");
            return cross;
        }
        LunarConnectionDiagnostics.recordOnceAfterStart("lunar.connection_logo_texture_unsupported",
                nativeResourceType.getName(), "nativeResourceBridge=false");
        return original;
    }

    public static int color(int original) { return original == OFFLINE_MARKER ? 0xFF000000|DARK_RED : original; }

    /** Lunar's compiled nametags append four XYZ/UV/color vertices, bypassing textured GUI draws. */
    public static void rotateCompiledQuad(java.nio.FloatBuffer vertices,float x,float y,float width,float height,int color) {
        if(color!=OFFLINE_MARKER||vertices==null||vertices.position()<24)return;
        int start=vertices.position()-24;float cx=x+width*.5f,cy=y+height*.5f;
        float[] expectedX={x,x,x+width,x+width},expectedY={y,y+height,y+height,y};
        for(int i=0;i<4;i++)if(Math.abs(vertices.get(start+i*6)-expectedX[i])>.001f
                ||Math.abs(vertices.get(start+i*6+1)-expectedY[i])>.001f)return;
        float packed=Float.intBitsToFloat(Integer.reverseBytes((DARK_RED<<8)|255));
        for(int i=0;i<4;i++){
            int offset=start+i*6;float dx=vertices.get(offset)-cx,dy=vertices.get(offset+1)-cy;
            vertices.put(offset,cx+(dx-dy)*.70710677f);vertices.put(offset+1,cy+(dx+dy)*.70710677f);
            vertices.put(offset+5,packed);
        }
        LunarConnectionDiagnostics.recordOnceAfterStart("lunar.connection_logo_compiled_quad","nametag",
                "offline=true vertices=4 rotation=45 center=preserved texture=native-plus");
    }

    /** Rotate the actual native Plus quad, so cached/native texture dispatch cannot keep it upright. */
    public static int beginRotation(Object context,float x,float y,float width,float height,int original) {
        ArrayDeque<RotationFrame> stack=ROTATIONS.get();
        if(original!=OFFLINE_MARKER){stack.push(NO_ROTATION);return original;}
        int mode=-1;boolean pushed=false,nativePushed=false;RotationAccess access=null;
        try {
            mode=GL11.glGetInteger(GL11.GL_MATRIX_MODE);GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();pushed=true;
            float cx=x+width*.5f,cy=y+height*.5f;
            if(context!=null)access=ROTATION_ACCESS.get(context.getClass()).orElse(null);
            if(access!=null){access.push.invoke(context);nativePushed=true;access.move(context,cx,cy);access.turn(context);access.move(context,-cx,-cy);}
            else{GL11.glTranslatef(cx,cy,0);GL11.glRotatef(45,0,0,1);GL11.glTranslatef(-cx,-cy,0);}
            GL11.glMatrixMode(mode);stack.push(new RotationFrame(mode,context,access));
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.connection_logo_rotation",context==null?"none":context.getClass().getName(),"degrees=45 mark=dark-red-x matrix="+(access==null?"raw-gl-fallback":"native-pose"));
        } catch(Throwable unavailable) {
            if(nativePushed&&access!=null)try{access.pop.invoke(context);}catch(Throwable ignored){}
            if(pushed)try{GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPopMatrix();GL11.glMatrixMode(mode);}catch(Throwable ignored){}
            stack.push(NO_ROTATION);
        }
        return 0xFF000000|DARK_RED;
    }

    /**
     * Rotate only the native Plus fallback. When the native resource was replaced by the
     * diagonal X texture, keep its original orientation and just apply the dark red tint.
     */
    public static int beginRotation(Object context,float x,float y,float width,float height,
                                    Object resource,int original) {
        if (original != OFFLINE_MARKER) return beginRotation(context,x,y,width,height,original);
        ResourceLocation cross = crossTexture;
        if (cross != null && (resource == cross || cross.equals(resource))) {
            ROTATIONS.get().push(NO_ROTATION);
            LunarConnectionDiagnostics.recordOnceAfterStart("lunar.connection_logo_native_draw",
                    resource.getClass().getName(), "offline=true source=replacement-x-texture rotation=0 tint=dark-red");
            return 0xFF000000|DARK_RED;
        }
        LunarConnectionDiagnostics.recordOnceAfterStart("lunar.connection_logo_native_draw",
                resource == null ? "null" : resource.getClass().getName(),
                "offline=true source=native-plus rotation=45 tint=dark-red");
        return beginRotation(context,x,y,width,height,original);
    }

    public static void endRotation() {
        ArrayDeque<RotationFrame> stack=ROTATIONS.get();if(stack.isEmpty())return;
        RotationFrame frame=stack.pop();
        if(frame.mode>=0){
            try{if(frame.access!=null)frame.access.pop.invoke(frame.context);}catch(Exception ignored){}
            GL11.glMatrixMode(GL11.GL_MODELVIEW);GL11.glPopMatrix();GL11.glMatrixMode(frame.mode);
        }
        if(stack.isEmpty())ROTATIONS.remove();
    }

    private static final class Layout {
        final Constructor<?> constructor;
        final Field[] fields;
        final int plus;
        volatile Object emptyOffline;
        Layout(Class<?> type) throws Exception {
            String owner = type.getName().replace('.', '/');
            ClassNode node = new ClassNode();
            try (InputStream input = classBytes(type,owner)) {
                if (input == null) throw new IllegalArgumentException("Missing icon layout");
                new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            Constructor<?> found = null;
            List<Field> order = new ArrayList<Field>();
            for (MethodNode method : node.methods) {
                if (!method.name.equals("<init>")) continue;
                Type[] args = Type.getArgumentTypes(method.desc);
                if (!(args.length == 3 && method.desc.startsWith("(IIL")
                        || args.length == 7 && method.desc.startsWith("(FFFZIZL"))) continue;
                if (found != null) throw new IllegalArgumentException("Ambiguous icon layout");
                for (Constructor<?> candidate : type.getDeclaredConstructors())
                    if (Type.getConstructorDescriptor(candidate).equals(method.desc)) found = candidate;
                for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; instruction = instruction.getNext()) {
                    if (!(instruction instanceof FieldInsnNode) || instruction.getOpcode() != Opcodes.PUTFIELD) continue;
                    FieldInsnNode put = (FieldInsnNode) instruction;
                    if (!put.owner.equals(owner)) continue;
                    Field field = type.getDeclaredField(put.name); field.setAccessible(true); order.add(field);
                }
                if (order.size() != args.length) throw new IllegalArgumentException("Unknown icon field order");
            }
            if (found == null) throw new IllegalArgumentException("Unsupported icon layout");
            constructor = found; constructor.setAccessible(true);
            fields = order.toArray(new Field[0]); plus = fields.length == 3 ? 1 : 4;
        }
    }

    private static InputStream classBytes(Class<?> type,String owner)throws Exception{
        InputStream resource=type.getResourceAsStream("/"+owner+".class");if(resource!=null)return resource;
        List<java.io.File> archives=new ArrayList<>();
        try{java.net.URL source=type.getProtectionDomain().getCodeSource().getLocation();if("file".equals(source.getProtocol()))archives.add(new java.io.File(source.toURI()));}catch(Exception ignored){}
        archives.add(new java.io.File(System.getProperty("user.home","."),".lunarclient/offline/multiver/lunar.jar"));
        for(java.io.File file:archives)if(file.isFile())try(java.util.jar.JarFile jar=new java.util.jar.JarFile(file)){
            java.util.jar.JarEntry entry=jar.getJarEntry(owner+".class");if(entry==null)continue;
            try(InputStream input=jar.getInputStream(entry);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
                return new java.io.ByteArrayInputStream(bytes.toByteArray());
            }
        }
        return null;
    }
}
