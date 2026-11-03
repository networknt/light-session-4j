package com.networknt.session.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class JdbcSessionOrphanCleanupTest {
    @TempDir Path directory;
    record CommandResult(int exit, String output) { }

    /** Start the actual main in a child JVM so getenv and the launcher are exercised. */
    static CommandResult command(Map<String, String> environment, String... arguments) throws Exception {
        return commandWithDriver(environment, null, arguments);
    }

    private static CommandResult commandWithDriver(Map<String, String> environment, String driver, String... arguments) throws Exception {
        List<String> invocation = new ArrayList<>();
        invocation.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        if (driver != null) invocation.add("-Djdbc.drivers=" + driver);
        invocation.add("-cp");
        invocation.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
        invocation.add(JdbcSessionOrphanCleanup.class.getName());
        invocation.addAll(List.of(arguments));
        Path output = Files.createTempFile("jdbc-orphan-cli-test-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(invocation).redirectErrorStream(true).redirectOutput(output.toFile());
            builder.environment().keySet().removeIf(key -> key.startsWith("JDBC_MAINTENANCE_"));
            builder.environment().putAll(environment);
            Process process = builder.start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly(); process.waitFor();
                fail("Maintenance command timed out");
            }
            return new CommandResult(process.exitValue(), Files.readString(output));
        } finally { Files.deleteIfExists(output); }
    }

    private String database(int orphans) throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("database-" + UUID.randomUUID());
        try (Connection connection = DriverManager.getConnection(url, "operator", "test-secret"); Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE light_session (session_id VARCHAR(100) PRIMARY KEY)");
            stmt.execute("CREATE TABLE light_session_attributes (session_id VARCHAR(100), attribute_name VARCHAR(200), PRIMARY KEY(session_id, attribute_name))");
            stmt.execute("INSERT INTO light_session VALUES ('valid')");
            stmt.execute("INSERT INTO light_session_attributes VALUES ('valid', 'keep')");
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO light_session_attributes VALUES (?, ?)")) {
                // Multiple names for each ID exercise both columns of the keyset predicate.
                for (int i = 0; i < orphans; i++) {
                    insert.setString(1, "orphan-" + String.format("%03d", i / 2));
                    insert.setString(2, "attribute-" + (i % 2)); insert.executeUpdate();
                }
            }
        }
        return url;
    }

    private Map<String, String> environment(String url) {
        return Map.of("JDBC_MAINTENANCE_URL", url, "JDBC_MAINTENANCE_USER", "operator", "JDBC_MAINTENANCE_PASSWORD", "test-secret");
    }

    private void onlyValidAttributeRemains(String url) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, "operator", "test-secret");
             Statement stmt = connection.createStatement(); ResultSet rows = stmt.executeQuery("SELECT session_id, attribute_name FROM light_session_attributes")) {
            assertTrue(rows.next()); assertEquals("valid", rows.getString(1)); assertEquals("keep", rows.getString(2));
            assertFalse(rows.next());
        }
    }

    @Test void mainRequiresAcknowledgementAndValidBatchArguments() throws Exception {
        var environment = environment(database(1));
        for (String[] args : List.of(new String[]{}, new String[]{"--wrong"}, new String[]{"--writers-stopped", "1", "extra"},
                new String[]{"--writers-stopped", "0"}, new String[]{"--writers-stopped", "-1"},
                new String[]{"--writers-stopped", "2147483647"}, new String[]{"--writers-stopped", "bad"})) {
            var result = command(environment, args);
            assertNotEquals(0, result.exit(), result.output());
            assertFalse(result.output().contains("Completed orphan cleanup"));
        }
    }

    @Test void mainRequiresUrlAndUsesUserAndPasswordEnvironment() throws Exception {
        var missing = command(Map.of(), "--writers-stopped");
        assertNotEquals(0, missing.exit()); assertTrue(missing.output().contains("Set JDBC_MAINTENANCE_URL"));
        var blank = command(environment(" "), "--writers-stopped");
        assertNotEquals(0, blank.exit()); assertTrue(blank.output().contains("Set JDBC_MAINTENANCE_URL"));
        String url = database(1);
        var wrong = new HashMap<>(environment(url)); wrong.put("JDBC_MAINTENANCE_PASSWORD", "wrong");
        assertNotEquals(0, command(wrong, "--writers-stopped").exit());
        wrong = new HashMap<>(environment(url)); wrong.put("JDBC_MAINTENANCE_USER", "wrong");
        assertNotEquals(0, command(wrong, "--writers-stopped").exit());
        var result = command(environment(url), "--writers-stopped", "1");
        assertEquals(0, result.exit(), result.output()); onlyValidAttributeRemains(url);
    }

    @Test void mainUsesDefaultBatchSizeAndPrintsCommittedTotals() throws Exception {
        String url = database(129);
        var result = command(environment(url), "--writers-stopped");
        assertEquals(0, result.exit(), result.output());
        assertEquals(List.of("Committed orphan attributes: 128", "Committed orphan attributes: 129", "Completed orphan cleanup; removed: 129"), result.output().lines().toList());
        onlyValidAttributeRemains(url);
        var rerun = command(environment(url), "--writers-stopped");
        assertEquals(0, rerun.exit(), rerun.output());
        assertEquals("Completed orphan cleanup; removed: 0", rerun.output().strip());
    }

    @Test void mainAdvancesSelectedKeysetEvenWhenWholePageDeletesZeroRows() throws Exception {
        String url = database(4);
        var result = commandWithDriver(environment("jdbc:vanishing:" + url), VanishingKeysDriver.class.getName(), "--writers-stopped", "2");
        assertEquals(0, result.exit(), result.output());
        assertTrue(result.output().contains("Committed orphan attributes: 0"), result.output());
        assertTrue(result.output().contains("Completed orphan cleanup; removed: 2"), result.output());
        assertTrue(result.output().contains("A.SESSION_ID > ? OR (A.SESSION_ID = ? AND A.ATTRIBUTE_NAME > ?)"), result.output());
        onlyValidAttributeRemains(url);
    }

    @Test void mainRejectsNullOrDuplicateLegacyKeysWithoutFalseCompletion() throws Exception {
        for (boolean nullKey : new boolean[]{true, false}) {
            String url = database(1);
            try (Connection connection = DriverManager.getConnection(url, "operator", "test-secret"); Statement stmt = connection.createStatement()) {
                stmt.execute("ALTER TABLE light_session_attributes DROP PRIMARY KEY");
                if (nullKey) {
                    stmt.execute("ALTER TABLE light_session_attributes ALTER COLUMN attribute_name DROP NOT NULL");
                    stmt.execute("INSERT INTO light_session_attributes VALUES ('null-orphan', NULL)");
                } else stmt.execute("INSERT INTO light_session_attributes VALUES ('orphan-000', 'attribute-0')");
            }
            var result = command(environment(url), "--writers-stopped", "2");
            assertNotEquals(0, result.exit(), result.output());
            assertTrue(result.output().contains(nullKey ? "non-NULL" : "unique"), result.output());
            assertFalse(result.output().contains("Completed orphan cleanup"));
        }
    }

    /** Only the disappearing-key schedule is scripted; queries and transactions execute on real H2. */
    public static final class VanishingKeysDriver implements Driver {
        static { try { DriverManager.registerDriver(new VanishingKeysDriver()); } catch (SQLException e) { throw new ExceptionInInitializerError(e); } }
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) return null;
            Connection connection = new org.h2.Driver().connect(url.substring("jdbc:vanishing:".length()), info);
            AtomicInteger deletes = new AtomicInteger();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                Object value = invoke(connection, m, a);
                if (!m.getName().equals("prepareStatement")) return value;
                String sql = (String) a[0];
                if (sql.startsWith("SELECT A.SESSION_ID")) System.out.println("Page SQL: " + sql);
                if (!sql.startsWith("DELETE FROM light_session_attributes")) return value;
                PreparedStatement stmt = (PreparedStatement) value;
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class}, (sp, sm, sa) -> {
                    if (sm.getName().equals("executeUpdate") && deletes.incrementAndGet() <= 2) stmt.executeUpdate();
                    return invoke(stmt, sm, sa);
                });
            });
        }
        private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
            try { return method.invoke(target, arguments); } catch (InvocationTargetException e) { throw e.getCause(); }
        }
        public boolean acceptsURL(String url) { return url.startsWith("jdbc:vanishing:"); }
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) { return new DriverPropertyInfo[0]; }
        public int getMajorVersion() { return 1; }
        public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public Logger getParentLogger() { return Logger.getGlobal(); }
    }
}
