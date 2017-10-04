
package com.networknt.session.jdbc;

import com.networknt.session.Session;
import com.networknt.session.SessionManager;
import com.networknt.session.SessionRepository;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.SessionConfig;
import io.undertow.util.AttachmentKey;

import java.util.Map;
import java.util.Objects;

public class JdbcSessionManager implements SessionManager {


    private final AttachmentKey<JdbcSessionRepository.JdbcSession> NEW_SESSION = AttachmentKey.create(JdbcSessionRepository.JdbcSession.class);

    private SessionConfig sessionConfig;
    private SessionRepository sessionRepository;
	public static final String DEPLOY_NAME = "JDBC-SESSION";

    public JdbcSessionManager(SessionConfig sessionConfig,SessionRepository sessionRepository){
		Objects.requireNonNull(sessionConfig);
		Objects.requireNonNull(sessionRepository);
		this.sessionConfig=sessionConfig;
        this.sessionRepository=sessionRepository;

    }

	@Override
	public String getDeploymentName() {
		return DEPLOY_NAME;
	}


	@Override
	public Session createSession(final HttpServerExchange serverExchange) {

	//	String sessionID = sessionConfig.findSessionId(serverExchange);

		final JdbcSessionRepository.JdbcSession session = (JdbcSessionRepository.JdbcSession)sessionRepository.createSession();
		sessionConfig.setSessionId(serverExchange, session.getId());
		serverExchange.putAttachment(NEW_SESSION, session);
		return session;
	}



	public Map<String, Session> getSessions() {
    	return null;

	}


	@Override
	public Session getSession(final HttpServerExchange serverExchange) {
		if (serverExchange != null) {
			JdbcSessionRepository.JdbcSession newSession = serverExchange.getAttachment(NEW_SESSION);
			if (newSession != null) {
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
