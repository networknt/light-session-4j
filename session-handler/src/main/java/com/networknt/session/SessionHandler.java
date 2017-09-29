package com.networknt.session;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.IMap;
import com.networknt.config.Config;
import com.networknt.service.SingletonServiceFactory;
import com.networknt.session.hazelcast.HazelcastSessionManager;
import com.networknt.session.jdbc.JdbcSessionManager;
import io.undertow.Handlers;
import io.undertow.UndertowMessages;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.session.InMemorySessionManager;
import io.undertow.server.session.Session;
import io.undertow.server.session.SessionManager;

import javax.sql.DataSource;


public class SessionHandler implements HttpHandler {

    private volatile HttpHandler next = ResponseCodeHandler.HANDLE_404;

    private static final String HAZELCAST_REPOSITORY = "hazelcast";
    private static final String JDBC_REPOSITORY = "jdbc";
    private static final String REDIS_REPOSITORY = "redis";

    private SessionManager sessionManager;

    private io.undertow.server.session.SessionConfig sessionConfig;
    private SessionStatistics sessionStatistics = SessionStatistics.getInstance() ;
    private IMap<String, MapSession> sessions = Hazelcast.newHazelcastInstance().getMap("sessions");

    public static final String CONFIG_NAME = "session";
    public static SessionConfig config;
    static {
        config = (SessionConfig) Config.getInstance().getJsonObjectConfig(CONFIG_NAME, SessionConfig.class);

    }


    public SessionHandler() {
        sessionConfig = (io.undertow.server.session.SessionConfig) SingletonServiceFactory.getBean(io.undertow.server.session.SessionConfig.class);

        if (HAZELCAST_REPOSITORY.equalsIgnoreCase(config.getType())) {
            this.sessionManager = new HazelcastSessionManager(sessions,  config.getDeployName(), config.getMaxSize(), sessionStatistics);
        } else if (JDBC_REPOSITORY.equalsIgnoreCase(config.getType()) ) {
            DataSource ds = (DataSource) SingletonServiceFactory.getBean(DataSource.class);
            this.sessionManager = new JdbcSessionManager(ds,  sessionConfig, config.getDeployName(), sessionStatistics);
        } else if (REDIS_REPOSITORY.equalsIgnoreCase(config.getType())) {
           // this.sessionManager = new RedisSessionManager(sessions,  config.getDeployName(), config.getMaxSize(), sessionStatistics);
        } else {
            this.sessionManager = new InMemorySessionManager(config.getDeployName(), config.getMaxSize(), true);
        }
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        exchange.putAttachment(SessionManager.ATTACHMENT_KEY, sessionManager);
        exchange.putAttachment(io.undertow.server.session.SessionConfig.ATTACHMENT_KEY, sessionConfig);
        final UpdateLastAccessTimeListener handler = new UpdateLastAccessTimeListener(sessionConfig, sessionManager);
        exchange.addExchangeCompleteListener(handler);
        next.handleRequest(exchange);

    }


    public HttpHandler getNext() {
        return next;
    }

    public SessionHandler setNext(final HttpHandler next) {
        Handlers.handlerNotNull(next);
        this.next = next;
        return this;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public SessionHandler setSessionManager(final SessionManager sessionManager) {
        if (sessionManager == null) {
            throw UndertowMessages.MESSAGES.sessionManagerMustNotBeNull();
        }
        this.sessionManager = sessionManager;
        return this;
    }

    private static class UpdateLastAccessTimeListener implements ExchangeCompletionListener {

        private final io.undertow.server.session.SessionConfig sessionConfig;
        private final SessionManager sessionManager;

        private UpdateLastAccessTimeListener(final io.undertow.server.session.SessionConfig sessionConfig, final SessionManager sessionManager) {
            this.sessionConfig = sessionConfig;
            this.sessionManager = sessionManager;
        }

        @Override
        public void exchangeEvent(final HttpServerExchange exchange, final NextListener next) {
            try {
                final Session session = sessionManager.getSession(exchange, sessionConfig);
                if (session != null) {
                    session.requestDone(exchange);
                }
            } finally {
                next.proceed();
            }
        }
    }

}
