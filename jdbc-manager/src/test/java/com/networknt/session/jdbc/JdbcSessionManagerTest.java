package com.networknt.session.jdbc;

import com.networknt.service.SingletonServiceFactory;
import org.h2.tools.RunScript;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Created by chenga on 2017-09-28.
 */
public class JdbcSessionManagerTest {

    public static DataSource ds;
    private static  JdbcSession session;
    static {
        ds = (DataSource) SingletonServiceFactory.getBean(DataSource.class);
        try (Connection connection = ds.getConnection()) {
            // Runscript doesn't work need to execute batch here.
            String schemaResourceName = "/create_session.sql";
            InputStream in = JdbcSessionManagerTest.class.getResourceAsStream(schemaResourceName);

            if (in == null) {
                throw new RuntimeException("Failed to load resource: " + schemaResourceName);
            }
            InputStreamReader reader = new InputStreamReader(in);
            RunScript.execute(connection, reader);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /*
    private static SessionConfig sessionConfig = (SessionConfig)SingletonServiceFactory.getBean(SessionConfig.class);
    private static SessionStatistics sessionStatistics = (SessionStatistics)SingletonServiceFactory.getBean(SessionStatistics.class);
    private static JdbcSessionManager jdbcSessionManager = new JdbcSessionManager(ds, sessionConfig, "light-session", sessionStatistics);

    @BeforeClass
    public static void setUp() {
        jdbcSessionManager.start();
        SecureRandomSessionIdGenerator sessionIdGenerator = new SecureRandomSessionIdGenerator();
        session = new JdbcSession(jdbcSessionManager, sessionIdGenerator.createSessionId());

    }

    @Test
    public void testSave() {
        jdbcSessionManager.save(session);

        Assert.assertTrue(jdbcSessionManager.getSessions().size()>0);

    }

    @Test
    public void testGetSession() {
        jdbcSessionManager.save(session);

        Assert.assertNotNull(jdbcSessionManager.getSession(session.getId()));

    }
    */
}
