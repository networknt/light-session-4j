package com.networknt.session.jdbc;

import com.networknt.session.jdbc.serializer.ValueBytesConverter;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Isolated;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Isolated
class JdbcSessionAggregateLimitTest {
    private JdbcDataSource database;
    private final Map<String, String> original = new HashMap<>();
    @BeforeEach void setup() throws Exception {
        database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection c = database.getConnection(); Statement stmt = c.createStatement()) {
            stmt.execute("CREATE TABLE light_session (session_id VARCHAR(100) PRIMARY KEY, creation_time BIGINT, last_access_time BIGINT, max_inactive_interval INT, expiry_time BIGINT, principal_name VARCHAR(100))");
            stmt.execute("CREATE TABLE light_session_attributes (session_id VARCHAR(100), attribute_name VARCHAR(200), attribute_bytes BYTEA, PRIMARY KEY(session_id, attribute_name))");
        }
    }
    @AfterEach void cleanup() throws Exception {
        original.forEach((key, value) -> { if (value == null) System.clearProperty(key); else System.setProperty(key, value); });
        try (Connection c = database.getConnection(); Statement stmt = c.createStatement()) { stmt.execute("SHUTDOWN"); }
    }
    private void limit(String key, int value) {
        key = JdbcSessionLimits.PREFIX + key;
        if (!original.containsKey(key)) original.put(key, System.getProperty(key));
        System.setProperty(key, Integer.toString(value));
    }
    private JdbcSessionRepository repository() { return new JdbcSessionRepository(database); }
    private void attribute(String id, String name, String value) throws Exception {
        byte[] bytes = new ValueBytesConverter().serializer(value);
        try (Connection c = database.getConnection(); PreparedStatement stmt = c.prepareStatement("INSERT INTO light_session_attributes VALUES(?,?,?)")) {
            stmt.setString(1, id); stmt.setString(2, name); stmt.setBytes(3, bytes); stmt.executeUpdate();
        }
    }
    @Test void writeCountRejectsBeforeSqlAndRetainsPendingChange() {
        limit("maxAttributes", 2);
        var repo = repository(); var session = repo.createSession();
        session.setAttribute("a", "one"); session.setAttribute("b", "two");
        assertThrows(IllegalStateException.class, () -> session.setAttribute("c", "three"));
        assertTrue(session.isChanged());
        assertNull(repo.findById(session.getId()).getAttribute("c"));
        session.removeAttribute("c");
        assertFalse(session.isChanged());
        assertEquals(2, repo.findById(session.getId()).getAttributeNames().size());
    }
    @Test void exactAggregateByteBoundaryAndUnchangedAttributesAreCounted() throws Exception {
        String value = "x".repeat(100);
        int bytes = new ValueBytesConverter().serializer(value).length;
        limit("maxSessionBytes", bytes * 2);
        var repo = repository(); var session = repo.createSession();
        session.setAttribute("a", value); session.setAttribute("b", value);
        assertEquals(2, repo.findById(session.getId()).getAttributeNames().size());
        assertThrows(IllegalStateException.class, () -> session.setAttribute("c", "extra"));
        assertNull(repo.findById(session.getId()).getAttribute("c"));
    }
    @Test void oversizedReadDropsOnlyThatSession() throws Exception {
        limit("maxSessionBytes", 150);
        var repo = repository(); var bad = repo.createSession(); var good = repo.createSession();
        attribute(bad.getId(), "a", "x".repeat(100)); attribute(bad.getId(), "b", "x".repeat(100));
        good.setAttribute("safe", "healthy");
        assertNull(repo.findById(bad.getId()));
        assertEquals(Set.of(good.getId()), repo.getSessions().keySet());
    }
    @Test void excessReadAttributeCountDropsOnlyThatSession() throws Exception {
        limit("maxAttributes", 2);
        var repo = repository(); var bad = repo.createSession(); var good = repo.createSession();
        attribute(bad.getId(), "a", "one"); attribute(bad.getId(), "b", "two"); attribute(bad.getId(), "c", "three");
        assertEquals(Set.of(good.getId()), repo.getSessions().keySet());
    }
    @Test void listingByteLimitFailsExplicitlyInsteadOfReturningEmptyOrPartialMap() throws Exception {
        limit("maxListingBytes", 200);
        var repo = repository();
        repo.createSession().setAttribute("a", "x".repeat(100));
        repo.createSession().setAttribute("a", "x".repeat(100));
        assertThrows(IllegalStateException.class, repo::getSessions);
    }
    @Test void listingSessionCountAndRowsFailExplicitly() {
        limit("maxListingSessions", 1);
        var repo = repository(); repo.createSession(); repo.createSession();
        assertThrows(IllegalStateException.class, repo::getSessions);
        limit("maxListingSessions", 10); limit("maxListingRows", 1);
        assertThrows(IllegalStateException.class, repository()::getSessions);
    }
    @Test void aggregateReadStopsAtBudgetPlusOneBeforeDeserialization() throws Exception {
        limit("maxSessionBytes", 150);
        byte[] serialized = new ValueBytesConverter().serializer("x".repeat(10000));
        var consumed = new java.util.concurrent.atomic.AtomicInteger();
        var stream = new java.io.ByteArrayInputStream(serialized) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                int n = super.read(bytes, offset, length); if (n > 0) consumed.addAndGet(n); return n;
            }
            @Override public synchronized int read() {
                int value = super.read(); if (value >= 0) consumed.incrementAndGet(); return value;
            }
        };
        var row = new java.util.concurrent.atomic.AtomicBoolean();
        ResultSet result = (ResultSet) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ResultSet.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> !row.getAndSet(true);
                    case "getString" -> args[0].equals("SESSION_ID") ? "oversized" : "attribute";
                    case "getInt" -> 1800;
                    case "getLong" -> System.currentTimeMillis();
                    case "getBinaryStream" -> stream;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        assertTrue(repository().extractData(result).isEmpty());
        assertEquals(151, consumed.get());
    }

    @Test void invalidBudgetFailsConstruction() {
        limit("maxSessionBytes", 0);
        assertThrows(IllegalArgumentException.class, this::repository);
    }
}
