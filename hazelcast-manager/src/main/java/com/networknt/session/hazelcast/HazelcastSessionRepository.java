package com.networknt.session.hazelcast;

import com.hazelcast.config.Config;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IMap;
import com.networknt.session.MapSession;
import com.networknt.session.Session;
import com.networknt.session.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A {@link com.networknt.session.SessionRepository} implementation that stores
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
 * @author Vedran Pavic
 * @author Tommy Ludwig
 * @author Mark Anderson
 * @author Aleksandar Stojsavljevic
 *
 */
public class HazelcastSessionRepository implements SessionRepository<HazelcastSessionRepository.HazelcastSession> {

    private static final Logger logger = LoggerFactory.getLogger(HazelcastSessionRepository.class);

    private final IMap<String, MapSession> sessions;

    private HazelcastFlushMode hazelcastFlushMode = HazelcastFlushMode.IMMEDIATE;

    /**
     * If non-null, this value is used to override
     * {@link MapSession#setMaxInactiveInterval(int)}.
     */
    private int defaultMaxInactiveInterval;

    public HazelcastSessionRepository() {
        Config config = new Config();
        HazelcastInstance hazelcastInstance = Hazelcast.newHazelcastInstance(config);
        this.sessions = hazelcastInstance.getMap("light:session:sessions");
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

    /**
     * Sets the Hazelcast flush mode. Default flush mode is
     * {@link HazelcastFlushMode#ON_SAVE}.
     * @param hazelcastFlushMode the new Hazelcast flush mode
     */
    public void setHazelcastFlushMode(HazelcastFlushMode hazelcastFlushMode) {
        this.hazelcastFlushMode = hazelcastFlushMode;
    }

    @Override
    public HazelcastSession createSession() {
        HazelcastSession result = new HazelcastSession();
        if (this.defaultMaxInactiveInterval != 0) {
            result.setMaxInactiveInterval(this.defaultMaxInactiveInterval);
        }
        return result;
    }

    @Override
    public void save(HazelcastSession session) {
        if (!session.getId().equals(session.originalId)) {
            this.sessions.remove(session.originalId);
            session.originalId = session.getId();
        }
        if (session.isChanged()) {
            this.sessions.put(session.getId(), session.getDelegate(), session.getMaxInactiveInterval(), TimeUnit.SECONDS);
            session.markUnchanged();
        }
    }

    @Override
    public HazelcastSession findById(String id) {
        MapSession saved = this.sessions.get(id);
        if (saved == null) {
            return null;
        }
        if (saved.isExpired()) {
            deleteById(saved.getId());
            return null;
        }
        return new HazelcastSession(saved);
    }

    @Override
    public void deleteById(String id) {
        this.sessions.remove(id);
    }

    /**
     * A custom implementation of {@link Session} that uses a {@link MapSession} as the
     * basis for its mapping. It keeps track if changes have been made since last save.
     *
     * @author Aleksandar Stojsavljevic
     */
    final class HazelcastSession implements Session {

        private final MapSession delegate;
        private boolean changed;
        private String originalId;

        /**
         * Creates a new instance ensuring to mark all of the new attributes to be
         * persisted in the next save operation.
         */
        HazelcastSession() {
            this(new MapSession());
            this.changed = true;
            flushImmediateIfNecessary();
        }

        /**
         * Creates a new instance from the provided {@link MapSession}.
         * @param cached the {@link MapSession} that represents the persisted session that
         * was retrieved. Cannot be {@code null}.
         */
        HazelcastSession(MapSession cached) {
            this.delegate = cached;
            this.originalId = cached.getId();
        }

        public void setLastAccessedTime(long lastAccessedTime) {
            this.delegate.setLastAccessedTime(lastAccessedTime);
            this.changed = true;
            flushImmediateIfNecessary();
        }

        @Override
        public boolean isExpired() {
            return this.delegate.isExpired();
        }

        @Override
        public long getCreationTime() {
            return this.delegate.getCreationTime();
        }

        @Override
        public String getId() {
            return this.delegate.getId();
        }

        @Override
        public String changeSessionId() {
            this.changed = true;
            String result = this.delegate.changeSessionId();
            return result;
        }

        @Override
        public long getLastAccessedTime() {
            return this.delegate.getLastAccessedTime();
        }

        @Override
        public void setMaxInactiveInterval(int interval) {
            this.delegate.setMaxInactiveInterval(interval);
            this.changed = true;
            flushImmediateIfNecessary();
        }

        @Override
        public int getMaxInactiveInterval() {
            return this.delegate.getMaxInactiveInterval();
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
            Object object = this.delegate.setAttribute(attributeName, attributeValue);
            this.changed = true;
            flushImmediateIfNecessary();
            return object;
        }

        @Override
        public Object removeAttribute(String attributeName) {
            Object object = this.delegate.removeAttribute(attributeName);
            this.changed = true;
            flushImmediateIfNecessary();
            return object;
        }

        boolean isChanged() {
            return this.changed;
        }

        void markUnchanged() {
            this.changed = false;
        }

        MapSession getDelegate() {
            return this.delegate;
        }

        private void flushImmediateIfNecessary() {
            if (HazelcastSessionRepository.this.hazelcastFlushMode == HazelcastFlushMode.IMMEDIATE) {
                HazelcastSessionRepository.this.save(this);
            }
        }

    }

}