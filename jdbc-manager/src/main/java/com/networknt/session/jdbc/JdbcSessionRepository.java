
package com.networknt.session.jdbc;

import com.networknt.session.MapSession;
import com.networknt.session.Session;
import com.networknt.session.SessionRepository;
import com.networknt.session.jdbc.serializer.SerializationFailedException;
import com.networknt.session.jdbc.serializer.AttributeReadException;
import com.networknt.session.jdbc.serializer.DefaultDeserializer.DecodedAttribute;
import com.networknt.session.jdbc.serializer.ValueBytesConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.*;
import java.util.*;

/**
 * A {@link SessionRepository} implementation that uses
 * Database to store sessions in a relational database. This
 * implementation does not support publishing of session events.
 * <p>
 * An example of how to create a new instance can be seen below:
 * </p>
 * <pre class="code">
 *
 * JdbcSessionRepository sessionRepository =
 * new JdbcSessionRepository(dataSource);
 * </pre>
 *
 * <p>
 * Depending on your database, the table definition can be described as below:
 * </p>
 *
 * <pre class="code">
 * CREATE TABLE light_session (
 * session_id VARCHAR2(100) NOT NULL,
 * creation_time bigint NOT NULL,
 * last_access_time bigint NOT NULL,
 * max_inactive_interval int,
 * expiry_time bigint,
 * principal_name VARCHAR(100),
 * PRIMARY KEY(session_id)
 * );
 *
 * CREATE TABLE light_session_attributes (
 * session_id VARCHAR2(100) NOT NULL,
 * attribute_name VARCHAR(200) NOT NULL,
 * attribute_bytes BYTEA,
 * PRIMARY KEY(session_id, attribute_name)
 * );
 * </pre>
 */

