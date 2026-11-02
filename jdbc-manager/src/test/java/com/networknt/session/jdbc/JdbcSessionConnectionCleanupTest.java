package com.networknt.session.jdbc;

import com.networknt.session.MapSession;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Scripted JDBC failures: tests repository ordering/cleanup, not vendor implementations. */
@org.junit.jupiter.api.parallel.Isolated
class JdbcSessionConnectionCleanupTest {
    @Test void rollbackFailureAbortsWithoutPotentiallyCommittingByRestoringSettings() {
        Stub jdbc = new Stub(); jdbc.writeFailure = true; jdbc.rollbackFailure = true;
        var repo = jdbc.repository(); var session = repo.new JdbcSession();
        var failure = assertThrows(IllegalStateException.class, () -> repo.save(session));
        assertSame(jdbc.writeError, failure.getCause());
        assertEquals(List.of(jdbc.rollbackError), Arrays.asList(failure.getCause().getSuppressed()));
        assertEquals(1, jdbc.aborts);
        assertEquals(List.of(false), jdbc.autoCommitSets);
        assertTrue(jdbc.isolationSets.isEmpty());
        assertTrue(session.isNew()); assertTrue(jdbc.closed);
    }

    @Test void abortFailureIsSuppressedOnTheOriginalWriteFailure() {
        Stub jdbc = new Stub(); jdbc.writeFailure = true; jdbc.rollbackFailure = true; jdbc.abortFailure = true;
        var repo = jdbc.repository();
        var failure = assertThrows(IllegalStateException.class, () -> repo.save(repo.new JdbcSession()));
        assertSame(jdbc.writeError, failure.getCause());
        assertEquals(List.of(jdbc.rollbackError, jdbc.abortError), Arrays.asList(jdbc.writeError.getSuppressed()));
        assertTrue(jdbc.closed);
    }

    @Test void restorationFailuresPreserveTheWriteFailureAndAbortTheConnection() {
        Stub jdbc = new Stub(); jdbc.isolation = Connection.TRANSACTION_SERIALIZABLE;
        jdbc.writeFailure = true; jdbc.restoreIsolationFailure = true; jdbc.restoreAutoFailure = true;
        var repo = jdbc.repository(); var session = existing(repo);
        var failure = assertThrows(IllegalStateException.class, () -> repo.save(session));
        assertSame(jdbc.writeError, failure.getCause());
        assertSame(jdbc.restoreIsolationError, jdbc.writeError.getSuppressed()[0]);
        assertSame(jdbc.restoreAutoError, jdbc.restoreIsolationError.getSuppressed()[0]);
        assertEquals(1, jdbc.aborts); assertTrue(session.getDelta().containsKey("a"));
        assertEquals(List.of(false, true), jdbc.autoCommitSets);
        assertTrue(jdbc.closed);
    }

    @Test void restorationFailureAfterCommitDoesNotMisreportTheCommittedSave() {
        Stub jdbc = new Stub(); jdbc.isolation = Connection.TRANSACTION_SERIALIZABLE;
        jdbc.restoreAutoFailure = true;
        var repo = jdbc.repository(); var session = existing(repo);
        assertDoesNotThrow(() -> repo.save(session));
        assertEquals(1, jdbc.commits); assertEquals(0, jdbc.rollbacks); assertEquals(1, jdbc.aborts);
        assertTrue(session.getDelta().isEmpty()); assertTrue(jdbc.closed);
    }

    @Test void metadataIsCachedAndUnchangedConnectionSettingsAreNotWritten() {
        Stub jdbc = new Stub(); jdbc.autoCommit = false;
        var repo = jdbc.repository();
        repo.save(repo.new JdbcSession());
        assertEquals(0, jdbc.isolationGets); assertEquals(0, jdbc.metadataGets);
        repo.save(existing(repo)); repo.save(existing(repo));
        assertEquals(1, jdbc.metadataGets); assertEquals(2, jdbc.isolationGets);
        assertTrue(jdbc.isolationSets.isEmpty()); assertTrue(jdbc.autoCommitSets.isEmpty());
    }

    @Test void oracleAndSqlServerUseTheirOwnQueriesAndPreserveBlobBinding() {
        for (String product : List.of("Oracle", "Microsoft SQL Server")) {
            Stub jdbc = new Stub(); jdbc.product = product;
            var repo = jdbc.repository(); repo.save(existing(repo)); repo.save(existing(repo));
            String function = product.equals("Oracle") ? "DBMS_LOB.GETLENGTH" : "DATALENGTH";
            assertTrue(jdbc.queries.stream().anyMatch(q -> q.contains(function)));
            assertFalse(jdbc.queries.stream().anyMatch(q -> q.contains("OCTET_LENGTH")));
            assertEquals(4, jdbc.blobBinds); assertEquals(0, jdbc.streamBinds);
            assertEquals(1, jdbc.metadataGets);
        }
    }

