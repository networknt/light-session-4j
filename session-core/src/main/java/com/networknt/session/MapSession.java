package com.networknt.session;



import io.undertow.server.session.SecureRandomSessionIdGenerator;
import io.undertow.server.session.Session;


import java.io.Serializable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * <p>
 * A {@link Session} implementation that is backed by a {@link Map}. The
 * defaults for the properties are:
 * </p>
 * <ul>
 * <li>id - a secure random generated id</li>
 * <li>creationTime - the moment the {@link MapSession} was instantiated</li>
 * <li>lastAccessedTime - the moment the {@link MapSession} was instantiated</li>
 * <li>maxInactiveInterval - 30 minutes</li>
 * </ul>
 *
 */
public final class MapSession implements Serializable {
    /**
     * Default {@link #setMaxInactiveInterval(int)} (30 minutes).
     */
    public static final int DEFAULT_MAX_INACTIVE_INTERVAL_SECONDS = 1800;

    private String id;
    private String originalId;
    private ConcurrentMap<String, Object> sessionAttrs = new ConcurrentHashMap<>();
    private long creationTime  = System.currentTimeMillis();
    private long lastAccessedTime = creationTime;
    private  boolean invalid = false;

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
        this.sessionAttrs = new ConcurrentHashMap<>(
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

    public long getCreationTime() {
        return this.creationTime;
    }

    public String getId() {
        return this.id;
    }

    String getOriginalId() {
        return this.originalId;
    }

    void setOriginalId(String originalId) {
        this.originalId = originalId;
    }

    public String changeSessionId() {
        String changedId = generateId();
        setId(changedId);
        return changedId;
    }

    public long getLastAccessedTime() {
        return this.lastAccessedTime;
    }

    public void setMaxInactiveInterval(int interval) {
        this.maxInactiveInterval = interval;
    }

    public int getMaxInactiveInterval() {
        return this.maxInactiveInterval;
    }

    public boolean isExpired() {
        return isExpired(new Date());
    }


    boolean isExpired(Date now) {
        if (this.maxInactiveInterval<0) {
            return false;
        }
        return now.getTime() - this.lastAccessedTime >= this.maxInactiveInterval;
    }


    public Object getAttribute(String attributeName) {
        return  this.sessionAttrs.get(attributeName);
    }

    public Set<String> getAttributeNames() {
        return this.sessionAttrs.keySet();
    }


    public Object setAttribute(String attributeName, Object attributeValue) {
        if (attributeValue == null) {
            return removeAttribute(attributeName);
        }
        else {
            final Object existing = this.sessionAttrs.put(attributeName, attributeValue);
            if (existing == null) {
                //	sessionManager.sessionListeners.attributeAdded(this, name, value);
            } else {
                //		sessionManager.sessionListeners.attributeUpdated(this, name, value, existing);
            }
            return existing;

        }
    }


    public Object removeAttribute(String attributeName) {
        return this.sessionAttrs.remove(attributeName);
    }

    /**
     * Sets the time that this {@link Session} was created. The default is when the
     * {@link Session} was instantiated.
     * @param creationTime the time that this {@link Session} was created.
     */
    public void setCreationTime(long creationTime) {
        this.creationTime = creationTime;
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

    public boolean equals(Object obj) {
        return obj instanceof Session && this.id.equals(((Session) obj).getId());
    }

    public boolean isInvalid() {
        return invalid;
    }

    public void setInvalid(boolean invalid) {
        this.invalid = invalid;
    }

    public int hashCode() {
        return this.id.hashCode();
    }

    private static String generateId() {
        return new SecureRandomSessionIdGenerator().createSessionId();
    }

    private static final long serialVersionUID = 7160779239673823561L;
}
