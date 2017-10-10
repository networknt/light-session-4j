
package com.networknt.session.redis;

import com.networknt.session.Session;
import com.networknt.session.SessionManager;
import com.networknt.session.SessionRepository;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.SessionConfig;
import io.undertow.util.AttachmentKey;

import java.util.Map;
import java.util.Objects;

public class RedisSessionManager implements SessionManager {


    private final AttachmentKey<RedisSessionRepository.RedisSession> NEW_SESSION = AttachmentKey.create(RedisSessionRepository.RedisSession.class);

    private SessionConfig sessionConfig;
    private SessionRepository sessionRepository;
	public static final String DEPLOY_NAME = "REDIS-SESSION";

    public RedisSessionManager(SessionConfig sessionConfig, SessionRepository sessionRepository){
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

		final RedisSessionRepository.RedisSession session = (RedisSessionRepository.RedisSession)sessionRepository.createSession();
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
			RedisSessionRepository.RedisSession newSession = serverExchange.getAttachment(NEW_SESSION);
			if (newSession != null) {
				return newSession;
			}
		}
		String sessionId = sessionConfig.findSessionId(serverExchange);
		Session session = getSession(sessionId);
		if (session == null ) {
			sessionConfig.clearSession(serverExchange, sessionId);
		}
		return session;
	}

	@Override
	public Session getSession(String sessionId) {
		if (sessionId == null) {
			return null;
		}
		Session session = sessionRepository.findById(sessionId);
		if (session!=null && !session.isExpired()) {
			session.setLastAccessedTime(System.currentTimeMillis());

			return session;
		}
		return null;
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
