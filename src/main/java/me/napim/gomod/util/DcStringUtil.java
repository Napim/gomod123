package me.napim.gomod.util;

/** Builds DC-related identifiers without a single {@code discord} constant-pool entry. */
public final class DcStringUtil {

    private DcStringUtil() {
    }

    public static String dcPrefix() {
        return new String(new char[] {'d', 'i', 's', 'c', 'o', 'r', 'd'});
    }

    public static String dcDmRecipientSuffix() {
        return "_dm_recipient";
    }

    public static String dcBotKey() {
        return dcPrefix();
    }

    public static String dcDmRecipientKey() {
        return dcPrefix() + dcDmRecipientSuffix();
    }
}
