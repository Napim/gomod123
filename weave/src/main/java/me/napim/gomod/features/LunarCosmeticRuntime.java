package me.napim.gomod.features;

import java.lang.reflect.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Native equipped definitions and immutable companion frames, resolved by constructor roles. */
public final class LunarCosmeticRuntime {
    private LunarCosmeticRuntime() { }
    private static final ClassValue<Optional<Constructor<?>>> DEFINITION_CONSTRUCTORS =
            new ClassValue<Optional<Constructor<?>>>() {
                @Override protected Optional<Constructor<?>> computeValue(Class<?> type) {
                    for (Class<?> current = type; current != null && current != Object.class;
                            current = current.getSuperclass()) {
                        Constructor<?> constructor = definitionConstructor(current);
                        if (constructor != null) return Optional.of(constructor);
                    }
                    return Optional.empty();
                }
            };
    private static final ClassValue<Field[]> DEFINITION = new ClassValue<Field[]>() {
        protected Field[] computeValue(Class<?> type) {
            try {
                Optional<Constructor<?>> match = DEFINITION_CONSTRUCTORS.get(type);
                if (!match.isPresent()) throw new IllegalStateException("Unknown cosmetic definition");
                Constructor<?> constructor = match.get();
                return roles(constructor.getDeclaringClass(), constructor);
            } catch (Exception error) { throw new IllegalStateException("Unknown cosmetic definition", error); }
        }
    };
    private static final ClassValue<Field[]> WRAPPER = new ClassValue<Field[]>() {
        protected Field[] computeValue(Class<?> type) {
            for(int depth=1;depth<=2;depth++) {
                List<Field[]> paths=new ArrayList<>();definitionPaths(type,depth,new HashSet<Class<?>>(),paths);
                if(paths.size()>1)throw new IllegalStateException("Ambiguous cosmetic wrapper");
                if(paths.size()==1){Field[] path=paths.get(0);for(Field field:path)field.setAccessible(true);return path;}
            }
            throw new IllegalStateException("Unknown cosmetic wrapper");
        }
    };
    /** Follow only final native ownership fields when an equipped instance adds another wrapper. */
    private static void definitionPaths(Class<?> type,int depth,Set<Class<?>> visiting,List<Field[]> paths) {
        if(!visiting.add(type))return;
        try {
            for(Class<?> c=type;c!=null&&c!=Object.class;c=c.getSuperclass())for(Field field:c.getDeclaredFields()) {
                if(Modifier.isStatic(field.getModifiers()))continue;
                if(depth==1) {
                    if(DEFINITION_CONSTRUCTORS.get(field.getType()).isPresent())paths.add(new Field[]{field});
                } else if(Modifier.isFinal(field.getModifiers())&&field.getType().getName().startsWith("com.moonsworth.lunar.client.")) {
                    List<Field[]> children=new ArrayList<>();definitionPaths(field.getType(),depth-1,visiting,children);
                    for(Field[] child:children){Field[] path=new Field[child.length+1];path[0]=field;System.arraycopy(child,0,path,1,child.length);paths.add(path);}
                }
            }
        } finally {visiting.remove(type);}
    }
    public static Object definition(Object item) throws Exception {
        if (item == null) throw new IllegalArgumentException("Missing cosmetic");
        if(DEFINITION_CONSTRUCTORS.get(item.getClass()).isPresent())return item;
        Object selected=item;
        for(Field field:WRAPPER.get(item.getClass())) {
            selected=field.get(selected);if(selected==null)throw new IllegalStateException("Missing equipped cosmetic definition");
        }
        return selected;
    }
    public static long id(Object item) throws Exception {
        Object definition = definition(item);
        return ((Number) DEFINITION.get(definition.getClass())[0].get(definition)).longValue();
    }
    public static String category(Object item) throws Exception {
        Object definition = definition(item);
        Object value = DEFINITION.get(definition.getClass())[3].get(definition);
        if (!(value instanceof Enum<?>)) throw new IllegalStateException("Unknown cosmetic category");
        return ((Enum<?>) value).name();
    }
    private static final ClassValue<Optional<Method>> RESOURCE = new ClassValue<Optional<Method>>() {
        protected Optional<Method> computeValue(Class<?> type) {
            Class<?> resourceType = DEFINITION.get(type)[2].getType();
            Method match = null;
            for (Method method : type.getMethods()) {
                if (Modifier.isStatic(method.getModifiers()) || method.getReturnType() != resourceType
                        || !Arrays.equals(method.getParameterTypes(), new Class<?>[]{UUID.class})) continue;
                if (match != null) throw new IllegalStateException("Ambiguous cosmetic resource getter");
                match = method;
            }
            return Optional.ofNullable(match);
        }
    };
    /** Uses the native account-specific texture selection, including animated cloak resource keys. */
    public static Object resource(Object item, UUID owner) throws Exception {
        Object definition = definition(item);
        Optional<Method> getter = RESOURCE.get(definition.getClass());
        return getter.isPresent() ? getter.get().invoke(definition, owner)
                : DEFINITION.get(definition.getClass())[2].get(definition);
    }
    /** Returns the {@code lunar-jit} resource path when a {@code lunar:} key is hosted via JIT assets, or {@code null}. */
    public static String jitAssetPath(String domain, String path) {
        if (!"lunar".equals(domain) || path == null || path.contains("dev_cosmetics")) return null;
        return path;
    }

