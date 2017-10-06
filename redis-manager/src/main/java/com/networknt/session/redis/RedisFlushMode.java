
package com.networknt.session.redis;

/**
 * Specifies when to write to the backing Redis instance.
 *
 */
public enum RedisFlushMode {
	/**
	 * Only writes to Redis when
	 * a web environment this is typically done as soon as the HTTP response is committed.
	 */
	ON_SAVE,

	/**
	 * Writes to Redis as soon as possible.
	 */
	IMMEDIATE
}
