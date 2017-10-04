package com.networknt.session;

import java.util.Map;

/**
 * A repository interface for managing {@link Session} instances.
 *
 * @param <S> the {@link Session} type
 * @author Rob Winch
 *
 */
public interface SessionRepository<S extends Session> {
	/**
	 * Creates a new {@link Session} that is capable of being persisted by this
	 * {@link SessionRepository}.
	 *
	 * <p>
	 * This allows optimizations and customizations in how the {@link Session} is
	 * persisted. For example, the implementation returned might keep track of the changes
	 * ensuring that only the delta needs to be persisted on a save.
	 * </p>
	 *
	 * @return a new {@link Session} that is capable of being persisted by this
	 * {@link SessionRepository}
	 */
	S createSession();

	/**
	 * Ensures the {@link Session} created by
	 * {@link com.networknt.session.SessionRepository#createSession()} is saved.
	 *
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
	 * Gets the {@link Session} by the {@link Session#getId()} or null if no
	 * {@link Session} is found.
	 *
	 * @param id the {@link com.networknt.session.Session#getId()} to lookup
	 * @return the {@link Session} by the {@link Session#getId()} or null if no
	 * {@link Session} is found.
	 */
	S findById(String id);

	/**
	 * Deletes the {@link Session} with the given {@link Session#getId()} or does nothing
	 * if the {@link Session} is not found.
	 * @param id the {@link com.networknt.session.Session#getId()} to delete
	 */
	void deleteById(String id);

}
