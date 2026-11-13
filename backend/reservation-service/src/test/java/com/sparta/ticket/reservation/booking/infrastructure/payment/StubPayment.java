package com.sparta.ticket.reservation.booking.infrastructure.payment;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "메서드 경로"마다 정해 둔 응답을 돌려주고, 받은 요청 본문과 횟수를 기록하는 HTTP 스텁.
 * 요청 한 건마다 연결을 닫는 HTTP/1.1만 다룬다. Feign 기본 클라이언트의 요청에는 이것으로 충분하다.
 */
final class StubPayment {

    private record Reply(int status, String body, Map<String, String> headers, long delayMillis) {}

    private final ServerSocket server;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Map<String, Reply> replies = new ConcurrentHashMap<>();
    private final Map<String, List<String>> bodies = new ConcurrentHashMap<>();

    private StubPayment(ServerSocket server) {
        this.server = server;
    }

    static StubPayment start() {
        try {
            StubPayment stub = new StubPayment(new ServerSocket(0, 50, InetAddress.getLoopbackAddress()));
            Thread acceptor = new Thread(stub::acceptLoop, "stub-payment");
            acceptor.setDaemon(true);
            acceptor.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    int port() {
        return server.getLocalPort();
    }

    void stop() {
        workers.shutdownNow();
        try {
            server.close();
        } catch (IOException ignored) {
            // 테스트가 끝난 뒤라 상관없다.
        }
    }

    void reset() {
        replies.clear();
        bodies.clear();
    }

    void respond(String key, int status, String body) {
        respond(key, status, body, Map.of());
    }

    void respond(String key, int status, String body, Map<String, String> headers) {
        replies.put(key, new Reply(status, body, headers, 0));
    }

    void delay(String key, long millis) {
        respondLate(key, millis, 200, "{}");
    }

    /** 늦게 오는 정상 답. 타임아웃이 걸리지 않으면 이 답을 받으므로 타임아웃이 걸렸는지 가를 수 있다. */
    void respondLate(String key, long millis, int status, String body) {
        replies.put(key, new Reply(status, body, Map.of(), millis));
    }

    String lastBody(String key) {
        List<String> received = bodies.get(key);
        return received.get(received.size() - 1);
    }

    int count(String key) {
        return bodies.getOrDefault(key, List.of()).size();
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                workers.execute(() -> handle(socket));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        try (socket) {
            BufferedReader in =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String[] requestLine = in.readLine().split(" ");
            int contentLength = 0;
            for (String header = in.readLine(); header != null && !header.isEmpty(); header = in.readLine()) {
                if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    contentLength = Integer.parseInt(
                            header.substring("content-length:".length()).trim());
                }
            }
            char[] body = new char[contentLength];
            int read = 0;
            while (read < contentLength) {
                int n = in.read(body, read, contentLength - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            String key = requestLine[0] + " " + requestLine[1];
            bodies.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(new String(body, 0, read));
            Reply reply = replies.getOrDefault(key, new Reply(500, "{}", Map.of(), 0));
            if (reply.delayMillis() > 0) {
                Thread.sleep(reply.delayMillis());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder("HTTP/1.1 " + reply.status() + " X\r\n")
                    .append("Content-Type: application/json\r\nContent-Length: ")
                    .append(bytes.length)
                    .append("\r\nConnection: close\r\n");
            reply.headers()
                    .forEach((name, value) ->
                            head.append(name).append(": ").append(value).append("\r\n"));
            OutputStream out = socket.getOutputStream();
            out.write(head.append("\r\n").toString().getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            // 클라이언트가 타임아웃으로 먼저 연결을 끊으면 쓰기가 실패한다. 테스트에는 상관없다.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
