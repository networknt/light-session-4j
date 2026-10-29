package com.networknt.session.jdbc;

import com.networknt.session.jdbc.serializer.DefaultDeserializer;
import com.networknt.session.jdbc.serializer.SerializedAttributeLimits;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.io.*;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class JdbcSessionRepositoryFailureTest {
    private JdbcDataSource database;
    private DataSource tracked;
    private JdbcSessionRepository repository;
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicInteger rollbacks = new AtomicInteger();

    @BeforeEach
    void createIsolatedDatabase() throws Exception {
        database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:a15_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = database.getConnection(); Statement statement = c.createStatement()) {
            statement.execute("CREATE TABLE light_session (session_id VARCHAR(100) PRIMARY KEY, creation_time BIGINT, last_access_time BIGINT, max_inactive_interval INT, expiry_time BIGINT, principal_name VARCHAR(100))");
            statement.execute("CREATE TABLE light_session_attributes (session_id VARCHAR(100), attribute_name VARCHAR(200), attribute_bytes BYTEA, PRIMARY KEY(session_id, attribute_name))");
        }
        tracked = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(database, method, args);
                    if (method.getName().equals("getConnection")) return track((Connection) result);
                    return result;
                });
        repository = new JdbcSessionRepository(tracked);
    }

    @AfterEach
    void closeDatabase() throws Exception {
        System.clearProperty(DefaultDeserializer.ALLOWED_CLASSES_PROPERTY);
        System.clearProperty(SerializedAttributeLimits.MAX_BYTES_PROPERTY);
        try (Connection c = database.getConnection(); Statement statement = c.createStatement()) { statement.execute("SHUTDOWN"); }
    }

    @Test
    void oversizedSetAttributeReportsFailureAndRetainsEditsForCorrection() throws Exception {
        var session = repository.createSession();
        session.setAttribute("safe", "persisted");
        writes.set(0);
        assertThrows(IllegalStateException.class,
                () -> session.setAttribute("large", "x".repeat(SerializedAttributeLimits.DEFAULT_MAX_BYTES)));
        assertEquals(0, writes.get(), "Preflight must precede all SQL writes");
        assertEquals(0, attributeCount(session.getId(), "large"));
        assertTrue(session.isChanged());
        assertTrue(session.getDelta().containsKey("large"));
        assertEquals("persisted", repository.findById(session.getId()).getAttribute("safe"));
        session.setAttribute("large", "corrected");
        assertFalse(session.isChanged());
        assertTrue(session.getDelta().isEmpty());
        assertEquals("corrected", repository.findById(session.getId()).getAttribute("large"));
    }

    @Test
    void failedCreateSessionReportsFailureAndDoesNotCreateAnyRow() throws Exception {
        ddl("ALTER TABLE light_session ADD CONSTRAINT reject_new CHECK(principal_name <> 'light-user')");
        assertThrows(IllegalStateException.class, repository::createSession);
        assertEquals(1, rollbacks.get());
        try (Connection c = database.getConnection(); Statement statement = c.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM light_session")) {
            rows.next(); assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void sqlFailureRollsBackEarlierAttributeWritesAndRetainsAllPendingEdits() throws Exception {
        var session = repository.createSession();
        session.setAttribute("stable", "original");
        int previousInterval = session.getMaxInactiveInterval();
        session.setMaxInactiveInterval(9999);
        ddl("ALTER TABLE light_session_attributes ADD CONSTRAINT reject_pending CHECK(attribute_name NOT IN ('a','b'))");
        assertThrows(IllegalStateException.class, () -> session.setAttribute("a", "first pending"));
        assertThrows(IllegalStateException.class, () -> session.setAttribute("b", "second pending"));
        ddl("ALTER TABLE light_session_attributes DROP CONSTRAINT reject_pending");
        ddl("ALTER TABLE light_session_attributes ADD CONSTRAINT reject_pending CHECK(attribute_name <> 'b')");
        writes.set(0); rollbacks.set(0);
        assertThrows(IllegalStateException.class, () -> repository.save(session));
        assertTrue(writes.get() >= 4, "Transaction must reach an attribute write before the later failure");
        assertEquals(1, rollbacks.get(), "Repository must explicitly roll back");
        assertEquals(0, attributeCount(session.getId(), "a"));
        assertEquals(0, attributeCount(session.getId(), "b"));
        assertEquals(previousInterval, storedInterval(session.getId()));
        assertTrue(session.isChanged());
        assertEquals(Set.of("a", "b"), session.getDelta().keySet());
        ddl("ALTER TABLE light_session_attributes DROP CONSTRAINT reject_pending");
        repository.save(session);
        assertTrue(session.getDelta().isEmpty());
        assertFalse(session.isChanged());
        var loaded = repository.findById(session.getId());
        assertEquals("first pending", loaded.getAttribute("a"));
        assertEquals("second pending", loaded.getAttribute("b"));
        assertEquals(9999, storedInterval(session.getId()));
    }

    @Test
    void getSessionsPreservesHealthySessionsAndRejectsWholeBadSessions() throws Exception {
        insertSession("good-one"); insertAttribute("good-one", "payload", serialize("healthy"));
        insertSession("good-empty");
        for (String id : List.of("oversized", "corrupt", "null-column")) {
            insertSession(id); insertAttribute(id, "a-safe", serialize("partial must not escape"));
        }
        insertAttribute("oversized", "z-bad", serialize("x".repeat(2 * SerializedAttributeLimits.DEFAULT_MAX_BYTES)));
        insertAttribute("corrupt", "z-bad", new byte[]{1, 2, 3});
        insertAttribute("null-column", "z-bad", null);
        writes.set(0);
        var sessions = repository.getSessions();
        assertEquals(Set.of("good-one", "good-empty"), sessions.keySet());
        assertEquals("healthy", sessions.get("good-one").getAttribute("payload"));
        assertNull(repository.findById("oversized"));
        assertNull(repository.findById("corrupt"));
        assertNull(repository.findById("null-column"));
        assertNotNull(repository.findById("good-one"));
        assertEquals(0, writes.get(), "Neither listing nor lookup may write hydrated values");
    }

    @Test
    void loadingDoesNotReserializeCustomValuesOrMarkTheSessionDirty() throws Exception {
        System.setProperty(DefaultDeserializer.ALLOWED_CLASSES_PROPERTY, CountingValue.class.getName());
        repository = new JdbcSessionRepository(tracked);
        insertSession("read-only"); insertAttribute("read-only", "payload", serialize(new CountingValue()));
        CountingValue.serializations = 0; writes.set(0);
        var loaded = repository.findById("read-only");
        assertInstanceOf(CountingValue.class, loaded.getAttribute("payload"));
        assertFalse(loaded.isChanged());
        assertTrue(loaded.getDelta().isEmpty());
        assertNotNull(repository.getSessions().get("read-only"));
        assertEquals(0, CountingValue.serializations);
        assertEquals(0, writes.get());
    }

    private Connection track(Connection connection) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("rollback")) rollbacks.incrementAndGet();
                    Object result = invoke(connection, method, args);
                    if (method.getName().equals("prepareStatement")) {
                        PreparedStatement statement = (PreparedStatement) result;
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (p, m, a) -> {
                                    if (m.getName().equals("executeUpdate") || m.getName().equals("executeBatch")) writes.incrementAndGet();
                                    return invoke(statement, m, a);
                                });
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    private void ddl(String sql) throws SQLException {
        try (Connection c = database.getConnection(); Statement statement = c.createStatement()) { statement.execute(sql); }
    }

    private void insertSession(String id) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement statement = c.prepareStatement("INSERT INTO light_session VALUES(?,?,?,?,?,?)")) {
            long now = System.currentTimeMillis();
            statement.setString(1, id); statement.setLong(2, now); statement.setLong(3, now);
            statement.setInt(4, 3600); statement.setLong(5, now + 3_600_000); statement.setString(6, "synthetic");
            statement.executeUpdate();
        }
    }

    private void insertAttribute(String id, String name, byte[] bytes) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement statement = c.prepareStatement("INSERT INTO light_session_attributes VALUES(?,?,?)")) {
            statement.setString(1, id); statement.setString(2, name); statement.setBytes(3, bytes); statement.executeUpdate();
        }
    }

    private int attributeCount(String id, String name) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement statement = c.prepareStatement("SELECT COUNT(*) FROM light_session_attributes WHERE session_id=? AND attribute_name=?")) {
            statement.setString(1, id); statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private int storedInterval(String id) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement statement = c.prepareStatement("SELECT max_inactive_interval FROM light_session WHERE session_id=?")) {
            statement.setString(1, id);
            try (ResultSet rows = statement.executeQuery()) { rows.next(); return rows.getInt(1); }
        }
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream writer = new ObjectOutputStream(bytes)) { writer.writeObject(value); }
        return bytes.toByteArray();
    }

    private static final class CountingValue implements Serializable {
        private static final long serialVersionUID = 1L;
        static int serializations;
        private void writeObject(ObjectOutputStream writer) throws IOException { serializations++; writer.defaultWriteObject(); }
    }
}
