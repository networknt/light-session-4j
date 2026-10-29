package com.networknt.session.jdbc.serializer;

/** Shared read/write byte policy, captured when a converter or deserializer is created. */
public final class SerializedAttributeLimits {
    public static final int DEFAULT_MAX_BYTES = 1_048_576;
    public static final String MAX_BYTES_PROPERTY = "light.session.jdbc.serialization.maxAttributeBytes";

    private SerializedAttributeLimits() { }

    static int configuredMaxBytes() {
        String value = System.getProperty(MAX_BYTES_PROPERTY);
        if (value == null) return DEFAULT_MAX_BYTES;
        try {
            int limit = Integer.parseInt(value.trim());
            if (limit > 0 && limit < Integer.MAX_VALUE) return limit;
        } catch (NumberFormatException ignored) {
            // Report the property name, never arbitrary configuration contents.
        }
        throw new IllegalArgumentException(MAX_BYTES_PROPERTY + " must be a positive integer below " + Integer.MAX_VALUE);
    }
}