public class JdbcSessionRepository implements
        SessionRepository<JdbcSessionRepository.JdbcSession> {

    private static final String CREATE_SESSION_QUERY =
            "INSERT INTO light_session(SESSION_ID, CREATION_TIME, LAST_ACCESS_TIME, MAX_INACTIVE_INTERVAL, EXPIRY_TIME, PRINCIPAL_NAME) " +
                    "VALUES (?, ?, ?, ?, NULL, ?)";

    private static final String CREATE_SESSION_ATTRIBUTE_QUERY =
            "INSERT INTO light_session_attributes(SESSION_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES) " +
                    "VALUES (?, ?, ?)";

    private static final String GET_SESSION_QUERY =
            "SELECT S.SESSION_ID, S.CREATION_TIME, S.LAST_ACCESS_TIME, S.MAX_INACTIVE_INTERVAL, SA.ATTRIBUTE_NAME, SA.ATTRIBUTE_BYTES " +
                    "FROM light_session S " +
                    "LEFT OUTER JOIN light_session_attributes SA ON S.SESSION_ID = SA.SESSION_ID " +
                    "WHERE S.SESSION_ID = ?";

    // Canonical liveness: getInt(NULL) historically means zero timeout, as in hydration.
    // Widen before multiplying so large int timeouts do not overflow in SQL.
    private static final String LIVE_SESSION_PREDICATE =
            "(COALESCE(MAX_INACTIVE_INTERVAL, 0) < 0 OR " +
                    "LAST_ACCESS_TIME + CAST(COALESCE(MAX_INACTIVE_INTERVAL, 0) AS DECIMAL(19, 0)) * 1000 > ?)";

    private static final String GET_LISTING_SESSION_QUERY = GET_SESSION_QUERY + " AND " + LIVE_SESSION_PREDICATE;

    private static final String GET_ALL_SESSION_QUERY =
            "SELECT SESSION_ID FROM light_session WHERE " + LIVE_SESSION_PREDICATE;

    private static final String UPDATE_SESSION_QUERY =
            "UPDATE light_session SET SESSION_ID = ?, LAST_ACCESS_TIME = ?, MAX_INACTIVE_INTERVAL = ?, EXPIRY_TIME = NULL, PRINCIPAL_NAME = ? " +
                    "WHERE SESSION_ID = ? AND " + LIVE_SESSION_PREDICATE;

    private static final String UPDATE_SESSION_ACCESS_TIME_QUERY =
            "UPDATE light_session SET LAST_ACCESS_TIME = ?, EXPIRY_TIME = NULL WHERE SESSION_ID = ? AND " + LIVE_SESSION_PREDICATE;

    private static final String UPDATE_SESSION_ATTRIBUTE_QUERY =
            "UPDATE light_session_attributes SET ATTRIBUTE_BYTES = ? " +
                    "WHERE SESSION_ID = ? " +
                    "AND ATTRIBUTE_NAME = ?";

    private static final String DELETE_SINGLE_ATTRIBUTE_QUERY =
            "DELETE FROM light_session_attributes " +
                    "WHERE SESSION_ID = ? " +
                    "AND ATTRIBUTE_NAME = ?";

    private static final String DELETE_SESSION_QUERY =
            "DELETE FROM light_session " +
                    "WHERE SESSION_ID = ?";

    private static final String LIST_SESSIONS_BY_PRINCIPAL_NAME_QUERY =
            "SELECT S.PRIMARY_ID, S.SESSION_ID, S.CREATION_TIME, S.LAST_ACCESS_TIME, S.MAX_INACTIVE_INTERVAL, SA.ATTRIBUTE_NAME, SA.ATTRIBUTE_BYTES " +
                    "FROM light_session S " +
                    "LEFT OUTER JOIN light_session_attributes SA ON S.PRIMARY_ID = SA.SESSION_PRIMARY_ID " +
                    "WHERE S.PRINCIPAL_NAME = ?";

    private static final int CLEANUP_BATCH_SIZE = 128;

    private static final String SELECT_EXPIRED_IDS_QUERY =
            "SELECT SESSION_ID FROM light_session WHERE NOT " + LIVE_SESSION_PREDICATE + " ORDER BY SESSION_ID";

    private static final String SELECT_EXPIRED_IDS_AFTER_QUERY =
            "SELECT SESSION_ID FROM light_session WHERE SESSION_ID > ? AND NOT " + LIVE_SESSION_PREDICATE + " ORDER BY SESSION_ID";

    private static final String DELETE_EXPIRED_PARENT_QUERY =
            "DELETE FROM light_session WHERE SESSION_ID = ? AND NOT " + LIVE_SESSION_PREDICATE;

    private static final String DELETE_ATTRIBUTES_FOR_SESSION_QUERY =
            "DELETE FROM light_session_attributes WHERE SESSION_ID = ?";

    private static final Logger logger = LoggerFactory.getLogger(JdbcSessionRepository.class);


    private volatile JdbcSessionDialect dialect;
    private String createSessionQuery;
    private String createSessionAttributeQuery;
    private String getSessionQuery;
    private String getAllSessionQuery;
    private String getListingSessionQuery;
    private String updateSessionQuery;
    private String updateSessionAttributeQuery;
    private String deleteSingleAttributeQuery;
    private String deleteSessionQuery;
    private String listSessionsByPrincipalNameQuery;
    private String selectExpiredIdsQuery;
    private String selectExpiredIdsAfterQuery;
    private String deleteExpiredParentQuery;
    private String deleteAttributesForSessionQuery;

    private Integer defaultMaxInactiveInterval = 30 * 60;


    private volatile long startTime;
    private ValueBytesConverter converter = new ValueBytesConverter();
    private final JdbcSessionLimits limits = new JdbcSessionLimits();

    private DataSource dataSource;

    public JdbcSessionRepository(DataSource dataSource) {
        this(dataSource, JdbcSessionDialect.configured());
    }

    /** Internal construction seam; deployment overrides use the databaseDialect JVM property. */
    JdbcSessionRepository(DataSource dataSource, JdbcSessionDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        prepareQueries();
    }


    /**
     * Set the maximum inactive interval in seconds between requests before newly created
     * sessions will be invalidated. A negative time indicates that the session will never
     * timeout. The default is 1800 (30 minutes).
     *
     * @param defaultMaxInactiveInterval the maximum inactive interval in seconds
     */
    public void setDefaultMaxInactiveInterval(int defaultMaxInactiveInterval) {
        this.defaultMaxInactiveInterval = defaultMaxInactiveInterval;
    }

    @Override
    public JdbcSession createSession() {
        JdbcSession session = new JdbcSession();
        if (this.defaultMaxInactiveInterval != null) {
            session.setMaxInactiveInterval(this.defaultMaxInactiveInterval);
        }
        save(session);
        return session;
    }

    public Map<String, Session> getSessions() {
        try {
            return readResource(dataSource.getConnection(), connection -> {
                List<String> ids = readIds(connection);
                return readSessions(connection, ids);
            }, "listing connection");
        } catch (SQLException e) {
            logger.error("SqlException:", e);
            return new HashMap<>();
        }
    }

    private List<String> readIds(Connection connection) throws SQLException {
        return readResource(connection.prepareStatement(getAllSessionQuery), stmt -> {
            stmt.setLong(1, System.currentTimeMillis());
            stmt.setMaxRows(limits.sessions + 1);
            return readResource(stmt.executeQuery(), this::collectIds, "listing ID cursor");
        }, "listing ID statement");
    }

    private List<String> collectIds(ResultSet rows) throws SQLException {
        List<String> ids = new ArrayList<>();
        while (rows.next()) {
            if (ids.size() >= limits.sessions) throw new ListingLimitException("sessions");
            ids.add(rows.getString("SESSION_ID"));
        }
        return ids;
    }

    private Map<String, Session> readSessions(Connection connection, List<String> ids) throws SQLException {
        // The ID cursor is closed before payload reads; no MARS requirement.
        return readResource(connection.prepareStatement(getListingSessionQuery), stmt -> {
            stmt.setMaxRows(limits.attributes + 1);
            ListingReadBudget budget = new ListingReadBudget();
            Map<String, Session> sessions = new HashMap<>();
            for (String id : ids) {
                stmt.setString(1, id);
                // Recheck expiry at hydration time; listing is a best-effort view.
                stmt.setLong(2, System.currentTimeMillis());
                Hydration state = readSessionHydration(stmt, budget);
                if (state != null) {
                    budget.admit(state.bytes);
                    sessions.put(id, state.session);
                }
            }
            return sessions;
        }, "listing session statement");
    }

    private Hydration readSessionHydration(PreparedStatement stmt, ListingReadBudget budget) throws SQLException {
        return readResource(stmt.executeQuery(), rows -> extractSingleSession(rows, budget), "session cursor");
    }

    private JdbcSession readLookupSession(Connection connection, String id) throws SQLException {
        return readResource(connection.prepareStatement(getSessionQuery), stmt -> {
            stmt.setString(1, id);
            stmt.setMaxRows(limits.attributes + 1);
            Hydration state = readSessionHydration(stmt, null);
            return state == null ? null : state.session;
        }, "lookup statement");
    }

    private final class ListingReadBudget {
        private int rows;
        private long bytes;
        void row() { if (++rows > limits.rows) throw new ListingLimitException("rows"); }
        void release(int size) { bytes -= size; }
        void admit(int size) {
            bytes += size;
            if (bytes > limits.listingBytes) throw new ListingLimitException("bytes");
        }
    }

    @FunctionalInterface
    private interface SqlRead<R, T> { T apply(R resource) throws SQLException; }

    private static <R extends AutoCloseable, T> T readResource(R resource, SqlRead<R, T> read, String operation) throws SQLException {
        Throwable failure = null;
        try { return read.apply(resource); }
        catch (SQLException | RuntimeException | Error e) { failure = e; throw e; }
        finally { cleanup(resource, failure, operation); }
    }

    private static void cleanup(AutoCloseable resource, Throwable failure, String operation) {
        if (resource == null) return;
        try { resource.close(); }
        catch (Exception e) { reportCleanup(failure, e, operation); }
    }

    private static void reportCleanup(Throwable failure, Exception cleanup, String operation) {
        if (failure != null) {
            if (failure != cleanup) failure.addSuppressed(cleanup);
        } else logger.warn("JDBC {} cleanup failed: {}", operation, cleanup.getClass().getSimpleName());
    }

    @Override
    public void save(JdbcSession session) {
        // Serialize pending values once. Unchanged objects need not remain serializable.
        Map<String, byte[]> attributes = new LinkedHashMap<>();
        try {
            if (session.getAttributeNames().size() > limits.attributes)
                throw new SessionBudgetExceededException(SessionBudgetExceededException.Reason.ATTRIBUTE_COUNT,
                        session.getAttributeNames().size(), limits.attributes);
            long pendingBytes = 0;
            Set<String> names = session.isNew() ? session.getAttributeNames() : session.getDelta().keySet();
            for (String name : names) {
                Object value = session.isNew() ? session.getAttribute(name) : session.getDelta().get(name);
                byte[] bytes = value == null ? null : converter.serializer(value);
                if (bytes != null) pendingBytes += bytes.length;
                if (pendingBytes > limits.sessionBytes)
                    throw new SessionBudgetExceededException(SessionBudgetExceededException.Reason.SESSION_BYTES,
                            pendingBytes, limits.sessionBytes);
                attributes.put(name, bytes);
            }
        } catch (SerializationFailedException e) {
            throw new IllegalStateException("Failed to serialize JDBC session attributes; pending changes retained", e);
        }

        boolean validate = !session.isNew() && !attributes.isEmpty();
        try {
            transact(validate ? Connection.TRANSACTION_READ_COMMITTED : null, connection -> {
                writeSession(connection, session, attributes);
                if (validate) validateStoredBudget(connection, session.getId());
                return null;
            });
            session.clearChangeFlags();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist JDBC session; pending changes retained", e);
        }
    }

    @FunctionalInterface
    private interface SqlTransaction<T> { T execute(Connection connection) throws SQLException; }

    /** Owns transaction outcome and borrowed-connection state for every multi-statement write. */
    private <T> T transact(Integer requiredIsolation, SqlTransaction<T> work) throws SQLException {
        boolean committed = false;
        T result = null;
        try (Connection connection = dataSource.getConnection()) {
            Integer isolationToRestore = null;
            boolean autoCommit = connection.getAutoCommit();
            boolean transactionEnded = true;
            Throwable failure = null;
            try {
                if (requiredIsolation != null) {
                    int isolation = connection.getTransactionIsolation();
                    if (isolation != requiredIsolation) {
                        isolationToRestore = isolation;
                        connection.setTransactionIsolation(requiredIsolation);
                    }
                }
                if (autoCommit) connection.setAutoCommit(false);
                transactionEnded = false;
                result = work.execute(connection);
                connection.commit();
                transactionEnded = true;
                committed = true;
            } catch (SQLException | RuntimeException | Error e) {
                failure = e;
                if (!transactionEnded) {
                    try { connection.rollback(); transactionEnded = true; }
                    catch (SQLException rollbackFailure) { e.addSuppressed(rollbackFailure); }
                }
                throw e;
            } finally {
                try {
                    if (transactionEnded) restoreConnection(connection, isolationToRestore, autoCommit);
                    else connection.abort(Runnable::run);
                } catch (SQLException cleanupFailure) {
                    reportCleanup(failure, cleanupFailure, "transaction connection state");
                }
            }
        } catch (SQLException e) {
            if (!committed) throw e;
            reportCleanup(null, e, "committed transaction connection");
        }
        return result;
    }

    private void writeSession(Connection connection, JdbcSession session, Map<String, byte[]> attributes) throws SQLException {
        boolean isNew = session.isNew();
        try (PreparedStatement stmt = connection.prepareStatement(isNew ? createSessionQuery : updateSessionQuery)) {
            stmt.setString(1, session.getId());
            if (isNew) {
                stmt.setLong(2, session.getCreationTime());
                stmt.setLong(3, session.getLastAccessedTime());
                stmt.setInt(4, session.getMaxInactiveInterval());
                stmt.setString(5, session.getPrincipalName());
            } else {
                stmt.setLong(2, session.getLastAccessedTime());
                stmt.setInt(3, session.getMaxInactiveInterval());
                stmt.setString(4, session.getPrincipalName());
                stmt.setString(5, session.getId());
                stmt.setLong(6, System.currentTimeMillis());
            }
            if (stmt.executeUpdate() != 1) throw new SQLException("JDBC session row is expired or missing; pending changes retained");
        }
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            String name = entry.getKey();
            byte[] bytes = entry.getValue();
            if (bytes == null) {
                try (PreparedStatement stmt = connection.prepareStatement(deleteSingleAttributeQuery)) {
                    stmt.setString(1, session.getId()); stmt.setString(2, name); stmt.executeUpdate();
                }
            } else if (isNew) {
                insertAttribute(connection, session.getId(), name, bytes);
            } else {
                int count;
                try (PreparedStatement stmt = connection.prepareStatement(updateSessionAttributeQuery)) {
                    dialect(connection).bind(stmt, 1, bytes);
                    stmt.setString(2, session.getId()); stmt.setString(3, name);
                    count = stmt.executeUpdate();
                }
                if (count == 0) insertAttribute(connection, session.getId(), name, bytes);
            }
        }
    }

    private static void restoreConnection(Connection connection, Integer isolation, boolean autoCommit) throws SQLException {
        SQLException failure = null;
        try { if (isolation != null) connection.setTransactionIsolation(isolation); }
        catch (SQLException e) { failure = e; }
        try { if (autoCommit) connection.setAutoCommit(true); }
        catch (SQLException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) {
            try { connection.abort(Runnable::run); }
            catch (SQLException abortFailure) { failure.addSuppressed(abortFailure); }
            throw failure;
        }
    }

    /** The parent update holds its row lock until this aggregate check and commit finish. */
    private void validateStoredBudget(Connection connection, String id) throws SQLException {
        JdbcSessionDialect capabilities = dialect(connection);
        String query = capabilities.budgetQuery;
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, id);
            try (ResultSet rows = stmt.executeQuery()) {
                if (capabilities.blobLengths) {
                    long count = 0;
                    long bytes = 0;
                    while (rows.next()) {
                        checkStoredBudget(++count, bytes);
                        Blob blob = rows.getBlob(1);
                        if (blob != null) {
                            try { bytes += blob.length(); } finally { blob.free(); }
                        }
                        checkStoredBudget(count, bytes);
                    }
                } else {
                    if (!rows.next()) throw new SQLException("Missing JDBC session budget result");
                    checkStoredBudget(rows.getLong("ATTRIBUTE_COUNT"), rows.getLong("SESSION_BYTES"));
                }
            }
        }
    }

    private void checkStoredBudget(long count, long bytes) {
        if (count > limits.attributes)
            throw new SessionBudgetExceededException(SessionBudgetExceededException.Reason.ATTRIBUTE_COUNT, count, limits.attributes);
        if (bytes > limits.sessionBytes)
            throw new SessionBudgetExceededException(SessionBudgetExceededException.Reason.SESSION_BYTES, bytes, limits.sessionBytes);
    }

    private JdbcSessionDialect dialect(Connection connection) throws SQLException {
        JdbcSessionDialect result = dialect;
        if (result == null) {
            // Concurrent first use may detect the same immutable capabilities more than once.
            result = JdbcSessionDialect.detect(connection.getMetaData());
            if (result == JdbcSessionDialect.BLOB)
                logger.warn("Unrecognized JDBC database; using BLOB locators for session budgets. Set light.session.jdbc.databaseDialect to override detection");
            dialect = result;
        }
        return result;
    }

    private void insertAttribute(Connection connection, String id, String name, byte[] bytes) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(createSessionAttributeQuery)) {
            stmt.setString(1, id); stmt.setString(2, name);
            dialect(connection).bind(stmt, 3, bytes);
            stmt.executeUpdate();
        }
    }


    @Override
    public JdbcSession findById(final String id) {
        JdbcSession session = null;
        try {
            session = readResource(dataSource.getConnection(), connection -> readLookupSession(connection, id), "lookup connection");
        } catch (SQLException e) {
            logger.error("SqlException:", e);
        }


        if (session != null) {
            if (session.isExpired()) {
                deleteById(id);
            } else {
                return session;
            }
        }
        return null;
    }

    @Override
    public void deleteById(final String id) {
        try {
            transact(null, connection -> {
                try (PreparedStatement parent = connection.prepareStatement(deleteSessionQuery)) {
                    parent.setString(1, id);
                    if (parent.executeUpdate() == 1) deleteAttributesForSession(connection, id);
                }
                return null;
            });
        } catch (SQLException e) { logger.error("SqlException:", e); }
    }

    private void deleteAttributesForSession(Connection connection, String id) throws SQLException {
        try (PreparedStatement attributes = connection.prepareStatement(deleteAttributesForSessionQuery)) {
            attributes.setString(1, id); attributes.executeUpdate();
        }
    }

    private record CleanupPage(String lastId, int deleted) { }

    public void cleanUpExpiredSessions() {
        long cutoff = System.currentTimeMillis();
        String after = null;
        int deleted = 0;
        try {
            do {
                String previous = after;
                CleanupPage page = transact(null, connection -> deleteExpiredPage(connection, previous, cutoff));
                deleted += page.deleted();
                after = page.lastId();
            } while (after != null);
            logger.debug("Cleaned up {} expired sessions", deleted);
        } catch (SQLException e) { logger.error("SqlException:", e); }
    }

    private CleanupPage deleteExpiredPage(Connection connection, String after, long cutoff) throws SQLException {
        List<String> ids = readResource(connection.prepareStatement(after == null ? selectExpiredIdsQuery : selectExpiredIdsAfterQuery), stmt -> {
            int parameter = 1;
            if (after != null) stmt.setString(parameter++, after);
            stmt.setLong(parameter, cutoff); stmt.setMaxRows(CLEANUP_BATCH_SIZE);
            return readResource(stmt.executeQuery(), rows -> {
                List<String> selected = new ArrayList<>();
                while (rows.next()) selected.add(rows.getString("SESSION_ID"));
                return selected;
            }, "cleanup ID cursor");
        }, "cleanup ID statement");
        if (ids.isEmpty()) return new CleanupPage(null, 0);
        int deleted = 0;
        // Only successful parent deletes authorize a keyed child delete in this transaction.
        try (PreparedStatement parent = connection.prepareStatement(deleteExpiredParentQuery);
             PreparedStatement attributes = connection.prepareStatement(deleteAttributesForSessionQuery)) {
            for (String id : ids) {
                parent.setString(1, id); parent.setLong(2, cutoff);
                if (parent.executeUpdate() != 1) continue;
                attributes.setString(1, id); attributes.executeUpdate(); deleted++;
            }
        }
        return new CleanupPage(ids.getLast(), deleted);
    }

    public void updateSessionLastAccessTime(final String id) {
        updateSessionAccess(id, System.currentTimeMillis());
    }

    private void updateSessionAccess(String id, long lastAccessedTime) {
        try {
            readResource(dataSource.getConnection(), connection ->
                    readResource(connection.prepareStatement(UPDATE_SESSION_ACCESS_TIME_QUERY), stmt -> {
                        stmt.setLong(1, lastAccessedTime); stmt.setString(2, id);
                        stmt.setLong(3, System.currentTimeMillis());
                        if (stmt.executeUpdate() != 1)
                            throw new IllegalStateException("JDBC session access refused: stored session is expired or missing");
                        return null;
                    }, "access statement"), "access connection");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to refresh JDBC session; in-memory access time unchanged", e);
        }
    }

    private String getQuery(String base) {
        return base;
    }

    private void prepareQueries() {
        this.createSessionQuery = getQuery(CREATE_SESSION_QUERY);
        this.createSessionAttributeQuery = getQuery(CREATE_SESSION_ATTRIBUTE_QUERY);
        this.getSessionQuery = getQuery(GET_SESSION_QUERY);
        this.getAllSessionQuery = getQuery(GET_ALL_SESSION_QUERY);
        this.getListingSessionQuery = getQuery(GET_LISTING_SESSION_QUERY);
        this.updateSessionQuery = getQuery(UPDATE_SESSION_QUERY);
        this.updateSessionAttributeQuery = getQuery(UPDATE_SESSION_ATTRIBUTE_QUERY);
        this.deleteSingleAttributeQuery = getQuery(DELETE_SINGLE_ATTRIBUTE_QUERY);
        this.deleteSessionQuery = getQuery(DELETE_SESSION_QUERY);
        this.listSessionsByPrincipalNameQuery =
                getQuery(LIST_SESSIONS_BY_PRINCIPAL_NAME_QUERY);
        this.selectExpiredIdsQuery = getQuery(SELECT_EXPIRED_IDS_QUERY);
        this.selectExpiredIdsAfterQuery = getQuery(SELECT_EXPIRED_IDS_AFTER_QUERY);
        this.deleteExpiredParentQuery = getQuery(DELETE_EXPIRED_PARENT_QUERY);
        this.deleteAttributesForSessionQuery = getQuery(DELETE_ATTRIBUTES_FOR_SESSION_QUERY);
    }

    private DecodedAttribute deserialize(ResultSet rs, int budget) throws SQLException, SerializationFailedException {
        InputStream stream = rs.getBinaryStream("ATTRIBUTE_BYTES");
        SerializationFailedException failure = null;
        try {
            return converter.deserializeStream(stream, budget);
        } catch (SerializationFailedException e) {
            failure = e;
            throw e;
        } finally {
            cleanup(stream, failure, "attribute stream");
        }
    }

    private static final class ListingLimitException extends IllegalStateException {
        private ListingLimitException(String reason) { super("JDBC session listing exceeds configured resource limits: " + reason); }
    }

    private static final class Hydration {
        JdbcSession session;
        int count;
        int bytes;
        Hydration(JdbcSession session) { this.session = session; }
        void reject(String id, String reason) {
            session = null;
            // Do not log bearer session IDs or arbitrary attribute names/contents.
            logger.warn("Ignoring JDBC session [idHash={}] reason={} attributes={} serializedBytes={}",
                    Integer.toHexString(id.hashCode()), reason, count, bytes);
        }
    }

    /**
     * Maps caller-owned rows with row/session-count caps and a hard byte cap on
     * retained candidates, including incomplete sessions. A later rejection cannot
     * rescue an extraction that already exceeded this retention cap. Prefer
     * getSessions() for complete-session validation before result-byte admission.
     * @deprecated Use {@link #getSessions()} for aggregate listing limits, or {@link #findById(String)}.
     */
    @Deprecated
    public List<JdbcSession> extractData(ResultSet rs) throws SQLException {
        Map<String, Hydration> states = new LinkedHashMap<>();
        ListingReadBudget budget = new ListingReadBudget();
        while (rs.next()) {
            budget.row();
            String id = rs.getString("SESSION_ID");
            Hydration state = states.get(id);
            if (state == null) {
                if (states.size() >= limits.sessions) throw new ListingLimitException("sessions");
                state = hydration(rs, id);
                states.put(id, state);
            }
            if (state.session != null) {
                int retained = state.bytes;
                hydrateAttribute(rs, state, id);
                if (state.session == null) budget.release(retained);
                else budget.admit(state.bytes - retained);
            }
        }
        List<JdbcSession> sessions = new ArrayList<>();
        for (Hydration state : states.values()) {
            if (state.session == null) continue;
            sessions.add(state.session);
        }
        return sessions;
    }

    private Hydration hydration(ResultSet rs, String id) throws SQLException {
        MapSession delegate = new MapSession(id);
        delegate.setMaxInactiveInterval(rs.getInt("MAX_INACTIVE_INTERVAL"));
        delegate.setCreationTime(rs.getLong("CREATION_TIME"));
        delegate.setLastAccessedTime(rs.getLong("LAST_ACCESS_TIME"));
        return new Hydration(new JdbcSession(delegate));
    }

    /** Lookup remains a single bounded streaming pass, without disk staging. */
    private Hydration extractSingleSession(ResultSet rs, ListingReadBudget budget) throws SQLException {
        Hydration state = null;
        while (rs.next()) {
            if (budget != null) budget.row();
            String id = rs.getString("SESSION_ID");
            if (state == null) state = hydration(rs, id);
            hydrateAttribute(rs, state, id);
            if (state.session == null) return null;
        }
        return state;
    }

    private void hydrateAttribute(ResultSet rs, Hydration state, String id) throws SQLException {
        String name = rs.getString("ATTRIBUTE_NAME");
        if (name == null) return;
        if (++state.count > limits.attributes) {
            state.reject(id, "ATTRIBUTE_COUNT");
            return;
        }
        try {
            DecodedAttribute attribute = deserialize(rs, limits.sessionBytes - state.bytes);
            state.bytes += attribute.bytes();
            state.session.delegate.setAttribute(name, attribute.value());
        } catch (SerializationFailedException e) {
            if (e instanceof AttributeReadException failure) state.bytes += failure.bytesRead;
            state.reject(id, e instanceof AttributeReadException failure ? failure.reason.name() : "STREAM_FAILURE");
        }
    }


    /**
     * The {@link Session} to use for {@link JdbcSessionRepository}.
     *
     * @author Vedran Pavic
     */
    final class JdbcSession implements Session {

        private final MapSession delegate;

        private boolean isNew;

        private boolean changed;

        private Map<String, Object> delta = new HashMap<>();

        JdbcSession() {
            this.delegate = new MapSession();
            this.isNew = true;
        }

        JdbcSession(MapSession delegate) {
            Objects.requireNonNull(delegate);
            this.delegate = delegate;
        }

        boolean isNew() {
            return this.isNew;
        }

        boolean isChanged() {
            return this.changed;
        }

        Map<String, Object> getDelta() {
            return this.delta;
        }

        void clearChangeFlags() {
            this.isNew = false;
            this.changed = false;
            this.delta.clear();
        }


        @Override
        public String getId() {
            return this.delegate.getId();
        }

        @Override
        public String changeSessionId() {
            this.changed = true;
            return this.delegate.changeSessionId();
        }

        @Override
        public Object getAttribute(String attributeName) {
            return this.delegate.getAttribute(attributeName);
        }

        @Override
        public Set<String> getAttributeNames() {
            return this.delegate.getAttributeNames();
        }

        @Override
        public Object setAttribute(String attributeName, Object attributeValue) {
            this.delegate.setAttribute(attributeName, attributeValue);
            this.delta.put(attributeName, attributeValue);
            this.changed = true;
            JdbcSessionRepository.this.save(this);
            return attributeValue;

        }

        @Override
        public Object removeAttribute(String attributeName) {
            Object object = this.delegate.removeAttribute(attributeName);
            this.delta.put(attributeName, null);
            this.changed = true;
            JdbcSessionRepository.this.save(this);
            return object;
        }

        @Override
        public long getCreationTime() {
            return this.delegate.getCreationTime();
        }

        @Override
        public void setLastAccessedTime(long lastAccessedTime) {
            if (!this.isNew) JdbcSessionRepository.this.updateSessionAccess(this.getId(), lastAccessedTime);
            this.delegate.setLastAccessedTime(lastAccessedTime);
            this.changed = true;
        }

        @Override
        public long getLastAccessedTime() {
            return this.delegate.getLastAccessedTime();
        }

        @Override
        public void setMaxInactiveInterval(int interval) {
            this.delegate.setMaxInactiveInterval(interval);
            this.changed = true;
        }

        @Override
        public int getMaxInactiveInterval() {
            return this.delegate.getMaxInactiveInterval();
        }


        public String getPrincipalName() {
            //TODO integrate woit
            return "light-user";
            //return PRINCIPAL_NAME_RESOLVER.resolvePrincipal(this);
        }

        @Override
        public boolean isExpired() {
            return this.delegate.isExpired();
        }

    }



}
