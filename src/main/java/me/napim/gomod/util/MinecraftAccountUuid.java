package me.napim.gomod.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;

import java.util.UUID;
import java.util.regex.Pattern;

/** Resolves the launcher account from the current session, independently of world and disguise profiles. */
public final class MinecraftAccountUuid {
    private static final Pattern COMPACT = Pattern.compile("(?i)[a-f0-9]{32}");
    private static final Pattern DASHED = Pattern.compile("(?i)[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private static volatile Account lastAccount;
    private static volatile SessionProfile lastSessionProfile;

    private MinecraftAccountUuid() { }

    public static UUID current() {
        Account account = readAccount();
        return account != null && account.canonical ? account.uuid : null;
    }

    /** Fit sharing also accepts session IDs with nonstandard hyphen placement. */
    public static UUID currentForFitSharing() {
        return currentFromCompactSessionId();
    }

    /** Matches the launcher ID after dropping hyphens, including legacy session formats. */
    public static UUID currentFromCompactSessionId() {
        Account account = readAccount();
        return account == null ? null : account.uuid;
    }

    /** Preserves Session#getProfile's UUID parsing for callers that use the profile identity. */
    public static UUID sessionProfileId(Minecraft mc) {
        Session session = mc == null ? null : mc.getSession();
        if (session == null) return null;
        SessionProfile cached = lastSessionProfile;
        if (cached != null && cached.session == session) return cached.uuid;
        GameProfile profile = session.getProfile();
        UUID uuid = profile == null ? null : profile.getId();
        if (uuid != null) lastSessionProfile = new SessionProfile(session, uuid);
        return uuid;
    }

    private static Account readAccount() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            Session session = mc == null ? null : mc.getSession();
            String raw = session == null ? null : session.getPlayerID();
            if (raw == null) return null;

            Account cached = lastAccount;
            if (cached != null && cached.raw.equals(raw)) return cached;

            String compact = raw.replace("-", "");
            UUID uuid = null;
            if (COMPACT.matcher(compact).matches()) {
                uuid = UUID.fromString(compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-"
                        + compact.substring(12, 16) + "-" + compact.substring(16, 20) + "-" + compact.substring(20));
            }
            boolean canonical = uuid != null && (raw.equals(compact) || DASHED.matcher(raw).matches());
            Account resolved = new Account(raw, uuid, canonical);
            lastAccount = resolved;
            return resolved;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static final class Account {
        final String raw;
        final UUID uuid;
        final boolean canonical;

        Account(String raw, UUID uuid, boolean canonical) {
            this.raw = raw;
            this.uuid = uuid;
            this.canonical = canonical;
        }
    }

    private static final class SessionProfile {
        final Session session;
        final UUID uuid;

        SessionProfile(Session session, UUID uuid) {
            this.session = session;
            this.uuid = uuid;
        }
    }
}
