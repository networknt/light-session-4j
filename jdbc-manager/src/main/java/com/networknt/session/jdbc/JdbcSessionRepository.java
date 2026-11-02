
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
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
                    "VALUES (?, ?, ?, ?, ?, ?)";

    private static final String SESSION_BUDGET_QUERY =
            "SELECT COUNT(*) AS ATTRIBUTE_COUNT, COALESCE(SUM(OCTET_LENGTH(ATTRIBUTE_BYTES)), 0) AS SESSION_BYTES " +
                    "FROM light_session_attributes WHERE SESSION_ID = ?";
    private static final String ORACLE_SESSION_BUDGET_QUERY =
            "SELECT COUNT(*) AS ATTRIBUTE_COUNT, COALESCE(SUM(DBMS_LOB.GETLENGTH(ATTRIBUTE_BYTES)), 0) AS SESSION_BYTES " +
                    "FROM light_session_attributes WHERE SESSION_ID = ?";

    private static final String CREATE_SESSION_ATTRIBUTE_QUERY =
            "INSERT INTO light_session_attributes(SESSION_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES) " +
                    "VALUES (?, ?, ?)";

    private static final String GET_SESSION_QUERY =
            "SELECT S.SESSION_ID, S.CREATION_TIME, S.LAST_ACCESS_TIME, S.MAX_INACTIVE_INTERVAL, SA.ATTRIBUTE_NAME, SA.ATTRIBUTE_BYTES " +
                    "FROM light_session S " +
                    "LEFT OUTER JOIN light_session_attributes SA ON S.SESSION_ID = SA.SESSION_ID " +
                    "WHERE S.SESSION_ID = ?";

    private static final String GET_ALL_SESSION_QUERY =
            "SELECT S.SESSION_ID, S.CREATION_TIME, S.LAST_ACCESS_TIME, S.MAX_INACTIVE_INTERVAL, SA.ATTRIBUTE_NAME, SA.ATTRIBUTE_BYTES " +
                    "FROM light_session S " +
                    "LEFT OUTER JOIN light_session_attributes SA ON S.SESSION_ID = SA.SESSION_ID " +
                    "WHERE EXPIRY_TIME > ?";

    private static final String UPDATE_SESSION_QUERY =
            "UPDATE light_session SET SESSION_ID = ?, LAST_ACCESS_TIME = ?, MAX_INACTIVE_INTERVAL = ?, EXPIRY_TIME = ?, PRINCIPAL_NAME = ? " +
                    "WHERE SESSION_ID = ?";

    private static final String UPDATE_SESSION_ACCESS_TIME_QUERY =
            "UPDATE light_session SET  LAST_ACCESS_TIME = ? " +
                    "WHERE SESSION_ID = ?";


    private static final String UPDATE_SESSION_ATTRIBUTE_QUERY =
            "UPDATE light_session_attributes SET ATTRIBUTE_BYTES = ? " +
                    "WHERE SESSION_ID = ? " +
                    "AND ATTRIBUTE_NAME = ?";

    private static final String DELETE_SESSION_ATTRIBUTE_QUERY =
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

    private static final String DELETE_SESSIONS_BY_EXPIRY_TIME_QUERY =
            "DELETE FROM light_session " +
                    "WHERE EXPIRY_TIME < ?";

    private static final Logger logger = LoggerFactory.getLogger(JdbcSessionRepository.class);


    private String sessionBudgetQuery;
    private String oracleSessionBudgetQuery;
    private String createSessionQuery;
    private String createSessionAttributeQuery;
    private String getSessionQuery;
    private String getAllSessionQuery;
    private String updateSessionQuery;
    private String updateSessionAttributeQuery;
    private String deleteSessionAttributeQuery;
    private String deleteSessionQuery;
    private String listSessionsByPrincipalNameQuery;
    private String deleteSessionsByExpiryTimeQuery;

    private Integer defaultMaxInactiveInterval = 30 * 60;


    private volatile long startTime;
    private ValueBytesConverter converter = new ValueBytesConverter();
    private final JdbcSessionLimits limits = new JdbcSessionLimits();

    private DataSource dataSource;

    public JdbcSessionRepository(DataSource dataSource) {
        this.dataSource = dataSource;
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
        Map<String, Session> sessionMap = new HashMap<>();
        try (final Connection connection = dataSource.getConnection()) {

            PreparedStatement stmt = connection.prepareStatement(this.getAllSessionQuery);
            stmt.setLong(1, System.currentTimeMillis());
            ResultSet rs = stmt.executeQuery();
            List<JdbcSession> sessions = extractData(rs);
            if (!sessions.isEmpty()) {

                for (JdbcSession session : sessions) {
                    sessionMap.put(session.getId(), session);
                }
            }

        } catch (SQLException e) {
            logger.error("SqlException:", e);
        }
        return sessionMap;
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

        boolean committed = false;
        try (Connection connection = dataSource.getConnection()) {
            int isolation = connection.getTransactionIsolation();
            boolean autoCommit = connection.getAutoCommit();
            Throwable failure = null;
            boolean transactionStarted = false;
            boolean transactionEnded = true;
            try {
                // The statement after acquiring the parent lock must see the previous writer.
                if (isolation != Connection.TRANSACTION_READ_COMMITTED)
                    connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                if (autoCommit) connection.setAutoCommit(false);
                transactionStarted = true;
                transactionEnded = false;
                boolean validate = !session.isNew() && !attributes.isEmpty();
                writeSession(connection, session, attributes);
                if (validate) validateStoredBudget(connection, session.getId());
                connection.commit();
                transactionEnded = true;
                committed = true;
                session.clearChangeFlags();
            } catch (SQLException | RuntimeException e) {
                failure = e;
                if (transactionStarted) {
                    try { connection.rollback(); transactionEnded = true; }
                    catch (SQLException rollbackFailure) { e.addSuppressed(rollbackFailure); }
                }
                throw e;
            } finally {
                try {
                    if (transactionEnded) restoreConnection(connection, isolation, autoCommit);
                    // Changing isolation/autocommit with an unresolved transaction could commit it.
                    else connection.abort(Runnable::run);
                } catch (SQLException cleanupFailure) {
                    if (failure != null) failure.addSuppressed(cleanupFailure);
                    else throw cleanupFailure;
                }
            }
        } catch (SQLException e) {
            if (committed) {
                logger.warn("Committed JDBC session; connection cleanup failed: {}", e.getClass().getSimpleName());
            } else {
                throw new IllegalStateException("Failed to persist JDBC session; pending changes retained", e);
            }
        }
    }

    private void writeSession(Connection connection, JdbcSession session, Map<String, byte[]> attributes) throws SQLException {
        boolean isNew = session.isNew();
        try (PreparedStatement stmt = connection.prepareStatement(isNew ? createSessionQuery : updateSessionQuery)) {
            stmt.setString(1, session.getId());
            if (isNew) {
                stmt.setLong(2, session.getCreationTime());
                stmt.setLong(3, session.getLastAccessedTime());
                stmt.setInt(4, session.getMaxInactiveInterval());
                stmt.setLong(5, session.getExpiryTime());
                stmt.setString(6, session.getPrincipalName());
            } else {
                stmt.setLong(2, session.getLastAccessedTime());
                stmt.setInt(3, session.getMaxInactiveInterval());
                stmt.setLong(4, session.getExpiryTime());
                stmt.setString(5, session.getPrincipalName());
                stmt.setString(6, session.getId());
            }
            if (stmt.executeUpdate() != 1) throw new SQLException("JDBC session row was not written");
        }
        for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
            String name = entry.getKey();
            byte[] bytes = entry.getValue();
            if (bytes == null) {
                try (PreparedStatement stmt = connection.prepareStatement(deleteSessionAttributeQuery)) {
                    stmt.setString(1, session.getId()); stmt.setString(2, name); stmt.executeUpdate();
                }
            } else if (isNew) {
                insertAttribute(connection, session.getId(), name, bytes);
            } else {
                int count;
                try (PreparedStatement stmt = connection.prepareStatement(updateSessionAttributeQuery)) {
                    stmt.setBinaryStream(1, new ByteArrayInputStream(bytes), bytes.length);
                    stmt.setString(2, session.getId()); stmt.setString(3, name);
                    count = stmt.executeUpdate();
                }
                if (count == 0) insertAttribute(connection, session.getId(), name, bytes);
            }
        }
    }

    private static void restoreConnection(Connection connection, int isolation, boolean autoCommit) throws SQLException {
        SQLException failure = null;
        try { connection.setTransactionIsolation(isolation); }
        catch (SQLException e) { failure = e; }
        try { connection.setAutoCommit(autoCommit); }
        catch (SQLException e) {
            if (failure == null) failure = e;
            else failure.addSuppressed(e);
        }
        if (failure != null) throw failure;
    }

    /** The parent update holds its row lock until this aggregate check and commit finish. */
    private void validateStoredBudget(Connection connection, String id) throws SQLException {
        String query = "Oracle".equals(connection.getMetaData().getDatabaseProductName())
                ? oracleSessionBudgetQuery : sessionBudgetQuery;
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, id);
            try (ResultSet rows = stmt.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing JDBC session budget result");
                long count = rows.getLong("ATTRIBUTE_COUNT");
                long bytes = rows.getLong("SESSION_BYTES");
                if (count > limits.attributes)
                    throw new SessionBudgetExceededException(SessionBudgetExceededException.Reason.ATTRIBUTE_COUNT, count, limits.attributes);
                if (bytes > limits.sessionBytes)
                    throw new SessionBudgetExceededException(SessionBudgetExceededException.Reason.SESSION_BYTES, bytes, limits.sessionBytes);
            }
        }
    }

    private void insertAttribute(Connection connection, String id, String name, byte[] bytes) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(createSessionAttributeQuery)) {
            stmt.setString(1, id); stmt.setString(2, name);
            stmt.setBinaryStream(3, new ByteArrayInputStream(bytes), bytes.length);
            stmt.executeUpdate();
        }
    }


    @Override
    public JdbcSession findById(final String id) {
        JdbcSession session = null;
        try (final Connection connection = dataSource.getConnection()) {

            try (PreparedStatement stmt = connection.prepareStatement(this.getSessionQuery)) {
                stmt.setString(1, id);
                stmt.setMaxRows(limits.attributes + 1);
                try (ResultSet rs = stmt.executeQuery()) {
                    List<JdbcSession> sessions = extractData(rs, false);
                    if (!sessions.isEmpty()) session = sessions.get(0);
                }
            }

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
        try (final Connection connection = dataSource.getConnection()) {
            try (PreparedStatement psAtt = connection.prepareStatement(this.deleteSessionQuery)) {
                psAtt.setString(1, id);
                psAtt.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("SqlException:", e);
        }
    }

    public void cleanUpExpiredSessions() {
        int deletedCount = 0;
        try (final Connection connection = dataSource.getConnection()) {
            try (PreparedStatement psAtt = connection.prepareStatement(this.deleteSessionsByExpiryTimeQuery)) {
                psAtt.setLong(1, System.currentTimeMillis());
                psAtt.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("SqlException:", e);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Cleaned up " + deletedCount + " expired sessions");
        }
    }

    public void updateSessionLastAccessTime(final String id) {
        int count = 0;
        try (final Connection connection = dataSource.getConnection()) {
            try (PreparedStatement psAtt = connection.prepareStatement(UPDATE_SESSION_ACCESS_TIME_QUERY)) {
                psAtt.setLong(1, System.currentTimeMillis());
                psAtt.setString(2, id);
                psAtt.executeUpdate();
            }
        } catch (SQLException e) {
            logger.error("SqlException:", e);
        }

        if (logger.isDebugEnabled()) {
            logger.debug("session access change " + id + " to current time");
        }
    }

    private String getQuery(String base) {
        return base;
    }

    private void prepareQueries() {
        this.sessionBudgetQuery = getQuery(SESSION_BUDGET_QUERY);
        this.oracleSessionBudgetQuery = getQuery(ORACLE_SESSION_BUDGET_QUERY);
        this.createSessionQuery = getQuery(CREATE_SESSION_QUERY);
        this.createSessionAttributeQuery = getQuery(CREATE_SESSION_ATTRIBUTE_QUERY);
        this.getSessionQuery = getQuery(GET_SESSION_QUERY);
        this.getAllSessionQuery = getQuery(GET_ALL_SESSION_QUERY);
        this.updateSessionQuery = getQuery(UPDATE_SESSION_QUERY);
        this.updateSessionAttributeQuery = getQuery(UPDATE_SESSION_ATTRIBUTE_QUERY);
        this.deleteSessionAttributeQuery = getQuery(DELETE_SESSION_ATTRIBUTE_QUERY);
        this.deleteSessionQuery = getQuery(DELETE_SESSION_QUERY);
        this.listSessionsByPrincipalNameQuery =
                getQuery(LIST_SESSIONS_BY_PRINCIPAL_NAME_QUERY);
        this.deleteSessionsByExpiryTimeQuery =
                getQuery(DELETE_SESSIONS_BY_EXPIRY_TIME_QUERY);
    }

    private DecodedAttribute deserialize(ResultSet rs, int budget, AttributeReadException.Reason reason) throws SQLException, SerializationFailedException {
        InputStream stream = rs.getBinaryStream("ATTRIBUTE_BYTES");
        SerializationFailedException failure = null;
        try {
            return converter.deserializeStream(stream, budget, reason);
        } catch (SerializationFailedException e) {
            failure = e;
            throw e;
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (IOException closeFailure) {
                    if (failure != null) failure.addSuppressed(closeFailure);
                    else logger.warn("JDBC attribute stream cleanup failed: {}", closeFailure.getClass().getSimpleName());
                }
            }
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

    public List<JdbcSession> extractData(ResultSet rs) throws SQLException {
        return extractData(rs, true);
    }

    List<JdbcSession> extractData(ResultSet rs, boolean listing) throws SQLException {
        Map<String, Hydration> states = new LinkedHashMap<>();
        long listingBytes = 0;
        int rows = 0;
        while (rs.next()) {
            if (listing && ++rows > limits.rows) throw new ListingLimitException("rows");
            // A lookup can contain at most maxAttributes valid rows plus one rejection row.
            if (!listing && ++rows > (long) limits.attributes + 1) return Collections.emptyList();
            String id = rs.getString("SESSION_ID");
            Hydration state = states.get(id);
            if (state == null) {
                if (listing && states.size() >= limits.sessions) throw new ListingLimitException("sessions");
                MapSession delegate = new MapSession(id);
                delegate.setMaxInactiveInterval(rs.getInt("MAX_INACTIVE_INTERVAL"));
                delegate.setCreationTime(rs.getLong("CREATION_TIME"));
                delegate.setLastAccessedTime(rs.getLong("LAST_ACCESS_TIME"));
                state = new Hydration(new JdbcSession(delegate));
                states.put(id, state);
            }
            if (state.session == null) {
                if (!listing) return Collections.emptyList();
                continue;
            }
            String name = rs.getString("ATTRIBUTE_NAME");
            if (name == null) continue;
            if (++state.count > limits.attributes) {
                listingBytes -= state.bytes;
                state.reject(id, "ATTRIBUTE_COUNT");
                if (!listing) return Collections.emptyList();
                continue;
            }
            try {
                // Intrinsic validity is independent of listing order and remaining listing space.
                DecodedAttribute attribute = deserialize(rs, limits.sessionBytes - state.bytes,
                        AttributeReadException.Reason.SESSION_BYTES);
                if (listing && listingBytes + attribute.bytes() > limits.listingBytes)
                    throw new ListingLimitException("bytes");
                listingBytes += attribute.bytes();
                state.bytes += attribute.bytes();
                state.session.delegate.setAttribute(name, attribute.value());
            } catch (AttributeReadException e) {
                listingBytes -= state.bytes;
                state.bytes += e.bytesRead;
                state.reject(id, e.reason.name());
                if (!listing) return Collections.emptyList();
            } catch (SerializationFailedException e) {
                listingBytes -= state.bytes;
                state.reject(id, "STREAM_FAILURE");
                if (!listing) return Collections.emptyList();
            }
        }
        List<JdbcSession> sessions = new ArrayList<>();
        for (Hydration state : states.values()) if (state.session != null) sessions.add(state.session);
        return sessions;
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


        public long getExpiryTime() {
            return getLastAccessedTime() + getMaxInactiveInterval();
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
            this.delegate.setLastAccessedTime(lastAccessedTime);
            this.changed = true;
            JdbcSessionRepository.this.updateSessionLastAccessTime(this.getId());
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
