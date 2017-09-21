

package com.networknt.session.data.hazelcast;

import java.util.*;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;


import com.networknt.session.SessionRepository;

import com.networknt.session.MapSession;

import com.networknt.session.FindByIndexNameSessionRepository;
import com.hazelcast.core.EntryEvent;
import com.hazelcast.core.IMap;
import com.hazelcast.map.listener.EntryAddedListener;
import com.hazelcast.map.listener.EntryEvictedListener;
import com.hazelcast.map.listener.EntryRemovedListener;
import com.hazelcast.query.Predicates;



import com.networknt.session.SessionRepository;
import com.networknt.session.data.HazelcastFlushMode;
import com.networknt.session.data.HazelcastSession;
import io.undertow.server.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link SessionRepository} implementation that stores
 * sessions in Hazelcast's distributed {@link IMap}.
 *
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
 *         .getMap("spring:session:sessions");
 *
 * HazelcastSessionRepository sessionRepository =
 *         new HazelcastSessionRepository(sessions);
 * </pre>
 *
 * In order to support finding sessions by principal name using
 * {@link #findByIndexNameAndIndexValue(String, String)} method, custom configuration of
 * {@code IMap} supplied to this implementation is required.
 *
 * The following snippet demonstrates how to define required configuration using
 * programmatic Hazelcast Configuration:
 *

 *
 */
public class HazelcastSessionRepository implements
		FindByIndexNameSessionRepository<HazelcastSession>,
		EntryAddedListener<String, MapSession>,
		EntryEvictedListener<String, MapSession>,
		EntryRemovedListener<String, MapSession> {

	/**
	 * The principal name custom attribute name.
	 */
	public static final String PRINCIPAL_NAME_ATTRIBUTE = "principalName";

	private static final Logger logger = LoggerFactory.getLogger(HazelcastSessionRepository.class);

	private final IMap<String, MapSession> sessions;

	private HazelcastFlushMode hazelcastFlushMode = HazelcastFlushMode.ON_SAVE;


	/**
	 * If non-null, this value is used to override
	 * {@link MapSession#setMaxInactiveInterval(int)}.
	 */
	private Integer defaultMaxInactiveInterval;

	private String sessionListenerId;

	public HazelcastSessionRepository(IMap<String, MapSession> sessions) {
		Objects.requireNonNull(sessions);
		this.sessions = sessions;
	}

	@PostConstruct
	private void init() {
		this.sessionListenerId = this.sessions.addEntryListener(this, true);
	}

	@PreDestroy
	private void close() {
		this.sessions.removeEntryListener(this.sessionListenerId);
	}

	/**
	 * Set the maximum inactive interval in seconds between requests before newly created
	 * sessions will be invalidated. A negative time indicates that the session will never
	 * timeout. The default is 1800 (30 minutes).
	 * @param defaultMaxInactiveInterval the maximum inactive interval in seconds
	 */
	public void setDefaultMaxInactiveInterval(Integer defaultMaxInactiveInterval) {
		this.defaultMaxInactiveInterval = defaultMaxInactiveInterval;
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

	public HazelcastSession createSession() {
		HazelcastSession result = new HazelcastSession(this);
		if (this.defaultMaxInactiveInterval != null) {
			result.setMaxInactiveInterval(this.defaultMaxInactiveInterval);
		}
		return result;
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

	public HazelcastSession findById(String id) {
		MapSession saved = this.sessions.get(id);
		if (saved == null) {
			return null;
		}
		if (saved.isExpired()) {
			deleteById(saved.getId());
			return null;
		}
		return new HazelcastSession(saved, this);
	}

	public void deleteById(String id) {
		this.sessions.remove(id);
	}

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
			sessionMap.put(session.getId(), new HazelcastSession(session, this));
		}
		return sessionMap;
	}

	public void entryAdded(EntryEvent<String, MapSession> event) {
		if (logger.isDebugEnabled()) {
			logger.debug("Session created with id: " + event.getValue().getId());
		}

	}

	public void entryEvicted(EntryEvent<String, MapSession> event) {
		if (logger.isDebugEnabled()) {
			logger.debug("Session expired with id: " + event.getOldValue().getId());
		}

	}

	public void entryRemoved(EntryEvent<String, MapSession> event) {
		if (logger.isDebugEnabled()) {
			logger.debug("Session deleted with id: " + event.getOldValue().getId());
		}

	}

	public HazelcastFlushMode getHazelcastFlushMode() {
		return hazelcastFlushMode;
	}
}