    @Test void postgresUsesByteaBindingAndUnknownDriversUseBlobLengthFallback() {
        Stub postgres = new Stub(); postgres.product = "PostgreSQL";
        var repo = postgres.repository(); repo.save(existing(repo));
        assertEquals(2, postgres.streamBinds); assertEquals(0, postgres.blobBinds);
        Stub unknown = new Stub(); unknown.product = "Other JDBC database";
        repo = unknown.repository(); repo.save(existing(repo));
        assertTrue(unknown.queries.contains(JdbcSessionDialect.BLOB_LENGTHS));
        assertFalse(unknown.queries.stream().anyMatch(q -> q.contains("SUM(")));
        assertEquals(1, unknown.blobLengths); assertEquals(1, unknown.blobFrees);
        assertEquals(2, unknown.blobBinds); assertEquals(0, unknown.streamBinds);
    }

    @Test void driverIdentityDoesNotOverrideTheServerAndInternalOverrideSkipsDetection() {
        Stub compatible = new Stub(); compatible.product = "MySQL"; compatible.driver = "PostgreSQL JDBC Driver";
        var repo = compatible.repository(); repo.save(existing(repo));
        assertEquals(0, compatible.streamBinds); assertEquals(2, compatible.blobBinds);
        assertTrue(compatible.queries.stream().anyMatch(q -> q.contains("SUM(OCTET_LENGTH")));
        assertFalse(compatible.queries.stream().anyMatch(q -> q.contains("DBMS_LOB")));
        Stub override = new Stub(); override.product = "Custom proxy";
        repo = override.repository(JdbcSessionDialect.POSTGRESQL); repo.save(existing(repo));
        assertEquals(2, override.streamBinds); assertEquals(0, override.metadataGets);
    }

    @Test void mysqlMariaDbAndDb2UseScalarLengthQueries() {
        for (String product : List.of("MySQL", "MariaDB", "DB2/LINUXX8664", "DB2/LINUXPPC64LE", "DB2/LINUXZ64", "DB2/SUN64", "DB2 UDB for AS/400")) {
            Stub jdbc = new Stub(); jdbc.product = product;
            var repo = jdbc.repository(); repo.save(existing(repo));
            String function = product.startsWith("DB2") ? "SUM(CAST(LENGTH(ATTRIBUTE_BYTES) AS BIGINT))" : "SUM(OCTET_LENGTH(ATTRIBUTE_BYTES))";
            assertTrue(jdbc.queries.stream().anyMatch(q -> q.contains(function)));
            assertEquals(0, jdbc.blobLengths);
        }
    }

    @Test void ambiguousSubstringsDoNotSelectVendorSql() {
        for (String name : List.of("Not Oracle", "postgres imitation", "Some oracle proxy", "Oracle MySQL Compatible")) {
            Stub jdbc = new Stub(); jdbc.product = name; jdbc.driver = "PostgreSQL JDBC Driver";
            var repo = jdbc.repository(); repo.save(existing(repo));
            assertTrue(jdbc.queries.contains(JdbcSessionDialect.BLOB_LENGTHS));
            assertEquals(0, jdbc.streamBinds);
        }
    }

    @Test void db2WideningPrecedesSumAndBudgetReportingRetainsTheLongTotal() {
        Stub jdbc = new Stub(); jdbc.product = "DB2/LINUXX8664"; jdbc.aggregateBytes = (long) Integer.MAX_VALUE + 1;
        var repo = jdbc.repository();
        var failure = assertThrows(SessionBudgetExceededException.class, () -> repo.save(existing(repo)));
        assertEquals(jdbc.aggregateBytes, failure.getMeasured());
        assertTrue(jdbc.queries.stream().anyMatch(q -> q.contains("SUM(CAST(LENGTH(ATTRIBUTE_BYTES) AS BIGINT))")));
        assertEquals(1, jdbc.rollbacks);
    }

    @Test void configurationSelectsDialectWithoutExpandingThePublicConstructorApi() {
        String previous = System.getProperty(JdbcSessionDialect.PROPERTY);
        try {
            System.setProperty(JdbcSessionDialect.PROPERTY, "postgresql");
            Stub jdbc = new Stub(); jdbc.product = "Custom proxy";
            var repo = jdbc.repository();
            System.setProperty(JdbcSessionDialect.PROPERTY, "mysql");
            repo.save(existing(repo));
            assertEquals(2, jdbc.streamBinds); assertEquals(0, jdbc.metadataGets);
            System.setProperty(JdbcSessionDialect.PROPERTY, "not-a-dialect");
            assertThrows(IllegalArgumentException.class, jdbc::repository);
            var constructors = JdbcSessionRepository.class.getConstructors();
            assertEquals(1, constructors.length);
            assertArrayEquals(new Class<?>[]{DataSource.class}, constructors[0].getParameterTypes());
            assertFalse(java.lang.reflect.Modifier.isPublic(JdbcSessionDialect.class.getModifiers()));
        } finally {
            if (previous == null) System.clearProperty(JdbcSessionDialect.PROPERTY);
            else System.setProperty(JdbcSessionDialect.PROPERTY, previous);
        }
    }

