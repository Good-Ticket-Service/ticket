package com.sparta.ticket.payment.infrastructure.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.port.pg.PgClient.PgApproveRequest;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.infrastructure.pg.toss.TossPgClient;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 실제 HTTP로 부르는 어댑터가 동시 호출 상한을 지키고, 넘친 승인을 PG에 보내지 않는지 본다. */
class PgBulkheadAdapterTest {

    private ServerSocket server;
    private final AtomicInteger requests = new AtomicInteger();
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    /** 요청마다 스레드를 띄우고, 응답은 release가 풀릴 때까지 붙잡는 최소 HTTP 서버다. */
    @BeforeEach
    void start() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    Thread handler = new Thread(() -> handle(socket));
                    handler.setDaemon(true);
                    handler.start();
                } catch (IOException e) {
                    return;
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void handle(Socket socket) {
        try (socket) {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            int contentLength = 0;
            boolean chunked = false;
            reader.readLine();
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                String name = line.substring(0, line.indexOf(':'));
                String value = line.substring(line.indexOf(':') + 1).trim();
                if (name.equalsIgnoreCase("Transfer-Encoding")) chunked = value.equalsIgnoreCase("chunked");
                if (name.equalsIgnoreCase("Content-Length")) contentLength = Integer.parseInt(value);
            }
            // Note: 요청 본문을 끝까지 읽어야 응답 전에 연결이 초기화되지 않는다.
            if (chunked) {
                int size;
                while ((size = Integer.parseInt(reader.readLine().split(";", 2)[0], 16)) != 0) {
                    reader.skip(size);
                    reader.readLine();
                }
                reader.readLine();
            } else {
                reader.skip(contentLength);
            }
            requests.incrementAndGet();
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 500 Internal Server Error\r\nContent-Type: application/json\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(body);
            out.flush();
        } catch (IOException | InterruptedException e) {
            // Note: 시험 종료 시 연결이 끊기는 것은 무시한다.
        }
    }

    @AfterEach
    void stop() throws IOException {
        release.countDown();
        server.close();
    }

    @Test
    void reservedPermitReachesActualHttpWithoutSecondAcquisitionAndReturnsUnusedSlot() throws Exception {
        var bulkhead = new PgBulkhead(new PgBulkheadProperties(true, 1, 1, Duration.ZERO), (MeterRegistry) null);
        var guard = new PgCallGuard(null, bulkhead);
        var client = new TossPgClient(
                RestClient.builder(),
                PgClientContractTest.properties("http://localhost:" + server.getLocalPort()),
                new ObjectMapper(),
                null,
                guard);
        var unused = client.reserveApproval().orElseThrow();
        assertThat(client.reserveApproval()).isEmpty();
        unused.close();
        unused.close();
        try (var permit = client.reserveApproval().orElseThrow()) {
            var call = CompletableFuture.supplyAsync(
                    () -> permit.approve(new PgApproveRequest("reserved", "key", "key", 10000, 1L)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(client.reserveApproval()).isEmpty();
            assertThat(requests).hasValue(1);
            release.countDown();
            assertThat(call.get(5, TimeUnit.SECONDS)).isInstanceOf(PgResult.Unknown.class);
        }
        try (var available = client.reserveApproval().orElseThrow()) {
            assertThat(available).isNotNull();
        }
    }

    @Test
    @DisplayName("상한이 차면 다음 승인은 PG로 보내지 않고 PG_BUSY로 거절되며, 앞선 승인은 그대로 처리된다")
    void overflowApprovalIsNotSent() throws Exception {
        PgBulkhead bulkhead =
                new PgBulkhead(new PgBulkheadProperties(true, 1, 1, Duration.ofMillis(30)), (MeterRegistry) null);
        TossPgClient client = new TossPgClient(
                RestClient.builder(),
                PgClientContractTest.properties("http://localhost:" + server.getLocalPort()),
                new ObjectMapper(),
                null,
                new PgCallGuard(null, bulkhead));

        CompletableFuture<PgResult> first = CompletableFuture.supplyAsync(
                () -> client.approve(new PgApproveRequest("order-1", "key-1", "key-1", 10_000, 1L)));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        // Note: 상한이 지켜지지 않으면 두 번째 승인이 서버에서 붙잡혀 제한 시간 안에 끝나지 않는다.
        PgResult overflow = CompletableFuture.supplyAsync(
                        () -> client.approve(new PgApproveRequest("order-2", "key-2", "key-2", 10_000, 1L)))
                .get(2, TimeUnit.SECONDS);

        assertThat(overflow).isInstanceOf(PgResult.Rejected.class);
        assertThat(((PgResult.Rejected) overflow).code()).isEqualTo("PG_BUSY");
        assertThat(((PgResult.Rejected) overflow).notSentToPg()).isTrue();
        assertThat(requests).hasValue(1);
        release.countDown();
        // Note: 보낸 승인의 결과 분류(500 → Unknown)는 상한과 무관하게 그대로다.
        assertThat(first.get(5, TimeUnit.SECONDS)).isInstanceOf(PgResult.Unknown.class);
    }
}
