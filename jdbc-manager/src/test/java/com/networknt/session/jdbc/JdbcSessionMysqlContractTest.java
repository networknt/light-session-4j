package com.networknt.session.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Connector/J contracts. The supplied database must be disposable: these fixtures own its tables. */
@Isolated
@EnabledIfSystemProperty(named = "jdbcMysqlUrl", matches = "jdbc:mysql:.*")
class JdbcSessionMysqlContractTest {
    @FunctionalInterface private interface Contract { void run(Connection admin, DataSource source) throws Exception; }

    private void withTables(boolean changedRows, Contract contract) throws Exception {
        String url = System.getProperty("jdbcMysqlUrl") + "&useAffectedRows=" + changedRows;
        DataSource source = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (p, m, a) -> { if (m.getName().equals("getConnection")) return DriverManager.getConnection(url); throw new UnsupportedOperationException(m.getName()); });
        try (Connection admin = source.getConnection(); Statement stmt = admin.createStatement()) {
            stmt.execute("CREATE TABLE light_session (session_id VARCHAR(100) PRIMARY KEY, creation_time BIGINT, last_access_time BIGINT, max_inactive_interval INT, expiry_time BIGINT, principal_name VARCHAR(100)) ENGINE=InnoDB");
            try {
                stmt.execute("CREATE TABLE light_session_attributes (session_id VARCHAR(100), attribute_name VARCHAR(200), attribute_bytes LONGBLOB, PRIMARY KEY(session_id, attribute_name)) ENGINE=InnoDB");
                try { contract.run(admin, source); }
                finally { stmt.execute("DROP TABLE light_session_attributes"); }
            } finally { stmt.execute("DROP TABLE light_session"); }
        }
    }

    @Test void foundAndChangedRowModesSupportUnchangedUpdatesAndRepeatedAttributes() throws Exception {
        for (boolean changedRows : new boolean[]{false, true}) withTables(changedRows, (admin, source) -> {
            var repo = new JdbcSessionRepository(source); var session = repo.createSession();
            try (PreparedStatement stmt = admin.prepareStatement("UPDATE light_session SET LAST_ACCESS_TIME = LAST_ACCESS_TIME WHERE SESSION_ID = ?")) {
                stmt.setString(1, session.getId());
                assertEquals(changedRows ? 0 : 1, stmt.executeUpdate(), "Fixture must actually exercise the requested driver mode");
            }
            session.setLastAccessedTime(session.getLastAccessedTime()); repo.save(session);
            session.setAttribute("a", "same"); session.setAttribute("a", "same"); session.setAttribute("b", "new");
            assertEquals(2, repo.findById(session.getId()).getAttributeNames().size());
            assertEquals("same", repo.getSessions().get(session.getId()).getAttribute("a"));
            repo.deleteById(session.getId());
            assertEquals(JdbcSessionException.Reason.UNAVAILABLE,
                    assertThrows(JdbcSessionException.class, () -> repo.updateSessionLastAccessTime(session.getId())).getReason());
        });
    }

    @Test void managerHandlesRealConcurrentRefusalAndDistinguishesRealSqlFailure() throws Exception {
        withTables(true, (admin, source) -> {
            var repo = new JdbcSessionRepository(source); String id = repo.createSession().getId();
            var expiredBetweenReads = new JdbcSessionRepository(source) {
                @Override public JdbcSession findById(String sessionId) {
                    var session = super.findById(sessionId);
                    try (PreparedStatement stmt = admin.prepareStatement("UPDATE light_session SET MAX_INACTIVE_INTERVAL = 0 WHERE SESSION_ID = ?")) {
                        stmt.setString(1, sessionId); stmt.executeUpdate();
                    } catch (SQLException e) { throw new AssertionError(e); }
                    return session;
                }
            };
            assertNull(new JdbcSessionManager(new io.undertow.server.session.SessionCookieConfig(), expiredBetweenReads).getSession(id));
            var loaded = repo.createSession();
            try (Statement stmt = admin.createStatement()) { stmt.execute("ALTER TABLE light_session RENAME TO temporarily_unavailable"); }
            try {
                var failure = assertThrows(JdbcSessionException.class, () -> loaded.setLastAccessedTime(System.currentTimeMillis()));
                assertEquals(JdbcSessionException.Reason.STORAGE_FAILURE, failure.getReason());
                assertInstanceOf(SQLException.class, failure.getCause());
            } finally {
                try (Statement stmt = admin.createStatement()) { stmt.execute("ALTER TABLE temporarily_unavailable RENAME TO light_session"); }
            }
        });
    }

    @Test void unchangedParentUpdateKeepsWriteLockInChangedRowMode() throws Exception {
        withTables(true, (admin, source) -> {
            var repo = new JdbcSessionRepository(source); String id = repo.createSession().getId();
            try (Connection holder = source.getConnection(); Connection contender = source.getConnection();
                 PreparedStatement first = holder.prepareStatement("UPDATE light_session SET LAST_ACCESS_TIME = LAST_ACCESS_TIME WHERE SESSION_ID = ?");
                 PreparedStatement second = contender.prepareStatement("UPDATE light_session SET LAST_ACCESS_TIME = LAST_ACCESS_TIME WHERE SESSION_ID = ?")) {
                holder.setAutoCommit(false); holder.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try {
                    first.setString(1, id); assertEquals(0, first.executeUpdate());
                    try (Statement stmt = contender.createStatement()) { stmt.execute("SET innodb_lock_wait_timeout = 1"); }
                    second.setString(1, id);
                    assertEquals(1205, assertThrows(SQLException.class, second::executeUpdate).getErrorCode());
                } finally { holder.rollback(); }
            }
        });
    }

    @Test void offlineMaintenanceCommitsBoundedPagesAndResumes() throws Exception {
        withTables(true, (admin, source) -> {
            var repo = new JdbcSessionRepository(source); var valid = repo.createSession(); valid.setAttribute("a", "keep");
            try (Statement stmt = admin.createStatement()) {
                stmt.execute("INSERT INTO light_session_attributes VALUES ('orphan', 'a', X'01'), ('orphan', 'b', X'02'), ('another', 'a', X'03')");
            }
            try (Connection maintenance = source.getConnection()) {
                maintenance.setAutoCommit(false); assertEquals(2, JdbcSessionOrphanCleanup.removePage(maintenance, 2));
            }
            try (Connection maintenance = source.getConnection()) {
                maintenance.setAutoCommit(false); assertEquals(1, JdbcSessionOrphanCleanup.removePage(maintenance, 2));
                assertEquals(0, JdbcSessionOrphanCleanup.removePage(maintenance, 2));
            }
            assertEquals("keep", repo.findById(valid.getId()).getAttribute("a"));
        });
    }
}
