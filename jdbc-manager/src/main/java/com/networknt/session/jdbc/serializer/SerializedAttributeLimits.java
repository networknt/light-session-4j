package com.networknt.session.jdbc.serializer;

/** Shared read/write byte policy, captured when a converter or deserializer is created. */
public final class SerializedAttributeLimits {
    public static final int DEFAULT_MAX_BYTES = 1_048_576;
    public static final String PREFIX = "light.session.jdbc.serialization.";
    public static final String MAX_BYTES_PROPERTY = PREFIX + "maxAttributeBytes";

    private SerializedAttributeLimits() { }

    static int configuredMaxBytes() {
        return configuredPositive("maxAttributeBytes", DEFAULT_MAX_BYTES);
    }

    public static int configuredPositive(String name, int fallback) {
        String key = PREFIX + name;
        String value = System.getProperty(key);
        if (value == null) return fallback;
        try {
            int limit = Integer.parseInt(value.trim());
            if (limit > 0 && limit < Integer.MAX_VALUE) return limit;
        } catch (NumberFormatException ignored) {
            // Report the property name, never arbitrary configuration contents.
        }
        throw new IllegalArgumentException(key + " must be a positive integer below " + Integer.MAX_VALUE);
    }
}
