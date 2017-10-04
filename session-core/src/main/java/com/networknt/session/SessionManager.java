package com.networknt.session;

import io.undertow.server.HttpServerExchange;

/**
 * Interface that manages sessions.
 * <p>
 * The session manager is responsible for maintaining session state.
 * <p>
 * As part of session creation the session manager MUST attempt to retrieve the {SessionCookieConfig} from
 * the {@link HttpServerExchange} and use it to set the session cookie. The frees up the session manager from
 * needing to know details of the cookie configuration. When invalidating a session the session manager MUST
 * also use this to clear the session cookie.
 *
 * @author Stuart Douglas
 */
public interface SessionManager {
    /**
     * Uniquely identifies this session manager
     * @return a unique identifier
     */
    String getDeploymentName();

    /**
     * Creates a new session.
     * <p>
     * This method *MUST* call {SessionConfig#findSessionId(HttpServerExchange)} first to
     * determine if an existing session ID is present in the exchange. If this id is present then it must be used
     * as the new session ID. If a session with this ID already exists then an {@link IllegalStateException} must be
     * thrown.
     * <p>
     * <p>
     * This requirement exists to allow forwards across servlet contexts to work correctly.
     *
     * The session manager is responsible for making sure that a newly created session is accessible to later calls to
     * {@link #getSession(HttpServerExchange)} from the same request. It is recommended
     * that a non static attachment key be used to store the newly created session as an attachment. The attachment key
     * must be static to prevent different session managers from interfering with each other.
     *
     * @return The created session
     */
    Session createSession(final HttpServerExchange serverExchange);

    /**
     * @return An IoFuture that can be used to retrieve the session, or an IoFuture that will return null if not found
     */
    Session getSession(final HttpServerExchange serverExchange);

    /**
     * Retrieves a session with the given session id
     *
     * @param sessionId The session ID
     * @return The session, or null if it does not exist
     */
    Session getSession(final String sessionId);

    /**
     * @return An IoFuture that can be used to remove the session from serverExchange, or an IoFuture that will return null if not found
     */
    Session removeSession(final HttpServerExchange serverExchange);

    /**
     * Remove a session with the given session id
     *
     * @param sessionId The session ID
     */
    void removeSession(final String sessionId);

}
