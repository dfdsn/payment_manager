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
 * SIMULATED Evolution API for tests only: a local HTTP server that records every request and answers with queued
 * responses. {@code GET /instance/connectionState/...} answers the configured state (by default {@code open});
 * {@code POST /message/sendText/...} answers by default an acceptance with a fresh {@code key.id} ({@code FAKE-n}).
 * It never runs outside the test classpath and nothing it returns is evidence of a real delivery.
 */
final class FakeEvolutionServer implements AutoCloseable {
    record Request(String method, String path, String apiKey, String body) { }

    record Response(int status, String body, long delayMillis, String retryAfter) { }

    private final HttpServer server;
    private final ConcurrentLinkedDeque<Response> responses = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Response> stateResponses = new ConcurrentLinkedDeque<>();
    private final List<Request> requests = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger ids = new AtomicInteger();
    private volatile String state = "open";

    FakeEvolutionServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var path = exchange.getRequestURI().getPath();
            requests.add(new Request(exchange.getRequestMethod(), path,
                    exchange.getRequestHeaders().getFirst("apikey"), body));
            Response response;
            if (path.startsWith("/instance/connectionState/")) {
                response = stateResponses.pollFirst();
                if (response == null) response = new Response(200, """
                        {"instance":{"instanceName":"%s","state":"%s"}}
                        """.formatted(path.substring(path.lastIndexOf('/') + 1), state), 0, null);
            } else {
                response = responses.pollFirst();
                if (response == null) response = new Response(201, """
                        {"key":{"remoteJid":"5511987654321@s.whatsapp.net","fromMe":true,"id":"FAKE-%d"},
                         "pushName":"","status":"PENDING","message":{"conversation":"x"},"messageType":"conversation",
                         "messageTimestamp":1791201600,"instanceId":"i","source":"unknown"}
                        """.formatted(ids.incrementAndGet()), 0, null);
            }
            try {
                if (response.delayMillis() > 0) Thread.sleep(response.delayMillis());
                var bytes = response.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                if (response.retryAfter() != null) exchange.getResponseHeaders().add("Retry-After", response.retryAfter());
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

    /** The state every later connection check reports ({@code open}, {@code close}, {@code connecting}). */
    void state(String state) {
        this.state = state;
    }

    /** One answer for the next connection check only. */
    void enqueueState(int status, String body) {
        stateResponses.addLast(new Response(status, body, 0, null));
    }

    void enqueue(int status, String body) {
        responses.addLast(new Response(status, body, 0, null));
    }

    void enqueueDelayed(int status, String body, long delayMillis) {
        responses.addLast(new Response(status, body, delayMillis, null));
    }

    /** H08.5: a refusal carrying a {@code Retry-After} header (seconds). */
    void enqueueRetryAfter(int status, String body, String retryAfter) {
        responses.addLast(new Response(status, body, 0, retryAfter));
    }

    /** Only the message sends ({@code POST /message/sendText/...}). */
    List<Request> requests() {
        synchronized (requests) {
            return requests.stream().filter(r -> r.path().startsWith("/message/")).toList();
        }
    }

    /** Only the connection checks. */
    List<Request> stateChecks() {
        synchronized (requests) {
            return requests.stream().filter(r -> r.path().startsWith("/instance/")).toList();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
