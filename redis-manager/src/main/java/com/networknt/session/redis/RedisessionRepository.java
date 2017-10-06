package com.networknt.session.redis;


import com.networknt.session.MapSession;
import com.networknt.session.Session;
import com.networknt.session.SessionRepository;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;


/**
 * A {@link SessionRepository} implementation that stores
 * sessions in Redis repository.

 */
public class RedisessionRepository implements SessionRepository<RedisessionRepository.RedisSession> {

    private static final Logger logger = LoggerFactory.getLogger(RedisessionRepository.class);

    private final RMap<String, MapSession> sessions;


    private RedisFlushMode redisFlushMode = RedisFlushMode.IMMEDIATE;

    private int defaultMaxInactiveInterval;

    public RedisessionRepository() {
        Config config = new Config();
        config.setUseLinuxNativeEpoll(true);
        config.useClusterServers()
                // use "rediss://" for SSL connection
                .addNodeAddress("redis://127.0.0.1:7181");

        RedissonClient redisson = Redisson.create(config);
        sessions = redisson.getMap("sessionMap");

        //   this.sessions = hazelcastInstance.getMap("light:session:sessions");
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
     * {@link RedisFlushMode#ON_SAVE}.
     * @param redisFlushMode the new Redis flush mode
     */
    public void setRedisFlushMode(RedisFlushMode redisFlushMode) {
        this.redisFlushMode = redisFlushMode;
    }

    @Override
    public RedisSession createSession() {
        RedisSession result = new RedisSession();
        if (this.defaultMaxInactiveInterval != 0) {
            result.setMaxInactiveInterval(this.defaultMaxInactiveInterval);
        }
        return result;
    }

    @Override
    public void save(RedisSession session) {
        if (!session.getId().equals(session.originalId)) {
            this.sessions.remove(session.originalId);
            session.originalId = session.getId();
        }
        if (session.isChanged()) {
        //    this.sessions.put(session.getId(), session.getDelegate(), session.getMaxInactiveInterval(), TimeUnit.SECONDS);
            session.markUnchanged();
        }
    }

    @Override
    public RedisSession findById(String id) {
        MapSession saved = this.sessions.get(id);
        if (saved == null) {
            return null;
        }
        if (saved.isExpired()) {
            deleteById(saved.getId());
            return null;
        }
        return new RedisSession(saved);
    }

    @Override
    public void deleteById(String id) {
        this.sessions.remove(id);
    }


    public Map<String, MapSession> getSessions() {
        return sessions;
    }


    final class RedisSession implements Session {

        private final MapSession delegate;
        private boolean changed;
        private String originalId;
        private boolean isNew;

        /**
         * Creates a new instance ensuring to mark all of the new attributes to be
         * persisted in the next save operation.
         */
        RedisSession() {
            this(new MapSession());
            this.isNew = true;
            flushImmediateIfNecessary();
        }

        /**
         * Creates a new instance from the provided {@link MapSession}.
         * @param cached the {@link MapSession} that represents the persisted session that
         * was retrieved. Cannot be {@code null}.
         */
        RedisSession(MapSession cached) {
            Objects.requireNonNull(cached);
            this.delegate = cached;
            this.originalId = cached.getId();
        }

        public boolean isNew() {
            return isNew;
        }

        public void setNew(boolean aNew) {
            isNew = aNew;
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
            if (RedisessionRepository.this.redisFlushMode == RedisFlushMode.IMMEDIATE) {
                RedisessionRepository.this.save(this);
            }
        }

    }

}