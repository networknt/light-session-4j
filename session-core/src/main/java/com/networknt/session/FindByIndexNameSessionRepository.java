
package com.networknt.session;

import io.undertow.server.session.Session;

import java.util.Map;

/**
 * Extends a basic {@link SessionRepository} to allow finding a session id by the
 * principal name. The principal name is defined by the {@link Session} attribute with the
 * name {@link FindByIndexNameSessionRepository#PRINCIPAL_NAME_INDEX_NAME}.
 *
 */
public interface FindByIndexNameSessionRepository<S extends Session>
		extends SessionRepository<S> {

	/**
	 * <p>
	 * A common session attribute that contains the current principal name (i.e.
	 * username).
	 * </p>
	 *
	 */
	String PRINCIPAL_NAME_INDEX_NAME = FindByIndexNameSessionRepository.class.getName()
			.concat(".PRINCIPAL_NAME_INDEX_NAME");

	/**
	 * Find a Map of the session id to the {@link Session} of all sessions that contain
	 * the session attribute with the name
	 * {@link FindByIndexNameSessionRepository#PRINCIPAL_NAME_INDEX_NAME} and the value of
	 * the specified principal name.
	 *
	 * @param indexName the name if the index (i.e.
	 * {@link FindByIndexNameSessionRepository#PRINCIPAL_NAME_INDEX_NAME})
	 * @param indexValue the value of the index to search for.
	 * @return a Map (never null) of the session id to the {@link Session} of all sessions
	 * that contain the session specified index name and the value of the specified index
	 * name. If no results are found, an empty Map is returned.
	 */
	Map<String, S> findByIndexNameAndIndexValue(String indexName, String indexValue);
}
