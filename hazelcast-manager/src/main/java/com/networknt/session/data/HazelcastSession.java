package com.networknt.session.data;

import com.networknt.session.MapSession;

import com.networknt.session.data.hazelcast.HazelcastSessionRepository;
import io.undertow.server.session.Session;

import java.util.Objects;
import java.util.Set;

/**
 * A custom implementation of {@link Session} that uses a {@link MapSession} as the
 * basis for its mapping. It keeps track if changes have been made since last save.
 *
 */
public class HazelcastSession implements Session {

    private final MapSession delegate;
    private boolean changed;
    private String originalId;
    private HazelcastSessionRepository hazelcastSessionRepository;
    /**
     * Creates a new instance ensuring to mark all of the new attributes to be
     * persisted in the next save operation.
     */

    public HazelcastSession(HazelcastSessionRepository hazelcastSessionRepository) {
        this(new MapSession(), hazelcastSessionRepository);
        this.changed = true;
        this.hazelcastSessionRepository = hazelcastSessionRepository;
        flushImmediateIfNecessary();
    }

    /**
     * Creates a new instance from the provided {@link MapSession}.
     * @param cached the {@link MapSession} that represents the persisted session that
     * was retrieved. Cannot be {@code null}.
     */
    public HazelcastSession(MapSession cached, HazelcastSessionRepository hazelcastSessionRepository) {
        Objects.requireNonNull(cached);
        this.delegate = cached;
        this.originalId = cached.getId();
        this.hazelcastSessionRepository = hazelcastSessionRepository;
    }

    public void setLastAccessedTime(long lastAccessedTime) {
        this.delegate.setLastAccessedTime(lastAccessedTime);
        this.changed = true;
        flushImmediateIfNecessary();
    }

    public boolean isExpired() {
        return this.delegate.isExpired();
    }

    public long getCreationTime() {
        return this.delegate.getCreationTime();
    }

    public String getId() {
        return this.delegate.getId();
    }

    public String changeSessionId() {
        this.changed = true;
        String result = this.delegate.changeSessionId();
        return result;
    }

    public long getLastAccessedTime() {
        return this.delegate.getLastAccessedTime();
    }

    public void setMaxInactiveInterval(int interval) {
        this.delegate.setMaxInactiveInterval(interval);
        this.changed = true;
        flushImmediateIfNecessary();
    }

    public int getMaxInactiveInterval() {
        return this.delegate.getMaxInactiveInterval();
    }

    public Object getAttribute(String attributeName) {
        return this.delegate.getAttribute(attributeName);
    }

    public Set<String> getAttributeNames() {
        return this.delegate.getAttributeNames();
    }

    public Object setAttribute(String attributeName, Object attributeValue) {
        this.delegate.setAttribute(attributeName, attributeValue);
        this.changed = true;
        flushImmediateIfNecessary();
        return attributeName;
    }

    public void setChanged(boolean changed) {
        this.changed = changed;
    }

    public String getOriginalId() {
        return originalId;
    }

    public void setOriginalId(String originalId) {
        this.originalId = originalId;
    }

    public Object removeAttribute(String attributeName) {
        this.delegate.removeAttribute(attributeName);
        this.changed = true;
        flushImmediateIfNecessary();
        return attributeName;
    }

    public boolean isChanged() {
        return this.changed;
    }

    public void markUnchanged() {
        this.changed = false;
    }

    public MapSession getDelegate() {
        return this.delegate;
    }

    private void flushImmediateIfNecessary() {
        if (hazelcastSessionRepository. getHazelcastFlushMode() == HazelcastFlushMode.IMMEDIATE) {
            hazelcastSessionRepository.save(this);
        }
    }

}


