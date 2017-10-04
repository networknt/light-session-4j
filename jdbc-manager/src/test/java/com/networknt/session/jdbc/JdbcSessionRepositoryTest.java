package com.networknt.session.jdbc;

import com.networknt.service.SingletonServiceFactory;
import com.networknt.session.Session;
import com.networknt.session.SessionRepository;
import io.undertow.server.session.SessionConfig;
import org.h2.tools.RunScript;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Created by chenga on 2017-09-28.
 */
public class JdbcSessionRepositoryTest {

    public static DataSource ds;
    private static Session session;
    static {
        ds = (DataSource) SingletonServiceFactory.getBean(DataSource.class);
        try (Connection connection = ds.getConnection()) {
            // Runscript doesn't work need to execute batch here.
            String schemaResourceName = "/create_session.sql";
            InputStream in = JdbcSessionRepositoryTest.class.getResourceAsStream(schemaResourceName);

            if (in == null) {
                throw new RuntimeException("Failed to load resource: " + schemaResourceName);
            }
            InputStreamReader reader = new InputStreamReader(in);
            RunScript.execute(connection, reader);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    private static SessionConfig sessionConfig = (SessionConfig)SingletonServiceFactory.getBean(SessionConfig.class);
    private static SessionRepository sessionRepository = (SessionRepository)SingletonServiceFactory.getBean(SessionRepository.class);


    @BeforeClass
    public static void setUp() {

    }

    @Test
    public void testSave() {
        session = sessionRepository.createSession();

    }

    @Test
    public void testGetSession() {
        session = sessionRepository.createSession();

        Assert.assertNotNull(sessionRepository.findById(session.getId()));

        System.out.println(sessionRepository.findById(session.getId()).getLastAccessedTime());

    }

}
