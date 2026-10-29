package com.networknt.session.jdbc;

/** Construction-time serialized-data budgets; these do not measure decoded JVM heap. */
final class JdbcSessionLimits {
    static final String PREFIX = "light.session.jdbc.serialization.";
    final int attributes = positive("maxAttributes", 64);
    final int sessionBytes = positive("maxSessionBytes", 4 * 1024 * 1024);
    final int listingBytes = positive("maxListingBytes", 64 * 1024 * 1024);
    final int sessions = positive("maxListingSessions", 1000);
    final int rows = positive("maxListingRows", 65_000);

    private static int positive(String name, int fallback) {
        String value = System.getProperty(PREFIX + name);
        if (value == null) return fallback;
        try {
            int limit = Integer.parseInt(value.trim());
            if (limit > 0 && limit < Integer.MAX_VALUE) return limit;
        } catch (NumberFormatException ignored) { /* report the key, never its value */ }
        throw new IllegalArgumentException(PREFIX + name + " must be a positive integer below " + Integer.MAX_VALUE);
    }
}
