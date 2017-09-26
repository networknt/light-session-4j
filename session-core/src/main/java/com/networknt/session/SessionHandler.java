/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.networknt.session;

import io.undertow.Handlers;
import io.undertow.UndertowMessages;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.session.Session;
import io.undertow.server.session.SessionAttachmentHandler;
import io.undertow.server.session.SessionConfig;
import io.undertow.server.session.SessionManager;


public class SessionHandler implements HttpHandler {

    private volatile HttpHandler next = ResponseCodeHandler.HANDLE_404;

    private volatile SessionManager sessionManager;

    private final SessionConfig sessionConfig;

    public SessionHandler(final SessionManager sessionManager, final SessionConfig sessionConfig) {
        this.sessionConfig = sessionConfig;
        if (sessionManager == null) {
            throw UndertowMessages.MESSAGES.sessionManagerMustNotBeNull();
        }
        this.sessionManager = sessionManager;
    }

    public SessionHandler(final HttpHandler next, final SessionManager sessionManager, final SessionConfig sessionConfig) {
        this.sessionConfig = sessionConfig;
        if (sessionManager == null) {
            throw UndertowMessages.MESSAGES.sessionManagerMustNotBeNull();
        }
        this.next = next;
        this.sessionManager = sessionManager;
    }

    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        exchange.putAttachment(SessionManager.ATTACHMENT_KEY, sessionManager);
        exchange.putAttachment(SessionConfig.ATTACHMENT_KEY, sessionConfig);
        final UpdateLastAccessTimeListener handler = new UpdateLastAccessTimeListener(sessionConfig, sessionManager);
        exchange.addExchangeCompleteListener(handler);
        next.handleRequest(exchange);

    }


    public HttpHandler getNext() {
        return next;
    }

    public SessionHandler setNext(final HttpHandler next) {
        Handlers.handlerNotNull(next);
        this.next = next;
        return this;
    }

    public SessionManager getSessionManager() {
        return sessionManager;
    }

    public SessionHandler setSessionManager(final SessionManager sessionManager) {
        if (sessionManager == null) {
            throw UndertowMessages.MESSAGES.sessionManagerMustNotBeNull();
        }
        this.sessionManager = sessionManager;
        return this;
    }

    private static class UpdateLastAccessTimeListener implements ExchangeCompletionListener {

        private final SessionConfig sessionConfig;
        private final SessionManager sessionManager;

        private UpdateLastAccessTimeListener(final SessionConfig sessionConfig, final SessionManager sessionManager) {
            this.sessionConfig = sessionConfig;
            this.sessionManager = sessionManager;
        }

        @Override
        public void exchangeEvent(final HttpServerExchange exchange, final NextListener next) {
            try {
                final Session session = sessionManager.getSession(exchange, sessionConfig);
                if (session != null) {
                    session.requestDone(exchange);
                }
            } finally {
                next.proceed();
            }
        }
    }

}
