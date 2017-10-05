
package com.networknt.session.jdbc;

import java.io.ByteArrayInputStream;
import java.sql.*;
import java.util.*;

import com.networknt.session.MapSession;
import com.networknt.session.Session;
import javax.sql.DataSource;

import com.networknt.session.SessionRepository;
import com.networknt.session.jdbc.serializer.SerializationFailedException;
import com.networknt.session.jdbc.serializer.ValueBytesConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link SessionRepository} implementation that uses
 * Database to store sessions in a relational database. This
 * implementation does not support publishing of session events.
 * <p>
 * An example of how to create a new instance can be seen below:
 *
 * <pre class="code">
 *
 * JdbcSessionRepository sessionRepository =
 *         new JdbcSessionRepository(dataSource);
 * </pre>
 *
 *
 * Depending on your database, the table definition can be described as below:
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

	private DataSource dataSource;

	public JdbcSessionRepository(DataSource dataSource) {
		this.dataSource = dataSource;
		prepareQueries();
	}


	/**
	 * Set the maximum inactive interval in seconds between requests before newly created
	 * sessions will be invalidated. A negative time indicates that the session will never
	 * timeout. The default is 1800 (30 minutes).
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
		if (session.isNew()) {
			try (final Connection connection = dataSource.getConnection()) {
				connection.setAutoCommit(false);

				PreparedStatement stmt = connection.prepareStatement(this.createSessionQuery);
				stmt.setString(1, session.getId());
				stmt.setLong(2, session.getCreationTime());
				stmt.setLong(3, session.getLastAccessedTime());
				stmt.setInt(4, session.getMaxInactiveInterval());
				stmt.setLong(5, session.getExpiryTime());
				stmt.setString(6, session.getPrincipalName());
				int count = stmt.executeUpdate();
				if (!session.getAttributeNames().isEmpty()) {
					final List<String> attributeNames = new ArrayList<>(session.getAttributeNames());
					try (PreparedStatement psAtt = connection.prepareStatement(this.createSessionAttributeQuery)) {
						for (String attributeName : attributeNames) {
							psAtt.setString(1, session.getId());
							psAtt.setString(2, attributeName);
							serialize(psAtt, 3, session.getAttribute(attributeName));
							psAtt.addBatch();
						}
						psAtt.executeBatch();
					}
				}
				connection.commit();

				if (count != 1) {
					logger.error("Failed to insert session: {}", session.getId());
				}
			} catch (SQLException e) {
				logger.error("SqlException:", e);
			}
		}
		else {
			try (final Connection connection = dataSource.getConnection()) {

				connection.setAutoCommit(false);
				PreparedStatement stmt = connection.prepareStatement(this.updateSessionQuery);
				stmt.setString(1, session.getId());
				stmt.setLong(2, session.getLastAccessedTime());
				stmt.setInt(3, session.getMaxInactiveInterval());
				stmt.setLong(4, session.getExpiryTime());
				stmt.setString(5, session.getPrincipalName());
				stmt.setString(6, session.getId());
				int count = stmt.executeUpdate();

				Map<String, Object> delta = session.getDelta();
				if (!delta.isEmpty()) {
					for (final Map.Entry<String, Object> entry : delta.entrySet()) {
						if (entry.getValue() == null) {
							try (PreparedStatement psAtt = connection.prepareStatement(this.deleteSessionAttributeQuery)) {
								psAtt.setString(1, session.getId());
								psAtt.setString(2, entry.getKey());
								psAtt.executeUpdate();
							}
						} else {
							int updatedCount = 0;
							try (PreparedStatement psAtt = connection.prepareStatement(this.updateSessionAttributeQuery)) {
								serialize(psAtt, 1, entry.getValue());
								psAtt.setString(2, session.getId());
								psAtt.setString(3, entry.getKey());
								updatedCount = psAtt.executeUpdate();
							}
							if (updatedCount == 0) {
								try (PreparedStatement psAtt = connection.prepareStatement(this.createSessionAttributeQuery)) {
									psAtt.setString(1, session.getId());
									psAtt.setString(2, entry.getKey());
									serialize(psAtt, 3, entry.getValue());

									psAtt.executeUpdate();
								}
							}

						}
					}
				}
				connection.commit();
			} catch (SQLException e) {
				logger.error("SqlException:", e);
			}
		}
		session.clearChangeFlags();
	}


	@Override
	public JdbcSession findById(final String id) {
		JdbcSession session = null;
		try (final Connection connection = dataSource.getConnection()) {

			PreparedStatement stmt = connection.prepareStatement(this.getSessionQuery);
			stmt.setString(1, id);
			ResultSet rs = stmt.executeQuery();
			List<JdbcSession> sessions = extractData(rs);
			if (!sessions.isEmpty()) {
				session = sessions.get(0);
			}

		} catch (SQLException e) {
			logger.error("SqlException:", e);
		}


		if (session != null) {
			if (session.isExpired()) {
				deleteById(id);
			}
			else {
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
		int deletedCount= 0;
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
		int count= 0;
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

	private void serialize(PreparedStatement ps, int paramIndex, Object attributeValue)
			throws SQLException {
		try {
			byte[]  bytes = converter.serializer(attributeValue);
			if (bytes != null) {
				ps.setBlob(paramIndex, new ByteArrayInputStream(bytes), bytes.length);
			}
			else {
				ps.setBlob(paramIndex, (Blob) null);
			}
		} catch (SerializationFailedException e) {
			throw new SQLException("Failed to serialize object ", e);
		}

	}

	private Object deserialize(ResultSet rs, String columnName)
			throws SQLException {
		byte[]  result = rs.getBytes(columnName);
		try {
			return converter.deserialize(result);
		} catch (SerializationFailedException e) {
			throw new SQLException("Failed to serialize object ", e);
		}
	}

	public List<JdbcSession> extractData(ResultSet rs) throws SQLException {
		List<JdbcSession> sessions = new ArrayList<>();
		while (rs.next()) {
			String id = rs.getString("SESSION_ID");
			JdbcSession session;
			if (sessions.size() > 0 && getLast(sessions).getId().equals(id)) {
				session = getLast(sessions);
			}
			else {
				MapSession delegate = new MapSession(rs.getString("SESSION_ID"));
				delegate.setMaxInactiveInterval(rs.getInt("MAX_INACTIVE_INTERVAL"));
				delegate.setCreationTime(rs.getLong("CREATION_TIME"));
				delegate.setLastAccessedTime(rs.getLong("LAST_ACCESS_TIME"));
				session = new JdbcSession(delegate);
			}
			String attributeName = rs.getString("ATTRIBUTE_NAME");
			if (attributeName != null) {
				session.setAttribute(attributeName, deserialize(rs, "ATTRIBUTE_BYTES"));
			}
			sessions.add(session);
		}
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
			return getLastAccessedTime()+getMaxInactiveInterval();
		}

		public String getId() {
			return this.delegate.getId();
		}

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
			Object object =  this.delegate.removeAttribute(attributeName);
			this.delta.put(attributeName, null);
			this.changed = true;
			JdbcSessionRepository.this.save(this);
			return object;
		}

		public long getCreationTime() {
			return this.delegate.getCreationTime();
		}

		@Override
		public void setLastAccessedTime(long lastAccessedTime) {
			this.delegate.setLastAccessedTime(lastAccessedTime);
			this.changed = true;
			JdbcSessionRepository.this.updateSessionLastAccessTime(this.getId());
		}

		public long getLastAccessedTime() {
			return this.delegate.getLastAccessedTime();
		}

		public void setMaxInactiveInterval(int interval) {
			this.delegate.setMaxInactiveInterval(interval);
			this.changed = true;
		}

		public int getMaxInactiveInterval() {
			return this.delegate.getMaxInactiveInterval();
		}


		public String getPrincipalName() {
			//TODO integrate woit
			return "light-user";
			//return PRINCIPAL_NAME_RESOLVER.resolvePrincipal(this);
		}

		public boolean isExpired() {
			return this.delegate.isExpired();
		}

	}


		private JdbcSession getLast(List<JdbcSession> sessions) {
			return sessions.get(sessions.size() - 1);
		}


}
