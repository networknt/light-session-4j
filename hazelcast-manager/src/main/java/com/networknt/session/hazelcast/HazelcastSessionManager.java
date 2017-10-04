package com.networknt.session.hazelcast;

import com.networknt.session.MapSession;
import com.networknt.session.Session;
import com.networknt.session.SessionManager;
import com.networknt.session.SessionRepository;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.SessionConfig;
import io.undertow.util.AttachmentKey;

public class HazelcastSessionManager implements SessionManager {

    private final AttachmentKey<HazelcastSessionRepository.HazelcastSession> NEW_SESSION = AttachmentKey.create(HazelcastSessionRepository.HazelcastSession.class);

    private SessionConfig sessionConfig;
    private SessionRepository sessionRepository;

    public HazelcastSessionManager(SessionConfig sessionConfig, SessionRepository sessionRepository) {
        this.sessionConfig = sessionConfig;
        this.sessionRepository = sessionRepository;
    }

    @Override
    public String getDeploymentName() {
        return "Hazelcast";
    }

    @Override
    public Session createSession(HttpServerExchange serverExchange) {
        final HazelcastSessionRepository.HazelcastSession session = (HazelcastSessionRepository.HazelcastSession)sessionRepository.createSession();
        sessionConfig.setSessionId(serverExchange, session.getId());
        serverExchange.putAttachment(NEW_SESSION, session);
        return session;
    }

    @Override
    public Session getSession(HttpServerExchange serverExchange) {
        if (serverExchange != null) {
            HazelcastSessionRepository.HazelcastSession newSession = serverExchange.getAttachment(NEW_SESSION);
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
            Session oldSession =  serverExchange.removeAttachment(NEW_SESSION);
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
