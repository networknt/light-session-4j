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
    private final java.util.concurrent.atomic.AtomicInteger connections = new java.util.concurrent.atomic.AtomicInteger();
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
    private JdbcSessionRepository repository() {
        javax.sql.DataSource tracked = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) connections.incrementAndGet();
                    try { return method.invoke(database, args); }
                    catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
        return new JdbcSessionRepository(tracked);
    }
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
        connections.set(0);
        assertThrows(IllegalStateException.class, () -> session.setAttribute("c", "three"));
        assertEquals(0, connections.get(), "Count preflight must not acquire a connection or execute SQL");
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

    @Test void lookupIgnoresAllListingBudgets() throws Exception {
        limit("maxListingBytes", 1); limit("maxListingRows", 1); limit("maxListingSessions", 1);
        var repo = repository(); var session = repo.createSession();
        session.setAttribute("a", "one"); session.setAttribute("b", "two");
        assertEquals(2, repo.findById(session.getId()).getAttributeNames().size());
        assertThrows(IllegalStateException.class, repo::getSessions);
    }

    @Test void readCountAndSessionBytesAcceptExactBoundaryAndRejectNextByteOrAttribute() throws Exception {
        String value = "x".repeat(100);
        int size = new ValueBytesConverter().serializer(value).length;
        var repo = repository(); var session = repo.createSession();
        attribute(session.getId(), "a", value); attribute(session.getId(), "b", value);
        limit("maxAttributes", 2); limit("maxSessionBytes", size * 2);
        assertEquals(2, repository().findById(session.getId()).getAttributeNames().size());
        assertEquals(1, repository().getSessions().size());
        limit("maxSessionBytes", size * 2 - 1);
        assertNull(repository().findById(session.getId()));
        limit("maxSessionBytes", size * 2); limit("maxAttributes", 1);
        assertNull(repository().findById(session.getId()));
    }

    @Test void listingAcceptsExactBytesRowsAndSessionsThenRejectsOneLess() throws Exception {
        String value = "x".repeat(100);
        int size = new ValueBytesConverter().serializer(value).length;
        var repo = repository();
        repo.createSession().setAttribute("a", value); repo.createSession().setAttribute("a", value);
        limit("maxListingBytes", size * 2); limit("maxListingRows", 2); limit("maxListingSessions", 2);
        assertEquals(2, repository().getSessions().size());
        limit("maxListingBytes", size * 2 - 1);
        assertThrows(IllegalStateException.class, repository()::getSessions);
        limit("maxListingBytes", size * 2); limit("maxListingRows", 1);
        assertThrows(IllegalStateException.class, repository()::getSessions);
        limit("maxListingRows", 2); limit("maxListingSessions", 1);
        assertThrows(IllegalStateException.class, repository()::getSessions);
    }

    @Test void unchangedMutatedValueDoesNotBlockOrReserializeDuringOtherWrites() throws Exception {
        var repo = repository(); var session = repo.createSession();
        var value = new CountingValue();
        session.setAttribute("unchanged", value);
        assertEquals(1, value.writes);
        value.member = new Object(); // No longer serializable, but the stored bytes remain valid.
        for (int i = 0; i < 10; i++) session.setAttribute("other" + i, "value");
        assertEquals(1, value.writes);
        assertEquals(11, session.getAttributeNames().size());
    }

    private static final class CountingValue implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        transient int writes;
        Object member = "initial";
        private void writeObject(java.io.ObjectOutputStream stream) throws java.io.IOException {
            writes++;
            stream.defaultWriteObject();
        }
    }

    @Test void concurrentStaleWritersCannotJointlyExceedCountOrByteBudget() throws Exception {
        for (boolean countLimit : new boolean[]{true, false}) {
            limit("maxAttributes", countLimit ? 1 : 64);
            int size = new ValueBytesConverter().serializer("value").length;
            limit("maxSessionBytes", countLimit ? 4096 : size);
            var repo = repository(); var originalSession = repo.createSession();
            var left = repo.findById(originalSession.getId());
            var right = repo.findById(originalSession.getId());
            var start = new java.util.concurrent.CountDownLatch(1);
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var first = pool.submit(() -> attemptWrite(start, left, "left"));
                var second = pool.submit(() -> attemptWrite(start, right, "right"));
                start.countDown();
                assertNotEquals(first.get(10, java.util.concurrent.TimeUnit.SECONDS),
                        second.get(10, java.util.concurrent.TimeUnit.SECONDS), "Exactly one writer may commit");
                var loaded = repo.findById(originalSession.getId());
                assertNotNull(loaded);
                assertEquals(1, loaded.getAttributeNames().size());
                var loser = left.isChanged() ? left : right;
                assertTrue(loser.isChanged());
                assertFalse(loser.getDelta().isEmpty());
            } finally { pool.shutdownNow(); }
        }
    }

    private boolean attemptWrite(java.util.concurrent.CountDownLatch start, JdbcSessionRepository.JdbcSession session, String name) throws Exception {
        start.await();
        try { session.setAttribute(name, "value"); return true; }
        catch (IllegalStateException e) {
            assertTrue(e.getMessage().startsWith("Stored JDBC session exceeds"), e.toString());
            return false;
        }
    }

    @Test void rejectionLogsIdentifyBudgetAndCountersWithoutRawSessionId() throws Exception {
        limit("maxAttributes", 1);
        var repo = repository(); var session = repo.createSession();
        attribute(session.getId(), "a", "one"); attribute(session.getId(), "b", "two");
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JdbcSessionRepository.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start(); logger.addAppender(events);
        try {
            assertNull(repo.findById(session.getId()));
            String message = events.list.getLast().getFormattedMessage();
            assertTrue(message.contains("reason=ATTRIBUTE_COUNT"));
            assertTrue(message.contains("attributes=2"));
            assertTrue(message.contains("serializedBytes="));
            assertTrue(message.contains("idHash="));
            assertFalse(message.contains(session.getId()));
        } finally { logger.detachAppender(events); events.stop(); }
    }

    @Test void effectiveByteBudgetReportsTypedOutcome() throws Exception {
        var converter = new ValueBytesConverter();
        byte[] bytes = converter.serializer("value");
        for (var reason : List.of(
                com.networknt.session.jdbc.serializer.AttributeReadException.Reason.SESSION_BYTES,
                com.networknt.session.jdbc.serializer.AttributeReadException.Reason.LISTING_BYTES)) {
            var rejected = assertThrows(com.networknt.session.jdbc.serializer.AttributeReadException.class,
                    () -> converter.deserializeStream(new java.io.ByteArrayInputStream(bytes), bytes.length - 1, reason));
            assertEquals(reason, rejected.reason);
            assertEquals(bytes.length, rejected.bytesRead);
            assertEquals("value", converter.deserializeStream(new java.io.ByteArrayInputStream(bytes), bytes.length, reason).value());
        }
    }

    @Test void invalidBudgetFailsConstruction() {
        limit("maxSessionBytes", 0);
        assertThrows(IllegalArgumentException.class, this::repository);
    }
}
