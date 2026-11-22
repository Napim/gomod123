package me.napim.gomod.util;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Forge client data lives under the real game directory: {@code <.minecraft>/gomod123/}.
 * Not {@code ~/.weave/...} (legacy Lunar/Weave layout).
 */
public final class GomodPaths {

    private GomodPaths() {
    }

    private static final class ForgeDirectory {
        private static final ForgeDirectory ACCESS = resolve();
        private final Method loaderInstance;
        private final Method configDirectory;

        private ForgeDirectory(Method loaderInstance, Method configDirectory) {
            this.loaderInstance = loaderInstance;
            this.configDirectory = configDirectory;
        }

        private static ForgeDirectory resolve() {
            try {
                Class<?> loader = Class.forName("net.minecraftforge.fml.common.Loader", false,
                        GomodPaths.class.getClassLoader());
                return new ForgeDirectory(loader.getMethod("instance"), loader.getMethod("getConfigDir"));
            } catch (Throwable ignored) {
                return null;
            }
        }

        private File getGameDirectory() throws ReflectiveOperationException {
            Object loader = loaderInstance.invoke(null);
            File config = loader == null ? null : (File) configDirectory.invoke(loader);
            return config == null ? null : config.getParentFile();
        }
    }

    /**
     * Minecraft instance directory (the folder that contains {@code saves}, {@code mods}, {@code config}, etc.).
     */
    public static File getMinecraftGameDir() {
        ForgeDirectory forge = ForgeDirectory.ACCESS;
        if (forge != null) {
            try {
                // Forge may publish its config directory after the first call during startup.
                File parent = forge.getGameDirectory();
                if (parent != null) return parent.getAbsoluteFile();
            } catch (Throwable ignored) {
            }
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) {
            File mc = new File(appData, ".minecraft");
            if (mc.isDirectory()) {
                return mc.getAbsoluteFile();
            }
        }
        return new File(System.getProperty("user.home", "."), ".minecraft").getAbsoluteFile();
    }

    /** All gomod123 mod files: configs, routes, caches, debug logs — {@code <gameDir>/gomod123}. */
    public static File getGomod123Dir() {
        return new File(getMinecraftGameDir(), "gomod123").getAbsoluteFile();
    }

    /** @deprecated Use {@link #getGomod123Dir()} */
    @Deprecated
    public static File getWeaveLegacyGomod123Dir() {
        return new File(new File(System.getProperty("user.home", "."), ".weave"), "gomod123").getAbsoluteFile();
    }

    public static File getWeaveLegacyGomodDir() {
        return new File(new File(System.getProperty("user.home", "."), ".weave"), "gomod").getAbsoluteFile();
    }
}
