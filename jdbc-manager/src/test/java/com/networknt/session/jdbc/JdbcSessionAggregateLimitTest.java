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
        key = com.networknt.session.jdbc.serializer.SerializedAttributeLimits.PREFIX + key;
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
    private long storedExpiry(String id) throws Exception {
        try (Connection connection = database.getConnection();
             PreparedStatement stmt = connection.prepareStatement("SELECT EXPIRY_TIME FROM light_session WHERE SESSION_ID = ?")) {
            stmt.setString(1, id);
            try (ResultSet rows = stmt.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }

    @Test void expiryUsesMillisecondsForDefaultAndShortTimeoutsAndRefreshes() throws Exception {
        var repo = repository();
        var session = repo.createSession();
        assertEquals(1800, session.getMaxInactiveInterval());
        assertEquals(session.getLastAccessedTime() + 1_800_000L, storedExpiry(session.getId()));
        long fiveSecondsAgo = System.currentTimeMillis() - 5_000;
        session.setLastAccessedTime(fiveSecondsAgo);
        assertEquals(session.getCreationTime() + 1_800_000L, storedExpiry(session.getId()), "Access does not maintain the legacy cache");
        assertNotNull(repo.findById(session.getId()));
        assertTrue(repo.getSessions().containsKey(session.getId()), "Default session must survive more than 1.8 seconds");

        session.setMaxInactiveInterval(60);
        repo.save(session);
        assertEquals(fiveSecondsAgo + 60_000L, storedExpiry(session.getId()));
        long refreshed = System.currentTimeMillis();
        session.setLastAccessedTime(refreshed);
        assertEquals(fiveSecondsAgo + 60_000L, storedExpiry(session.getId()), "Liveness must work with a stale cache");
        assertEquals(refreshed, repo.findById(session.getId()).getLastAccessedTime());
        repo.cleanUpExpiredSessions();
        assertTrue(repo.getSessions().containsKey(session.getId()));

        session.setLastAccessedTime(refreshed - 61_000);
        assertFalse(repo.getSessions().containsKey(session.getId()));
        assertNull(repo.findById(session.getId()));
    }

    @Test void negativeTimeoutNeverExpiresAndLongIntervalsDoNotOverflow() throws Exception {
        var repo = repository(); repo.setDefaultMaxInactiveInterval(-1);
        var session = repo.createSession();
        session.setLastAccessedTime(1);
        assertEquals(Long.MAX_VALUE, session.getExpiryTime());
        assertEquals(Long.MAX_VALUE, storedExpiry(session.getId()));
        repo.cleanUpExpiredSessions();
        assertNotNull(repo.findById(session.getId()));
        assertTrue(repo.getSessions().containsKey(session.getId()));
        session.setMaxInactiveInterval(Integer.MAX_VALUE);
        repo.save(session);
        assertEquals(1L + Integer.MAX_VALUE * 1000L, storedExpiry(session.getId()));
        session.setLastAccessedTime(2);
        assertEquals(1L + Integer.MAX_VALUE * 1000L, storedExpiry(session.getId()));
        assertTrue(repo.getSessions().containsKey(session.getId()), "SQL timeout multiplication must not overflow");
        session.setMaxInactiveInterval(0); repo.save(session);
        assertFalse(repo.getSessions().containsKey(session.getId()));
        assertNull(repo.findById(session.getId()));
    }

    @Test void deprecatedMapperEnforcesExactRowSessionAndResultByteCaps() throws Exception {
        byte[] value = new ValueBytesConverter().serializer("value");
        var ids = List.of("one", "two", "one"); var values = List.of(value, value, value);
        limit("maxListingRows", 3); limit("maxListingSessions", 2); limit("maxListingBytes", 3 * value.length);
        assertEquals(2, repository().extractData(listingRows(ids, values)).size());
        limit("maxListingRows", 2);
        var rowsFailure = assertThrows(IllegalStateException.class, () -> repository().extractData(listingRows(ids, values)));
        assertTrue(rowsFailure.getMessage().endsWith("rows"));
        limit("maxListingRows", 3); limit("maxListingSessions", 1);
        var sessionFailure = assertThrows(IllegalStateException.class, () -> repository().extractData(listingRows(ids, values)));
        assertTrue(sessionFailure.getMessage().endsWith("sessions"));
        limit("maxListingSessions", 2); limit("maxListingBytes", 3 * value.length - 1);
        var byteFailure = assertThrows(IllegalStateException.class, () -> repository().extractData(listingRows(ids, values)));
        assertTrue(byteFailure.getMessage().endsWith("bytes"));
    }

    @Test void deprecatedMapperCountsRejectedSessionsAndStopsAtTheRowSentinel() throws Exception {
        limit("maxListingSessions", 1);
        byte[] corrupt = {1, 2, 3};
        var sessionFailure = assertThrows(IllegalStateException.class, () -> repository().extractData(
                listingRows(List.of("bad", "other"), List.of(corrupt, corrupt))));
        assertTrue(sessionFailure.getMessage().endsWith("sessions"));
        limit("maxListingSessions", 2); limit("maxListingRows", 2);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var rowFailure = assertThrows(IllegalStateException.class, () -> repository().extractData(syntheticRows(calls, List.of(corrupt), true)));
        assertTrue(rowFailure.getMessage().endsWith("rows")); assertEquals(3, calls.get());
    }

    @Test void deprecatedMapperStopsAtRetainedByteCeilingBeforeLateRejections() throws Exception {
        byte[] value = new ValueBytesConverter().serializer("value");
        limit("maxListingBytes", 2 * value.length);
        ResultSet rows = listingRows(List.of("one", "two", "three", "one", "two", "three"),
                List.of(value, value, value, new byte[]{1}, new byte[]{1}, new byte[]{1}));
        var consumed = new java.util.concurrent.atomic.AtomicInteger();
        ResultSet counted = (ResultSet) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (p, m, a) -> {
                    if (m.getName().equals("next")) consumed.incrementAndGet();
                    return invoke(rows, m, a);
                });
        var failure = assertThrows(IllegalStateException.class, () -> repository().extractData(counted));
        assertTrue(failure.getMessage().endsWith("bytes"));
        assertEquals(3, consumed.get(), "Must stop on the first excessive candidate, before EOF or its later rejection");
    }

    @Test void deprecatedMapperReleasesRejectedCandidatesWithinTheRetentionCeiling() throws Exception {
        byte[] value = new ValueBytesConverter().serializer("value");
        limit("maxListingBytes", 2 * value.length);
        var sessions = repository().extractData(listingRows(List.of("bad", "one", "bad", "two"),
                List.of(value, value, new byte[]{1}, value)));
        assertEquals(List.of("one", "two"), sessions.stream().map(v -> v.getId()).toList());
    }

    @Test void compatibilityAccessUpdatesTimestampWithoutHydratingAttributes() throws Exception {
        var repo = repository(); repo.setDefaultMaxInactiveInterval(-1);
        var session = repo.createSession(); session.setLastAccessedTime(1);
        attribute(session.getId(), "corrupt", "value");
        try (Connection connection = database.getConnection(); Statement stmt = connection.createStatement()) {
            stmt.executeUpdate("UPDATE light_session_attributes SET ATTRIBUTE_BYTES = X'010203'");
        }
        repo.updateSessionLastAccessTime(session.getId());
        try (Connection connection = database.getConnection(); PreparedStatement stmt = connection.prepareStatement("SELECT LAST_ACCESS_TIME FROM light_session WHERE SESSION_ID = ?")) {
            stmt.setString(1, session.getId());
            try (ResultSet rows = stmt.executeQuery()) { assertTrue(rows.next()); assertTrue(rows.getLong(1) > 1); }
        }
        repo.updateSessionLastAccessTime("missing");
    }

    @Test void legacyExpiryValuesDoNotControlListingOrCleanup() throws Exception {
        var repo = repository(); long oldAccess = System.currentTimeMillis() - 5_000;
        var ids = List.of("a-active", "b-expired", "c-null", "d-never", "e-large", "f-zero", "g-boundary");
        Integer[] timeouts = {1800, 1, null, -1, Integer.MAX_VALUE, 0, 60};
        Long[] cache = {0L, Long.MAX_VALUE, Long.MAX_VALUE, null, 0L, Long.MAX_VALUE, Long.MAX_VALUE};
        try (Connection connection = database.getConnection(); PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO light_session (SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME) VALUES (?, ?, ?, ?, ?)")) {
            for (int i = 0; i < ids.size(); i++) {
                long access = i == 6 ? oldAccess - 60_000 : oldAccess;
                insert.setString(1, ids.get(i)); insert.setLong(2, access); insert.setLong(3, access);
                if (timeouts[i] == null) insert.setNull(4, Types.INTEGER); else insert.setInt(4, timeouts[i]);
                if (cache[i] == null) insert.setNull(5, Types.BIGINT); else insert.setLong(5, cache[i]);
                insert.executeUpdate();
            }
        }
        Set<String> live = Set.of("a-active", "d-never", "e-large");
        assertEquals(live, repo.getSessions().keySet());
        assertNotNull(repo.findById("a-active"));
        try (Connection connection = database.getConnection(); Statement stmt = connection.createStatement()) {
            try (ResultSet rows = stmt.executeQuery("SELECT SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, NULL AS ATTRIBUTE_NAME FROM light_session WHERE SESSION_ID = 'c-null'")) {
                var nullSession = repo.extractData(rows).getFirst();
                assertEquals(0, nullSession.getMaxInactiveInterval()); assertTrue(nullSession.isExpired());
            }
            // Old writers can continue writing their broken cache formula; corrected readers ignore it.
            stmt.executeUpdate("UPDATE light_session SET EXPIRY_TIME = LAST_ACCESS_TIME + MAX_INACTIVE_INTERVAL");
            assertEquals(live, repo.getSessions().keySet());
            stmt.executeUpdate("UPDATE light_session SET EXPIRY_TIME = NULL");
            assertEquals(live, repo.getSessions().keySet());
            stmt.executeUpdate("UPDATE light_session SET EXPIRY_TIME = 9223372036854775807");
            assertEquals(live, repo.getSessions().keySet());
        }
        repo.cleanUpExpiredSessions();
        assertEquals(live, repo.getSessions().keySet());
        try (Connection connection = database.getConnection(); Statement stmt = connection.createStatement(); ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM light_session")) {
            assertTrue(rows.next()); assertEquals(3, rows.getInt(1));
        }
    }

    @Test void nullTimeoutRetainsZeroSemanticsAndAccessDoesNotRewriteIt() throws Exception {
        var repo = repository(); repo.setDefaultMaxInactiveInterval(10);
        var session = repo.createSession();
        try (Connection connection = database.getConnection(); PreparedStatement stmt = connection.prepareStatement(
                "UPDATE light_session SET MAX_INACTIVE_INTERVAL = NULL, EXPIRY_TIME = 9223372036854775807 WHERE SESSION_ID = ?")) {
            stmt.setString(1, session.getId()); stmt.executeUpdate();
        }
        repo.updateSessionLastAccessTime(session.getId());
        assertFalse(repo.getSessions().containsKey(session.getId()));
        try (Connection connection = database.getConnection(); PreparedStatement stmt = connection.prepareStatement("SELECT MAX_INACTIVE_INTERVAL FROM light_session WHERE SESSION_ID = ?")) {
            stmt.setString(1, session.getId());
            try (ResultSet rows = stmt.executeQuery()) { assertTrue(rows.next()); assertNull(rows.getObject(1)); }
        }
        assertNull(repo.findById(session.getId()), "NULL remains an expired zero-timeout session");
    }

    @Test void accessUsesStoredLivenessUntilPendingTimeoutIsSaved() throws Exception {
        var repo = repository(); var session = repo.createSession(); var stale = repo.findById(session.getId());
        session.setMaxInactiveInterval(60); repo.save(session);
        stale.setMaxInactiveInterval(-1);
        stale.setLastAccessedTime(System.currentTimeMillis() - 61_000);
        assertEquals(-1, stale.getMaxInactiveInterval()); assertTrue(stale.isChanged());
        assertFalse(repo.getSessions().containsKey(session.getId()), "Stored timeout must govern persisted liveness");
        try (Connection connection = database.getConnection(); PreparedStatement stmt = connection.prepareStatement("SELECT MAX_INACTIVE_INTERVAL FROM light_session WHERE SESSION_ID = ?")) {
            stmt.setString(1, session.getId());
            try (ResultSet rows = stmt.executeQuery()) { assertTrue(rows.next()); assertEquals(60, rows.getInt(1)); }
        }
        repo.save(stale);
        assertTrue(repo.getSessions().containsKey(session.getId()));
        assertEquals(-1, repo.findById(session.getId()).getMaxInactiveInterval());
    }

    @Test void accessAfterDeletionDoesNotRecreateTheRow() {
        var repo = repository(); var session = repo.createSession(); var loaded = repo.findById(session.getId());
        repo.deleteById(session.getId());
        assertDoesNotThrow(() -> loaded.setLastAccessedTime(System.currentTimeMillis()));
        assertDoesNotThrow(() -> repo.updateSessionLastAccessTime(session.getId()));
        assertNull(repo.findById(session.getId())); assertTrue(repo.getSessions().isEmpty());
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
                    case "close" -> null;
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
        catch (SessionBudgetExceededException e) {
            assertTrue(e.getMeasured() > e.getLimit());
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
        var rejected = assertThrows(com.networknt.session.jdbc.serializer.AttributeReadException.class,
                () -> converter.deserializeStream(new java.io.ByteArrayInputStream(bytes), bytes.length - 1));
        assertEquals(com.networknt.session.jdbc.serializer.AttributeReadException.Reason.SESSION_BYTES, rejected.reason);
        assertEquals(bytes.length, rejected.bytesRead);
        assertEquals("value", converter.deserializeStream(new java.io.ByteArrayInputStream(bytes), bytes.length).value());
    }

    @Test void connectionStateIsRestoredAfterCommitBudgetRejectionAndSqlFailure() throws Exception {
        limit("maxAttributes", 1);
        for (boolean initialAutoCommit : new boolean[]{true, false}) {
            var closed = new java.util.concurrent.atomic.AtomicInteger();
            javax.sql.DataSource tracked = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("getConnection")) return invoke(database, method, args);
                        Connection c = database.getConnection();
                        c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                        c.setAutoCommit(initialAutoCommit);
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                                (cp, cm, ca) -> {
                                    if (cm.getName().equals("close")) {
                                        assertEquals(Connection.TRANSACTION_SERIALIZABLE, c.getTransactionIsolation());
                                        assertEquals(initialAutoCommit, c.getAutoCommit());
                                        closed.incrementAndGet();
                                    }
                                    return invoke(c, cm, ca);
                                });
                    });
            var repo = new JdbcSessionRepository(tracked);
            var session = repo.createSession();
            var stale = repo.findById(session.getId());
            session.setAttribute("first", "value");
            var failure = assertThrows(SessionBudgetExceededException.class, () -> stale.setAttribute("second", "value"));
            assertEquals(SessionBudgetExceededException.Reason.ATTRIBUTE_COUNT, failure.getReason());
            assertTrue(failure.getMessage().contains("pending changes retained"));
            assertEquals(Set.of("first"), repo.findById(session.getId()).getAttributeNames());
            try (Connection c = database.getConnection(); Statement stmt = c.createStatement()) {
                stmt.execute("ALTER TABLE light_session ADD CONSTRAINT reject_interval CHECK(max_inactive_interval < 9000)");
            }
            session.setMaxInactiveInterval(9999);
            assertThrows(IllegalStateException.class, () -> repo.save(session));
            assertTrue(closed.get() >= 6);
            try (Connection c = database.getConnection(); Statement stmt = c.createStatement()) {
                stmt.execute("ALTER TABLE light_session DROP CONSTRAINT reject_interval");
            }
        }
    }

    @Test void storedValidationUsesScalarSqlAndSkipsEmptyCreatesAndUnchangedSaves() throws Exception {
        var queries = new java.util.concurrent.atomic.AtomicInteger();
        javax.sql.DataSource tracked = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) return invoke(database, method, args);
                    Connection c = database.getConnection();
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                            (cp, cm, ca) -> {
                                if (cm.getName().equals("prepareStatement") && ca[0].toString().startsWith("SELECT COUNT(*)")) {
                                    assertTrue(ca[0].toString().contains("SUM(OCTET_LENGTH(ATTRIBUTE_BYTES))"));
                                    queries.incrementAndGet();
                                }
                                return invoke(c, cm, ca);
                            });
                });
        var repo = new JdbcSessionRepository(tracked); var session = repo.createSession();
        assertEquals(0, queries.get());
        session.setAttribute("a", "value");
        assertEquals(1, queries.get());
        repo.save(session); session.setMaxInactiveInterval(3600); repo.save(session);
        assertEquals(1, queries.get());
        session.removeAttribute("a");
        assertEquals(2, queries.get());
    }

    @Test void replacementAndRemovalPlusAddUseResultingStoredBudget() throws Exception {
        limit("maxAttributes", 2);
        String large = "x".repeat(100);
        int size = new ValueBytesConverter().serializer(large).length;
        limit("maxSessionBytes", size * 2);
        var repo = repository(); var session = repo.createSession();
        session.setAttribute("a", "small"); session.setAttribute("b", "small");
        var left = repo.findById(session.getId()); var right = repo.findById(session.getId());
        left.setAttribute("a", large); right.setAttribute("b", large);
        assertEquals(large, repo.findById(session.getId()).getAttribute("b"));
        var stale = repo.findById(session.getId());
        var rejection = assertThrows(SessionBudgetExceededException.class, () -> stale.setAttribute("a", large + "x"));
        assertEquals(SessionBudgetExceededException.Reason.SESSION_BYTES, rejection.getReason());
        assertEquals(large, repo.findById(session.getId()).getAttribute("a"));
        var countRejection = assertThrows(SessionBudgetExceededException.class, () -> stale.setAttribute("c", large));
        assertEquals(SessionBudgetExceededException.Reason.ATTRIBUTE_COUNT, countRejection.getReason());
        stale.removeAttribute("a"); // Saves the retained add and removal in the same transaction.
        assertEquals(Set.of("b", "c"), repo.findById(session.getId()).getAttributeNames());
    }

    @Test void lookupStopsAtFirstRejectedRowAndAtAttributeCountBoundary() throws Exception {
        limit("maxAttributes", 2);
        var nextCalls = new java.util.concurrent.atomic.AtomicInteger();
        ResultSet rows = syntheticRows(nextCalls, List.of(new byte[]{1, 2, 3}), true);
        assertNull(lookupRows(rows).findById("session"));
        assertEquals(1, nextCalls.get());
        nextCalls.set(0);
        byte[] value = new ValueBytesConverter().serializer("valid");
        rows = syntheticRows(nextCalls, List.of(value), true);
        assertNull(lookupRows(rows).findById("session"));
        assertEquals(3, nextCalls.get());
    }

    @Test void intrinsicallyRejectedAttributesDoNotBecomeListingFailuresInEitherOrder() throws Exception {
        var converter = new ValueBytesConverter();
        byte[] good = converter.serializer("ok");
        byte[] oversized = converter.serializer("x".repeat(100));
        byte[] corrupt = new byte[100];
        byte[] disallowed = converter.serializer(new CountingValue());
        for (byte[] bad : List.of(oversized, corrupt, disallowed)) {
            for (boolean badFirst : new boolean[]{true, false}) {
                int intrinsicLimit = bad == oversized ? 50 : bad.length;
                limit("maxAttributeBytes", intrinsicLimit); limit("maxSessionBytes", intrinsicLimit); limit("maxListingBytes", good.length);
                var rows = syntheticRows(new java.util.concurrent.atomic.AtomicInteger(),
                        badFirst ? List.of(bad, good) : List.of(good, bad), false);
                assertEquals(1, repository().extractData(rows).size());
            }
        }
        // Equal attribute/session/listing limits favor the intrinsic rejection too.
        limit("maxAttributeBytes", 50); limit("maxSessionBytes", 50); limit("maxListingBytes", 50);
        assertTrue(repository().extractData(syntheticRows(new java.util.concurrent.atomic.AtomicInteger(), List.of(oversized), false)).isEmpty());
    }

    private JdbcSessionRepository lookupRows(ResultSet rows) {
        PreparedStatement statement = (PreparedStatement) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (p, m, a) -> switch (m.getName()) {
                    case "executeQuery" -> rows;
                    case "setMaxRows" -> { assertEquals(3, a[0]); yield null; }
                    case "setString", "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        Connection connection = (Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (p, m, a) -> switch (m.getName()) {
                    case "prepareStatement" -> statement;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        javax.sql.DataSource source = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{javax.sql.DataSource.class},
                (p, m, a) -> connection);
        return new JdbcSessionRepository(source);
    }

    private ResultSet syntheticRows(java.util.concurrent.atomic.AtomicInteger nextCalls, List<byte[]> bytes, boolean sameSession) {
        return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> nextCalls.incrementAndGet() <= (sameSession ? 100_000 : bytes.size());
                    case "getString" -> args[0].equals("SESSION_ID") ? (sameSession ? "session" : "session-" + nextCalls.get()) : "attribute-" + nextCalls.get();
                    case "close" -> null;
                    case "getInt" -> 1800;
                    case "getLong" -> System.currentTimeMillis();
                    case "getBinaryStream" -> new java.io.ByteArrayInputStream(bytes.get(sameSession ? 0 : nextCalls.get() - 1));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
    }

    @Test void pendingByteBudgetRejectsBeforeConnectionWithTypedReason() {
        limit("maxSessionBytes", 1);
        var repo = repository(); var session = repo.createSession();
        connections.set(0);
        var rejected = assertThrows(SessionBudgetExceededException.class, () -> session.setAttribute("a", "value"));
        assertEquals(SessionBudgetExceededException.Reason.SESSION_BYTES, rejected.getReason());
        assertTrue(rejected.getMeasured() > rejected.getLimit());
        assertEquals(0, connections.get());
        assertTrue(session.isChanged());
    }

    @Test void legacyMapperDropsInvalidSessionsInEitherOrder() throws Exception {
        var converter = new ValueBytesConverter();
        byte[] prefix = converter.serializer("x".repeat(100));
        byte[] good = converter.serializer("ok");
        for (String reason : List.of("count", "bytes", "corrupt", "filter")) {
            limit("maxAttributes", reason.equals("count") ? 1 : 64);
            limit("maxSessionBytes", reason.equals("bytes") ? prefix.length : 4096);
            limit("maxListingBytes", 2 * (prefix.length + good.length));
            byte[] last = switch (reason) {
                case "corrupt" -> new byte[]{1, 2, 3};
                case "filter" -> converter.serializer(new CountingValue());
                default -> good;
            };
            for (boolean rejectedFirst : new boolean[]{true, false}) {
                for (boolean invalidLast : new boolean[]{true, false}) {
                    var ids = rejectedFirst ? List.of("bad", "bad", "good") : List.of("good", "bad", "bad");
                    var bad = invalidLast ? List.of(prefix, last) : List.of(last, prefix);
                    var values = rejectedFirst ? List.of(bad.get(0), bad.get(1), good) : List.of(good, bad.get(0), bad.get(1));
                    var sessions = repository().extractData(listingRows(ids, values));
                    assertEquals(1, sessions.size());
                    assertEquals("good", sessions.getFirst().getId());
                }
            }
        }
    }

    @Test void extractorPreservesInterleavedSessionsAndRejectsOnlyThePoisonedSession() throws Exception {
        byte[] value = new ValueBytesConverter().serializer("value");
        var sessions = repository().extractData(listingRows(List.of("one", "two", "one"), List.of(value, value, value)));
        assertEquals(List.of("one", "two"), sessions.stream().map(s -> s.getId()).toList());
        assertEquals(2, sessions.getFirst().getAttributeNames().size());
        limit("maxListingBytes", 2 * value.length);
        sessions = repository().extractData(listingRows(List.of("one", "two", "one"), List.of(value, value, new byte[]{1, 2, 3})));
        assertEquals(List.of("two"), sessions.stream().map(s -> s.getId()).toList());
    }

    @Test void legacyMapperPreservesInterleavedLateRejections() throws Exception {
        var converter = new ValueBytesConverter();
        byte[] prefix = converter.serializer("x".repeat(100));
        byte[] good = converter.serializer("ok");
        byte[] filtered = converter.serializer(new CountingValue());
        int sessionBudget = prefix.length + Math.max(filtered.length, good.length);
        limit("maxSessionBytes", sessionBudget); limit("maxListingBytes", 8 * sessionBudget + good.length);
        for (String reason : List.of("count", "bytes", "corrupt", "filter")) {
            limit("maxAttributes", reason.equals("count") ? 1 : 64);
            byte[] last = switch (reason) {
                case "corrupt" -> new byte[]{1, 2, 3};
                case "filter" -> filtered;
                case "bytes" -> converter.serializer("x".repeat(sessionBudget));
                default -> good;
            };
            List<String> ids = new ArrayList<>(); List<byte[]> values = new ArrayList<>();
            for (int i = 0; i < 8; i++) { ids.add("bad-" + i); values.add(prefix); }
            ids.add("good"); values.add(good);
            for (int i = 0; i < 8; i++) { ids.add("bad-" + i); values.add(last); }
            for (int order = 0; order < 3; order++) {
                if (order == 1) { Collections.reverse(ids); Collections.reverse(values); }
                if (order == 2) {
                    List<Integer> indices = new ArrayList<>(); for (int i = 0; i < ids.size(); i++) indices.add(i);
                    Collections.shuffle(indices, new Random(17));
                    var oldIds = new ArrayList<>(ids); var oldValues = new ArrayList<>(values);
                    for (int i = 0; i < ids.size(); i++) { ids.set(i, oldIds.get(indices.get(i))); values.set(i, oldValues.get(indices.get(i))); }
                }
                var sessions = repository().extractData(listingRows(ids, values));
                assertEquals(List.of("good"), sessions.stream().map(v -> v.getId()).toList(), reason + " order=" + order);
            }
        }
    }


    @Test void nativeListingKeepsACompleteResultAfterConnectionOrCursorCloseFailure() throws Exception {
        var writer = repository();
        var session = writer.createSession(); session.setAttribute("a", "value");
        for (boolean cursorFailure : new boolean[]{false, true}) {
            javax.sql.DataSource failing = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("getConnection")) return invoke(database, method, args);
                        Connection connection = database.getConnection();
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                                (p, m, a) -> {
                                    if (m.getName().equals("close") && !cursorFailure) { connection.close(); throw new SQLException("synthetic connection close failure"); }
                                    Object result = invoke(connection, m, a);
                                    if (m.getName().equals("prepareStatement") && cursorFailure) {
                                        PreparedStatement statement = (PreparedStatement) result;
                                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                                (sp, sm, sa) -> {
                                                    Object value = invoke(statement, sm, sa);
                                                    if (sm.getName().equals("close")) throw new SQLException("synthetic statement close failure");
                                                    if (sm.getName().equals("executeQuery")) {
                                                        ResultSet rows = (ResultSet) value;
                                                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                                                                (rp, rm, ra) -> {
                                                                    Object read = invoke(rows, rm, ra);
                                                                    if (rm.getName().equals("close")) throw new SQLException("synthetic cursor close failure");
                                                                    return read;
                                                                });
                                                    }
                                                    return value;
                                                });
                                    }
                                    return result;
                                });
                    });
            assertEquals(Set.of(session.getId()), new JdbcSessionRepository(failing).getSessions().keySet());
        }
    }

    @Test void nativeListingDiscardsPartialResultsAfterALaterQueryFails() throws Exception {
        var writer = repository();
        writer.createSession().setAttribute("a", "one"); writer.createSession().setAttribute("a", "two");
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        javax.sql.DataSource failing = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) return invoke(database, method, args);
                    Connection connection = database.getConnection();
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                            (p, m, a) -> {
                                Object value = invoke(connection, m, a);
                                if (m.getName().equals("prepareStatement") && a[0].toString().contains("WHERE S.SESSION_ID = ?")) {
                                    PreparedStatement statement = (PreparedStatement) value;
                                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                            (sp, sm, sa) -> {
                                                if (sm.getName().equals("executeQuery") && reads.incrementAndGet() == 2)
                                                    throw new SQLException("synthetic later query failure");
                                                return invoke(statement, sm, sa);
                                            });
                                }
                                return value;
                            });
                });
        assertTrue(new JdbcSessionRepository(failing).getSessions().isEmpty());
        assertEquals(2, reads.get());
    }

    @FunctionalInterface
    private interface DatabaseChange { void run() throws Exception; }

    /** Run the change after the ID cursor closes, before any payload query. */
    private javax.sql.DataSource listingSource(DatabaseChange change, java.util.concurrent.atomic.AtomicInteger prepares,
                                              java.util.concurrent.atomic.AtomicInteger reads, java.util.concurrent.atomic.AtomicInteger idRows,
                                              java.util.concurrent.atomic.AtomicInteger idMaxRows) {
        return (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{javax.sql.DataSource.class},
                (p, m, a) -> {
                    if (!m.getName().equals("getConnection")) return invoke(database, m, a);
                    Connection connection = database.getConnection();
                    return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (cp, cm, ca) -> {
                        Object result = invoke(connection, cm, ca);
                        if (!cm.getName().equals("prepareStatement")) return result;
                        boolean ids = ca[0].toString().startsWith("SELECT SESSION_ID FROM");
                        if (!ids) prepares.incrementAndGet();
                        PreparedStatement statement = (PreparedStatement) result;
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (sp, sm, sa) -> {
                            if (ids && sm.getName().equals("setMaxRows")) idMaxRows.set((Integer) sa[0]);
                            Object value = invoke(statement, sm, sa);
                            if (!sm.getName().equals("executeQuery")) return value;
                            if (!ids) { reads.incrementAndGet(); return value; }
                            ResultSet rows = (ResultSet) value;
                            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class}, (rp, rm, ra) -> {
                                Object read = invoke(rows, rm, ra);
                                if (rm.getName().equals("next") && Boolean.TRUE.equals(read)) idRows.incrementAndGet();
                                if (rm.getName().equals("close")) change.run();
                                return read;
                            });
                        });
                    });
                });
    }

    @Test void listingReusesOneStatementAndObservesChangesAfterIdSelection() throws Exception {
        var writer = repository();
        var deleted = writer.createSession(); deleted.setAttribute("a", "deleted");
        var expired = writer.createSession(); expired.setAttribute("a", "expired");
        var refreshed = writer.createSession(); refreshed.setAttribute("a", "old");
        var rewritten = writer.createSession(); rewritten.setAttribute("a", "old");
        var rejected = writer.createSession(); rejected.setAttribute("a", "old");
        limit("maxAttributes", 1);
        var prepares = new java.util.concurrent.atomic.AtomicInteger();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var source = listingSource(() -> {
            try (Connection connection = database.getConnection()) {
                try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM light_session WHERE SESSION_ID = ?")) {
                    stmt.setString(1, deleted.getId()); stmt.executeUpdate();
                }
                try (PreparedStatement stmt = connection.prepareStatement("UPDATE light_session SET EXPIRY_TIME = ?, LAST_ACCESS_TIME = ? WHERE SESSION_ID = ?")) {
                    stmt.setLong(1, 0); stmt.setLong(2, 0); stmt.setString(3, expired.getId()); stmt.executeUpdate();
                    long now = System.currentTimeMillis();
                    stmt.setLong(1, now + 120_000); stmt.setLong(2, now); stmt.setString(3, refreshed.getId()); stmt.executeUpdate();
                }
                try (PreparedStatement stmt = connection.prepareStatement("UPDATE light_session_attributes SET ATTRIBUTE_BYTES = ? WHERE SESSION_ID = ?")) {
                    stmt.setBytes(1, new ValueBytesConverter().serializer("fresh"));
                    stmt.setString(2, rewritten.getId()); stmt.executeUpdate();
                }
            }
            attribute(rejected.getId(), "extra", "over count");
        }, prepares, reads, new java.util.concurrent.atomic.AtomicInteger(), new java.util.concurrent.atomic.AtomicInteger());
        var sessions = new JdbcSessionRepository(source).getSessions();
        assertEquals(Set.of(refreshed.getId(), rewritten.getId()), sessions.keySet());
        assertEquals("fresh", sessions.get(rewritten.getId()).getAttribute("a"));
        assertEquals("old", sessions.get(refreshed.getId()).getAttribute("a"));
        assertTrue(sessions.get(refreshed.getId()).getLastAccessedTime() >= refreshed.getLastAccessedTime());
        assertEquals(1, prepares.get());
        assertEquals(5, reads.get(), "Deleted and expired IDs are also rebound on the same statement");
    }

    @Test void sessionLimitUsesSentinelRowBeforePreparingAnyPayloadStatement() throws Exception {
        var writer = repository();
        for (int i = 0; i < 4; i++) writer.createSession();
        limit("maxListingSessions", 2);
        var prepares = new java.util.concurrent.atomic.AtomicInteger();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        var idRows = new java.util.concurrent.atomic.AtomicInteger();
        var maxRows = new java.util.concurrent.atomic.AtomicInteger();
        var source = listingSource(() -> {}, prepares, reads, idRows, maxRows);
        var failure = assertThrows(IllegalStateException.class, () -> new JdbcSessionRepository(source).getSessions());
        assertTrue(failure.getMessage().endsWith("sessions"));
        assertEquals(3, maxRows.get()); assertEquals(3, idRows.get());
        assertEquals(0, prepares.get()); assertEquals(0, reads.get());
    }

    private ResultSet listingRows(List<String> ids, List<byte[]> values) {
        var index = new java.util.concurrent.atomic.AtomicInteger(-1);
        return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> index.incrementAndGet() < ids.size();
                    case "getString" -> args[0].equals("SESSION_ID") ? ids.get(index.get()) : "attribute-" + index.get();
                    case "close" -> null;
                    case "getInt" -> 1800;
                    case "getLong" -> System.currentTimeMillis();
                    case "getBinaryStream" -> new java.io.ByteArrayInputStream(values.get(index.get()));
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test void invalidBudgetFailsConstruction() {
        limit("maxSessionBytes", 0);
        assertThrows(IllegalArgumentException.class, this::repository);
    }
}
