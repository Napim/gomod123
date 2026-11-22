package me.napim.gomod.features;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.function.Consumer;

/** Supplies the legacy AuthSuccess JWT to Lunar clients that expect encrypted_jwt. */
public final class LunarLegacyAuthSuccessJwtFallback {
    private static final String SUCCESS_FIELD = "success";

    private LunarLegacyAuthSuccessJwtFallback() {
    }

    /**
     * Returns true only when a legacy JWT was handed to the authenticator's existing
     * Assets-connection callback. The normal Lunar handler remains untouched otherwise.
     */
    @SuppressWarnings("unchecked")
    public static boolean consume(Object authenticator, Object authSuccess) {
        if (authenticator == null || authSuccess == null) {
            return false;
        }

        String stage = "read-encrypted-jwt";
        try {
            Method encryptedJwtGetter = authSuccess.getClass().getMethod("getEncryptedJwt");
            Object encryptedJwt = encryptedJwtGetter.invoke(authSuccess);
            if (encryptedJwt == null) {
                return false;
            }
            // The protobuf value may be a package-private ByteString implementation.
            // Invoke size() through the generated getter's public declared return type.
            stage = "read-encrypted-jwt-size";
            Method sizeGetter = encryptedJwtGetter.getReturnType().getMethod("size");
            Object sizeValue = sizeGetter.invoke(encryptedJwt);
            if (!(sizeValue instanceof Number)) {
                return false;
            }
            int encryptedJwtBytes = ((Number) sizeValue).intValue();
            if (encryptedJwtBytes >= 28) {
                return false;
            }

            stage = "read-legacy-jwt";
            Object jwtValue = authSuccess.getClass().getMethod("getJwt").invoke(authSuccess);
            if (!(jwtValue instanceof String) || ((String) jwtValue).isEmpty()) {
                return false;
            }

            stage = "read-token-callback";
            Field consumerField = findTokenConsumerField(authenticator.getClass());
            consumerField.setAccessible(true);
            Object consumerValue = consumerField.get(authenticator);
            if (!(consumerValue instanceof Consumer)) {
                return false;
            }

            stage = "set-auth-success";
            Field successField = findSuccessField(authenticator.getClass());
            successField.setAccessible(true);
            successField.setBoolean(authenticator, true);
            stage = "invoke-token-callback";
            ((Consumer<String>) consumerValue).accept((String) jwtValue);

            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "lunar.auth_legacy_jwt_fallback", "encrypted-jwt-field-absent",
                    "accepted=true encryptedJwtBytes=" + encryptedJwtBytes + " legacyJwtPresent=true");
            return true;
        } catch (Exception error) {
            Throwable cause = error instanceof InvocationTargetException
                    ? ((InvocationTargetException) error).getTargetException() : error;
            LunarConnectionDiagnostics.recordOnceAfterStart(
                    "lunar.auth_legacy_jwt_fallback_failed", "reflection-or-callback-failed",
                    "stage=" + stage + " exception=" + cause.getClass().getName());
            return false;
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Lunar obfuscates this callback field between builds; identify its String consumer by type. */
    private static Field findTokenConsumerField(Class<?> type) throws NoSuchFieldException {
        Field match = null;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Consumer.class.isAssignableFrom(field.getType()) || !acceptsString(field)) {
                    continue;
                }
                if (match != null) {
                    throw new NoSuchFieldException("ambiguous-token-consumer");
                }
                match = field;
            }
        }
        if (match == null) {
            throw new NoSuchFieldException("token-consumer");
        }
        return match;
    }

    private static boolean acceptsString(Field field) {
        Type generic = field.getGenericType();
        if (!(generic instanceof ParameterizedType)) {
            return true;
        }
        Type[] arguments = ((ParameterizedType) generic).getActualTypeArguments();
        return arguments.length == 1 && arguments[0] == String.class;
    }

    /** Prefer Lunar's named flag; newer obfuscation can still be handled when the flag is unique. */
    private static Field findSuccessField(Class<?> type) throws NoSuchFieldException {
        try {
            Field named = findField(type, SUCCESS_FIELD);
            if (named.getType() == boolean.class) {
                return named;
            }
        } catch (NoSuchFieldException ignored) {
            // Fall through to the structurally unique declared boolean below.
        }

        Field match = null;
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != boolean.class) {
                continue;
            }
            if (match != null) {
                throw new NoSuchFieldException("ambiguous-auth-success-flag");
            }
            match = field;
        }
        if (match == null) {
            throw new NoSuchFieldException("auth-success-flag");
        }
        return match;
    }
}
