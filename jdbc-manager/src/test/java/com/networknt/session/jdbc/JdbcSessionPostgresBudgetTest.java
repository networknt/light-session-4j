package com.networknt.session.jdbc;

import com.networknt.session.jdbc.serializer.SerializedAttributeLimits;
import com.networknt.session.jdbc.serializer.ValueBytesConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in contract against an explicitly supplied disposable PostgreSQL database. */
@Isolated
@EnabledIfSystemProperty(named = "jdbcPostgresUrl", matches = "jdbc:postgresql:.*")
class JdbcSessionPostgresBudgetTest {
    @Test void concurrentInsertUpdateAndRemovalContracts() throws Exception {
        String url = System.getProperty("jdbcPostgresUrl");
        String schema = "jdbc_budget_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> original = new HashMap<>();
        for (String name : List.of("maxAttributes", "maxSessionBytes"))
            original.put(name, System.getProperty(SerializedAttributeLimits.PREFIX + name));
        try (Connection admin = DriverManager.getConnection(url); Statement stmt = admin.createStatement()) {
            stmt.execute("CREATE SCHEMA " + schema);
            try {
                stmt.execute("CREATE TABLE " + schema + ".light_session (session_id VARCHAR(100) PRIMARY KEY, creation_time BIGINT, last_access_time BIGINT, max_inactive_interval INT, expiry_time BIGINT, principal_name VARCHAR(100))");
                stmt.execute("CREATE TABLE " + schema + ".light_session_attributes (session_id VARCHAR(100), attribute_name VARCHAR(200), attribute_bytes BYTEA, PRIMARY KEY(session_id, attribute_name))");
                DataSource source = source(url, schema);
                limit("maxAttributes", 1); limit("maxSessionBytes", 4096);
                var repo = new JdbcSessionRepository(source);
                String id = repo.createSession().getId();
                var left = repo.findById(id); var right = repo.findById(id);
                assertEquals(1, race(() -> left.setAttribute("a", "value"), () -> right.setAttribute("b", "value")));
                assertEquals(1, repo.findById(id).getAttributeNames().size());

                limit("maxAttributes", 2);
                String large = "x".repeat(100);
                int budget = new ValueBytesConverter().serializer(large).length + new ValueBytesConverter().serializer("x").length;
                limit("maxSessionBytes", budget);
                repo = new JdbcSessionRepository(source);
                var session = repo.createSession();
                session.setAttribute("a", "x"); session.setAttribute("b", "x");
                var updateLeft = repo.findById(session.getId()); var updateRight = repo.findById(session.getId());
                assertEquals(1, race(() -> updateLeft.setAttribute("a", large), () -> updateRight.setAttribute("b", large)));
                var loaded = repo.findById(session.getId());
                assertNotNull(loaded);
                assertEquals(1, loaded.getAttributeNames().stream().filter(n -> large.equals(loaded.getAttribute(n))).count());

                limit("maxSessionBytes", 4096);
                var removalRepo = new JdbcSessionRepository(source);
                var removalSession = removalRepo.createSession();
                removalSession.setAttribute("a", "value"); removalSession.setAttribute("b", "value");
                var removeLeft = removalRepo.findById(removalSession.getId());
                var removeRight = removalRepo.findById(removalSession.getId());
                // Count preflight retains each add; removing the same old attribute saves the paired delta.
                assertThrows(SessionBudgetExceededException.class, () -> removeLeft.setAttribute("c", "value"));
                assertThrows(SessionBudgetExceededException.class, () -> removeRight.setAttribute("d", "value"));
                assertEquals(1, race(() -> removeLeft.removeAttribute("a"), () -> removeRight.removeAttribute("a")));
                var names = removalRepo.findById(removalSession.getId()).getAttributeNames();
                assertEquals(2, names.size()); assertTrue(names.contains("b")); assertFalse(names.contains("a"));
            } finally { stmt.execute("DROP SCHEMA " + schema + " CASCADE"); }
        } finally {
            original.forEach((name, value) -> {
                String key = SerializedAttributeLimits.PREFIX + name;
                if (value == null) System.clearProperty(key); else System.setProperty(key, value);
            });
        }
    }

