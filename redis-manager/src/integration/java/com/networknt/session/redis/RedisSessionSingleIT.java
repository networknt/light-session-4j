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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by gavin on 2017-09-27.
 */
public class RedisSessionSingleIT {

    static final Logger logger = LoggerFactory.getLogger(RedisSessionSingleIT.class);
    public static final String COUNT = "count";

    static Undertow server = null;

    @BeforeAll
    public static void setUp() {
        if(server == null) {
            logger.info("starting server");
            HttpHandler handler = getTestHandler();
            server = Undertow.builder()
                    .addHttpListener(18080, "localhost")
                    .setHandler(handler)
                    .build();
            server.start();
        }
    }

    @AfterAll
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
            HttpGet get = new HttpGet("http://localhost:18080/get");
            HttpResponse result = client.execute(get);
            Assertions.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            Header[] header = result.getHeaders(COUNT);
            Assertions.assertEquals("0", header[0].getValue());

            get = new HttpGet("http://localhost:18080/get");
            result = client.execute(get);
            Assertions.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            header = result.getHeaders(COUNT);
            Assertions.assertEquals("1", header[0].getValue());

            get = new HttpGet("http://localhost:18080/get");
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
