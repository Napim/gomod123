package me.napim.gomod.features;

import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import org.lwjgl.BufferUtils;
import org.lwjgl.MemoryUtil;
import org.lwjgl.opengl.*;

/** Scales each cosmetic mesh around its rendered geometry center, after Lunar positions it. */
public final class LunarCosmeticSizeRenderer {
    private static final class MatrixAccess {
        final Method push, pop, scale, translate;
        final Field bridge;

        MatrixAccess(Class<?> type) throws Exception {
            push = accessible(type.getMethod("push"));
            pop = accessible(type.getMethod("pop"));
            scale = accessible(type.getMethod("scale", float.class, float.class, float.class));
            Method move;
            try { move = type.getMethod("translate", double.class, double.class, double.class); }
            catch (NoSuchMethodException unavailable) { move = null; }
            translate = move == null ? null : accessible(move);

            Field found = null;
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    try {
                        if (Modifier.isStatic(field.getModifiers())) continue;
                        field.getType().getMethod("bridge$scale", float.class, float.class, float.class);
                        field.setAccessible(true);
                        if (found != null) throw new IllegalArgumentException("Ambiguous matrix bridge");
                        found = field;
                    } catch (NoSuchMethodException ignored) { }
                }
            }
            bridge = found;
        }

        Object key(Object context) throws Exception { return bridge == null ? context : bridge.get(context); }
    }

    private static final class GeometryAccess {
        final Method vertexCount, vertexFormat, byteBuffer;

        GeometryAccess(Class<?> type) throws Exception {
            vertexCount = accessible(type.getMethod("getVertexCount"));
            vertexFormat = accessible(type.getMethod("getVertexFormat"));
            byteBuffer = accessible(type.getMethod("getByteBuffer"));
        }

        MeshGeometry geometry(Object renderer) throws Exception {
            if (renderer == null) return null;
            int count = ((Number) vertexCount.invoke(renderer)).intValue();
            Object format = vertexFormat.invoke(renderer);
            if (format == null || count <= 0 || count > 1_000_000) return null;

            Class<?> formatType = format.getClass();
            int stride = ((Number) accessible(formatType.getMethod("getNextOffset")).invoke(format)).intValue();
            @SuppressWarnings("unchecked")
            List<Object> elements = (List<Object>) accessible(formatType.getMethod("getElements")).invoke(format);
            Method offsetMethod = accessible(formatType.getMethod("getOffset", int.class));
            int positionOffset = -1;
            for (int i = 0; i < elements.size(); i++) {
                Object element = elements.get(i);
                Class<?> elementType = element.getClass();
                Object usage = accessible(elementType.getMethod("getUsage")).invoke(element);
                Object type = accessible(elementType.getMethod("getType")).invoke(element);
                int components = ((Number) accessible(elementType.getMethod("getElementCount")).invoke(element)).intValue();
                String usageName = usage instanceof Enum<?> ? ((Enum<?>) usage).name() : String.valueOf(usage);
                String typeName = type instanceof Enum<?> ? ((Enum<?>) type).name() : String.valueOf(type);
                if ("POSITION".equals(usageName) && "FLOAT".equals(typeName) && components >= 3) {
                    positionOffset = ((Number) offsetMethod.invoke(format, i)).intValue();
                    break;
                }
            }
            if (stride < 12 || positionOffset < 0 || positionOffset + 12 > stride) return null;

            Object raw = byteBuffer.invoke(renderer);
            if (!(raw instanceof ByteBuffer)) return null;
            ByteBuffer data = (ByteBuffer) raw;
            long required = (long) (count - 1) * stride + positionOffset + 12;
            if (required > data.limit()) return null;

            double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
            double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
            for (int i = 0; i < count; i++) {
                int at = i * stride + positionOffset;
                float x = data.getFloat(at), y = data.getFloat(at + 4), z = data.getFloat(at + 8);
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) return null;
                minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
            }
            return new MeshGeometry(data,count,stride,positionOffset,
                    new double[] {(minX + maxX) * .5, (minY + maxY) * .5, (minZ + maxZ) * .5});
        }
    }
    private static final class MeshGeometry {
        final ByteBuffer data; final int count,stride,positionOffset; final double[] center;
        MeshGeometry(ByteBuffer data,int count,int stride,int positionOffset,double[] center) {
            this.data=data;this.count=count;this.stride=stride;this.positionOffset=positionOffset;this.center=center;
        }
    }
    /** A reversible CPU-side mesh edit makes size affect the vertices that the draw call actually consumes. */
    private static final class VertexPatch {
        final ByteBuffer data; final int count,stride,positionOffset; final float[] original;
        VertexPatch(ByteBuffer data,int count,int stride,int positionOffset,float[] original) {
            this.data=data;this.count=count;this.stride=stride;this.positionOffset=positionOffset;this.original=original;
        }
        void restore() {
            for(int vertex=0;vertex<count;vertex++) {
                int at=vertex*stride+positionOffset,index=vertex*3;
                data.putFloat(at,original[index]);data.putFloat(at+4,original[index+1]);data.putFloat(at+8,original[index+2]);
            }
        }
    }
    private static VertexPatch patchVertices(MeshGeometry geometry,double factor,double offsetY) {
        if(geometry==null||geometry.data.isReadOnly()||geometry.count>200_000
                ||!Double.isFinite(factor)||factor<=0||!Double.isFinite(offsetY)
                ||Math.abs(factor-1)<1e-9&&Math.abs(offsetY)<1e-9)return null;
        float[] original=new float[geometry.count*3];int changed=0;
        try {
            for(int vertex=0;vertex<geometry.count;vertex++) {
                int at=vertex*geometry.stride+geometry.positionOffset,index=vertex*3;
                float x=geometry.data.getFloat(at),y=geometry.data.getFloat(at+4),z=geometry.data.getFloat(at+8);
                if(!Float.isFinite(x)||!Float.isFinite(y)||!Float.isFinite(z))return null;
                original[index]=x;original[index+1]=y;original[index+2]=z;
            }
            for(int vertex=0;vertex<geometry.count;vertex++) {
                int at=vertex*geometry.stride+geometry.positionOffset,index=vertex*3;
                changed=vertex+1;
                geometry.data.putFloat(at,(float)(geometry.center[0]+(original[index]-geometry.center[0])*factor));
                geometry.data.putFloat(at+4,(float)(geometry.center[1]+(original[index+1]-geometry.center[1])*factor+offsetY));
                geometry.data.putFloat(at+8,(float)(geometry.center[2]+(original[index+2]-geometry.center[2])*factor));
            }
            return new VertexPatch(geometry.data,geometry.count,geometry.stride,geometry.positionOffset,original);
        } catch(Throwable unavailable) {
            for(int vertex=0;vertex<changed;vertex++) {
                int at=vertex*geometry.stride+geometry.positionOffset,index=vertex*3;
                try {geometry.data.putFloat(at,original[index]);geometry.data.putFloat(at+4,original[index+1]);geometry.data.putFloat(at+8,original[index+2]);}
                catch(Throwable ignored) { }
            }
            return null;
        }
    }

    private static Method accessible(Method method) { method.setAccessible(true); return method; }
    private static final ClassValue<MatrixAccess> MATRIX = new ClassValue<MatrixAccess>() {
        @Override protected MatrixAccess computeValue(Class<?> type) {
            try { return new MatrixAccess(type); }
            catch (Exception error) { throw new IllegalArgumentException(error); }
        }
    };
    private static final ClassValue<GeometryAccess> GEOMETRY = new ClassValue<GeometryAccess>() {
        @Override protected GeometryAccess computeValue(Class<?> type) {
            try { return new GeometryAccess(type); }
            catch (Exception error) { throw new IllegalArgumentException(error); }
        }
    };
    private static final class BoundsAccess {
        final Field field;
        final Method[] getters = new Method[6];
        BoundsAccess(Class<?> type) throws Exception {
            Field match = null;
            for (Class<?> c=type;c!=null;c=c.getSuperclass()) for (Field f:c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    String[] names={"MinX","MinY","MinZ","MaxX","MaxY","MaxZ"};
                    Method[] found=new Method[6];
                    for(int i=0;i<6;i++)found[i]=accessible(f.getType().getMethod("bridge$get"+names[i]));
                    if(match!=null)throw new IllegalArgumentException("Ambiguous mesh bounds");
                    f.setAccessible(true);match=f;System.arraycopy(found,0,getters,0,6);
                } catch(NoSuchMethodException ignored) { }
            }
            if(match==null)throw new NoSuchFieldException("Native mesh bounds");
            field=match;
        }
        double[] center(Object mesh) throws Exception {
            Object bounds=field.get(mesh);if(bounds==null)return null;
            double[] center=new double[3];
            for(int i=0;i<3;i++){
                double min=((Number)getters[i].invoke(bounds)).doubleValue();
                double max=((Number)getters[i+3].invoke(bounds)).doubleValue();
                if(!Double.isFinite(min)||!Double.isFinite(max)||max<min)return null;
                center[i]=(min+max)*.5;
            }
            return center;
        }
    }
    private static final ClassValue<Optional<BoundsAccess>> BOUNDS=new ClassValue<Optional<BoundsAccess>>() {
        @Override protected Optional<BoundsAccess> computeValue(Class<?> type) {
            try{return Optional.of(new BoundsAccess(type));}catch(Exception unavailable){return Optional.empty();}
        }
    };
    private static final class GpuBounds {
        final int bytes,components,stride;final long offset;final double[] center;
        final java.lang.ref.WeakReference<Object> owner;
        GpuBounds(int bytes,int components,int stride,long offset,double[] center,Object owner){this.bytes=bytes;this.components=components;this.stride=stride;this.offset=offset;this.center=center;this.owner=new java.lang.ref.WeakReference<>(owner);}
    }
    private static Object gpuContext;
    private static final Map<Integer,GpuBounds> GPU_BOUNDS=new LinkedHashMap<>();

    /** Native meshes may bypass WorldRenderer entirely and retain only their vertex buffer on the GPU. */
    private static double[] gpuCenter(Object mesh) {
        int previous=0;boolean bound=false;
        try {
            Object context=GLContext.getCapabilities();
            if(gpuContext!=context){GPU_BOUNDS.clear();gpuContext=context;}
            int buffer=GL11.glGetInteger(GL15.GL_VERTEX_ARRAY_BUFFER_BINDING);
            if(buffer==0||GL11.glGetInteger(GL11.GL_VERTEX_ARRAY_TYPE)!=GL11.GL_FLOAT)return null;
            int components=GL11.glGetInteger(GL11.GL_VERTEX_ARRAY_SIZE);
            if(components<3||components>4)return null;
            int stride=GL11.glGetInteger(GL11.GL_VERTEX_ARRAY_STRIDE);if(stride==0)stride=components*4;
            long offset=MemoryUtil.getAddress0Safe(GL11.glGetPointer(GL11.GL_VERTEX_ARRAY_POINTER,0));
            previous=GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,buffer);bound=true;
            int bytes=GL15.glGetBufferParameteri(GL15.GL_ARRAY_BUFFER,GL15.GL_BUFFER_SIZE);
            if(bytes<=0||bytes>16*1024*1024||offset<0||offset>=bytes||stride<components*4)return null;
            GpuBounds cached=GPU_BOUNDS.get(buffer);
            if(cached!=null&&cached.owner.get()==mesh&&cached.bytes==bytes&&cached.components==components&&cached.stride==stride&&cached.offset==offset)return cached.center;
            ByteBuffer data=BufferUtils.createByteBuffer(bytes);GL15.glGetBufferSubData(GL15.GL_ARRAY_BUFFER,0,data);
            double[] min={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY};
            double[] max={Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
            int count=0;
            for(long at=offset;at+components*4<=bytes;at+=stride){
                double w=components==4?data.getFloat((int)at+12):1;
                if(!Double.isFinite(w)||Math.abs(w)<1e-12)continue;
                for(int axis=0;axis<3;axis++){
                    double value=data.getFloat((int)at+axis*4)/w;if(!Double.isFinite(value))return null;
                    min[axis]=Math.min(min[axis],value);max[axis]=Math.max(max[axis],value);
                }
                count++;
            }
            if(count==0)return null;
            double[] center={(min[0]+max[0])*.5,(min[1]+max[1])*.5,(min[2]+max[2])*.5};
            if(GPU_BOUNDS.size()>=512)GPU_BOUNDS.clear();
            GPU_BOUNDS.put(buffer,new GpuBounds(bytes,components,stride,offset,center,mesh));
            LunarConnectionDiagnostics.recordOnce("cosmetics.size.gpu_anchor",Integer.toString(buffer),"vertices="+count+" components="+components+" pivot=geometry_bounds_center");
            return center;
        } catch(Throwable unavailable){return null;}
        finally{if(bound)GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER,previous);}
    }
    public static void forgetBuffer(int buffer){GPU_BOUNDS.remove(buffer);}

    private static final class AppearanceFrame {
        final AppearanceFrame parent;
        Object context;
        AppearanceFrame(AppearanceFrame parent) { this.parent = parent; }
    }
    private static final class Frame {
        final Frame parent;
        final Object context, key;
        final AppearanceFrame appearance;
        Frame(Frame parent, Object context, Object key, AppearanceFrame appearance) {
            this.parent = parent; this.context = context; this.key = key; this.appearance = appearance;
        }
    }
    private static final class DrawFrame {
        final DrawFrame parent;
        final Object context, key;
        final Frame render;
        final AppearanceFrame appearance;
        final double scale;
        final boolean owned;
        final VertexPatch vertexPatch;
        DrawFrame(DrawFrame parent, Object context, Object key, Frame render,
                  AppearanceFrame appearance, double scale, boolean owned,VertexPatch vertexPatch) {
            this.parent = parent; this.context = context; this.key = key; this.render = render;
            this.appearance = appearance; this.scale = scale; this.owned = owned;this.vertexPatch=vertexPatch;
        }
    }

    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<AppearanceFrame> APPEARANCE = new ThreadLocal<>();
    private static final ThreadLocal<DrawFrame> DRAWING = new ThreadLocal<>();

    private LunarCosmeticSizeRenderer() { }

    public static void beginAppearanceScope() { APPEARANCE.set(new AppearanceFrame(APPEARANCE.get())); }
    public static void endAppearanceScope() {
        AppearanceFrame appearance = APPEARANCE.get();
        if (appearance == null) return;
        unwindAppearance(appearance);
        if (appearance.parent == null) APPEARANCE.remove(); else APPEARANCE.set(appearance.parent);
    }

    public static void begin(Object context) {
        if (context == null) return;
        Frame parent = CURRENT.get();
        AppearanceFrame appearance = APPEARANCE.get();
        if (appearance == null && parent == null && LunarCosmeticAppearanceRenderer.sizeForDraw() == 1) return;
        try {
            Object key = MATRIX.get(context.getClass()).key(context);
            Frame frame = new Frame(parent, context, key, appearance);
            CURRENT.set(frame);
            if (appearance != null) appearance.context = context;
        } catch (Throwable failure) {
            LunarConnectionDiagnostics.recordOnce("cosmetics.size.render", context.getClass().getName(),
                    "exception=" + failure.getClass().getSimpleName());
        }
    }

    /** Retain the matrix context passed to Lunar's deferred cosmetic draw until its GPU meshes finish. */
    public static boolean beginDeferredDrawContext(Object context) {
        Frame before = CURRENT.get();
        begin(context);
        return CURRENT.get() != before;
    }

    public static void end() {
        Frame frame = CURRENT.get();
        if (frame == null) return;
        unwindRender(frame);
        if (frame.parent == null) CURRENT.remove(); else CURRENT.set(frame.parent);
        if (frame.appearance != null && (frame.parent == null || frame.parent.appearance != frame.appearance))
            frame.appearance.context = frame.context;
        else if (frame.appearance != null && frame.parent != null)
            frame.appearance.context = frame.parent.context;
    }

    /** Called before Minecraft uploads a mesh, after Lunar has positioned the cosmetic. */
    public static void beginDraw() { beginDraw(null); }

    /** Uses the uploaded vertices to keep the rendered mesh center fixed while scaling. */
    public static void beginDraw(Object worldRenderer) {
        Frame render = CURRENT.get();
        AppearanceFrame appearance = APPEARANCE.get();
        double requested = LunarCosmeticAppearanceRenderer.sizeForDraw();
        double stackOffsetY = LunarCosmeticAppearanceRenderer.sameTypeHatOffsetYForDraw();
        // Preserve the native sign; the current deferred render path suppresses this optional stacking offset.
        double appliedStackOffsetY = stackOffsetY;
        Object context = render != null ? render.context : appearance == null ? null : appearance.context;
        if (context == null) {
            VertexPatch patch=null;
            try {if(worldRenderer!=null&&(requested!=1||stackOffsetY>0))
                patch=patchVertices(GEOMETRY.get(worldRenderer.getClass()).geometry(worldRenderer),requested,appliedStackOffsetY);}
            catch(Throwable unavailable) { }
            if(patch!=null) {
                DRAWING.set(new DrawFrame(DRAWING.get(),null,null,render,appearance,requested,false,patch));
                if(requested!=1&&LunarConnectionDiagnostics.isEnabled())LunarConnectionDiagnostics.recordOnce(
                        "cosmetics.size.draw_state",LunarCosmeticAppearanceRenderer.sizeDiagnosticKey()+":mesh",
                        "result=vertex_patched scale="+requested+" context=none geometryPivot=true");
                if(stackOffsetY>0)LunarConnectionDiagnostics.recordOnce(
                        "cosmetics.multi_same_type.stack_offset_draw",LunarCosmeticAppearanceRenderer.sizeDiagnosticKey()+":no-context",
                        "result=vertex_translated offsetY="+stackOffsetY+" scale="+requested+" context=none");
                return;
            }
            if(stackOffsetY>0&&LunarConnectionDiagnostics.isEnabled())LunarConnectionDiagnostics.recordOnce(
                    "cosmetics.multi_same_type.stack_offset_draw",LunarCosmeticAppearanceRenderer.sizeDiagnosticKey()+":no-context",
                    "result=offset_skipped reason=vertex_patch_unavailable offsetY="+stackOffsetY);
            if(requested!=1&&LunarConnectionDiagnostics.isEnabled())
                LunarConnectionDiagnostics.recordOnce("cosmetics.size.draw_state",LunarCosmeticAppearanceRenderer.sizeDiagnosticKey(),
                        "result=missing_matrix_context scale="+requested);
            return;
        }
        DrawFrame parent = DRAWING.get();
        Object key = context;
        double inherited = 1;
        boolean pushed = false;
        boolean offsetByMatrix = false;
        VertexPatch vertexPatch=null;
        MatrixAccess access = null;
        try {
            access = MATRIX.get(context.getClass());
            key = access.key(context);
            inherited = parent != null && parent.key == key && parent.owned ? parent.scale : 1;
            double factor = requested / inherited;
            if(stackOffsetY>0) {
                if(access.translate!=null) {
                    access.push.invoke(context);
                    pushed=true;
                    access.translate.invoke(context,0.0,appliedStackOffsetY,0.0);
                    offsetByMatrix=true;
                    if(LunarConnectionDiagnostics.isEnabled())LunarConnectionDiagnostics.recordOnce(
                            "cosmetics.multi_same_type.stack_offset_draw",
                            LunarCosmeticAppearanceRenderer.sizeDiagnosticKey()+":"+context.getClass().getName(),
                            "result=matrix_translated logicalUp="+stackOffsetY+" appliedY="+appliedStackOffsetY
                                    +" context="+context.getClass().getName());
                } else if(LunarConnectionDiagnostics.isEnabled()) {
                    LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.stack_offset_draw",
                            LunarCosmeticAppearanceRenderer.sizeDiagnosticKey()+":"+context.getClass().getName(),
                            "result=offset_skipped reason=matrix_translate_unavailable offsetY="+stackOffsetY);
                }
            }
            if (Math.abs(factor - 1) > 1e-9 || stackOffsetY>0&&!offsetByMatrix) {
                double[] pivot = null;
                MeshGeometry geometry=null;
                if(worldRenderer!=null)try {
                    geometry=GEOMETRY.get(worldRenderer.getClass()).geometry(worldRenderer);
                    if(geometry!=null)pivot=geometry.center;
                } catch(Throwable unavailable) { }
                if(geometry!=null)vertexPatch=patchVertices(geometry,factor,offsetByMatrix?0.0:appliedStackOffsetY);
                if(vertexPatch!=null) {
                    if(Math.abs(factor-1)>1e-9)LunarConnectionDiagnostics.recordOnce("cosmetics.size.vertex_patch",
                                LunarCosmeticAppearanceRenderer.sizeDiagnosticKey()+":"+geometry.count,
                                "result=patched vertices="+geometry.count+" scale="+requested+" factor="+factor
                                        +" pivot=geometry_bounds_center restoreAfterDraw=true");
                    if(stackOffsetY>0&&!offsetByMatrix)LunarConnectionDiagnostics.recordOnce(
                            "cosmetics.multi_same_type.stack_offset_draw",
                            LunarCosmeticAppearanceRenderer.sizeDiagnosticKey()+":"+context.getClass().getName(),
                            "result=vertex_translated logicalUp="+stackOffsetY+" appliedY="+appliedStackOffsetY+" scale="+requested
                                    +" context="+context.getClass().getName());
                }
                if(vertexPatch==null&&Math.abs(factor-1)>1e-9) {
                    if (worldRenderer != null && access.translate != null) {
                        Optional<BoundsAccess> bounds=BOUNDS.get(worldRenderer.getClass());
                        if(bounds.isPresent())pivot=bounds.get().center(worldRenderer);
                    }
                    if(pivot==null&&access.translate!=null)pivot=gpuCenter(worldRenderer);
                    if(!pushed){access.push.invoke(context);pushed=true;}
                    if (pivot != null) access.translate.invoke(context, pivot[0], pivot[1], pivot[2]);
                    access.scale.invoke(context, (float) factor, (float) factor, (float) factor);
                    if (pivot != null) access.translate.invoke(context, -pivot[0], -pivot[1], -pivot[2]);
                    if (pivot != null) LunarConnectionDiagnostics.recordOnce("cosmetics.size.anchor_applied",context.getClass().getName(),"pivot=geometry_bounds_center scale="+requested);
                    else
                        LunarConnectionDiagnostics.recordOnce("cosmetics.size.mesh_anchor", worldRenderer==null?"direct-native":worldRenderer.getClass().getName(),
                                "result=geometry_bounds_unavailable fallback=matrix_origin");
                }
                if(stackOffsetY>0&&!offsetByMatrix&&vertexPatch==null&&LunarConnectionDiagnostics.isEnabled())
                    LunarConnectionDiagnostics.recordOnce("cosmetics.multi_same_type.stack_offset_draw",
                            LunarCosmeticAppearanceRenderer.sizeDiagnosticKey()+":"+context.getClass().getName(),
                            "result=offset_skipped reason=vertex_patch_unavailable offsetY="+stackOffsetY);
            }
            DRAWING.set(new DrawFrame(parent, context, key, render, appearance, requested, pushed,vertexPatch));
            if(requested!=1&&LunarConnectionDiagnostics.isEnabled())
                LunarConnectionDiagnostics.recordOnce("cosmetics.size.draw_state",LunarCosmeticAppearanceRenderer.sizeDiagnosticKey()+":"+context.getClass().getName(),
                        "result="+(vertexPatch!=null?"vertex_patched":pushed?"scaled":parent!=null&&parent.key==key?"inherited":"unit")
                                +" scale="+requested+" context="+context.getClass().getName()+" geometryPivot="+(worldRenderer!=null)
                                +" vertexPatch="+(vertexPatch!=null));
        } catch (Throwable failure) {
            if (pushed && access != null) try { access.pop.invoke(context); } catch (Throwable ignored) { }
            DRAWING.set(new DrawFrame(parent, context, key, render, appearance, inherited, false,vertexPatch));
            LunarConnectionDiagnostics.recordOnce("cosmetics.size.draw", context.getClass().getName(),
                    "exception=" + failure.getClass().getSimpleName());
        }
    }

    public static void endDraw() {
        LunarPetOpacityPass.restoreDraw();
        DrawFrame frame = DRAWING.get();
        if (frame == null) return;
        restore(frame);
        if (frame.parent == null) DRAWING.remove(); else DRAWING.set(frame.parent);
    }

    private static void unwindRender(Frame ending) {
        while (true) {
            DrawFrame draw = DRAWING.get();
            if (draw == null || !isWithin(draw.render, ending)) return;
            endDraw();
        }
    }
    private static void unwindAppearance(AppearanceFrame ending) {
        while (true) {
            DrawFrame draw = DRAWING.get();
            if (draw == null || !isWithin(draw.appearance, ending)) return;
            endDraw();
        }
    }
    private static boolean isWithin(Frame candidate, Frame ancestor) {
        for (Frame frame = candidate; frame != null; frame = frame.parent) if (frame == ancestor) return true;
        return false;
    }
    private static boolean isWithin(AppearanceFrame candidate, AppearanceFrame ancestor) {
        for (AppearanceFrame frame = candidate; frame != null; frame = frame.parent) if (frame == ancestor) return true;
        return false;
    }
    private static void restore(DrawFrame frame) {
        if(frame.vertexPatch!=null)try{frame.vertexPatch.restore();}
        catch(Throwable failure){LunarConnectionDiagnostics.recordOnce("cosmetics.size.vertex_restore",
                frame.context==null?"no-context":frame.context.getClass().getName(),"exception="+failure.getClass().getSimpleName());}
        if(frame.owned)try { MATRIX.get(frame.context.getClass()).pop.invoke(frame.context); }
        catch (Throwable failure) { LunarConnectionDiagnostics.recordOnce("cosmetics.size.restore", frame.context.getClass().getName(),
                    "exception=" + failure.getClass().getSimpleName()); }
    }
}
