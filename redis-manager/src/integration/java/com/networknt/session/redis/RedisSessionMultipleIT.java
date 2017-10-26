package com.networknt.session.redis;

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
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisSessionMultipleIT {
    static final Logger logger = LoggerFactory.getLogger(RedisSessionMultipleIT.class);
    public static final String COUNT = "count";

    static Undertow server1 = null;
    static Undertow server2 = null;

    @BeforeClass
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

    @AfterClass
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
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            Header[] header = result.getHeaders(COUNT);
            Assert.assertEquals("0", header[0].getValue());

            get = new HttpGet("http://localhost:8082/get");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            header = result.getHeaders(COUNT);
            Assert.assertEquals("1", header[0].getValue());

            get = new HttpGet("http://localhost:8081/get");
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