    @Test void byteaLookupListingAndInterleavedExtraction() throws Exception {
        withSchema("BYTEA", fixture -> {
            List<String> queries = new ArrayList<>();
            var repo = new JdbcSessionRepository(traceQueries(fixture.source(), queries));
            var first = repo.createSession(); first.setAttribute("a", "one"); first.setAttribute("c", "three");
            var second = repo.createSession(); second.setAttribute("b", "two"); second.setAttribute("d", "four");
            var empty = repo.createSession(); var bad = repo.createSession();
            first.setLastAccessedTime(System.currentTimeMillis() - 5_000);
            empty.setMaxInactiveInterval(-1); repo.save(empty); empty.setLastAccessedTime(1);
            try (PreparedStatement insert = fixture.admin().prepareStatement("INSERT INTO light_session_attributes VALUES (?, ?, ?)")) {
                insert.setString(1, bad.getId()); insert.setString(2, "safe_bad");
                insert.setBytes(3, new ValueBytesConverter().serializer("x".repeat(100))); insert.executeUpdate();
                insert.setString(2, "z_bad"); insert.setBytes(3, new byte[]{1, 2, 3}); insert.executeUpdate();
            }
            assertEquals("three", repo.findById(first.getId()).getAttribute("c"));
            assertNull(repo.findById(bad.getId()));
            assertEquals(Set.of(first.getId(), second.getId(), empty.getId()), repo.getSessions().keySet());
            assertTrue(queries.stream().anyMatch(q -> q.startsWith("SELECT SESSION_ID FROM light_session")));
            assertFalse(queries.stream().anyMatch(q -> q.contains("ORDER BY")), "Repository listing must not sort the BLOB join");
            limit("maxAttributes", 1);
            assertNull(new JdbcSessionRepository(fixture.source()).findById(first.getId()));
            limit("maxAttributes", 64);
            try (Statement stmt = fixture.admin().createStatement(); ResultSet rows = stmt.executeQuery(
                    "SELECT S.SESSION_ID, S.CREATION_TIME, S.LAST_ACCESS_TIME, S.MAX_INACTIVE_INTERVAL, A.ATTRIBUTE_NAME, A.ATTRIBUTE_BYTES " +
                    "FROM light_session S LEFT JOIN light_session_attributes A ON S.SESSION_ID = A.SESSION_ID " +
                    "ORDER BY A.ATTRIBUTE_NAME, S.SESSION_ID")) {
                var loaded = repo.extractData(rows);
                assertEquals(Set.of(first.getId(), second.getId(), empty.getId()), loaded.stream().map(v -> v.getId()).collect(java.util.stream.Collectors.toSet()));
                assertEquals(2, loaded.stream().filter(v -> v.getId().equals(first.getId())).findFirst().orElseThrow().getAttributeNames().size());
            }
        });
    }

    @Test void blobLocatorValidationUsesRealPostgresLargeObjects() throws Exception {
        withSchema("OID", fixture -> {
            String value = "x".repeat(20);
            int bytes = new ValueBytesConverter().serializer(value).length;
            limit("maxSessionBytes", bytes * 2);
            var repo = new JdbcSessionRepository(fixture.source(), JdbcSessionDialect.BLOB);
            // Use INSERT for the first LOB and direct fixture inserts for the rest,
            // keeping every test-owned locator referenced for deterministic cleanup.
            var session = repo.new JdbcSession();
            session.setAttribute("a", value);
            try (PreparedStatement insert = fixture.admin().prepareStatement(
                    "INSERT INTO light_session_attributes VALUES (?, ?, lo_from_bytea(0, ?))")) {
                insert.setString(1, session.getId()); insert.setString(2, "b");
                insert.setBytes(3, new ValueBytesConverter().serializer(value)); insert.executeUpdate();
                session.removeAttribute("absent"); // Validates two real locators at the exact byte boundary.
                insert.setString(2, "c"); insert.setBytes(3, new ValueBytesConverter().serializer("x")); insert.executeUpdate();
            }
            var failure = assertThrows(SessionBudgetExceededException.class, () -> session.removeAttribute("again"));
            assertEquals(SessionBudgetExceededException.Reason.SESSION_BYTES, failure.getReason());
            assertTrue(session.getDelta().containsKey("again"));
            try (Statement stmt = fixture.admin().createStatement(); ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM light_session_attributes")) {
                assertTrue(rows.next()); assertEquals(3, rows.getInt(1));
            }
        });
    }

