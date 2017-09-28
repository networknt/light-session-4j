package com.networknt.session.hazelcast;

import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.IMap;
import com.networknt.session.SessionImpl;
import com.networknt.session.SessionStatistics;
import io.undertow.server.session.SecureRandomSessionIdGenerator;
import io.undertow.server.session.SessionConfig;
import io.undertow.server.session.SessionCookieConfig;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Created by gavin on 2017-09-27.
 */
public class HazelCastSessionManagerTest {

    private  static IMap<String, SessionImpl> sessions;

    private static HazelcastSessionManager sessionManager;

    private static SessionStatistics sessionStatistics = SessionStatistics.getInstance() ;

    private static SessionConfig config;
    private static  HazelcastSession session;

    @BeforeClass
    public static void setUp() {
        sessions =  Hazelcast.newHazelcastInstance().getMap("sessions");
        sessionManager = new HazelcastSessionManager(sessions,  "light-session", 1000, sessionStatistics);
        config =  new SessionCookieConfig();
        SecureRandomSessionIdGenerator sessionIdGenerator = new SecureRandomSessionIdGenerator();
        session= new HazelcastSession(sessionManager, sessionIdGenerator.createSessionId());
        sessionManager.start();
    }

    @Test
    public void testSave() {
        sessionManager.save(session);
        Assert.assertTrue(sessions.size()>0);
    }
}
