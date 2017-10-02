package com.networknt.session;

import io.undertow.server.session.SecureRandomSessionIdGenerator;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * <p>
 * A {@link Session} implementation that is backed by a {@link java.util.Map}. The
 * defaults for the properties are:
 * </p>
 * <ul>
 * <li>id - a secure random generated id</li>
 * <li>creationTime - the moment the {@link MapSession} was instantiated</li>
 * <li>lastAccessedTime - the moment the {@link MapSession} was instantiated</li>
 * <li>maxInactiveInterval - 30 minutes</li>
 * </ul>
 *
 * <p>
 * This implementation has no synchronization, so it is best to use the copy constructor
 * when working on multiple threads.
 * </p>
 *
 * @author Gavin Chen, Steve Hu
 */
public final class MapSession implements Session, Serializable {
    /**
     * Default {@link #setMaxInactiveInterval(int)} (30 minutes).
     */
    public static final int DEFAULT_MAX_INACTIVE_INTERVAL_SECONDS = 1800;

    private String id;
    private String originalId;
    private Map<String, Object> sessionAttrs = new HashMap<>();
    private long creationTime  = System.currentTimeMillis();
    private long lastAccessedTime = creationTime;

    /**
     * Defaults to 30 minutes.
     */
    private int maxInactiveInterval = DEFAULT_MAX_INACTIVE_INTERVAL_SECONDS;

    /**
     * Creates a new instance with a secure randomly generated identifier.
     */
    public MapSession() {
        this(generateId());
    }


    /**
     * Creates a new instance with the specified id. This is preferred to the default
     * constructor when the id is known to prevent unnecessary consumption on entropy
     * which can be slow.
     *
     * @param id the identifier to use
     */
    public MapSession(String id) {
        this.id = id;
        this.originalId = id;
    }

    /**
     * Creates a new instance from the provided {@link Session}.
     *
     * @param session the {@link Session} to initialize this {@link Session} with. Cannot
     * be null.
     */
    public MapSession(Session session) {
        if (session == null) {
            throw new IllegalArgumentException("session cannot be null");
        }
        this.id = session.getId();
        this.originalId = this.id;
        this.sessionAttrs = new HashMap<>(
                session.getAttributeNames().size());
        for (String attrName : session.getAttributeNames()) {
            Object attrValue = session.getAttribute(attrName);
            if (attrValue != null) {
                this.sessionAttrs.put(attrName, attrValue);
            }
        }
        this.lastAccessedTime = session.getLastAccessedTime();
        this.creationTime = session.getCreationTime();
        this.maxInactiveInterval = session.getMaxInactiveInterval();
    }



    public void setLastAccessedTime(long lastAccessedTime) {
        this.lastAccessedTime = lastAccessedTime;
    }

    @Override
    public long getCreationTime() {
        return this.creationTime;
    }

    @Override
    public String getId() {
        return this.id;
    }

    String getOriginalId() {
        return this.originalId;
    }

    void setOriginalId(String originalId) {
        this.originalId = originalId;
    }

    @Override
    public String changeSessionId() {
        String changedId = generateId();
        setId(changedId);
        return changedId;
    }

    @Override
    public long getLastAccessedTime() {
        return this.lastAccessedTime;
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        this.maxInactiveInterval = interval;
    }

    @Override
    public int getMaxInactiveInterval() {
        return this.maxInactiveInterval;
    }

    @Override
    public boolean isExpired() {
        return isExpired(System.currentTimeMillis());
    }

    boolean isExpired(long now) {
        if (this.maxInactiveInterval<0) {
            return false;
        }
        return now - this.lastAccessedTime >= this.maxInactiveInterval * 1000L;
    }

    @Override
    public Object getAttribute(String attributeName) {
        return  this.sessionAttrs.get(attributeName);
    }

    @Override
    public Set<String> getAttributeNames() {
        return this.sessionAttrs.keySet();
    }

    @Override
    public Object setAttribute(String attributeName, Object attributeValue) {
        if (attributeValue == null) {
            return removeAttribute(attributeName);
        } else {
            return this.sessionAttrs.put(attributeName, attributeValue);
        }
    }

    @Override
    public Object removeAttribute(String attributeName) {
        return this.sessionAttrs.remove(attributeName);
    }

    /**
     * Sets the identifier for this {@link Session}. The id should be a secure random
     * generated value to prevent malicious users from guessing this value. The default is
     * a secure random generated identifier.
     *
     * @param id the identifier for this session.
     */
    public void setId(String id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Session && this.id.equals(((Session) obj).getId());
    }

    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    private static String generateId() {
        return new SecureRandomSessionIdGenerator().createSessionId();
    }

    // TODO change the Java serialization to something more efficient.
    private static final long serialVersionUID = 1L;
}