    @Test void lateCorruptionAfterInterleavedPrefixesDoesNotConsumeTheValidListingBudget() throws Exception {
        withSchema("BYTEA", fixture -> {
            var converter = new ValueBytesConverter();
            byte[] prefix = converter.serializer("x".repeat(1024));
            byte[] good = converter.serializer("ok");
            limit("maxAttributes", 64); limit("maxSessionBytes", prefix.length + 3); limit("maxListingBytes", good.length);
            var repo = new JdbcSessionRepository(fixture.source());
            try (PreparedStatement insert = fixture.admin().prepareStatement("INSERT INTO light_session_attributes VALUES (?, ?, ?)")) {
                for (int i = 0; i < 8; i++) {
                    String id = repo.createSession().getId();
                    insert.setString(1, id); insert.setString(2, "a-" + i); insert.setBytes(3, prefix); insert.executeUpdate();
                    insert.setString(2, "z-" + i); insert.setBytes(3, new byte[]{1, 2, 3}); insert.executeUpdate();
                }
            }
            var healthy = repo.createSession(); healthy.setAttribute("middle", "ok");
            assertEquals(Set.of(healthy.getId()), repo.getSessions().keySet());
            // The deliberately interleaved custom query forces every prefix ahead of every rejection.
            try (Statement stmt = fixture.admin().createStatement(); ResultSet rows = stmt.executeQuery(
                    "SELECT S.SESSION_ID, S.CREATION_TIME, S.LAST_ACCESS_TIME, S.MAX_INACTIVE_INTERVAL, A.ATTRIBUTE_NAME, A.ATTRIBUTE_BYTES " +
                    "FROM light_session S JOIN light_session_attributes A ON S.SESSION_ID = A.SESSION_ID ORDER BY A.ATTRIBUTE_NAME")) {
                var failure = assertThrows(IllegalStateException.class, () -> repo.extractData(rows));
                assertTrue(failure.getMessage().endsWith("bytes"), "Unordered helper must enforce retention before late corrupt rows");
            }
        });
    }

