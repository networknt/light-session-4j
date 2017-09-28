
package com.networknt.session.hazelcast;

import com.networknt.session.SessionRepository;
import io.undertow.server.session.Session;

/**
 * Specifies when to write to the backing Hazelcast instance.
 *
 */
public enum HazelcastFlushMode {

	/**
	 * Only writes to Hazelcast when
	 * {@link SessionRepository#save(Session)} is invoked. In
	 * a web environment this is typically done as soon as the HTTP response is committed.
	 */
	ON_SAVE,

	/**
	 * Writes to Hazelcast as soon as possible. For example
	 * {createSession()} will write the session to Hazelcast.
	 * Another example is that setting an attribute on the session will also write to
	 * Hazelcast immediately.
	 */
	IMMEDIATE

}
