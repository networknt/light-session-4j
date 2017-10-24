package com.networknt.session.jdbc;

import com.networknt.service.SingletonServiceFactory;
import com.networknt.session.Session;
import com.networknt.session.SessionManager;
import com.networknt.session.SessionRepository;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.server.session.SessionConfig;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCookieStore;
import org.h2.tools.RunScript;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Created by gavin on 2017-09-27.
 */
public class JdbcSessionSingleTest {

    static final Logger logger = LoggerFactory.getLogger(JdbcSessionSingleTest.class);
    public static final String COUNT = "count";

    public static DataSource ds;
    static {
        ds = (DataSource) SingletonServiceFactory.getBean(DataSource.class);
        try (Connection connection = ds.getConnection()) {
            // Runscript doesn't work need to execute batch here.
            String schemaResourceName = "/create_session.sql";
            InputStream in = JdbcSessionRepositoryTest.class.getResourceAsStream(schemaResourceName);

            if (in == null) {
                throw new RuntimeException("Failed to load resource: " + schemaResourceName);
            }
            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            RunScript.execute(connection, reader);

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static SessionConfig sessionConfig = (SessionConfig)SingletonServiceFactory.getBean(SessionConfig.class);
    private static SessionRepository sessionRepository = (SessionRepository)SingletonServiceFactory.getBean(SessionRepository.class);

    static Undertow server = null;

    @BeforeClass
    public static void setUp() {
        if(server == null) {
            logger.info("starting server");
            HttpHandler handler = getTestHandler();
            server = Undertow.builder()
                    .addHttpListener(8080, "localhost")
                    .setHandler(handler)
                    .build();
            server.start();
        }
    }

    @AfterClass
    public static void tearDown() throws Exception {
        if(server != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {

            }
            server.stop();
            logger.info("The server is stopped.");
        }
    }

    static RoutingHandler getTestHandler() {
        return Handlers.routing()
                .add(Methods.GET, "/get", exchange -> {
                    SessionManager sessionManager = SingletonServiceFactory.getBean(SessionManager.class);
                    Session session = sessionManager.getSession(exchange);
                    if(session == null) {
                        session = sessionManager.createSession(exchange);
                        session.setAttribute(COUNT, 0);
                        logger.debug("first time access create a session and count is 0 sessionId = " + session.getId());
                    }
                    Integer count = (Integer) session.getAttribute(COUNT);
                    logger.debug("not the first time, get count from session = " + count + " sessionId = " + session.getId());
                    exchange.getResponseHeaders().add(new HttpString(COUNT), count.toString());
                    session.setAttribute(COUNT, ++count);
                });
    }

    @Test
    public void testGet() throws Exception {
        TestHttpClient client = new TestHttpClient();
        client.setCookieStore(new BasicCookieStore());
        try {
            HttpGet get = new HttpGet("http://localhost:8080/get");
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            Header[] header = result.getHeaders(COUNT);
            Assert.assertEquals("0", header[0].getValue());

            get = new HttpGet("http://localhost:8080/get");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            header = result.getHeaders(COUNT);
            Assert.assertEquals("1", header[0].getValue());

            get = new HttpGet("http://localhost:8080/get");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            header = result.getHeaders(COUNT);
            Assert.assertEquals("2", header[0].getValue());


        } finally {
            client.getConnectionManager().shutdown();
        }
    }

}
