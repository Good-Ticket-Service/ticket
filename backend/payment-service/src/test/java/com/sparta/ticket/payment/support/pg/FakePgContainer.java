package com.sparta.ticket.payment.support.pg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * 계약 테스트용 fake PG. fake-pg 실행 jar(Gradle이 {@code fakePg.jar}로 경로를 넘긴다)를 JRE 컨테이너에서 띄우고,
 * 거래 기록용 PostgreSQL을 같은 네트워크에 둔다. 모든 계약 테스트가 한 번 띄운 컨테이너를 같이 쓴다.
 */
public final class FakePgContainer {

    private static final int PORT = 8090;
    private static final Network NETWORK = Network.newNetwork();

    private static final PostgreSQLContainer<?> DB =
            new PostgreSQLContainer<>("postgres:16").withNetwork(NETWORK).withNetworkAliases("fake-pg-db");

    private static final GenericContainer<?> PG = new GenericContainer<>("eclipse-temurin:17-jre")
            .withNetwork(NETWORK)
            .withCopyFileToContainer(MountableFile.forHostPath(jar()), "/app.jar")
            .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://fake-pg-db:5432/" + DB.getDatabaseName())
            .withEnv("SPRING_DATASOURCE_USERNAME", DB.getUsername())
            .withEnv("SPRING_DATASOURCE_PASSWORD", DB.getPassword())
            // 지급대행 처리 지연을 짧게 둬 접수 → 처리 중 → 확정을 빨리 시험한다
            .withEnv("FAKE_PG_PAYOUT_DELAY_MS", "300")
            .withCommand("java", "-Xmx256m", "-jar", "/app.jar")
            .withExposedPorts(PORT)
            .dependsOn(DB)
            .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    static {
        PG.start();
    }

    private FakePgContainer() {}

    public static String baseUrl() {
        return "http://" + PG.getHost() + ":" + PG.getMappedPort(PORT);
    }

    private static Path jar() {
        String path = System.getProperty("fakePg.jar");
        if (path == null || !Files.exists(Path.of(path))) {
            throw new IllegalStateException("fake-pg jar가 없다. ./gradlew :payment-service:test로 실행해야 한다: " + path);
        }
        return Path.of(path);
    }
}
