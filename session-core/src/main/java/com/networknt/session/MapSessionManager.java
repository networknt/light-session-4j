package com.networknt.session;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.SessionConfig;
import io.undertow.util.AttachmentKey;

public class MapSessionManager implements SessionManager {

    private final AttachmentKey<MapSession> NEW_SESSION = AttachmentKey.create(MapSession.class);

    private SessionConfig sessionConfig;
    private SessionRepository sessionRepository;

    public MapSessionManager(SessionConfig sessionConfig, SessionRepository sessionRepository) {
        this.sessionConfig = sessionConfig;
        this.sessionRepository = sessionRepository;
    }

    @Override
    public String getDeploymentName() {
        return "Map";
    }

    @Override
    public Session createSession(HttpServerExchange serverExchange) {
        final MapSession session = (MapSession)sessionRepository.createSession();
        sessionConfig.setSessionId(serverExchange, session.getId());
        serverExchange.putAttachment(NEW_SESSION, session);
        return session;
    }

    @Override
    public Session getSession(HttpServerExchange serverExchange) {
        if (serverExchange != null) {
            MapSession newSession = serverExchange.getAttachment(NEW_SESSION);
            if(newSession != null) {
                return newSession;
            }
        }
        String sessionId = sessionConfig.findSessionId(serverExchange);
        return getSession(sessionId);
    }

    @Override
    public Session getSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        return sessionRepository.findById(sessionId);
    }

    @Override
    public Session removeSession(HttpServerExchange serverExchange) {
        if (serverExchange != null) {
            String sessionId = sessionConfig.findSessionId(serverExchange);
            MapSession oldSession =  serverExchange.removeAttachment(NEW_SESSION);
             removeSession(sessionId);
             return oldSession;
        }

        return null;
    }

    @Override
    public void removeSession(String sessionId) {
        sessionRepository.deleteById(sessionId);
    }
}
