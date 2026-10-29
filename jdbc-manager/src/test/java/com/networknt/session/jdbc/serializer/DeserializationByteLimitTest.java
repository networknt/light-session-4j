package com.networknt.session.jdbc.serializer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DeserializationByteLimitTest {
    private static final int LIMIT = SerializedAttributeLimits.DEFAULT_MAX_BYTES;

    @AfterEach
    void resetPolicy() {
        System.clearProperty(DefaultDeserializer.ALLOWED_CLASSES_PROPERTY);
        System.clearProperty(DefaultDeserializer.ALLOWED_PACKAGES_PROPERTY);
        Hook.executions = 0;
        System.clearProperty(SerializedAttributeLimits.MAX_BYTES_PROPERTY);
    }

    @Test
    void concreteStringsAndStringArraysCannotBypassTheByteLimit() throws Exception {
        String large = "x".repeat(2 * LIMIT);
        Map<String, Object> nested = new HashMap<>();
        nested.put("value", large);
        for (Object value : new Object[]{large, new String[]{large, "y".repeat(2 * LIMIT)}, nested}) {
            byte[] bytes = serialize(value);
            assertTrue(bytes.length > LIMIT);
            assertThrows(SerializationFailedException.class,
                    () -> new DefaultDeserializer().deserialize(new ByteArrayInputStream(bytes)));
            assertThrows(SerializationFailedException.class,
                    () -> new ValueBytesConverter().deserialize(bytes));
            assertThrows(SerializationFailedException.class,
                    () -> new ValueBytesConverter().deserializeStream(new ByteArrayInputStream(bytes)));
        }
    }

    @Test
    void exactSerializedLimitRoundTripsButOneExtraByteIsRejected() throws Exception {
        // Long ASCII strings have a 4-byte stream header, 1-byte tag and 8-byte length.
        String value = "x".repeat(LIMIT - 13);
        byte[] bytes = serialize(value);
        assertEquals(LIMIT, bytes.length);
        ValueBytesConverter converter = new ValueBytesConverter();
        assertEquals(value, new DefaultDeserializer().deserialize(new ByteArrayInputStream(bytes)));
        assertEquals(value, converter.deserialize(bytes));
        assertArrayEquals(bytes, converter.serializer(value));
        assertEquals(LIMIT + 1, serialize(value + "x").length);
        assertThrows(SerializationFailedException.class, () -> converter.serializer(value + "x"));
        assertThrows(SerializationFailedException.class, () -> converter.deserialize(serialize(value + "x")));
        // Even ignored trailing bytes count toward the complete attribute limit.
        byte[] trailing = Arrays.copyOf(bytes, LIMIT + 1);
        assertThrows(SerializationFailedException.class,
                () -> new DefaultDeserializer().deserialize(new ByteArrayInputStream(trailing)));
    }

    @Test
    void oversizedPayloadIsRejectedBeforeEvenAnAllowedReadObjectHook() throws Exception {
        System.setProperty(DefaultDeserializer.ALLOWED_CLASSES_PROPERTY, Hook.class.getName());
        DefaultDeserializer reader = new DefaultDeserializer();
        assertThrows(SerializationFailedException.class,
                () -> reader.deserialize(new ByteArrayInputStream(serialize(new Hook("x".repeat(2 * LIMIT))))));
        assertEquals(0, Hook.executions);
        reader.deserialize(new ByteArrayInputStream(serialize(new Hook("small"))));
        assertEquals(1, Hook.executions, "Control must demonstrate that this class was permitted");
    }

    @Test
    void unlistedRootAndNestedHooksRemainBlocked() throws Exception {
        for (Object value : new Object[]{new Hook("root"), new Object[]{new Hook("nested")}}) {
            SerializationFailedException error = assertThrows(SerializationFailedException.class,
                    () -> new DefaultDeserializer().deserialize(new ByteArrayInputStream(serialize(value))));
            assertInstanceOf(InvalidClassException.class, error.getCause());
        }
        assertEquals(0, Hook.executions);
    }

    @Test
    void arrayDepthAndReferenceFiltersRemainActiveBelowTheByteLimit() throws Exception {
        Object deep = "leaf";
        for (int i = 0; i < 25; i++) {
            ArrayList<Object> next = new ArrayList<>(); next.add(deep); deep = next;
        }
        ArrayList<Object> references = new ArrayList<>();
        for (int i = 0; i < 10050; i++) references.add(Integer.valueOf(1));
        for (Object value : new Object[]{new byte[100001], deep, references}) {
            byte[] bytes = serialize(value);
            assertTrue(bytes.length < LIMIT);
            SerializationFailedException error = assertThrows(SerializationFailedException.class,
                    () -> new DefaultDeserializer().deserialize(new ByteArrayInputStream(bytes)));
            assertInstanceOf(InvalidClassException.class, error.getCause());
        }
    }

    @Test
    void readsOnlyLimitPlusOneBytesAndLeavesTheCallerStreamOpen() throws Exception {
        class EndlessInput extends InputStream {
            int consumed; boolean closed;
            @Override public int read() { consumed++; return 0; }
            @Override public int read(byte[] bytes, int offset, int length) {
                Arrays.fill(bytes, offset, offset + length, (byte) 0);
                consumed += length; return length;
            }
            @Override public void close() { closed = true; }
        }
        EndlessInput source = new EndlessInput();
        assertThrows(SerializationFailedException.class, () -> new ValueBytesConverter().deserializeStream(source));
        assertEquals(LIMIT + 1, source.consumed);
        assertFalse(source.closed, "The deserializer does not own its supplied stream");
    }

    @Test
    void successfulDeserializationAlsoLeavesTheCallerStreamOpen() throws Exception {
        class OwnedInput extends ByteArrayInputStream {
            boolean closed;
            OwnedInput(byte[] bytes) { super(bytes); }
            @Override public void close() { closed = true; }
        }
        OwnedInput input = new OwnedInput(serialize("safe"));
        assertEquals("safe", new DefaultDeserializer().deserialize(input));
        assertFalse(input.closed);
    }

    @Test
    void missingOrCorruptPayloadUsesCheckedFailure() {
        ValueBytesConverter converter = new ValueBytesConverter();
        assertThrows(SerializationFailedException.class, () -> converter.deserialize(null));
        assertThrows(SerializationFailedException.class, () -> converter.deserializeStream(null));
        assertThrows(SerializationFailedException.class, () -> converter.deserialize(new byte[]{1, 2, 3}));
    }

    @Test
    void configuredLimitAppliesToNewReadersAndWritersTogether() throws Exception {
        System.setProperty(SerializedAttributeLimits.MAX_BYTES_PROPERTY, "3145728");
        String value = "x".repeat(2 * LIMIT);
        ValueBytesConverter converter = new ValueBytesConverter();
        byte[] bytes = converter.serializer(value);
        assertEquals(value, converter.deserialize(bytes));
        assertEquals(value, new DefaultDeserializer().deserialize(new ByteArrayInputStream(bytes)));
        // A previously created converter keeps its policy, avoiding read/write drift.
        System.clearProperty(SerializedAttributeLimits.MAX_BYTES_PROPERTY);
        assertEquals(value, converter.deserialize(bytes));
        assertThrows(SerializationFailedException.class, () -> new ValueBytesConverter().deserialize(bytes));
    }

    @Test
    void invalidLimitsFailAtConstructionInsteadOfDisablingTheBound() {
        for (String value : new String[]{"", "0", "-1", "invalid", "2147483647", "2147483648"}) {
            System.setProperty(SerializedAttributeLimits.MAX_BYTES_PROPERTY, value);
            assertThrows(IllegalArgumentException.class, DefaultDeserializer::new);
            assertThrows(IllegalArgumentException.class, ValueBytesConverter::new);
        }
    }

    @Test
    void nullAndOversizedInputsHaveDistinctMessages() throws Exception {
        ValueBytesConverter converter = new ValueBytesConverter();
        assertTrue(assertThrows(SerializationFailedException.class, () -> converter.deserialize(null))
                .getMessage().contains("must not be null"));
        assertTrue(assertThrows(SerializationFailedException.class, () -> converter.deserialize(new byte[LIMIT + 1]))
                .getMessage().contains("at most"));
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream writer = new ObjectOutputStream(bytes)) { writer.writeObject(value); }
        return bytes.toByteArray();
    }

    private static final class Hook implements Serializable {
        private static final long serialVersionUID = 1L;
        static int executions;
        private final String value;
        Hook(String value) { this.value = value; }
        private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
            executions++; input.defaultReadObject();
        }
    }
}
