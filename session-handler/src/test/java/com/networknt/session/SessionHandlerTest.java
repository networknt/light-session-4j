package com.networknt.session;

import com.networknt.client.Http2Client;
import com.networknt.exception.ClientException;
import com.networknt.session.hazelcast.HazelcastSession;
import com.networknt.session.hazelcast.HazelcastSessionManager;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.server.session.SessionConfig;
import io.undertow.server.session.SessionManager;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.IoUtils;
import org.xnio.OptionMap;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by gavin on 2017-09-29.
 */
public class SessionHandlerTest {

    static final Logger logger = LoggerFactory.getLogger(SessionHandlerTest.class);

    static Undertow server = null;
    public static final String COUNT = "count";
    @BeforeClass
    public static void setUp() {
        if(server == null) {
            logger.info("starting server");
            HttpHandler handler = getTestHandler();

            SessionHandler sessionHandler = new SessionHandler();
            sessionHandler.setNext(new HttpHandler() {
                                       @Override
                                       public void handleRequest(final HttpServerExchange exchange) throws Exception {
                                           final HazelcastSessionManager manager = (HazelcastSessionManager) exchange.getAttachment(SessionManager.ATTACHMENT_KEY);
                                           SessionConfig sessionConfig = exchange.getAttachment(SessionConfig.ATTACHMENT_KEY);
                                           HazelcastSession session = manager.getSession(exchange, sessionConfig);
                                           if (session == null) {
                                               session = manager.createSession(exchange, sessionConfig);
                                               session.setAttribute(COUNT, 1);
                                           }
                                           Integer count = (Integer) session.getAttribute(COUNT);
                                           exchange.getResponseHeaders().add(new HttpString(COUNT), count.toString());
                                           session.setAttribute(COUNT, ++count);
                                           manager.save(session);
                                       }
                                   }

            );
            handler = sessionHandler;

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
                .add(Methods.GET, "/v2/pet/{petId}", exchange -> exchange.getResponseSender().send("test"));
    }

    @Test
    public void testGetMethod() throws Exception {
        final Http2Client client = Http2Client.getInstance();
        final CountDownLatch latch = new CountDownLatch(1);
        final ClientConnection connection;
        try {
            connection = client.connect(new URI("http://localhost:8080"), Http2Client.WORKER, Http2Client.SSL, Http2Client.POOL, OptionMap.EMPTY).get();
        } catch (Exception e) {
            throw new ClientException(e);
        }
        final AtomicReference<ClientResponse> reference = new AtomicReference<>();
        try {
            ClientRequest request = new ClientRequest().setPath("/get").setMethod(Methods.GET);

            connection.sendRequest(request, client.createClientCallback(reference, latch));

            latch.await();
        } catch (Exception e) {
            logger.error("Exception: ", e);
            throw new ClientException(e);
        } finally {
            IoUtils.safeClose(connection);
        }
        int statusCode = reference.get().getResponseCode();
        Assert.assertEquals(200, statusCode);
        if(statusCode == 200) {
            String body = reference.get().getAttachment(Http2Client.RESPONSE_BODY);
            Assert.assertNotNull(body);
            Assert.assertEquals("1", reference.get().getResponseHeaders().get(COUNT).get(0));

        }
     }
}
