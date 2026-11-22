package me.napim.gomod.features;

import com.mojang.authlib.GameProfile;
import me.napim.gomod.GomodInternalFeatures;
import net.minecraft.entity.player.EntityPlayer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.UUID;

/** Render-only hiding for explicit /disguiser entries. Does not alter anyone's saved outfit. */
public final class LunarRemoteDisguiseCosmetics {
    private static final ThreadLocal<Deque<Boolean>> PLAYER_SCOPES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ClassValue<Optional<Method>> UUID_ACCESSORS = new ClassValue<Optional<Method>>() {
        @Override protected Optional<Method> computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("bridge$getUniqueID");
                return method.getReturnType() == UUID.class && !Modifier.isNative(method.getModifiers())
                        && !Modifier.isStatic(method.getModifiers()) ? Optional.of(method) : Optional.empty();
            } catch (ReflectiveOperationException ignored) { return Optional.empty(); }
        }
    };
    private LunarRemoteDisguiseCosmetics() { }

    public static boolean shouldHideProfile(GameProfile profile) {
        return GomodFixes.isWeaveRuntime()
                && RemoteDisguiseManager.getManualDisguiseTarget(profile) != null
                && !RemoteDisguiseManager.isManualFullLunarDisguise(profile);
    }

    public static boolean shouldHideBridge(Object player) {
        if (player instanceof EntityPlayer) return shouldHideProfile(((EntityPlayer)player).getGameProfile());
        if (player == null) return false;
        try {
            Optional<Method> accessor = UUID_ACCESSORS.get(player.getClass());
            if (!accessor.isPresent()) return false;
            UUID uuid = (UUID)accessor.get().invoke(player);
            return uuid != null && shouldHideProfile(new GameProfile(uuid, null));
        } catch (ReflectiveOperationException ignored) { return false; }
    }

    public static void beginPlayerRender(Object player) { PLAYER_SCOPES.get().addLast(shouldHideBridge(player)); }
    public static void endPlayerRender() {
        Deque<Boolean> scopes = PLAYER_SCOPES.get();
        if (!scopes.isEmpty()) scopes.removeLast();
        if (scopes.isEmpty()) PLAYER_SCOPES.remove();
    }
    public static boolean shouldHideCurrentPlayer() {
        Deque<Boolean> scopes = PLAYER_SCOPES.get();
        return !scopes.isEmpty() && Boolean.TRUE.equals(scopes.peekLast());
    }
}
