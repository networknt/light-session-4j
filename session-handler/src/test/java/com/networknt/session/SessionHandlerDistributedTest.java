package com.networknt.session;

import com.networknt.client.Http2Client;
import com.networknt.exception.ClientException;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientRequest;
import io.undertow.client.ClientResponse;
import io.undertow.server.RoutingHandler;
import io.undertow.util.Methods;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.IoUtils;
import org.xnio.OptionMap;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Created by gavin on 2017-09-29.
 */
public class SessionHandlerDistributedTest {

    static final Logger logger = LoggerFactory.getLogger(SessionHandlerDistributedTest.class);


    static Undertow server1 = null;
    static Undertow server2 = null;

    @ClassRule
    public static TestServer server = TestServer.getInstance();

    static final boolean enableHttp2 = server.getServerConfig().isEnableHttp2();
    static final boolean enableHttps = server.getServerConfig().isEnableHttps();
    static final int httpPort = server.getServerConfig().getHttpPort();
    static final int httpsPort = server.getServerConfig().getHttpsPort();
    static final String url = enableHttp2 || enableHttps ? "https://localhost:" + httpsPort : "http://localhost:" + httpPort;
    public static final String COUNT = "count";
    /*
    @BeforeClass
    public static void setUp() {
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

        if(server1 == null) {
            logger.info("starting server");

            server1 = Undertow.builder()
                    .addHttpListener(8080, "localhost")
                    .setHandler(handler)
                    .build();
            server1.start();
        }

        if(server2 == null) {
            logger.info("starting server");

            server2 = Undertow.builder()
                    .addHttpListener(8081, "localhost")
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
                .add(Methods.GET, "/v2/pet/{petId}", exchange -> exchange.getResponseSender().send("test"));
    }

    @Test
    public void testGet() throws Exception {
        final Http2Client client = Http2Client.getInstance();
        final CountDownLatch latch = new CountDownLatch(10);
        final ClientConnection connection;
        try {
            connection = client.connect(new URI(url), Http2Client.WORKER, Http2Client.SSL, Http2Client.POOL, enableHttp2 ? OptionMap.create(UndertowOptions.ENABLE_HTTP2, true): OptionMap.EMPTY).get();
        } catch (Exception e) {
            throw new ClientException(e);
        }
        final List<AtomicReference<ClientResponse>> references = new CopyOnWriteArrayList<>();
        try {
            connection.getIoThread().execute(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 10; i++) {
                        AtomicReference<ClientResponse> reference = new AtomicReference<>();
                        references.add(i, reference);
                        final ClientRequest request = new ClientRequest().setMethod(Methods.GET).setPath("/");
                        connection.sendRequest(request, client.createClientCallback(reference, latch));
                    }
                }

            });

            latch.await();
        } catch (Exception e) {
            logger.error("Exception: ", e);
            throw new ClientException(e);
        } finally {
            IoUtils.safeClose(connection);
        }
        for (final AtomicReference<ClientResponse> reference : references) {
            System.out.println(reference.get().getAttachment(Http2Client.RESPONSE_BODY));
            System.out.println("session value:" + reference.get().getResponseHeaders().get(COUNT).get(0));
        }
    }
    */
}
