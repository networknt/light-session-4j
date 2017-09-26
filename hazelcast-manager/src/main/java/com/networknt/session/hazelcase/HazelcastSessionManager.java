

package com.networknt.session.hazelcase;

import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryEvictedListener;
import com.hazelcast.map.listener.EntryRemovedListener;
import com.hazelcast.query.Predicates;
import com.networknt.session.FindByIndexNameSessionRepository;
import com.networknt.session.SessionImpl;
import com.networknt.session.SessionRepository;
import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.*;
import io.undertow.util.AttachmentKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
		FindByIndexNameSessionRepository<HazelcastSession>, SessionManager, SessionManagerStatistics,
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

	private final IMap<String, SessionImpl> sessions;
	private final SessionListeners sessionListeners = new SessionListeners();


	private HazelcastFlushMode hazelcastFlushMode = HazelcastFlushMode.ON_SAVE;
	private final String deploymentName;
	private volatile int defaultSessionTimeout = 30 * 60;
	private final AtomicLong createdSessionCount = new AtomicLong();
	private final AtomicLong rejectedSessionCount = new AtomicLong();
	private volatile long longestSessionLifetime = 0;
	private volatile long expiredSessionCount = 0;
	private volatile BigInteger totalSessionLifetime = BigInteger.ZERO;
	private final AtomicInteger highestSessionCount = new AtomicInteger();
	private final int maxSize;
	private final boolean statisticsEnabled;

	private volatile long startTime;


	private String sessionListenerId;

	public HazelcastSessionManager(IMap<String, SessionImpl> sessions) {
	//	Objects.requireNonNull(sessions);
		this(sessions, DEPLOY_NAME);
	}

	public HazelcastSessionManager(IMap<String, SessionImpl> sessions, String id) {
		this(sessions, id, -1);
	}



	public HazelcastSessionManager(IMap<String, SessionImpl> sessions, String deploymentName, int maxSessions) {
		this(sessions, deploymentName, maxSessions,  true);
	}

	public HazelcastSessionManager(IMap<String, SessionImpl> sessions, String deploymentName, int maxSessions, boolean statisticsEnabled) {
		Objects.requireNonNull(sessions);
		this.sessions = sessions;
		this.deploymentName = deploymentName;
		this.maxSize = maxSessions;
		this.statisticsEnabled = statisticsEnabled;
	}

	@Override
	public String getDeploymentName() {
		return this.deploymentName;
	}

	@Override
	public void start() {
		createdSessionCount.set(0);
		expiredSessionCount = 0;
		rejectedSessionCount.set(0);
		totalSessionLifetime = BigInteger.ZERO;
		startTime = System.currentTimeMillis();
	}

	@Override
	public void stop() {
		for (Map.Entry<String, SessionImpl> session : sessions.entrySet()) {
			sessionListeners.sessionDestroyed(session.getValue(), null, SessionListener.SessionDestroyedReason.UNDEPLOY);
		}
		sessions.clear();
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
	public HazelcastSession createSession() {
		HazelcastSession session = new HazelcastSession(this);

		return session;
	}

	@Override
	public Session createSession(final HttpServerExchange serverExchange, final SessionConfig config) {
		if(maxSize>0 && sessions.size() >= maxSize) {
			if(statisticsEnabled) {
				rejectedSessionCount.incrementAndGet();
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
			createdSessionCount.incrementAndGet();
			int highest;
			int sessionSize;
			do {
				highest = highestSessionCount.get();
				sessionSize = sessions.size();
				if(sessionSize <= highest) {
					break;
				}
			} while (!highestSessionCount.compareAndSet(highest, sessionSize));
		}
		return session;

	}

	public SessionListeners getSessionListeners() {
		return sessionListeners;
	}

	public IMap<String, SessionImpl> getSessions() {
		return sessions;
	}

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
	public Session getSession(final HttpServerExchange serverExchange, final SessionConfig config) {
		if (serverExchange != null) {
			HazelcastSession newSession = serverExchange.getAttachment(NEW_SESSION);
			if(newSession != null) {
				return newSession;
			}
		}
		String sessionId = config.findSessionId(serverExchange);
		HazelcastSession session =  getSession(sessionId);
		if(session != null) {
			session.getDelegate().requestStarted(serverExchange);
		}
		return session;
	}

	@Override
	public HazelcastSession getSession(String id) {
		SessionImpl saved = this.sessions.get(id);
		if (saved == null) {
			return null;
		}
		if (saved.isExpired()) {
			deleteById(saved.getId());
			return null;
		}
		return new HazelcastSession(saved, this, new SessionCookieConfig());
	}

	public void deleteById(String id) {
		this.sessions.remove(id);
	}

	public Map<String, HazelcastSession> findByIndexNameAndIndexValue(
			String indexName, String indexValue) {
		if (!PRINCIPAL_NAME_INDEX_NAME.equals(indexName)) {
			return Collections.emptyMap();
		}
		Collection<SessionImpl> sessions = this.sessions.values(
				Predicates.equal(PRINCIPAL_NAME_ATTRIBUTE, indexValue));
		Map<String, HazelcastSession> sessionMap = new HashMap<>(
				sessions.size());
		for (SessionImpl session : sessions) {
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
			expiredSessionCount++;
			totalSessionLifetime = totalSessionLifetime.add(BigInteger.valueOf(life));
			if(longestSessionLifetime < life) {
				longestSessionLifetime = life;
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
		return this;
	}

	public long getCreatedSessionCount() {
		return createdSessionCount.get();
	}

	@Override
	public long getMaxActiveSessions() {
		return maxSize;
	}

	public long getHighestSessionCount() {
		return highestSessionCount.get();
	}

	@Override
	public long getActiveSessionCount() {
		return sessions.size();
	}

	@Override
	public long getExpiredSessionCount() {
		return expiredSessionCount;
	}

	@Override
	public long getRejectedSessions() {
		return rejectedSessionCount.get();

	}

	@Override
	public long getMaxSessionAliveTime() {
		return longestSessionLifetime;
	}

	@Override
	public synchronized long getAverageSessionAliveTime() {
		//this method needs to be synchronised to make sure the session count and the total are in sync
		if(expiredSessionCount == 0) {
			return 0;
		}
		return new BigDecimal(totalSessionLifetime).divide(BigDecimal.valueOf(expiredSessionCount), MathContext.DECIMAL128).longValue();
	}

	@Override
	public long getStartTime() {
		return startTime;
	}
}
