package com.networknt.session.jdbc;

import static com.networknt.session.jdbc.serializer.SerializedAttributeLimits.configuredPositive;
import static com.networknt.session.jdbc.serializer.SerializedAttributeLimits.configuredPositiveProperty;

/** Construction-time serialized-data budgets; these do not measure decoded JVM heap. */
final class JdbcSessionLimits {
    final int cleanupBatchSize = configuredPositiveProperty("light.session.jdbc.cleanupBatchSize", 128);
    final int attributes = configuredPositive("maxAttributes", 64);
    final int sessionBytes = configuredPositive("maxSessionBytes", 4 * 1024 * 1024);
    final int listingBytes = configuredPositive("maxListingBytes", 64 * 1024 * 1024);
    final int sessions = configuredPositive("maxListingSessions", 1000);
    final int rows = configuredPositive("maxListingRows", 65_000);
}
