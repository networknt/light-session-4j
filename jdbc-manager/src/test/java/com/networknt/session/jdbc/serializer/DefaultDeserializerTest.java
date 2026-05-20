package com.networknt.session.jdbc.serializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

class DefaultDeserializerTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(DefaultDeserializer.ALLOWED_CLASSES_PROPERTY);
        System.clearProperty(DefaultDeserializer.ALLOWED_PACKAGES_PROPERTY);
    }

    @Test
    void deserializeAllowsDefaultSessionAttributeTypes() throws Exception {
        Map<String, Object> value = new HashMap<>();
        value.put("count", 1);
        value.put("roles", Arrays.asList("admin", "user"));

        Object deserialized = deserialize(serialize(value));

        Assertions.assertEquals(value, deserialized);
    }

    @Test
    void deserializeRejectsClassesOutsideAllowlist() throws Exception {
        byte[] serialized = serialize(new BlockedSessionAttribute("blocked"));

        SerializationFailedException exception = Assertions.assertThrows(SerializationFailedException.class,
                () -> deserialize(serialized));

        Assertions.assertTrue(exception.getMessage().contains("Rejected deserialization"));
    }

    @Test
    void deserializeAllowsConfiguredApplicationClasses() throws Exception {
        System.setProperty(DefaultDeserializer.ALLOWED_CLASSES_PROPERTY, BlockedSessionAttribute.class.getName());
        BlockedSessionAttribute value = new BlockedSessionAttribute("allowed");

        Object deserialized = deserialize(serialize(value));

        Assertions.assertEquals(value, deserialized);
    }

    private static Object deserialize(byte[] bytes) throws Exception {
        return new DefaultDeserializer().deserialize(new ByteArrayInputStream(bytes));
    }

    private static byte[] serialize(Object source) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        new DefaultSerializer().serialize(source, outputStream);
        return outputStream.toByteArray();
    }

    private static final class BlockedSessionAttribute implements Serializable {
        private final String value;

        private BlockedSessionAttribute(String value) {
            this.value = value;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof BlockedSessionAttribute that)) {
                return false;
            }
            return value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }
}
