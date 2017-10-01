
package com.networknt.session.jdbc;

import com.networknt.session.SessionRepository;

/**
 * A {@link SessionRepository} implementation that uses
 * Database to store sessions in a relational database. This
 * implementation does not support publishing of session events.
 * <p>
 * An example of how to create a new instance can be seen below:
 *
 * <pre class="code">
 * JdbcTemplate jdbcTemplate = new JdbcTemplate();
 *
 * // ... configure jdbcTemplate ...
 *
 * PlatformTransactionManager transactionManager = new DataSourceTransactionManager();
 *
 * // ... configure transactionManager ...
 *
 * JdbcSessionManager sessionRepository =
 *         new JdbcSessionManager(jdbcTemplate, transactionManager);
 * </pre>
 *
 *
 * Depending on your database, the table definition can be described as below:
 *
 * <pre class="code">
 * CREATE TABLE SPRING_SESSION (
 *   PRIMARY_ID CHAR(36) NOT NULL,
 *   SESSION_ID CHAR(36) NOT NULL,
 *   CREATION_TIME BIGINT NOT NULL,
 *   LAST_ACCESS_TIME BIGINT NOT NULL,
 *   MAX_INACTIVE_INTERVAL INT NOT NULL,
 *   EXPIRY_TIME BIGINT NOT NULL,
 *   PRINCIPAL_NAME VARCHAR(100),
 *   CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
 * );
 *
 * CREATE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
 * CREATE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (EXPIRY_TIME);
 *
 * CREATE TABLE SPRING_SESSION_ATTRIBUTES (
 *  SESSION_PRIMARY_ID CHAR(36) NOT NULL,
 *  ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
 *  ATTRIBUTE_BYTES BYTEA NOT NULL,
 *  CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
 *  CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
 * );
 *
 * CREATE INDEX SPRING_SESSION_ATTRIBUTES_IX1 ON SPRING_SESSION_ATTRIBUTES (SESSION_PRIMARY_ID);
 * </pre>
 */
