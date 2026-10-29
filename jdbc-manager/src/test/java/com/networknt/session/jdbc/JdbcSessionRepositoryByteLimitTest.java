package com.networknt.session.jdbc;

import com.networknt.session.jdbc.serializer.DefaultDeserializer;
import com.networknt.session.jdbc.serializer.SerializationFailedException;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class JdbcSessionRepositoryByteLimitTest {
    private static final int LIMIT = DefaultDeserializer.MAX_SERIALIZED_BYTES;

    private JdbcSessionRepository repository() {
        return new JdbcSessionRepository(null) {
            // extractData currently calls setAttribute/save even for loaded data.
            // Avoid writes: this fixture exercises only the actual result extraction path.
            @Override public void save(JdbcSession session) { }
        };
    }

    @Test
    void loadsAColumnUsingTheBinaryStreamAndClosesIt() throws Exception {
        TrackingInput input = new TrackingInput(serialize("safe"));
        var sessions = repository().extractData(row(input));
        assertEquals(1, sessions.size());
        assertEquals("safe", sessions.getFirst().getAttribute("payload"));
        assertTrue(input.closed);
    }

    @Test
    void acceptsAnExactlyLimitSizedSerializedColumn() throws Exception {
        String value = "x".repeat(LIMIT - 13);
        byte[] bytes = serialize(value);
        assertEquals(LIMIT, bytes.length);
        TrackingInput input = new TrackingInput(bytes);
        assertEquals(value, repository().extractData(row(input)).getFirst().getAttribute("payload"));
        assertTrue(input.closed);
    }

    @Test
    void stopsReadingOversizedColumnsAtLimitPlusOneAndClosesThem() throws Exception {
        class LargeColumn extends InputStream {
            int consumed; boolean closed;
            @Override public int read() { consumed++; return 0; }
            @Override public int read(byte[] bytes, int offset, int length) {
                Arrays.fill(bytes, offset, offset + length, (byte) 0);
                consumed += length; return length;
            }
            @Override public void close() { closed = true; }
        }
        LargeColumn column = new LargeColumn();
        SQLException error = assertThrows(SQLException.class, () -> repository().extractData(row(column)));
        assertInstanceOf(SerializationFailedException.class, error.getCause());
        assertEquals(LIMIT + 1, column.consumed);
        assertTrue(column.closed);
    }

    @Test
    void closesTruncatedOrCorruptColumnsOnFailure() throws Exception {
        for (byte[] bytes : new byte[][]{new byte[0], new byte[]{1, 2, 3}, Arrays.copyOf(serialize("safe"), 6)}) {
            TrackingInput input = new TrackingInput(bytes);
            assertThrows(SQLException.class, () -> repository().extractData(row(input)));
            assertTrue(input.closed);
        }
    }

    @Test
    void closesColumnWhenTheDriverStreamThrows() {
        class BrokenInput extends InputStream {
            boolean closed;
            @Override public int read() throws IOException { throw new IOException("synthetic read failure"); }
            @Override public void close() { closed = true; }
        }
        BrokenInput input = new BrokenInput();
        assertThrows(SQLException.class, () -> repository().extractData(row(input)));
        assertTrue(input.closed);
    }

    @Test
    void nullColumnFailsWithSqlExceptionInsteadOfUncheckedNullPointer() {
        assertThrows(SQLException.class, () -> repository().extractData(row(null)));
    }

    private ResultSet row(InputStream input) {
        AtomicBoolean first = new AtomicBoolean(true);
        return (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> first.getAndSet(false);
                    case "getString" -> "SESSION_ID".equals(args[0]) ? "synthetic-session" : "payload";
                    case "getInt" -> 1800;
                    case "getLong" -> 1L;
                    case "getBinaryStream" -> input;
                    case "getBytes" -> throw new AssertionError("Unbounded getBytes must not be used");
                    default -> throw new AssertionError("Unexpected ResultSet method: " + method.getName());
                });
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream writer = new ObjectOutputStream(bytes)) { writer.writeObject(value); }
        return bytes.toByteArray();
    }

    private static final class TrackingInput extends ByteArrayInputStream {
        boolean closed;
        TrackingInput(byte[] bytes) { super(bytes); }
        @Override public void close() { closed = true; }
    }
}