    @Test void canonicalLivenessIgnoresCacheRewritesAndPreservesNullAsZero() throws Exception {
        withSchema("BYTEA", fixture -> {
            var repo = new JdbcSessionRepository(fixture.source());
            var active = repo.createSession(); active.setLastAccessedTime(System.currentTimeMillis() - 5_000);
            var never = repo.createSession(); never.setMaxInactiveInterval(-1); repo.save(never); never.setLastAccessedTime(1);
            var expired = repo.createSession(); expired.setMaxInactiveInterval(1); repo.save(expired);
            expired.setLastAccessedTime(System.currentTimeMillis() - 5_000);
            var large = repo.createSession(); large.setMaxInactiveInterval(Integer.MAX_VALUE); repo.save(large);
            var nullTimeout = repo.createSession();
            try (PreparedStatement stmt = fixture.admin().prepareStatement("UPDATE light_session SET MAX_INACTIVE_INTERVAL = NULL WHERE SESSION_ID = ?")) {
                stmt.setString(1, nullTimeout.getId()); stmt.executeUpdate();
            }
            Set<String> live = Set.of(active.getId(), never.getId(), large.getId());
            try (Statement oldWriter = fixture.admin().createStatement()) {
                oldWriter.executeUpdate("UPDATE light_session SET EXPIRY_TIME = LAST_ACCESS_TIME + MAX_INACTIVE_INTERVAL");
                assertEquals(live, repo.getSessions().keySet());
                oldWriter.executeUpdate("UPDATE light_session SET EXPIRY_TIME = NULL");
                assertEquals(live, repo.getSessions().keySet());
                oldWriter.executeUpdate("UPDATE light_session SET EXPIRY_TIME = 9223372036854775807");
                assertEquals(live, repo.getSessions().keySet());
                try (ResultSet rows = oldWriter.executeQuery("SELECT SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, NULL AS ATTRIBUTE_NAME FROM light_session WHERE MAX_INACTIVE_INTERVAL IS NULL")) {
                    var loaded = repo.extractData(rows).getFirst(); assertEquals(0, loaded.getMaxInactiveInterval()); assertTrue(loaded.isExpired());
                }
            }
            repo.cleanUpExpiredSessions();
            assertEquals(live, repo.getSessions().keySet());
            assertNotNull(repo.findById(active.getId())); assertNull(repo.findById(expired.getId()));
            assertNull(repo.findById(nullTimeout.getId()));
        });
    }

    @Test void accessUsesCanonicalStoredTimeoutWithoutRetryState() throws Exception {
        withSchema("BYTEA", fixture -> {
            var repo = new JdbcSessionRepository(fixture.source()); var session = repo.createSession();
            var stale = repo.findById(session.getId());
            session.setMaxInactiveInterval(60); repo.save(session);
            stale.setMaxInactiveInterval(-1); stale.setLastAccessedTime(System.currentTimeMillis() - 61_000);
            assertFalse(repo.getSessions().containsKey(session.getId()));
            try (PreparedStatement check = fixture.admin().prepareStatement("SELECT MAX_INACTIVE_INTERVAL FROM light_session WHERE SESSION_ID = ?")) {
                check.setString(1, session.getId());
                try (ResultSet rows = check.executeQuery()) { assertTrue(rows.next()); assertEquals(60, rows.getInt(1)); }
            }
            assertThrows(IllegalStateException.class, () -> repo.save(stale));
            assertFalse(repo.getSessions().containsKey(session.getId()));
            try (PreparedStatement stmt = fixture.admin().prepareStatement("UPDATE light_session SET MAX_INACTIVE_INTERVAL = NULL WHERE SESSION_ID = ?")) {
                stmt.setString(1, session.getId()); stmt.executeUpdate();
            }
            assertThrows(IllegalStateException.class, () -> repo.updateSessionLastAccessTime(session.getId()));
            assertFalse(repo.getSessions().containsKey(session.getId())); assertNull(repo.findById(session.getId()));
            long before = stale.getLastAccessedTime();
            assertThrows(IllegalStateException.class, () -> stale.setLastAccessedTime(System.currentTimeMillis()));
            assertEquals(before, stale.getLastAccessedTime());
            assertThrows(IllegalStateException.class, () -> repo.updateSessionLastAccessTime(session.getId()));
            assertNull(repo.findById(session.getId()));
        });
    }