public class JdbcSessionManager {} /*implements
		FindByIndexNameSessionRepository<JdbcSession>, SessionManager{


	public final AttachmentKey<JdbcSession> NEW_SESSION = AttachmentKey.create(JdbcSession.class);
	public static final String DEPLOY_NAME = "LIGHT-SESSION";


	private static final String SECURITY_CONTEXT = "SECURITY_CONTEXT";

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

	private static final Logger logger = LoggerFactory.getLogger(JdbcSessionManager.class);

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
	private final SessionListeners sessionListeners = new SessionListeners();

	private Integer defaultMaxInactiveInterval = 30 * 60;
	private SessionConfig config;

	private final String deploymentName;
	private final SessionStatistics sessionStatistics = null;
	private boolean statisticsEnabled = false;

	private volatile long startTime;
	private ValueBytesConverter converter = new ValueBytesConverter();

	private DataSource dataSource;

	public JdbcSessionManager(DataSource dataSource) {
		this(dataSource,  new SessionCookieConfig());
	}

	public JdbcSessionManager(DataSource dataSource, SessionConfig config) {
		this(dataSource, config, DEPLOY_NAME, null);
	}

	public JdbcSessionManager(DataSource dataSource, SessionConfig config, String deploymentName,  SessionStatistics sessionStatistics) {
		this.dataSource = dataSource;
		converter = new ValueBytesConverter();
		this.config = config;
		this.deploymentName = deploymentName;
		if (sessionStatistics!=null) {
			this.statisticsEnabled = true;
		}
		prepareQueries();
	}


	public SessionConfig getConfig() {
		return config;
	}

	public void setConfig(SessionConfig config) {
		this.config = config;
	}

	public JdbcSession createSession() {
		JdbcSession session = new JdbcSession(this);
		if (this.defaultMaxInactiveInterval != null) {
			session.setMaxInactiveInterval(this.defaultMaxInactiveInterval);
		}
		return session;
	}

	@Override
	public Session createSession(final HttpServerExchange serverExchange, final SessionConfig config) {
		if (config == null) {
			throw UndertowMessages.MESSAGES.couldNotFindSessionCookieConfig();
		}
		String sessionID = config.findSessionId(serverExchange);
		if (sessionID == null) {
			int count = 0;
			SecureRandomSessionIdGenerator sessionIdGenerator = new SecureRandomSessionIdGenerator();
			sessionID = sessionIdGenerator.createSessionId();
		}

		JdbcSession session = new JdbcSession(this, sessionID);

		UndertowLogger.SESSION_LOGGER.debugf("Created session with id %s for exchange %s", sessionID, serverExchange);

		config.setSessionId(serverExchange, session.getId());
		session.setLastAccessedTime(System.currentTimeMillis());

		sessionListeners.sessionCreated(session, serverExchange);
		serverExchange.putAttachment(NEW_SESSION, session);

		if(statisticsEnabled) {
			sessionStatistics.setCreatedSessionCount(sessionStatistics.getCreatedSessionCount()+1);
			long highest;
			int sessionSize;
			highest = sessionStatistics.getHighestSessionCount();
			if (highest<=getSessions().size()) {
				sessionStatistics.setHighestSessionCount(getSessions().size());
			}
		}
		return session;

	}

	public SessionListeners getSessionListeners() {
		return sessionListeners;
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
								psAtt.setString(1, session.getId());
								psAtt.setString(2, entry.getKey());
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
	public Session getSession(final HttpServerExchange serverExchange, final SessionConfig config) {
		if (serverExchange != null) {
			JdbcSession newSession = serverExchange.getAttachment(NEW_SESSION);
			if(newSession != null) {
				return newSession;
			}
		}
		String sessionId = config.findSessionId(serverExchange);
		JdbcSession session =  getSession(sessionId);
		if(session != null) {
			session.getDelegate().requestStarted(serverExchange);
		}
		return session;
	}


	@Override
	public JdbcSession getSession(String id) {
		return findById(id);
	}

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


	public void removeSession(final HttpServerExchange serverExchange, String id) {
		deleteById(id);
		if (serverExchange != null) {
			getConfig().clearSession(serverExchange, id);
		}

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

	public Map<String, JdbcSession> findByIndexNameAndIndexValue(String indexName,
			final String indexValue) {
		if (!PRINCIPAL_NAME_INDEX_NAME.equals(indexName)) {
			return Collections.emptyMap();
		}


		try (final Connection connection = dataSource.getConnection()) {

			PreparedStatement stmt = connection.prepareStatement(this.listSessionsByPrincipalNameQuery);
			stmt.setString(1, indexValue);
			ResultSet rs = stmt.executeQuery();
			List<JdbcSession> sessions = extractData(rs);

			if (sessions.isEmpty()) {
				return null;
			}

			Map<String, JdbcSession> sessionMap = new HashMap<>(
					sessions.size());
			for (JdbcSession session : sessions) {
				sessionMap.put(session.getId(), session);
			}
			return sessionMap;
		} catch (SQLException e) {
			logger.error("SqlException:", e);
		}
		return null;

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


	@Override
	public String getDeploymentName() {
		return this.deploymentName;
	}

	@Override
	public void start() {
		if (sessionStatistics!=null && sessionStatistics.getStartTime()<=0) {
			sessionStatistics.setStartTime(System.currentTimeMillis());
		}
	}

	@Override
	public void stop() {
	//	for (Map.Entry<String, SessionImpl> session : sessions.entrySet()) {
		//	sessionListeners.sessionDestroyed(session.getValue(), null, SessionListener.SessionDestroyedReason.UNDEPLOY);
	//	}
	//	sessions.clear();
	}


	@Override
	public synchronized void registerSessionListener(final SessionListener listener) {
		UndertowLogger.SESSION_LOGGER.debugf("Registered session listener %s", listener);
		sessionListeners.addSessionListener(listener);
	}

	@Override
	public synchronized void removeSessionListener(final SessionListener listener) {
		UndertowLogger.SESSION_LOGGER.debugf("Removed session listener %s", listener);
		sessionListeners.removeSessionListener(listener);
	}

	public boolean isStatisticsEnabled() {
		return statisticsEnabled;
	}

	@Override
	public Set<String> getTransientSessions() {
		return getAllSessions();
	}

	@Override
	public Set<String> getActiveSessions() {
		return getAllSessions();
	}

	@Override
	public Set<String> getAllSessions() {
		//TODO
		return null;
	}

	public void setDefaultMaxInactiveInterval(Integer defaultMaxInactiveInterval) {
		this.defaultMaxInactiveInterval = defaultMaxInactiveInterval;
	}

	@Override
	public void setDefaultSessionTimeout(final int timeout) {
		this.defaultMaxInactiveInterval = timeout;
	}


	@Override
	public SessionManagerStatistics getStatistics() {
		return null;
	}

	public Integer getDefaultMaxInactiveInterval() {
		return defaultMaxInactiveInterval;
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

	public void updateStatistics(JdbcSession session) {
		long life = System.currentTimeMillis() - session.getCreationTime();
		synchronized (this) {
			sessionStatistics.setExpiredSessionCount(sessionStatistics.getExpiredSessionCount()+1);
			sessionStatistics.setTotalSessionLifetime(sessionStatistics.getTotalSessionLifetime().add(BigInteger.valueOf(life)));

			if(sessionStatistics.getLongestSessionLifetime() < life) {
				sessionStatistics.setLongestSessionLifetime(life);
			}
		}

	}

	public String resolvePrincipal(Session session) {
		String principalName = (String)session.getAttribute(PRINCIPAL_NAME_INDEX_NAME);
		if (principalName != null) {
			return principalName;
		}
		Object authentication = session.getAttribute(SECURITY_CONTEXT);
		if (authentication != null) {
			//TODO
			return null;
		}
		return null;
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
				SessionImpl delegate = new SessionImpl(this, rs.getString("SESSION_ID"), rs.getInt("MAX_INACTIVE_INTERVAL"), rs.getLong("CREATION_TIME"));
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

	private JdbcSession getLast(List<JdbcSession> sessions) {
		return sessions.get(sessions.size() - 1);
	}


} */
