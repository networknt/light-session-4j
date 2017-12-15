package com.networknt.session.jdbc;

import com.networknt.service.SingletonServiceFactory;
import com.networknt.session.Session;
import com.networknt.session.SessionManager;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.server.HttpHandler;
import io.undertow.server.RoutingHandler;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCookieStore;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

public class JdbcSessionMultipleTest {
    static final Logger logger = LoggerFactory.getLogger(JdbcSessionMultipleTest.class);
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

    static Undertow server1 = null;
    static Undertow server2 = null;

    @BeforeAll
    public static void setUp() {
        if(server1 == null) {
            logger.info("starting server1");
            HttpHandler handler = getTestHandler();
            server1 = Undertow.builder()
                    .addHttpListener(8081, "localhost")
                    .setHandler(handler)
                    .build();
            server1.start();
        }
        if(server2 == null) {
            logger.info("starting server2");
            HttpHandler handler = getTestHandler();
            server2 = Undertow.builder()
                    .addHttpListener(8082, "localhost")
                    .setHandler(handler)
                    .build();
            server2.start();
        }

    }

    @AfterAll
    public static void tearDown() throws Exception {
        if(server1 != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {

            }
            server1.stop();
            logger.info("The server1 is stopped.");
        }
        if(server2 != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {

            }
            server2.stop();
            logger.info("The server2 is stopped.");
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
            HttpGet get = new HttpGet("http://localhost:8081/get");
            HttpResponse result = client.execute(get);
            Assertions.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            Header[] header = result.getHeaders(COUNT);
            Assertions.assertEquals("0", header[0].getValue());

            get = new HttpGet("http://localhost:8082/get");
            result = client.execute(get);
            Assertions.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            header = result.getHeaders(COUNT);
            Assertions.assertEquals("1", header[0].getValue());

            get = new HttpGet("http://localhost:8081/get");
            result = client.execute(get);
            Assertions.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            header = result.getHeaders(COUNT);
            Assertions.assertEquals("2", header[0].getValue());
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

}
