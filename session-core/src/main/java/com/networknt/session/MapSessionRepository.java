
package com.networknt.session;


import io.undertow.server.session.Session;

import java.util.Map;

/**
 * A {@link SessionRepository} backed by a {@link Map} and that uses a
 * {@link MapSession}. The injected {@link Map} can be backed by a distributed
 * NoSQL store like Hazelcast, for instance. Note that the supplied map itself is
 * responsible for purging the expired sessions.
 *
 */
public class MapSessionRepository implements SessionRepository<MapSession> {

	/**
	 * If non-null, this value is used to override
	 * {@link Session#setMaxInactiveInterval(int)}.
	 */
	private Integer defaultMaxInactiveInterval;

	private final Map<String, MapSession> sessions;

	/**
	 * Creates a new instance backed by the provided {@link Map}. This allows
	 * injecting a distributed {@link Map}.
	 *
	 * @param sessions the {@link Map} to use. Cannot be null.
	 */
	public MapSessionRepository(Map<String, MapSession> sessions) {
		if (sessions == null) {
			throw new IllegalArgumentException("sessions cannot be null");
		}
		this.sessions = sessions;
	}

	/**
	 * If non-null, this value is used to override
	 * {@link Session#setMaxInactiveInterval(int)}.
	 * @param defaultMaxInactiveInterval the number of seconds that the {@link Session}
	 * should be kept alive between client requests.
	 */
	public void setDefaultMaxInactiveInterval(int defaultMaxInactiveInterval) {
		this.defaultMaxInactiveInterval = Integer.valueOf(defaultMaxInactiveInterval);
	}

	public void save(MapSession session) {
		if (!session.getId().equals(session.getOriginalId())) {
			this.sessions.remove(session.getOriginalId());
			session.setOriginalId(session.getId());
		}
		this.sessions.put(session.getId(), new MapSession(session));
	}

	public MapSession findById(String id) {
		MapSession saved = this.sessions.get(id);
		if (saved == null) {
			return null;
		}
		if (saved.isExpired()) {
			deleteById(saved.getId());
			return null;
		}
		return new MapSession(saved);
	}

	public void deleteById(String id) {
		this.sessions.remove(id);
	}

	public MapSession createSession() {
		MapSession result = new MapSession();
		if (this.defaultMaxInactiveInterval != null) {
			result.setMaxInactiveInterval(
					this.defaultMaxInactiveInterval);
		}
		return result;
	}

}
