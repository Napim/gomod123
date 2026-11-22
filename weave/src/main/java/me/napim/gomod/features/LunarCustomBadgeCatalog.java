package me.napim.gomod.features;

import com.google.gson.JsonObject;
import me.napim.gomod.util.GomodResourcePackInjector;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Registers optional locally packaged Gomod badges in Lunar's native badge catalog. */
public final class LunarCustomBadgeCatalog {
    /** Add future custom badges here; the catalog, ownership and image pipeline stays reusable. */
    private static final BadgeDefinition[] BADGES = {};
    private static volatile Set<Integer> installedIds = Collections.emptySet();
    private static volatile String imageDataJson;

    private static final class BadgeDefinition {
        final int id;
        final String name, description, path;
        final Instant availableAt;

        BadgeDefinition(int id, String name, String description, String path, Instant availableAt) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.path = path;
            this.availableAt = availableAt;
        }
    }

    private LunarCustomBadgeCatalog() {
    }

    /** Called by the native badge catalog loader after Lunar finishes parsing its downloaded rows. */
    public static synchronized void appendToCatalog(Object manager, String badgeTypeName,
                                                     String resourceTypeName, String resourceFactoryName) {
        installedIds = Collections.emptySet();
        if (manager == null || BADGES.length == 0) return;
        Set<Integer> installed = new HashSet<Integer>();
        try {
            if (badgeTypeName == null || resourceTypeName == null || resourceFactoryName == null) {
                throw new IllegalArgumentException("native badge constructor or resource factory was not resolved");
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> catalog = (Map<Object, Object>) badgeCatalogMap(manager);
            if (catalog == null) throw new IllegalStateException("native badge map is null");
            ClassLoader loader = manager.getClass().getClassLoader();
            Class<?> badgeType = Class.forName(badgeTypeName, false, loader);
            Class<?> resourceType = Class.forName(resourceTypeName, false, loader);
            Method createResource = resourceType.getMethod(resourceFactoryName, String.class, String.class);
            Constructor<?> constructor = badgeType.getDeclaredConstructor(
                    int.class, String.class, String.class, resourceType, resourceType,
                    Instant.class, boolean.class);
            constructor.setAccessible(true);

            for (BadgeDefinition badge : BADGES) {
                if (register(catalog, createResource, constructor, badge)) installed.add(Integer.valueOf(badge.id));
            }
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            System.err.println("[gomod] Could not register custom Lunar badges: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        } finally {
            installedIds = Collections.unmodifiableSet(new HashSet<Integer>(installed));
            if (!installed.isEmpty()) GomodResourcePackInjector.ensureInstalled();
            System.out.println("[gomod] Custom badge catalog: registered=" + installed.size());
        }
    }

    /** Finds the inherited native catalog accessor without relying on Lunar's obfuscated method name. */
    private static Object badgeCatalogMap(Object manager) throws Exception {
        Method accessor = null;
        for (Method candidate : manager.getClass().getMethods()) {
            if (candidate.getParameterCount() != 0 || !Map.class.isAssignableFrom(candidate.getReturnType())) {
                continue;
            }
            if (accessor != null && !accessor.equals(candidate)) {
                throw new IllegalStateException("multiple public badge map accessors found");
            }
            accessor = candidate;
        }
        if (accessor == null) throw new NoSuchMethodException("public badge map accessor");
        return accessor.invoke(manager);
    }

    private static boolean register(Map<Object, Object> catalog,
                                    Method createResource, Constructor<?> constructor,
                                    BadgeDefinition badge) throws Exception {
        Integer key = Integer.valueOf(badge.id);
        Object existing = catalog.get(key);
        if (existing != null) {
            Object existingName = existing.getClass().getMethod("name").invoke(existing);
            if (badge.name.equals(existingName)) return true;
            System.err.println("[gomod] Custom badge ID collision; skipped " + badge.name + " (id=" + badge.id + ")");
            return false;
        }
        Object resource = createResource.invoke(null, "gomod", badge.path);
        Object nativeBadge = constructor.newInstance(badge.id, badge.name, badge.description, resource, resource,
                badge.availableAt, false);
        catalog.put(key, nativeBadge);
        return true;
    }

    /** Adds local custom badge entitlements to the response Lunar is about to consume. */
    public static Object appendOwnedBadges(Object response) {
        if (response == null || installedIds.isEmpty()
                || !response.getClass().getName().equals("com.lunarclient.websocket.badge.v1.LoginResponse")) {
            return response;
        }
        try {
            ClassLoader loader = response.getClass().getClassLoader();
            @SuppressWarnings("unchecked")
            List<Object> existing = (List<Object>) response.getClass()
                    .getMethod("getOwnedBadgesList").invoke(response);
            Set<Integer> ownedIds = new HashSet<Integer>();
            Method badgeId = Class.forName("com.lunarclient.websocket.badge.v1.OwnedBadge", false, loader)
                    .getMethod("getBadgeId");
            for (Object row : existing) ownedIds.add(Integer.valueOf(((Number) badgeId.invoke(row)).intValue()));

            Object builder = response.getClass().getMethod("toBuilder").invoke(response);
            Class<?> ownedType = Class.forName("com.lunarclient.websocket.badge.v1.OwnedBadge", false, loader);
            Method addOwnedBadge = builder.getClass().getMethod("addOwnedBadges", ownedType);
            boolean changed = false;
            for (BadgeDefinition badge : BADGES) {
                Integer id = Integer.valueOf(badge.id);
                if (installedIds.contains(id) && ownedIds.add(id)) {
                    addOwnedBadge.invoke(builder, ownedBadge(ownedType, badge.id));
                    changed = true;
                }
            }
            if (!changed) return response;
            System.out.println("[gomod] Added custom badge ownership: count=" + installedIds.size());
            return builder.getClass().getMethod("build").invoke(builder);
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            System.err.println("[gomod] Could not add custom badge ownership: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return response;
        }
    }

    private static Object ownedBadge(Class<?> ownedType, int id) throws Exception {
        Object builder = ownedType.getMethod("newBuilder").invoke(null);
        builder.getClass().getMethod("setBadgeId", int.class).invoke(builder, id);
        return builder.getClass().getMethod("build").invoke(builder);
    }

    /** Supplies UI-safe image URLs because Lunar's WebOSR badge renderer receives only resource paths. */
    public static String imageDataJson() {
        String cached = imageDataJson;
        if (cached != null) return cached;
        synchronized (LunarCustomBadgeCatalog.class) {
            if (imageDataJson != null) return imageDataJson;
            JsonObject images = new JsonObject();
            for (BadgeDefinition badge : BADGES) {
                String data = imageDataUri(badge.path);
                if (data != null) images.addProperty(String.valueOf(badge.id), data);
            }
            imageDataJson = images.toString();
            return imageDataJson;
        }
    }

    private static String imageDataUri(String path) {
        String resource = "/assets/gomod/" + path;
        try (InputStream input = LunarCustomBadgeCatalog.class.getResourceAsStream(resource)) {
            if (input == null) return null;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception ignored) {
            return null;
        }
    }
}
