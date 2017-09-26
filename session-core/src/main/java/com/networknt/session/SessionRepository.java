
package com.networknt.session;

import io.undertow.server.session.Session;

/**
 * A repository interface for managing session instances.
 *

 */
public interface SessionRepository<S extends Session> {


	/**
	 * Ensures the {@link Session} created by*
	 * <p>
	 * Some implementations may choose to save as the {@link Session} is updated by
	 * returning a {@link Session} that immediately persists any changes. In this case,
	 * this method may not actually do anything.
	 * </p>
	 *
	 * @param session the {@link Session} to save
	 */
	void save(S session);



	/**
	 * Deletes the {@link Session} with the given {@link Session#getId()} or does nothing
	 * if the {@link Session} is not found.
	 * @param id the  to delete
	 */
	void deleteById(String id);



}
