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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporaryDirectory;
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
        assertTrue(repository().extractData(rows, false).isEmpty());
        assertEquals(1, nextCalls.get());
        nextCalls.set(0);
        byte[] value = new ValueBytesConverter().serializer("valid");
        rows = syntheticRows(nextCalls, List.of(value), true);
        assertTrue(repository().extractData(rows, false).isEmpty());
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

    private ResultSet syntheticRows(java.util.concurrent.atomic.AtomicInteger nextCalls, List<byte[]> bytes, boolean sameSession) {
        return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> nextCalls.incrementAndGet() <= (sameSession ? 100_000 : bytes.size());
                    case "getString" -> args[0].equals("SESSION_ID") ? (sameSession ? "session" : "session-" + nextCalls.get()) : "attribute-" + nextCalls.get();
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

    @Test void listingChargesOnlyCompleteValidSessionsRegardlessOfAttributeOrSessionOrder() throws Exception {
        var converter = new ValueBytesConverter();
        byte[] prefix = converter.serializer("x".repeat(100));
        byte[] good = converter.serializer("ok");
        for (String reason : List.of("count", "bytes", "corrupt", "filter")) {
            limit("maxAttributes", reason.equals("count") ? 1 : 64);
            limit("maxSessionBytes", reason.equals("bytes") ? prefix.length : 4096);
            limit("maxListingBytes", good.length);
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
        limit("maxListingBytes", value.length);
        sessions = repository().extractData(listingRows(List.of("one", "two", "one"), List.of(value, value, new byte[]{1, 2, 3})));
        assertEquals(List.of("two"), sessions.stream().map(s -> s.getId()).toList());
    }

    @Test void interleavedLateRejectionsPastTheOldCeilingLeaveTheValidListingAvailable() throws Exception {
        var converter = new ValueBytesConverter();
        byte[] prefix = converter.serializer("x".repeat(100));
        byte[] good = converter.serializer("ok");
        byte[] filtered = converter.serializer(new CountingValue());
        int sessionBudget = prefix.length + Math.max(filtered.length, good.length);
        limit("maxSessionBytes", sessionBudget); limit("maxListingBytes", good.length);
        assertTrue(prefix.length * 8 > sessionBudget + good.length, "Prefixes must exceed the removed staging ceiling");
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

    @Test void listingDecodesOnlyAfterAllRowsAndNeverReserializesStagedValues() throws Exception {
        String property = com.networknt.session.jdbc.serializer.DefaultDeserializer.ALLOWED_CLASSES_PROPERTY;
        String previous = System.getProperty(property);
        ReadCountingValue.reads = 0; ReadCountingValue.writes = 0;
        try {
            System.setProperty(property, ReadCountingValue.class.getName());
            var converter = new ValueBytesConverter();
            byte[] custom = converter.serializer(new ReadCountingValue());
            byte[] string = converter.serializer("healthy");
            ResultSet rows = listingRows(List.of("one", "two", "one"), List.of(custom, string, string));
            ResultSet tracked = (ResultSet) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("next")) assertEquals(0, ReadCountingValue.reads);
                        return invoke(rows, method, args);
                    });
            assertEquals(2, repository().extractData(tracked).size());
            assertEquals(1, ReadCountingValue.reads); assertEquals(1, ReadCountingValue.writes);
        } finally {
            if (previous == null) System.clearProperty(property); else System.setProperty(property, previous);
        }
    }

    private static final class ReadCountingValue implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        static int reads, writes;
        private void readObject(java.io.ObjectInputStream stream) throws java.io.IOException, ClassNotFoundException {
            reads++; stream.defaultReadObject();
        }
        private void writeObject(java.io.ObjectOutputStream stream) throws java.io.IOException {
            writes++; stream.defaultWriteObject();
        }
    }

    @Test void nativeListingAndSmallExtractionDoNotDependOnTheTempDirectory() throws Exception {
        java.nio.file.Path unavailable = unavailableDirectory();
        String previous = System.getProperty("java.io.tmpdir");
        try {
            System.setProperty("java.io.tmpdir", unavailable.toString());
            var repo = new JdbcSessionRepository(database) {
                @Override JdbcSessionStaging newStaging() { throw new AssertionError("Native listing must not stage payloads"); }
            };
            repo.setDefaultMaxInactiveInterval(60_000);
            var session = repo.createSession(); session.setAttribute("a", "healthy");
            assertEquals(Set.of(session.getId()), repo.getSessions().keySet());
            byte[] bytes = new ValueBytesConverter().serializer("healthy");
            assertEquals(1, repository().extractData(listingRows(List.of("small"), List.of(bytes))).size());
        } finally { restoreTempDirectory(previous); }
    }

    @Test void largeUnorderedExtractionReportsAnUnavailableSpillDirectory() throws Exception {
        String previous = System.getProperty("java.io.tmpdir");
        byte[] value = new ValueBytesConverter().serializer("value");
        limit("maxSessionBytes", value.length * 2); limit("maxListingBytes", value.length);
        try {
            System.setProperty("java.io.tmpdir", unavailableDirectory().toString());
            var failure = assertThrows(IllegalStateException.class, () -> repository().extractData(
                    listingRows(List.of("one", "two", "three", "four"), List.of(value, value, value, value))));
            assertTrue(failure.getMessage().contains("Unable to stage"));
            assertInstanceOf(java.io.IOException.class, failure.getCause());
        } finally { restoreTempDirectory(previous); }
    }

    @Test void listingLimitDuringRawExtractionClosesSpilledStorage() throws Exception {
        limit("maxListingRows", 1);
        boolean[] closed = {false};
        JdbcSessionStaging staging = new JdbcSessionStaging(temporaryDirectory, 0) {
            @Override public void close() throws java.io.IOException { try { super.close(); } finally { closed[0] = true; } }
        };
        var repo = stagingRepository(staging);
        byte[] value = new ValueBytesConverter().serializer("value");
        var failure = assertThrows(IllegalStateException.class, () -> repo.extractData(
                listingRows(List.of("one", "two"), List.of(value, value))));
        assertTrue(failure.getMessage().contains("rows")); assertTrue(closed[0]);
        assertNoStagingFiles();
    }

    @Test void cleanupOnlyFailurePreservesACompleteListingAndLogsIt() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JdbcSessionRepository.class);
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start(); logger.addAppender(events);
        try {
            var repo = stagingRepository(failingCloseStaging());
            byte[] value = new ValueBytesConverter().serializer("value");
            assertEquals(1, repo.extractData(listingRows(List.of("one"), List.of(value))).size());
            assertTrue(events.list.stream().anyMatch(e -> e.getFormattedMessage().contains("staging cleanup failed")));
        } finally { logger.detachAppender(events); events.stop(); }
        assertNoStagingFiles();
    }

    @Test void cleanupFailureDoesNotReplaceThePrimaryListingLimit() throws Exception {
        limit("maxListingBytes", 1);
        var repo = stagingRepository(failingCloseStaging());
        byte[] value = new ValueBytesConverter().serializer("value");
        var failure = assertThrows(IllegalStateException.class, () -> repo.extractData(listingRows(List.of("one"), List.of(value))));
        assertTrue(failure.getMessage().contains("limits: bytes"));
        assertEquals(1, failure.getSuppressed().length);
        assertInstanceOf(java.io.IOException.class, failure.getSuppressed()[0]);
        assertNoStagingFiles();
    }

    @Test void nativeListingKeepsACompleteResultAfterConnectionOrCursorCloseFailure() throws Exception {
        var writer = repository(); writer.setDefaultMaxInactiveInterval(60_000);
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
        var writer = repository(); writer.setDefaultMaxInactiveInterval(60_000);
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

    private JdbcSessionStaging failingCloseStaging() {
        return new JdbcSessionStaging(temporaryDirectory, 0) {
            @Override public void close() throws java.io.IOException { super.close(); throw new java.io.IOException("synthetic close failure"); }
        };
    }

    private JdbcSessionRepository stagingRepository(JdbcSessionStaging staging) {
        return new JdbcSessionRepository(database) {
            @Override JdbcSessionStaging newStaging() { return staging; }
        };
    }

    private java.nio.file.Path unavailableDirectory() throws Exception {
        java.nio.file.Path path = temporaryDirectory.resolve("not-a-directory");
        java.nio.file.Files.writeString(path, "marker");
        return path;
    }

    private void assertNoStagingFiles() throws Exception {
        try (var files = java.nio.file.Files.list(temporaryDirectory)) { assertEquals(0, files.count()); }
    }

    private static void restoreTempDirectory(String previous) {
        if (previous == null) System.clearProperty("java.io.tmpdir"); else System.setProperty("java.io.tmpdir", previous);
    }

    private ResultSet listingRows(List<String> ids, List<byte[]> values) {
        var index = new java.util.concurrent.atomic.AtomicInteger(-1);
        return (ResultSet) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResultSet.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "next" -> index.incrementAndGet() < ids.size();
                    case "getString" -> args[0].equals("SESSION_ID") ? ids.get(index.get()) : "attribute-" + index.get();
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
