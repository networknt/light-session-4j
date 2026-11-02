package com.networknt.session.jdbc;

/** Construction-time serialized-data budgets; these do not measure decoded JVM heap. */
final class JdbcSessionLimits {
    static final String PREFIX = com.networknt.session.jdbc.serializer.SerializedAttributeLimits.PREFIX;
    final int attributes = positive("maxAttributes", 64);
    final int sessionBytes = positive("maxSessionBytes", 4 * 1024 * 1024);
    final int listingBytes = positive("maxListingBytes", 64 * 1024 * 1024);
    final int sessions = positive("maxListingSessions", 1000);
    final int rows = positive("maxListingRows", 65_000);

    private static int positive(String name, int fallback) {
        return com.networknt.session.jdbc.serializer.SerializedAttributeLimits.configuredPositive(name, fallback);
    }
}
