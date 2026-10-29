package com.sparta.ticket.payment.infrastructure.pg;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class PgHttpObservationTest {
    @ParameterizedTest
    @ValueSource(ints = {200, 400, 429, 503})
    void actualHttpIsCountedOnceWithBoundedOutcomeAndWithoutTransactionPath(int status) throws Exception {
        var calls = new AtomicInteger();
        var server = new HttpFixture(status, calls, null, null);
        var meters = new SimpleMeterRegistry();
        try {
            var client = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.port())
                    .requestFactory(new JdkClientHttpRequestFactory())
                    .build();
            var result = PgHttp.call(c -> c.get().uri("/private-order-123"), client, meters, "TOSS", "inquire");
            assertThat(result).isEqualTo(new PgHttp.PgCall.Responded(status, "{}"));
            assertThat(calls).hasValue(1);
            assertThat(meters.get("payment.pg.http.duration")
                            .tag("outcome", status == 429 ? "429" : status / 100 + "xx")
                            .timer()
                            .count())
                    .isEqualTo(1);
            assertThat(meters.get("payment.pg.http.inflight").longTaskTimer().activeTasks())
                    .isZero();
            assertThat(meters.getMeters())
                    .allSatisfy(m -> assertThat(m.getId().getTags().toString()).doesNotContain("private-order"));
        } finally {
            server.close();
            meters.close();
        }
    }

    @Test
    void inFlightAndResponseLossAreVisibleWithoutRetry() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var server = new HttpFixture(200, new AtomicInteger(), entered, release);
        var meters = new SimpleMeterRegistry();
        try {
            var factory = new JdkClientHttpRequestFactory();
            factory.setReadTimeout(Duration.ofSeconds(1));
            var client = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + server.port())
                    .requestFactory(factory)
                    .build();
            var request = CompletableFuture.supplyAsync(
                    () -> PgHttp.call(c -> c.post().uri("/cancel"), client, meters, "KAKAO", "cancel"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(meters.get("payment.pg.http.inflight").longTaskTimer().activeTasks())
                    .isEqualTo(1);
            assertThat(request.get(5, TimeUnit.SECONDS)).isInstanceOf(PgHttp.PgCall.SentButFailed.class);
            assertThat(meters.get("payment.pg.http.duration")
                            .tag("outcome", "response_lost")
                            .timer()
                            .count())
                    .isEqualTo(1);
            assertThat(meters.get("payment.pg.http.inflight").longTaskTimer().activeTasks())
                    .isZero();
        } finally {
            release.countDown();
            server.close();
            meters.close();
        }
    }

    private static final class HttpFixture implements AutoCloseable {
        private final ServerSocket server;

        private HttpFixture(int status, AtomicInteger calls, CountDownLatch entered, CountDownLatch release)
                throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket socket = server.accept()) {
                        var input = new BufferedReader(
                                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        String line = input.readLine();
                        while (line != null && !line.isEmpty()) line = input.readLine();
                        calls.incrementAndGet();
                        if (entered != null) {
                            entered.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        } else {
                            socket.getOutputStream()
                                    .write(("HTTP/1.1 " + status
                                                    + " Test\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
                                            .getBytes(StandardCharsets.UTF_8));
                        }
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        private int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
