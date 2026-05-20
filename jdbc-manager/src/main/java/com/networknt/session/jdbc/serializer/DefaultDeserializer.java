package com.networknt.session.jdbc.serializer;

import java.io.IOException;
import java.io.InvalidClassException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;



/**
 * A default {@link Deserializer} implementation that reads an input stream
 * using Java serialization.
 */
public class DefaultDeserializer implements Deserializer<Object> {
    public static final String ALLOWED_CLASSES_PROPERTY = "light.session.jdbc.deserialization.allowedClasses";
    public static final String ALLOWED_PACKAGES_PROPERTY = "light.session.jdbc.deserialization.allowedPackages";
    private static final long MAX_ARRAY_LENGTH = 100_000;
    private static final long MAX_DEPTH = 20;
    private static final long MAX_REFERENCES = 10_000;
    private static final long MAX_STREAM_BYTES = 1_048_576;
    private static final Set<String> DEFAULT_ALLOWED_CLASSES = Set.of(
            "java.lang.String",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Character",
            "java.lang.Number",
            "java.math.BigDecimal",
            "java.math.BigInteger",
            "java.sql.Date",
            "java.sql.Time",
            "java.sql.Timestamp",
            "java.time.Duration",
            "java.time.Instant",
            "java.time.LocalDate",
            "java.time.LocalDateTime",
            "java.time.LocalTime",
            "java.time.MonthDay",
            "java.time.OffsetDateTime",
            "java.time.OffsetTime",
            "java.time.Period",
            "java.time.Year",
            "java.time.YearMonth",
            "java.time.ZonedDateTime",
            "java.util.AbstractCollection",
            "java.util.AbstractList",
            "java.util.AbstractMap",
            "java.util.AbstractSet",
            "java.util.ArrayList",
            "java.util.Arrays$ArrayList",
            "java.util.Collections$EmptyList",
            "java.util.Collections$EmptyMap",
            "java.util.Collections$EmptySet",
            "java.util.Collections$SingletonList",
            "java.util.Collections$SingletonMap",
            "java.util.Collections$SingletonSet",
            "java.util.Date",
            "java.util.HashMap",
            "java.util.HashSet",
            "java.util.LinkedHashMap",
            "java.util.LinkedHashSet",
            "java.util.LinkedList",
            "java.util.Locale",
            "java.util.Map$Entry",
            "java.util.TreeMap",
            "java.util.TreeSet",
            "java.util.UUID"
    );

    private final Set<String> allowedClasses;
    private final Set<String> allowedPackagePrefixes;

    /**
     * Create a {@code DefaultDeserializer} with an {@link ObjectInputFilter}
     * that restricts the classes accepted from serialized session attributes.
     */
    public DefaultDeserializer() {
        this.allowedClasses = configuredSet(ALLOWED_CLASSES_PROPERTY, DEFAULT_ALLOWED_CLASSES);
        this.allowedPackagePrefixes = configuredPackagePrefixes(ALLOWED_PACKAGES_PROPERTY);
    }


    /**
     * Read from the supplied {@code InputStream} and deserialize the contents
     * into an object.
     * @see ObjectInputStream#readObject()
     */
    @Override
    @SuppressWarnings("resource")
    public Object deserialize(InputStream inputStream) throws IOException, SerializationFailedException {
        ObjectInputStream objectInputStream = new ObjectInputStream(inputStream);
        objectInputStream.setObjectInputFilter(this::validateObjectInput);
        try {
            return objectInputStream.readObject();
        }
        catch (InvalidClassException ex) {
            throw new SerializationFailedException("Rejected deserialization of disallowed object type", ex);
        }
        catch (ClassNotFoundException ex) {
            throw new SerializationFailedException("Failed to deserialize object type", ex);
        }
    }

    private ObjectInputFilter.Status validateObjectInput(ObjectInputFilter.FilterInfo filterInfo) {
        if (exceedsLimit(filterInfo.arrayLength(), MAX_ARRAY_LENGTH)
                || exceedsLimit(filterInfo.depth(), MAX_DEPTH)
                || exceedsLimit(filterInfo.references(), MAX_REFERENCES)
                || exceedsLimit(filterInfo.streamBytes(), MAX_STREAM_BYTES)) {
            return ObjectInputFilter.Status.REJECTED;
        }

        Class<?> serialClass = filterInfo.serialClass();
        if (serialClass == null) {
            return ObjectInputFilter.Status.UNDECIDED;
        }

        return isAllowed(serialClass) ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
    }

    private boolean isAllowed(Class<?> serialClass) {
        Class<?> candidate = serialClass;
        while (candidate.isArray()) {
            candidate = candidate.getComponentType();
        }
        if (candidate.isPrimitive() || Object.class.equals(candidate)) {
            return true;
        }

        String className = candidate.getName();
        if (allowedClasses.contains(className)) {
            return true;
        }
        for (String packagePrefix : allowedPackagePrefixes) {
            if (className.startsWith(packagePrefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean exceedsLimit(long value, long limit) {
        return value >= 0 && value > limit;
    }

    private static Set<String> configuredSet(String propertyName, Set<String> defaults) {
        Set<String> configured = new HashSet<>(defaults);
        configured.addAll(splitProperty(System.getProperty(propertyName)));
        return configured;
    }

    private static Set<String> configuredPackagePrefixes(String propertyName) {
        return splitProperty(System.getProperty(propertyName)).stream()
                .map(DefaultDeserializer::normalizePackagePrefix)
                .collect(Collectors.toSet());
    }

    private static Set<String> splitProperty(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toSet());
    }

    private static String normalizePackagePrefix(String packagePrefix) {
        return packagePrefix.endsWith(".") ? packagePrefix : packagePrefix + ".";
    }

}
