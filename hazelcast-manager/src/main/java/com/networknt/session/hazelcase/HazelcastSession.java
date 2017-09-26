package com.networknt.session.hazelcase;

import com.networknt.session.MapSession;

import com.networknt.session.SessionImpl;
import io.undertow.UndertowMessages;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.*;

import java.util.Objects;
import java.util.Set;

/**
 * A custom implementation of {@link Session} that uses a {@link MapSession} as the
 * basis for its mapping. It keeps track if changes have been made since last save.
 *
 */
public class HazelcastSession implements Session {

    private final SessionImpl delegate;
    private boolean changed;
    private String originalId;
    private HazelcastSessionManager hazelcastSessionManager;
    private SessionConfig sessionCookieConfig;
    /**
     * Creates a new instance ensuring to mark all of the new attributes to be
     * persisted in the next save operation.
     */


    public HazelcastSession(HazelcastSessionManager hazelcastSessionManager) {
        this (hazelcastSessionManager, new SessionCookieConfig());
    }

    public HazelcastSession(HazelcastSessionManager hazelcastSessionManager, String sessionId) {
        this (hazelcastSessionManager, new SessionCookieConfig(), sessionId);
    }

    public HazelcastSession(HazelcastSessionManager hazelcastSessionManager, SessionConfig sessionCookieConfig) {
        this(new SessionImpl(hazelcastSessionManager, hazelcastSessionManager.getDefaultSessionTimeout()), hazelcastSessionManager, sessionCookieConfig);
        this.changed = true;
        flushImmediateIfNecessary();
    }

    public HazelcastSession(HazelcastSessionManager hazelcastSessionManager, SessionConfig sessionCookieConfig, String sessionId) {
        this(new SessionImpl(hazelcastSessionManager, sessionId, hazelcastSessionManager.getDefaultSessionTimeout()), hazelcastSessionManager, sessionCookieConfig);
        this.changed = true;
        flushImmediateIfNecessary();
    }

    /**
     * Creates a new instance from the provided {@link MapSession}.
     * @param cached the {@link MapSession} that represents the persisted session that
     * was retrieved. Cannot be {@code null}.
     */
    public HazelcastSession(SessionImpl cached, HazelcastSessionManager hazelcastSessionManager, SessionConfig sessionCookieConfig) {
        Objects.requireNonNull(cached);
        this.delegate = cached;
        this.originalId = cached.getId();
        this.hazelcastSessionManager = hazelcastSessionManager;
        this.sessionCookieConfig = sessionCookieConfig;
    }



    public void setLastAccessedTime(long lastAccessedTime) {
        this.delegate.setLastAccessedTime(lastAccessedTime);
        this.changed = true;
        flushImmediateIfNecessary();
    }

    public boolean isExpired() {
        return this.delegate.isExpired();
    }

    @Override
    public void requestDone(final HttpServerExchange serverExchange) {
        this.delegate.requestDone(serverExchange);
    }

    @Override
    public long getCreationTime() {
        return this.delegate.getCreationTime();
    }

    public String getId() {
        return this.delegate.getId();
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

    @Override
    public Object removeAttribute(String attributeName) {
        final Object existing = this.delegate.removeAttribute(attributeName);
        hazelcastSessionManager.getSessionListeners().attributeRemoved(this, attributeName, existing);
        this.changed = true;
        flushImmediateIfNecessary();
        return existing;
    }

    @Override
    public SessionManager getSessionManager() {
        return hazelcastSessionManager;
    }

    @Override
    public void invalidate(final HttpServerExchange exchange) {
        invalidate(exchange, SessionListener.SessionDestroyedReason.INVALIDATED);
        if(exchange != null) {
            exchange.removeAttachment(hazelcastSessionManager.NEW_SESSION);
        }
    }



    void invalidate(final HttpServerExchange exchange, SessionListener.SessionDestroyedReason reason) {
        SessionImpl sess = (SessionImpl) hazelcastSessionManager.getSessions().remove(getId());
        if (sess == null) {
            if (reason == SessionListener.SessionDestroyedReason.INVALIDATED) {
                throw UndertowMessages.MESSAGES.sessionAlreadyInvalidated();
            }
            return;
        }


        hazelcastSessionManager.getSessionListeners().sessionDestroyed(this, exchange, reason);
        this.delegate.setInvalid(true);

        if(hazelcastSessionManager.isStatisticsEnabled()) {
            hazelcastSessionManager.updateStatistics(this);
        }
        if (exchange != null) {
            sessionCookieConfig.clearSession(exchange, this.getId());
        }
    }

    @Override
    public String changeSessionId(final HttpServerExchange exchange, final SessionConfig config) {
        String oldId = getId();
        String newId = this.delegate.changeSessionId(exchange, config);
        if(!this.delegate.isInvalid()) {
            hazelcastSessionManager.getSessions().put(newId, this.getDelegate());
            config.setSessionId(exchange, this.getId());
        }
        hazelcastSessionManager.getSessions().remove(oldId);
        hazelcastSessionManager.getSessionListeners().sessionIdChanged(this, oldId);
        return newId;
    }

    public boolean isChanged() {
        return this.changed;
    }

    public void markUnchanged() {
        this.changed = false;
    }

    public SessionImpl getDelegate() {
        return this.delegate;
    }

    private void flushImmediateIfNecessary() {
        if (hazelcastSessionManager. getHazelcastFlushMode() == HazelcastFlushMode.IMMEDIATE) {
            hazelcastSessionManager.save(this);
        }
    }

}