    private static JdbcSessionRepository.JdbcSession existing(JdbcSessionRepository repo) {
        var session = repo.new JdbcSession(new MapSession("synthetic"));
        session.getDelta().put("a", "value");
        return session;
    }

    private static final class Stub {
        int isolation = Connection.TRANSACTION_READ_COMMITTED;
        boolean autoCommit = true, closed;
        boolean writeFailure, rollbackFailure, abortFailure, restoreIsolationFailure, restoreAutoFailure;
        String product = "H2", driver = "Generic JDBC";
        int aborts, commits, rollbacks, metadataGets, isolationGets, blobBinds, streamBinds, blobLengths, blobFrees;
        long aggregateBytes = 1;
        final List<String> queries = new ArrayList<>();
        final List<Integer> isolationSets = new ArrayList<>();
        final List<Boolean> autoCommitSets = new ArrayList<>();
        final SQLException writeError = new SQLException("write"), rollbackError = new SQLException("rollback"),
                abortError = new SQLException("abort"), restoreIsolationError = new SQLException("restore isolation"),
                restoreAutoError = new SQLException("restore autocommit");

        JdbcSessionRepository repository() { return repository(null); }

        JdbcSessionRepository repository(JdbcSessionDialect override) {
            DataSource ds = proxy(DataSource.class, (method, args) -> {
                if (method.equals("getConnection")) return connection();
                throw new AssertionError(method);
            });
            return override == null ? new JdbcSessionRepository(ds) : new JdbcSessionRepository(ds, override);
        }

        Connection connection() {
            return proxy(Connection.class, (method, args) -> switch (method) {
                case "getAutoCommit" -> autoCommit;
                case "getTransactionIsolation" -> { isolationGets++; yield isolation; }
                case "setAutoCommit" -> {
                    autoCommitSets.add((Boolean) args[0]);
                    if ((Boolean) args[0] && restoreAutoFailure) throw restoreAutoError;
                    autoCommit = (Boolean) args[0]; yield null;
                }
                case "setTransactionIsolation" -> {
                    isolationSets.add((Integer) args[0]);
                    if ((Integer) args[0] == Connection.TRANSACTION_SERIALIZABLE && restoreIsolationFailure) throw restoreIsolationError;
                    isolation = (Integer) args[0]; yield null;
                }
                case "prepareStatement" -> statement((String) args[0]);
                case "getMetaData" -> {
                    metadataGets++;
                    yield proxy(DatabaseMetaData.class, (m, a) -> {
                        if (m.equals("getDatabaseProductName")) return product;
                        if (m.equals("getDriverName")) return driver;
                        throw new AssertionError(m);
                    });
                }
                case "commit" -> { commits++; yield null; }
                case "rollback" -> { rollbacks++; if (rollbackFailure) throw rollbackError; yield null; }
                case "abort" -> { aborts++; if (abortFailure) throw abortError; yield null; }
                case "close" -> { closed = true; yield null; }
                default -> throw new AssertionError(method);
            });
        }

        PreparedStatement statement(String query) {
            queries.add(query);
            return proxy(PreparedStatement.class, (method, args) -> switch (method) {
                case "setString", "setInt", "setLong", "close" -> null;
                case "setBlob" -> { blobBinds++; yield null; }
                case "setBinaryStream" -> { streamBinds++; yield null; }
                case "executeUpdate" -> {
                    if (writeFailure) throw writeError;
                    yield query.startsWith("UPDATE light_session_attributes") ? 0 : 1;
                }
                case "executeQuery" -> rows();
                default -> throw new AssertionError(method);
            });
        }

        ResultSet rows() {
            boolean[] next = {true};
            return proxy(ResultSet.class, (method, args) -> switch (method) {
                case "next" -> { boolean result = next[0]; next[0] = false; yield result; }
                case "getLong" -> args[0].equals("SESSION_BYTES") ? aggregateBytes : 1L;
                case "getBlob" -> proxy(Blob.class, (m, a) -> switch (m) {
                    case "length" -> { blobLengths++; yield 1L; }
                    case "free" -> { blobFrees++; yield null; }
                    default -> throw new AssertionError("Payload must not be read: " + m);
                });
                case "close" -> null;
                default -> throw new AssertionError(method);
            });
        }
    }

    @FunctionalInterface private interface Call { Object invoke(String method, Object[] args) throws Throwable; }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (p, method, args) -> call.invoke(method.getName(), args)));
    }
}
