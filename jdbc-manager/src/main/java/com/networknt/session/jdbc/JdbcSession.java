package com.networknt.session.jdbc;

import com.networknt.session.SessionImpl;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.Session;
import io.undertow.server.session.SessionConfig;
import io.undertow.server.session.SessionManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Created by gavin on 2017-09-22.
 */
public class JdbcSession implements Session {


    private final SessionImpl delegate;
    //private final String primaryKey;
    private boolean isNew;
    private boolean changed;
    private JdbcSessionManager jdbcSessionManager;
    private SessionConfig sessionCookieConfig;

    private Map<String, Object> delta = new HashMap<>();

    JdbcSession(JdbcSessionManager jdbcSessionManager,  SessionConfig sessionCookieConfig) {
            this.jdbcSessionManager = jdbcSessionManager;
            this.sessionCookieConfig = sessionCookieConfig;
            this.delegate = new SessionImpl(jdbcSessionManager, jdbcSessionManager.getDefaultMaxInactiveInterval());
            this.isNew = true;
    }

    JdbcSession(SessionImpl delegate,  SessionConfig sessionCookieConfig) {
            //this.primaryKey = primaryKey;
            this.delegate = delegate;
            this.sessionCookieConfig = sessionCookieConfig;
    }

    public boolean isNew() {
            return this.isNew;
        }

    public boolean isChanged() {
            return this.changed;
        }

    public Map<String, Object> getDelta() {
            return this.delta;
        }

    public  void clearChangeFlags() {
            this.isNew = false;
            this.changed = false;
            this.delta.clear();
    }

    public String getPrincipalName() {
        return null;
            //return PRINCIPAL_NAME_RESOLVER.resolvePrincipal(this);
    }

    public long getExpiryTime() {
            return getLastAccessedTime() + getMaxInactiveInterval();
        }

    public String getId() {
            return this.delegate.getId();
        }

    @Override
    public String changeSessionId(final HttpServerExchange exchange, final SessionConfig config) {
            this.changed = true;
            String newSessionId =  this.delegate.changeSessionId(exchange, config);
            return newSessionId;
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
            this.delta.put(attributeName, attributeValue);
            this.changed = true;
         //   if (PRINCIPAL_NAME_INDEX_NAME.equals(attributeName) ||
          ///          SPRING_SECURITY_CONTEXT.equals(attributeName)) {
         //       this.changed = true;
          //  }
            return attributeName;
    }

    public Object removeAttribute(String attributeName) {
            final Object existing = this.delegate.removeAttribute(attributeName);
            this.delta.put(attributeName, null);
            return existing;
    }

    public long getCreationTime() {
            return this.delegate.getCreationTime();
        }

    public void setLastAccessedTime(long lastAccessedTime) {
            this.delegate.setLastAccessedTime(lastAccessedTime);
            this.changed = true;
    }

    public long getLastAccessedTime() {
            return this.delegate.getLastAccessedTime();
        }

    public void setMaxInactiveInterval(int interval) {
            this.delegate.setMaxInactiveInterval(interval);
            this.changed = true;
    }

    public int getMaxInactiveInterval() {
            return this.delegate.getMaxInactiveInterval();
        }

    public boolean isExpired() {
            return this.delegate.isExpired();
        }

    public void markUnchanged() {
        this.changed = false;
    }

    public SessionImpl getDelegate() {
        return this.delegate;
    }

    @Override
    public SessionManager getSessionManager() {
        return this.jdbcSessionManager;
    }

    @Override
    public void requestDone(final HttpServerExchange serverExchange) {
        this.delegate.requestDone(serverExchange);
    }

    @Override
    public void invalidate(final HttpServerExchange exchange) {
        //invalidate(exchange, SessionListener.SessionDestroyedReason.INVALIDATED);
        if(exchange != null) {
            exchange.removeAttachment(jdbcSessionManager.NEW_SESSION);
        }
    }

}
