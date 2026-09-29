package com.malyah.accountmanager.notifications.infrastructure;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

/**
 * SIMULATED Meta Cloud API for tests only: a local HTTP server that records every request and answers with queued
 * responses (by default an acceptance with a fresh {@code wamid.FAKE-n}). It never runs outside the test classpath
 * and nothing it returns is evidence of a real delivery.
 */
final class FakeMetaServer implements AutoCloseable {
    record Request(String method, String path, String authorization, String body) { }

    record Response(int status, String body, long delayMillis) { }

    private final HttpServer server;
    private final ConcurrentLinkedDeque<Response> responses = new ConcurrentLinkedDeque<>();
    private final List<Request> requests = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger ids = new AtomicInteger();

    FakeMetaServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), body));
            var response = responses.pollFirst();
            if (response == null) response = new Response(200, """
                    {"messaging_product":"whatsapp","contacts":[{"input":"x","wa_id":"x"}],
                     "messages":[{"id":"wamid.FAKE-%d","message_status":"accepted"}]}
                    """.formatted(ids.incrementAndGet()), 0);
            try {
                if (response.delayMillis() > 0) Thread.sleep(response.delayMillis());
                var bytes = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            } catch (InterruptedException | IOException ignored) {
                // The client gave up (timeout); nothing else to do.
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void enqueue(int status, String body) {
        responses.addLast(new Response(status, body, 0));
    }

    void enqueueDelayed(int status, String body, long delayMillis) {
        responses.addLast(new Response(status, body, delayMillis));
    }

    List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
