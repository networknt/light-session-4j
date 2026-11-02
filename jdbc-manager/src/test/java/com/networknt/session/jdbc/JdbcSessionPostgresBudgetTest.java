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
            repo.setDefaultMaxInactiveInterval(60_000);
            var first = repo.createSession(); first.setAttribute("a", "one"); first.setAttribute("c", "three");
            var second = repo.createSession(); second.setAttribute("b", "two"); second.setAttribute("d", "four");
            var empty = repo.createSession(); var bad = repo.createSession();
            try (PreparedStatement insert = fixture.admin().prepareStatement("INSERT INTO light_session_attributes VALUES (?, ?, ?)")) {
                insert.setString(1, bad.getId()); insert.setString(2, "safe_bad");
                insert.setBytes(3, new ValueBytesConverter().serializer("x".repeat(100))); insert.executeUpdate();
                insert.setString(2, "z_bad"); insert.setBytes(3, new byte[]{1, 2, 3}); insert.executeUpdate();
            }
            assertEquals("three", repo.findById(first.getId()).getAttribute("c"));
            assertNull(repo.findById(bad.getId()));
            assertEquals(Set.of(first.getId(), second.getId(), empty.getId()), repo.getSessions().keySet());
            assertTrue(queries.stream().anyMatch(q -> q.contains("WHERE EXPIRY_TIME > ?")));
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
            repo.setDefaultMaxInactiveInterval(60_000);
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
                var loaded = repo.extractData(rows);
                assertEquals(List.of(healthy.getId()), loaded.stream().map(v -> v.getId()).toList());
            }
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
