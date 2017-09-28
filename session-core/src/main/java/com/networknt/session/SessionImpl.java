package com.networknt.session;

import io.undertow.UndertowLogger;
import io.undertow.UndertowMessages;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.*;
import io.undertow.util.AttachmentKey;


import java.io.Serializable;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


/**
 * Created by chenga on 2017-09-22.
 */
public class SessionImpl implements Session, Serializable {

 //   final AttachmentKey<Boolean> FIRST_REQUEST_ACCESS = AttachmentKey.create(Boolean.class);
    final SessionManager sessionManager;
    final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile long lastAccessed;
    private long creationTime;
    volatile int maxInactiveInterval;


    private String sessionId;
    private volatile long expireTime = -1;
    private volatile boolean invalid = false;
    private volatile boolean invalidationStarted = false;

    public SessionImpl(SessionManager sessionManager) {
        this(sessionManager, 30 * 60);
    }

    public SessionImpl(SessionManager sessionManager, final int maxInactiveInterval) {
        this(sessionManager, generateId(),maxInactiveInterval);
    }

    public SessionImpl(SessionManager sessionManager, final String sessionId, final int maxInactiveInterval) {

        this(sessionManager, sessionId, maxInactiveInterval, System.currentTimeMillis());
        lastAccessed = System.currentTimeMillis();
    }

    public SessionImpl(SessionManager sessionManager, final String sessionId, final int maxInactiveInterval, long creationTime) {
        this.sessionManager = sessionManager;
        this.sessionId = sessionId;

        this.creationTime = creationTime;
        this.maxInactiveInterval = maxInactiveInterval;
    }


    @Override
    public String getId() {
        return sessionId;
    }

    public void requestStarted(HttpServerExchange serverExchange) {
    /*    Boolean existing = serverExchange.getAttachment(FIRST_REQUEST_ACCESS);
        if(existing == null) {
            if (!invalid) {
                lastAccessed = System.currentTimeMillis();
            }
           serverExchange.putAttachment(FIRST_REQUEST_ACCESS, Boolean.TRUE);
        }*/
    }

    @Override
    public void requestDone(final HttpServerExchange serverExchange) {
    }

    @Override
    public long getCreationTime() {
        if (invalid) {
            throw UndertowMessages.MESSAGES.sessionIsInvalid(sessionId);
        }
        return creationTime;
    }

    @Override
    public long getLastAccessedTime() {
        if (invalid) {
            throw UndertowMessages.MESSAGES.sessionIsInvalid(sessionId);
        }
        return lastAccessed;
    }

    public void setLastAccessedTime(long lastAccessedTime) {
        lastAccessed = lastAccessedTime;
    }

    @Override
    public void setMaxInactiveInterval(final int interval) {
        if (invalid) {
            throw UndertowMessages.MESSAGES.sessionIsInvalid(sessionId);
        }
        UndertowLogger.SESSION_LOGGER.debugf("Setting max inactive interval for %s to %s", sessionId, interval);
        maxInactiveInterval = interval;
    }

    @Override
    public int getMaxInactiveInterval() {
        if (invalid) {
            throw UndertowMessages.MESSAGES.sessionIsInvalid(sessionId);
        }
        return maxInactiveInterval;
    }

    public boolean isExpired() {
        return isExpired(new Date());
    }

    public boolean isExpired(Date now) {
        if (this.maxInactiveInterval<0) {
            return false;
        }
        return now.getTime() - this.lastAccessed >= this.maxInactiveInterval;
    }

    @Override
    public Object getAttribute(final String name) {
        if (invalid) {
            throw UndertowMessages.MESSAGES.sessionIsInvalid(sessionId);
        }
        return attributes.get(name);
    }

    @Override
    public Set<String> getAttributeNames() {
        if (invalid) {
            throw UndertowMessages.MESSAGES.sessionIsInvalid(sessionId);
        }
        return attributes.keySet();
    }

    @Override
    public Object setAttribute(final String name, final Object value) {
        if (value == null) {
            return removeAttribute(name);
        }
        if (invalid) {
            throw UndertowMessages.MESSAGES.sessionIsInvalid(sessionId);
        }
        final Object existing = attributes.put(name, value);
      /*  if (existing == null) {
            sessionManager.sessionListeners.attributeAdded(this, name, value);
        } else {
            sessionManager.sessionListeners.attributeUpdated(this, name, value, existing);
        }*/
        UndertowLogger.SESSION_LOGGER.tracef("Setting session attribute %s to %s for session %s", name, value, sessionId);
        return existing;
    }

    @Override
    public Object removeAttribute(final String name) {
        if (invalid) {
            throw UndertowMessages.MESSAGES.sessionIsInvalid(sessionId);
        }
        final Object existing = attributes.remove(name);
     //   sessionManager.sessionListeners.attributeRemoved(this, name, existing);

        UndertowLogger.SESSION_LOGGER.tracef("Removing session attribute %s for session %s", name, sessionId);
        return existing;
    }

    @Override
    public void invalidate(final HttpServerExchange exchange) {
 //       invalidate(exchange, SessionListener.SessionDestroyedReason.INVALIDATED);
        if(exchange != null) {
    //        exchange.removeAttachment(sessionManager.NEW_SESSION);
        }
    }



 /*   void invalidate(final HttpServerExchange exchange, SessionListener.SessionDestroyedReason reason) {
        synchronized(InMemorySessionManager.SessionImpl.this) {

            InMemorySessionManager.SessionImpl sess = sessionManager.sessions.remove(sessionId);
            if (sess == null) {
                if (reason == SessionListener.SessionDestroyedReason.INVALIDATED) {
                    throw UndertowMessages.MESSAGES.sessionAlreadyInvalidated();
                }
                return;
            }
            invalidationStarted = true;
        }
        UndertowLogger.SESSION_LOGGER.debugf("Invalidating session %s for exchange %s", sessionId, exchange);

        sessionManager.sessionListeners.sessionDestroyed(this, exchange, reason);
        invalid = true;

        if(sessionManager.statisticsEnabled) {
            long life = System.currentTimeMillis() - creationTime;
            synchronized (sessionManager) {
                sessionManager.expiredSessionCount++;
                sessionManager.totalSessionLifetime = sessionManager.totalSessionLifetime.add(BigInteger.valueOf(life));
                if(sessionManager.longestSessionLifetime < life) {
                    sessionManager.longestSessionLifetime = life;
                }
            }
        }
        if (exchange != null) {
            sessionCookieConfig.clearSession(exchange, this.getId());
        }
    }*/

    @Override
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    @Override
    public String changeSessionId(final HttpServerExchange exchange, final SessionConfig config) {
        String newId = new SecureRandomSessionIdGenerator().createSessionId();
        this.sessionId = newId;

        return newId;
    }

    public boolean isInvalid() {
        return invalid;
    }

    public void setInvalid(boolean invalid) {
        this.invalid = invalid;
    }

    private static String generateId() {
        return new SecureRandomSessionIdGenerator().createSessionId();
    }
}
