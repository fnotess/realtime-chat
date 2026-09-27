package com.sithija.chat;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one username rule, shared by sign-up, login and message routing, and mirrored by the
 * CHECK constraint in V2__auth.sql.
 *
 * Restricted to [a-z0-9_] on purpose: no Unicode means no look-alike names ("аlice" with a
 * Cyrillic а), and no characters that need escaping in URLs, logs or HTML.
 */
public final class Usernames {

    private static final Pattern VALID = Pattern.compile("[a-z0-9_]{3,32}");

    private Usernames() {
    }

    /** Lowercases (Locale.ROOT: a Turkish default locale would turn 'I' into a dotless 'ı'). */
    public static String normalize(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    public static boolean isValid(String username) {
        return username != null && VALID.matcher(username).matches();
    }
}