    /** Extracts a texture key from an active retained/deferred {@code ModelDraw} or wrapper. */
    public static Object drawTextureResource(Object draw, Class<?> resourceClass) {
        return drawTextureResource(draw, resourceClass, 0);
    }

    private static Object drawTextureResource(Object draw, Class<?> resourceClass, int depth) {
        if (draw == null || resourceClass == null || depth > 2) return null;
        Optional<DrawResourceReader> reader = DRAW_RESOURCES.get(draw.getClass());
        return reader.isPresent() ? reader.get().extract(draw, resourceClass, depth) : null;
    }

    private static final ClassValue<Optional<DrawResourceReader>> DRAW_RESOURCES =
            new ClassValue<Optional<DrawResourceReader>>() {
                @Override protected Optional<DrawResourceReader> computeValue(Class<?> type) {
                    Field modelField = null, modelResField = null, supplierField = null, supplierResField = null, wrapperField = null;
                    Method supplierMethod = null;
                    try {
                        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                            for (Field field : c.getDeclaredFields()) {
                                if (Modifier.isStatic(field.getModifiers())) continue;
                                Class<?> fieldType = field.getType();
                                if (isLunarResourceFieldType(fieldType, null)) {
                                    field.setAccessible(true);
                                    return Optional.of(new DrawResourceReader(field, null, null, false));
                                }
                                if (!fieldType.isPrimitive() && !fieldType.isArray() && !fieldType.isEnum()) {
                                    if (DEFINITION_CONSTRUCTORS.get(fieldType).isPresent()) continue;
                                    Method getter = uniqueZeroArgModelMethod(fieldType);
                                    Field subRes = getter == null ? null : uniqueLunarResourceField(getter.getReturnType(), null);
                                    if (getter != null && subRes != null) {
                                        if (supplierField != null) return Optional.empty();
                                        supplierField = field; supplierMethod = getter; supplierResField = subRes;
                                        continue;
                                    }
                                    Field directRes = uniqueLunarResourceField(fieldType, null);
                                    if (directRes != null) {
                                        if (modelField != null) return Optional.empty();
                                        modelField = field; modelResField = directRes;
                                        continue;
                                    }
                                    if (isModelDrawInterface(fieldType)) {
                                        if (wrapperField != null) return Optional.empty();
                                        wrapperField = field;
                                    }
                                }
                            }
                        }
                    } catch (LinkageError unavailable) { return Optional.empty(); }
                    if (supplierField != null) {
                        supplierField.setAccessible(true); supplierMethod.setAccessible(true); supplierResField.setAccessible(true);
                        return Optional.of(new DrawResourceReader(supplierField, supplierMethod, supplierResField, false));
                    }
                    if (modelField != null) {
                        modelField.setAccessible(true); modelResField.setAccessible(true);
                        return Optional.of(new DrawResourceReader(modelField, null, modelResField, false));
                    }
                    if (wrapperField != null) {
                        wrapperField.setAccessible(true);
                        return Optional.of(new DrawResourceReader(wrapperField, null, null, true));
                    }
                    return Optional.empty();
                }
            };

    private static volatile Class<?> cachedResourceType;

    private static boolean isLunarResourceFieldType(Class<?> candidate, Class<?> knownResourceClass) {
        if (candidate == null || candidate == Object.class) return false;
        if (knownResourceClass != null) {
            if (knownResourceClass.isAssignableFrom(candidate)) return true;
            if (candidate.isInterface() && candidate.getName().startsWith("com.moonsworth.lunar.")
                    && candidate.isAssignableFrom(knownResourceClass)) return true;
        }
        if ("net.minecraft.util.ResourceLocation".equals(candidate.getName())) return true;
        if (candidate.isInterface() && hasBridgeResourceMethods(candidate)) return true;
        Class<?> known = cachedResourceType;
        return known != null && known == candidate;
    }

    private static boolean hasBridgeResourceMethods(Class<?> iface) {
        if (!iface.getName().startsWith("com.moonsworth.lunar.")) return false;
        boolean hasDomain = false, hasPath = false;
        try {
            for (Method m : iface.getMethods()) {
                if (m.getParameterTypes().length != 0 || m.getReturnType() != String.class) continue;
                if ("bridge$getDomain".equals(m.getName())) hasDomain = true;
                if ("bridge$getPath".equals(m.getName())) hasPath = true;
            }
        } catch (LinkageError unavailable) { return false; }
        return hasDomain && hasPath;
    }

    private static Field uniqueLunarResourceField(Class<?> owner, Class<?> knownResourceClass) {
        if (owner == null || owner.isPrimitive() || owner == Object.class
                || !owner.getName().startsWith("com.moonsworth.lunar.")) return null;
        Field match = null;
        try {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || !isLunarResourceFieldType(field.getType(), knownResourceClass)) continue;
                    if (match != null) return null;
                    match = field;
                }
            }
        } catch (LinkageError unavailable) { return null; }
        return match;
    }

    private static Method uniqueZeroArgModelMethod(Class<?> functionalType) {
        if (!functionalType.isInterface() || !functionalType.getName().startsWith("com.moonsworth.lunar.")) return null;
        Method match = null;
        try {
            for (Method method : functionalType.getMethods()) {
                if (method.getDeclaringClass() == Object.class || Modifier.isStatic(method.getModifiers())
                        || method.getParameterTypes().length != 0 || method.isDefault()) continue;
                if (match != null) return null;
                match = method;
            }
        } catch (LinkageError unavailable) { return null; }
        return match;
    }

    private static boolean isModelDrawInterface(Class<?> candidate) {
        if (!candidate.isInterface() || !candidate.getName().startsWith("com.moonsworth.lunar.")) return false;
        boolean hasTranslucent = false, hasHighlight = false;
        try {
            for (Method method : candidate.getMethods()) {
                if (method.getParameterTypes().length != 0) continue;
                if ("translucent".equals(method.getName()) && method.getReturnType() == boolean.class) hasTranslucent = true;
                if ("highlightEntry".equals(method.getName())) hasHighlight = true;
            }
        } catch (LinkageError unavailable) { return false; }
        return hasTranslucent && hasHighlight;
    }

    private static final class DrawResourceReader {
        final Field field;
        final Method step;
        final Field resourceField;
        final boolean unwrap;

        DrawResourceReader(Field field, Method step, Field resourceField, boolean unwrap) {
            this.field = field;
            this.step = step;
            this.resourceField = resourceField;
            this.unwrap = unwrap;
        }

        Object extract(Object target, Class<?> resourceClass, int depth) {
            try {
                Object value = field.get(target);
                if (value == null) return null;
                if (unwrap) return drawTextureResource(value, resourceClass, depth + 1);
                if (step != null) value = step.invoke(value);
                if (value == null) return null;
                Object res = resourceField == null ? value : resourceField.get(value);
                return res != null && isLunarResourceFieldType(res.getClass(), resourceClass) ? res : null;
            } catch (Throwable unavailable) {
                return null;
            }
        }
    }

    /**
     * Resolves a loaded model's texture key when the cosmetic definition points to a model
     * descriptor (for example a {@code .gek.json} file) instead of the image texture itself.
     */
    public static Object modelTextureResource(Object item) throws Exception {
        Object definition = definition(item);
        Class<?> rt = DEFINITION.get(definition.getClass())[2].getType();
        if (cachedResourceType == null) cachedResourceType = rt;
        Optional<ModelResourceExtractor> extractor = MODEL_RESOURCE.get(definition.getClass());
        return extractor.isPresent() ? extractor.get().extract(definition) : null;
    }
    private static final ClassValue<Optional<ModelResourceExtractor>> MODEL_RESOURCE =
            new ClassValue<Optional<ModelResourceExtractor>>() {
                @Override protected Optional<ModelResourceExtractor> computeValue(Class<?> type) {
                    Class<?> baseDefinition = DEFINITION.get(type)[2].getDeclaringClass();
                    if (type == baseDefinition) return Optional.empty();
                    Class<?> resourceType = DEFINITION.get(type)[2].getType();
                    Field modelField = null;
                    Field textureField = null;
                    for (Class<?> c = type; c != null && c != baseDefinition; c = c.getSuperclass()) {
                        for (Field field : c.getDeclaredFields()) {
                            if (Modifier.isStatic(field.getModifiers())) continue;
                            Class<?> candidateType = field.getType();
                            if (candidateType.isPrimitive() || candidateType.isEnum() || candidateType.isArray()
                                    || candidateType == resourceType
                                    || !candidateType.getName().startsWith("com.moonsworth.lunar.")) continue;
                            Field foundTexture = uniqueFieldOfType(candidateType, resourceType);
                            if (foundTexture == null) continue;
                            if (modelField != null) return Optional.empty();
                            modelField = field;
                            textureField = foundTexture;
                        }
                    }
                    if (modelField == null || textureField == null) return Optional.empty();
                    modelField.setAccessible(true);
                    textureField.setAccessible(true);
                    List<Method> modelGetters = new ArrayList<Method>();
                    Class<?> targetModelType = modelField.getType();
                    for (Method method : type.getMethods()) {
                        if (Modifier.isStatic(method.getModifiers()) || method.getParameterTypes().length != 0
                                || method.getReturnType() != targetModelType
                                || method.getDeclaringClass() == Object.class) continue;
                        method.setAccessible(true);
                        modelGetters.add(method);
                    }
                    return Optional.of(new ModelResourceExtractor(modelField, modelGetters, textureField));
                }
            };
    private static Field uniqueFieldOfType(Class<?> owner, Class<?> fieldType) {
        Field match = null;
        try {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType() != fieldType) continue;
                    if (match != null) return null;
                    match = field;
                }
            }
        } catch (LinkageError unavailable) { return null; }
        return match;
    }
    private static final class ModelResourceExtractor {
        final Field modelField;
        final List<Method> modelGetters;
        final Field textureField;
        ModelResourceExtractor(Field modelField, List<Method> modelGetters, Field textureField) {
            this.modelField = modelField;
            this.modelGetters = modelGetters;
            this.textureField = textureField;
        }
        Object extract(Object definition) throws Exception {
            Object model = modelField.get(definition);
            if (model == null) {
                for (Method getter : modelGetters) {
                    model = getter.invoke(definition);
                    if (model != null) break;
                }
            }
            return model == null ? null : textureField.get(model);
        }
    }
    private static Constructor<?> definitionConstructor(Class<?> type) {
        Constructor<?> match = null;
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length != 13 || p[0] != long.class || p[1] != String.class || !p[3].isEnum()
                    || p[4] != boolean.class || p[5] != long.class || p[6] != java.time.Instant.class
                    || p[7] != List.class || p[8] != List.class || p[9] != boolean.class || p[12] != boolean.class) continue;
            if (match != null) throw new IllegalStateException("Ambiguous cosmetic constructor"); match = ctor;
        }
        return match;
    }
    public static Field[] roles(Class<?> type, Constructor<?> ctor) throws Exception {
        if (ctor == null) throw new NoSuchMethodException("Unknown constructor layout");
        ClassNode node = LunarColorRuntime.readClass(type);
        String descriptor = org.objectweb.asm.Type.getConstructorDescriptor(ctor);
        MethodNode body = null;
        for (MethodNode method : node.methods) if (method.name.equals("<init>") && method.desc.equals(descriptor)) body = method;
        if (body == null) throw new NoSuchMethodException("Missing constructor bytecode");
        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(descriptor);
        Map<Integer, Integer> slots = new HashMap<Integer, Integer>();
        for (int i = 0, slot = 1; i < args.length; i++) { slots.put(slot, i); slot += args[i].getSize(); }
        Field[] result = new Field[args.length];
        for (AbstractInsnNode insn : body.instructions) if (insn instanceof FieldInsnNode && insn.getOpcode() == Opcodes.PUTFIELD) {
            FieldInsnNode put = (FieldInsnNode) insn; AbstractInsnNode value = previous(insn), receiver = previous(value);
            if (!put.owner.equals(node.name) || !(value instanceof VarInsnNode) || !(receiver instanceof VarInsnNode)
                    || receiver.getOpcode() != Opcodes.ALOAD || ((VarInsnNode) receiver).var != 0) continue;
            Integer role = slots.get(((VarInsnNode) value).var);
            if (role == null || !put.desc.equals(args[role].getDescriptor())) continue;
            if (result[role] != null) throw new IllegalStateException("Ambiguous constructor role");
            Field field = type.getDeclaredField(put.name); field.setAccessible(true); result[role] = field;
        }
        return result;
    }
    private static AbstractInsnNode previous(AbstractInsnNode insn) {
        if (insn == null) return null;
        do { insn = insn.getPrevious(); } while (insn != null && insn.getOpcode() < 0); return insn;
    }
    private static final ClassValue<FrameLayout> FRAMES = new ClassValue<FrameLayout>() {
        protected FrameLayout computeValue(Class<?> type) {
            try { return new FrameLayout(type); }
            catch (Exception error) { throw new IllegalStateException("Unknown companion frame", error); }
        }
    };
    public static double[] framePosition(Object frame) throws Exception {
        Field[] f = FRAMES.get(frame.getClass()).fields;
        return new double[]{f[2].getDouble(frame), f[3].getDouble(frame), f[4].getDouble(frame), f[5].getFloat(frame)};
    }
    /** Records have final fields; construct a replacement instead of attempting forbidden writes. */
    public static Object copyFrame(Object frame, double x, double y, double z, float yaw) throws Exception {
        FrameLayout layout = FRAMES.get(frame.getClass()); Field[] f = layout.fields;
        return layout.constructor.newInstance(f[0].get(frame), f[1].get(frame), x, y, z, yaw, f[6].getDouble(frame));
    }
    private static final class FrameLayout {
        final Constructor<?> constructor; final Field[] fields;
        FrameLayout(Class<?> type) throws Exception {
            Constructor<?> match = null;
            for (Constructor<?> c : type.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length != 7 || p[0].isPrimitive() || p[1].isPrimitive() || p[2] != double.class
                        || p[3] != double.class || p[4] != double.class || p[5] != float.class || p[6] != double.class) continue;
                if (match != null) throw new IllegalStateException("Ambiguous companion frame"); match = c;
            }
            if (match == null) throw new NoSuchMethodException("Unknown companion frame");
            match.setAccessible(true); constructor = match; fields = roles(type, match);
            for (Field f : fields) if (f == null) throw new NoSuchFieldException("Missing companion frame role");
        }
    }
}
