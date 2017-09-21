
package com.networknt.session.data.hazelcast;

import com.hazelcast.query.extractor.ValueCollector;
import com.hazelcast.query.extractor.ValueExtractor;
import com.networknt.session.FindByIndexNameSessionRepository;
import com.networknt.session.MapSession;
import io.undertow.server.session.Session;


/**
 * Hazelcast {@link ValueExtractor} responsible for extracting principal name from the
 * {@link MapSession}.
 *
 */
public class PrincipalNameExtractor extends ValueExtractor<MapSession, String> {

	private static final PrincipalNameResolver PRINCIPAL_NAME_RESOLVER =
			new PrincipalNameResolver();

	@SuppressWarnings("unchecked")
	public void extract(MapSession target, String argument,
			ValueCollector collector) {
		String principalName = PRINCIPAL_NAME_RESOLVER.resolvePrincipal(target);
		if (principalName != null) {
			collector.addObject(principalName);
		}
	}


	static class PrincipalNameResolver {

		private static final String SECURITY_CONTEXT = "SECURITY_CONTEXT";


		public String resolvePrincipal(Session session) {
			String principalName = (String)session.getAttribute(
					FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME);
			if (principalName != null) {
				return principalName;
			}
			Object authentication = session.getAttribute(SECURITY_CONTEXT);
			if (authentication != null) {
				//TODO handler it here
				return authentication.toString();
			}
			return null;
		}

	}

}
