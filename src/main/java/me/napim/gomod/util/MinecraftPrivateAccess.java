package me.napim.gomod.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.util.Timer;
import java.lang.reflect.Field;
import java.util.List;

/** Private {@link Minecraft} fields via Forge reflection (avoids fragile {@code @Accessor} on {@code Minecraft}). */
public final class MinecraftPrivateAccess {
    private static final String[] TIMER = { "timer", "field_71428_T" };
    private static final String[] RESOURCE_PACK_REPO = { "mcResourcePackRepository", "field_110448_aq" };
    private static final String[] DEFAULT_RESOURCE_PACKS = { "defaultResourcePacks", "field_110449_ao" };
    private static final String[] REPOSITORY_ENTRIES = { "repositoryEntries", "field_110617_f" };
    private static final String[] ENTRY_RESOURCE_PACK = { "reResourcePack", "field_110524_c" };

    private MinecraftPrivateAccess() {}

    public static Timer getTimer(Minecraft mc) {
        if (mc == null) {
            return null;
        }
        try {
            return getPrivateValue(Minecraft.class, mc, TIMER);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static ResourcePackRepository getResourcePackRepository(Minecraft mc) {
        if (mc == null) {
            return null;
        }
        try {
            return getPrivateValue(Minecraft.class, mc, RESOURCE_PACK_REPO);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<IResourcePack> getDefaultResourcePacks(Minecraft mc) {
        if (mc == null) {
            return null;
        }
        try {
            return getPrivateValue(Minecraft.class, mc, DEFAULT_RESOURCE_PACKS);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static List<ResourcePackRepository.Entry> getRepositoryEntries(ResourcePackRepository repo) {
        if (repo == null) {
            return null;
        }
        try {
            return getPrivateValue(ResourcePackRepository.class, repo, REPOSITORY_ENTRIES);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static IResourcePack getResourcePackFromEntry(Object entry) {
        if (entry instanceof IResourcePack) {
            return (IResourcePack) entry;
        }
        if (!(entry instanceof ResourcePackRepository.Entry)) {
            return null;
        }
        try {
            return getPrivateValue(
                    ResourcePackRepository.Entry.class,
                    (ResourcePackRepository.Entry) entry,
                    ENTRY_RESOURCE_PACK);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Field findField(Class<?> owner, String... names) throws NoSuchFieldException {
        Class<?> current = owner;
        while (current != null) {
            for (String name : names) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    return field;
                } catch (NoSuchFieldException ignored) {
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchFieldException(owner.getName());
    }

    @SuppressWarnings("unchecked")
    public static <T> T getPrivateValue(Class<?> owner, Object target, String... names)
            throws ReflectiveOperationException {
        return (T) findField(owner, names).get(target);
    }

    public static void setPrivateValue(Class<?> owner, Object target, Object value, String... names)
            throws ReflectiveOperationException {
        findField(owner, names).set(target, value);
    }
}