    @Test void lateAccessCannotReviveAndDeletionRemovesAttributesWithoutCascade() throws Exception {
        withSchema("BYTEA", fixture -> {
            var repo = new JdbcSessionRepository(fixture.source()); var healthy = repo.createSession(); healthy.setAttribute("a", "keep");
            for (Integer timeout : Arrays.asList(1, 0, null)) {
                var session = repo.createSession(); session.setAttribute("a", "delete"); var late = repo.findById(session.getId());
                long oldAccess = System.currentTimeMillis() - 5_000;
                try (PreparedStatement stmt = fixture.admin().prepareStatement("UPDATE light_session SET LAST_ACCESS_TIME = ?, MAX_INACTIVE_INTERVAL = ? WHERE SESSION_ID = ?")) {
                    stmt.setLong(1, oldAccess); if (timeout == null) stmt.setNull(2, Types.INTEGER); else stmt.setInt(2, timeout);
                    stmt.setString(3, session.getId()); stmt.executeUpdate();
                }
                long before = late.getLastAccessedTime();
                assertThrows(IllegalStateException.class, () -> repo.updateSessionLastAccessTime(session.getId()));
                assertThrows(IllegalStateException.class, () -> late.setLastAccessedTime(Long.MAX_VALUE));
                assertEquals(before, late.getLastAccessedTime()); assertFalse(late.isChanged());
                late.setMaxInactiveInterval(-1);
                assertThrows(IllegalStateException.class, () -> repo.save(late));
                try (PreparedStatement stmt = fixture.admin().prepareStatement("SELECT LAST_ACCESS_TIME FROM light_session WHERE SESSION_ID = ?")) {
                    stmt.setString(1, session.getId()); try (ResultSet rows = stmt.executeQuery()) { assertTrue(rows.next()); assertEquals(oldAccess, rows.getLong(1)); }
                }
            }
            try (PreparedStatement stmt = fixture.admin().prepareStatement("INSERT INTO light_session_attributes VALUES (?, ?, ?)")) {
                stmt.setString(1, "legacy-orphan"); stmt.setString(2, "a"); stmt.setBytes(3, new ValueBytesConverter().serializer("orphan")); stmt.executeUpdate();
            }
            repo.cleanUpExpiredSessions(); assertEquals(Set.of(healthy.getId()), repo.getSessions().keySet());
            try (Statement stmt = fixture.admin().createStatement(); ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM light_session_attributes")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
            }
            repo.deleteById(healthy.getId());
            try (Statement stmt = fixture.admin().createStatement(); ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM light_session_attributes")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
        });
    }

    private static Object invokeJdbc(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    private static DataSource parentStatementHook(DataSource source, boolean deleting, CountDownLatch deleted, CountDownLatch writerEntered) {
        return (DataSource) Proxy.newProxyInstance(JdbcSessionPostgresBudgetTest.class.getClassLoader(), new Class<?>[]{DataSource.class}, (p, m, a) -> {
            Connection connection = (Connection) invokeJdbc(source, m, a);
            return Proxy.newProxyInstance(JdbcSessionPostgresBudgetTest.class.getClassLoader(), new Class<?>[]{Connection.class}, (cp, cm, ca) -> {
                Object value = invokeJdbc(connection, cm, ca);
                if (!cm.getName().equals("prepareStatement")) return value;
                String prefix = deleting ? "DELETE FROM light_session " : "UPDATE light_session SET SESSION_ID";
                if (!ca[0].toString().startsWith(prefix)) return value;
                PreparedStatement statement = (PreparedStatement) value;
                return Proxy.newProxyInstance(JdbcSessionPostgresBudgetTest.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (sp, sm, sa) -> {
                    boolean execute = sm.getName().equals("executeUpdate");
                    if (execute && !deleting) writerEntered.countDown();
                    Object result = invokeJdbc(statement, sm, sa);
                    if (execute && deleting) {
                        assertEquals(1, result); deleted.countDown();
                        assertTrue(writerEntered.await(10, TimeUnit.SECONDS));
                    }
                    return result;
                });
            });
        });
    }

    @Test void concurrentSaveCannotInsertAttributesAfterDeleteOrCleanup() throws Exception {
        withSchema("BYTEA", fixture -> {
            var repo = new JdbcSessionRepository(fixture.source());
            for (boolean cleanup : new boolean[]{false, true}) {
                var session = repo.createSession(); session.setAttribute("a", "old"); var stale = repo.findById(session.getId());
                if (cleanup) { session.setMaxInactiveInterval(0); repo.save(session); }
                CountDownLatch deleted = new CountDownLatch(1), writerEntered = new CountDownLatch(1);
                var deleter = new JdbcSessionRepository(parentStatementHook(fixture.source(), true, deleted, writerEntered));
                var saver = new JdbcSessionRepository(parentStatementHook(fixture.source(), false, deleted, writerEntered));
                // Load a session owned by the hooked saver before deletion starts.
                var pending = saver.new JdbcSession(new com.networknt.session.MapSession(stale));
                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                    Future<?> deletion = pool.submit(() -> { if (cleanup) deleter.cleanUpExpiredSessions(); else deleter.deleteById(session.getId()); });
                    assertTrue(deleted.await(10, TimeUnit.SECONDS));
                    Future<?> save = pool.submit(() -> pending.setAttribute("new", "must not persist"));
                    deletion.get(10, TimeUnit.SECONDS);
                    ExecutionException rejected = assertThrows(ExecutionException.class, () -> save.get(10, TimeUnit.SECONDS));
                    assertInstanceOf(IllegalStateException.class, rejected.getCause());
                    assertTrue(pending.getDelta().containsKey("new"));
                } finally { writerEntered.countDown(); pool.shutdownNow(); pool.awaitTermination(10, TimeUnit.SECONDS); }
                try (Statement stmt = fixture.admin().createStatement(); ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM light_session_attributes")) {
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                }
                assertNull(repo.findById(session.getId()));
            }
        });
    }

    @Test void cleanupCannotTouchAConcurrentNewSessionOrHistoricalOrphan() throws Exception {
        withSchema("BYTEA", fixture -> {
            var repo = new JdbcSessionRepository(fixture.source()); var expired = repo.createSession();
            expired.setAttribute("a", "expired"); expired.setMaxInactiveInterval(0); repo.save(expired);
            try (PreparedStatement stmt = fixture.admin().prepareStatement("INSERT INTO light_session_attributes VALUES ('historical-orphan', 'a', ?)")) {
                stmt.setBytes(1, new ValueBytesConverter().serializer("old")); stmt.executeUpdate();
            }
            var created = new java.util.concurrent.atomic.AtomicReference<String>();
            DataSource interleaved = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (p, m, a) -> {
                Connection connection = (Connection) invokeJdbc(fixture.source(), m, a);
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (cp, cm, ca) -> {
                    Object value = invokeJdbc(connection, cm, ca);
                    if (!cm.getName().equals("prepareStatement")) return value;
                    String query = ca[0].toString();
                    if (query.startsWith("DELETE FROM light_session_attributes"))
                        assertEquals("DELETE FROM light_session_attributes WHERE SESSION_ID = ?", query);
                    if (!query.startsWith("DELETE FROM light_session ")) return value;
                    PreparedStatement statement = (PreparedStatement) value;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (sp, sm, sa) -> {
                        Object result = invokeJdbc(statement, sm, sa);
                        if (sm.getName().equals("executeUpdate") && created.get() == null) {
                            var fresh = repo.createSession(); fresh.setAttribute("a", "fresh"); created.set(fresh.getId());
                        }
                        return result;
                    });
                });
            });
            new JdbcSessionRepository(interleaved).cleanUpExpiredSessions();
            assertNotNull(created.get()); assertEquals("fresh", repo.findById(created.get()).getAttribute("a"));
            assertNull(repo.findById(expired.getId()));
            try (Statement stmt = fixture.admin().createStatement(); ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM light_session_attributes")) {
                assertTrue(rows.next()); assertEquals(2, rows.getInt(1), "New session and historical orphan must both remain");
            }
            fixture.admin().setAutoCommit(false);
            assertEquals(1, JdbcSessionOrphanCleanup.removePage(fixture.admin(), 1));
            assertEquals(0, JdbcSessionOrphanCleanup.removePage(fixture.admin(), 1));
            fixture.admin().setAutoCommit(true);
            assertEquals("fresh", repo.findById(created.get()).getAttribute("a"));
        });
    }

    private record Fixture(Connection admin, DataSource source) { }
    @FunctionalInterface private interface DatabaseTest { void run(Fixture fixture) throws Exception; }

    private static void withSchema(String columnType, DatabaseTest test) throws Exception {
        String url = System.getProperty("jdbcPostgresUrl");
        String schema = "jdbc_reads_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, String> original = new HashMap<>();
        for (String name : List.of("maxAttributes", "maxSessionBytes", "maxListingBytes"))
            original.put(name, System.getProperty(SerializedAttributeLimits.PREFIX + name));
        try (Connection admin = DriverManager.getConnection(url); Statement stmt = admin.createStatement()) {
            stmt.execute("CREATE SCHEMA " + schema);
            admin.setSchema(schema);
            try {
                stmt.execute("CREATE TABLE light_session (session_id VARCHAR(100) PRIMARY KEY, creation_time BIGINT, last_access_time BIGINT, max_inactive_interval INT, expiry_time BIGINT, principal_name VARCHAR(100))");
                stmt.execute("CREATE TABLE light_session_attributes (session_id VARCHAR(100), attribute_name VARCHAR(200), attribute_bytes " + columnType + ", PRIMARY KEY(session_id, attribute_name))");
                test.run(new Fixture(admin, source(url, schema)));
            } finally {
                if (columnType.equals("OID")) {
                    try (ResultSet rows = stmt.executeQuery("SELECT lo_unlink(attribute_bytes) FROM light_session_attributes")) {
                        while (rows.next()) assertEquals(1, rows.getInt(1));
                    }
                }
                stmt.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        } finally {
            original.forEach((name, value) -> {
                String key = SerializedAttributeLimits.PREFIX + name;
                if (value == null) System.clearProperty(key); else System.setProperty(key, value);
            });
        }
    }

    private static DataSource traceQueries(DataSource source, List<String> queries) {
        return (DataSource) Proxy.newProxyInstance(JdbcSessionPostgresBudgetTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) throw new UnsupportedOperationException(method.getName());
                    Connection connection = source.getConnection();
                    return Proxy.newProxyInstance(JdbcSessionPostgresBudgetTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (cp, cm, ca) -> {
                                if (cm.getName().equals("prepareStatement")) queries.add((String) ca[0]);
                                try { return cm.invoke(connection, ca); }
                                catch (InvocationTargetException e) { throw e.getCause(); }
                            });
                });
    }

    private static DataSource source(String url, String schema) {
        return (DataSource) Proxy.newProxyInstance(JdbcSessionPostgresBudgetTest.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("getConnection")) throw new UnsupportedOperationException(method.getName());
                    Connection c = DriverManager.getConnection(url);
                    c.setSchema(schema);
                    c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                    return Proxy.newProxyInstance(JdbcSessionPostgresBudgetTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (cp, cm, ca) -> {
                                if (cm.getName().equals("close")) {
                                    assertEquals(Connection.TRANSACTION_SERIALIZABLE, c.getTransactionIsolation());
                                    assertTrue(c.getAutoCommit());
                                }
                                try { return cm.invoke(c, ca); }
                                catch (InvocationTargetException e) { throw e.getCause(); }
                            });
                });
    }

    private static int race(Runnable left, Runnable right) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> a = pool.submit(() -> attempt(start, left));
            Future<Boolean> b = pool.submit(() -> attempt(start, right));
            start.countDown();
            return (a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0);
        } finally { pool.shutdownNow(); }
    }

    private static boolean attempt(CountDownLatch start, Runnable action) throws InterruptedException {
        start.await();
        try { action.run(); return true; }
        catch (SessionBudgetExceededException e) { assertTrue(e.getMeasured() > e.getLimit()); return false; }
    }

    private static void limit(String name, int value) {
        System.setProperty(SerializedAttributeLimits.PREFIX + name, Integer.toString(value));
    }
}
