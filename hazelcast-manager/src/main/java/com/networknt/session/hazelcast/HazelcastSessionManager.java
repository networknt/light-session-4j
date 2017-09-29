

package com.networknt.session.hazelcast;

import com.hazelcast.core.*;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryEvictedListener;
import com.hazelcast.map.listener.EntryRemovedListener;
import com.hazelcast.query.Predicates;
import com.networknt.session.*;
import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.*;
import io.undertow.util.AttachmentKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * A {@link SessionRepository} implementation that stores
 * sessions in Hazelcast's distributed {@link IMap}.
 * <p>
 * An example of how to create a new instance can be seen below:
 *
 * <pre class="code">
 * Config config = new Config();
 *
 * // ... configure Hazelcast ...
 *
 * HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance(config);
 *
 * IMap{@code <String, MapSession>} sessions = hazelcastInstance
 *         .getMap("light-session:sessions");
 *
 * HazelcastSessionManager sessionRepository =
 *         new HazelcastSessionManager(sessions);
 * </pre>
 *
 */
public class HazelcastSessionManager implements
		FindByIndexNameSessionRepository<HazelcastSession>, SessionManager,
		EntryAddedListener<String, Session>,
		EntryEvictedListener<String, Session>,
		EntryRemovedListener<String, Session> {

	public final AttachmentKey<HazelcastSession> NEW_SESSION = AttachmentKey.create(HazelcastSession.class);
	/**
	 * The principal name custom attribute name.
	 */
	public static final String PRINCIPAL_NAME_ATTRIBUTE = "principalName";

	public static final String DEPLOY_NAME = "LIGHT-SESSION";

	private static final Logger logger = LoggerFactory.getLogger(HazelcastSessionManager.class);

	private final IMap<String, MapSession> sessions;
	private final SessionStatistics sessionStatistics = null;
	private final SessionListeners sessionListeners = new SessionListeners();


	private HazelcastFlushMode hazelcastFlushMode = HazelcastFlushMode.ON_SAVE;
	private final String deploymentName;
	private volatile int defaultSessionTimeout = 30 * 60;

	private final int maxSize;
	private boolean statisticsEnabled = false;


	private String sessionListenerId;

	public HazelcastSessionManager(IMap<String, MapSession> sessions) {
	//	Objects.requireNonNull(sessions);
		this(sessions, DEPLOY_NAME);
	}

	public HazelcastSessionManager(IMap<String, MapSession> sessions, String deploymentName) {
		this(sessions, deploymentName, -1);
	}



	public HazelcastSessionManager(IMap<String, MapSession> sessions, String deploymentName, int maxSessions) {
		this(sessions, deploymentName, maxSessions,  null);
	}

	public HazelcastSessionManager(IMap<String, MapSession> sessions, String deploymentName, int maxSessions, SessionStatistics sessionStatistics) {
		Objects.requireNonNull(sessions);
		this.sessions = sessions;
		this.deploymentName = deploymentName;
		this.maxSize = maxSessions;
		if (sessionStatistics!=null) {
			this.statisticsEnabled = true;
		}
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

	}


	@PostConstruct
	private void init() {
		this.sessionListenerId = this.sessions.addEntryListener(this, true);
	}

	@PreDestroy
	private void close() {
		this.sessions.removeEntryListener(this.sessionListenerId);
	}

	public int getDefaultSessionTimeout() {
		return defaultSessionTimeout;
	}

	/**
	 * Sets the Hazelcast flush mode. Default flush mode is
	 * {@link HazelcastFlushMode#ON_SAVE}.
	 * @param hazelcastFlushMode the new Hazelcast flush mode
	 */
	public void setHazelcastFlushMode(HazelcastFlushMode hazelcastFlushMode) {
		Objects.requireNonNull(hazelcastFlushMode);
		this.hazelcastFlushMode = hazelcastFlushMode;
	}

	@Override
	public HazelcastSession createSession(final HttpServerExchange serverExchange, final SessionConfig config) {
		if(maxSize>0 && sessions.size() >= maxSize) {
			if(statisticsEnabled) {
				sessionStatistics.setRejectedSessionCount(sessionStatistics.getRejectedSessionCount()+1);
			}
			throw UndertowMessages.MESSAGES.tooManySessions(maxSize);
		}
		if (config == null) {
			throw UndertowMessages.MESSAGES.couldNotFindSessionCookieConfig();
		}
		String sessionID = config.findSessionId(serverExchange);
		if (sessionID == null) {
			int count = 0;
			SecureRandomSessionIdGenerator sessionIdGenerator = new SecureRandomSessionIdGenerator();
			while (sessionID == null) {
				sessionID = sessionIdGenerator.createSessionId();
				if(sessions.containsKey(sessionID)) {
					sessionID = null;
				}
				if(count++ == 100) {
					//this should never happen
					//but we guard against pathalogical session id generators to prevent an infinite loop
					throw UndertowMessages.MESSAGES.couldNotGenerateUniqueSessionId();
				}
			}
		}

		HazelcastSession session = new HazelcastSession(this, sessionID);

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
			if (highest<=sessions.size()) {
				sessionStatistics.setHighestSessionCount(sessions.size());
			}
		}
		return session;

	}

	public SessionListeners getSessionListeners() {
		return sessionListeners;
	}

	public IMap<String, MapSession> getSessions() {
		return sessions;
	}

	@Override
	public void save(HazelcastSession session) {
		if (!session.getId().equals(session.getOriginalId())) {
			this.sessions.remove(session.getOriginalId());
			session.setOriginalId(session.getId());
		}
		if (session.isChanged()) {
			this.sessions.put(session.getId(), session.getDelegate(),
					session.getMaxInactiveInterval(), TimeUnit.SECONDS);
			session.markUnchanged();
		}
	}

	@Override
	public HazelcastSession getSession(final HttpServerExchange serverExchange, final SessionConfig config) {
		if (serverExchange != null) {
			HazelcastSession newSession = serverExchange.getAttachment(NEW_SESSION);
			if(newSession != null) {
				return newSession;
			}
		}
		String sessionId = config.findSessionId(serverExchange);
		if (sessionId == null) {
			return null;
		}
		HazelcastSession session =  getSession(sessionId);
		if(session != null) {
			session.requestStarted(serverExchange);
		}
		return session;
	}

	@Override
	public HazelcastSession getSession(String id) {
		MapSession saved = this.sessions.get(id);
		if (saved == null) {
			return null;
		}
		if (saved.isExpired()) {
			deleteById(saved.getId());
			return null;
		}
		return new HazelcastSession(saved, this, new SessionCookieConfig());
	}

	@Override
	public void deleteById(String id) {
		this.sessions.remove(id);
	}

	@Override
	public Map<String, HazelcastSession> findByIndexNameAndIndexValue(
			String indexName, String indexValue) {
		if (!PRINCIPAL_NAME_INDEX_NAME.equals(indexName)) {
			return Collections.emptyMap();
		}
		Collection<MapSession> sessions = this.sessions.values(
				Predicates.equal(PRINCIPAL_NAME_ATTRIBUTE, indexValue));
		Map<String, HazelcastSession> sessionMap = new HashMap<>(
				sessions.size());
		for (MapSession session : sessions) {
			sessionMap.put(session.getId(), new HazelcastSession(session, this, new SessionCookieConfig()));
		}
		return sessionMap;
	}

	public void entryAdded(EntryEvent<String, Session> event) {
		if (logger.isDebugEnabled()) {
			logger.debug("Session created with id: " + event.getValue().getId());
		}

	}

	public void entryEvicted(EntryEvent<String, Session> event) {
		if (logger.isDebugEnabled()) {
			logger.debug("Session expired with id: " + event.getOldValue().getId());
		}

	}

	public void entryRemoved(EntryEvent<String, Session> event) {
		if (logger.isDebugEnabled()) {
			logger.debug("Session deleted with id: " + event.getOldValue().getId());
		}

	}

	public HazelcastFlushMode getHazelcastFlushMode() {
		return hazelcastFlushMode;
	}


	@Override
	public void setDefaultSessionTimeout(final int timeout) {
		UndertowLogger.SESSION_LOGGER.debugf("Setting default session timeout to %s", timeout);
		defaultSessionTimeout = timeout;
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

	public void updateStatistics(HazelcastSession session) {
		long life = System.currentTimeMillis() - session.getCreationTime();
		synchronized (this) {
			sessionStatistics.setExpiredSessionCount(sessionStatistics.getExpiredSessionCount()+1);
			sessionStatistics.setTotalSessionLifetime(sessionStatistics.getTotalSessionLifetime().add(BigInteger.valueOf(life)));

			if(sessionStatistics.getLongestSessionLifetime() < life) {
				sessionStatistics.setLongestSessionLifetime(life);
			}
		}

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
		return new HashSet<>(sessions.keySet());
	}

	@Override
	public boolean equals(Object object) {
		if (!(object instanceof SessionManager)) return false;
		SessionManager manager = (SessionManager) object;
		return this.deploymentName.equals(manager.getDeploymentName());
	}

	@Override
	public int hashCode() {
		return this.deploymentName.hashCode();
	}

	@Override
	public String toString() {
		return this.deploymentName;
	}

	@Override
	public SessionManagerStatistics getStatistics() {
		return null;
	}



}
