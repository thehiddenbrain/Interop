package org.p32h.interop.epa.pave.client.lookup;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lookup API on the loopback interface for the saved-lookup tests (R-11): it records each request
 * as it arrived and answers each path with what the test set. Nothing leaves the machine.
 */
final class LoopbackApi implements AutoCloseable {

    /** One request as it arrived: method, path with its query, headers by lower-case name, body. */
    record Seen(String method, String path, Map<String, String> headers, String body) {
    }

    private record Answer(int status, String body, long delayMillis) {
    }

    private final HttpServer server;
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();

    LoopbackApi() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** The base address, such as http://127.0.0.1:12345. */
    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Answers every request whose path (less its query) is this one. */
    LoopbackApi answer(String path, int status, String body) {
        answers.put(path, new Answer(status, body, 0));
        return this;
    }

    LoopbackApi slow(String path, long delayMillis) {
        answers.put(path, new Answer(200, "{}", delayMillis));
        return this;
    }

    List<Seen> seen() {
        return List.copyOf(seen);
    }

    Seen last() {
        List<Seen> all = seen();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String path = exchange.getRequestURI().getRawPath();
        String query = exchange.getRequestURI().getRawQuery();
        seen.add(new Seen(exchange.getRequestMethod(), query == null ? path : path + "?" + query, headers, body));
        Answer answer = answers.getOrDefault(path, new Answer(404, "{}", 0));
        if (answer.delayMillis() > 0) {
            try {
                Thread.sleep(answer.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
        } catch (IOException gone) {
            // The client gave up first, which a timeout test means to happen.
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
