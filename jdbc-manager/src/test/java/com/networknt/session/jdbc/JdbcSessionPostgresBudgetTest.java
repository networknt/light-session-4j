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
