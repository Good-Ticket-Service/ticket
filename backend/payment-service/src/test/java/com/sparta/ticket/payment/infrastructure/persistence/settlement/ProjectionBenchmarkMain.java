package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.PaymentServiceApplication;
import com.sparta.ticket.payment.application.port.settlement.SettlementPreviewReader;
import com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementPreviewJdbc;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/** 성능 시험의 실제 Spring HTTP 서비스 JVM. 부하 발생기·집계 JVM과 분리한다. */
public final class ProjectionBenchmarkMain {
    private ProjectionBenchmarkMain() {}

    public static void main(String[] args) throws Exception {
        var options = new ArrayList<>(List.of(
                "--spring.profiles.active=measurement,projection-measurement",
                "--spring.cloud.config.enabled=false",
                "--eureka.client.enabled=false",
                "--spring.datasource.url=" + args[0],
                "--spring.datasource.username=" + args[1],
                "--spring.datasource.password=" + args[2],
                "--spring.datasource.hikari.maximum-pool-size=20",
                "--spring.datasource.hikari.minimum-idle=5",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.sql.init.mode=never",
                "--spring.jpa.open-in-view=false",
                "--spring.task.scheduling.pool.size=13",
                "--server.address=127.0.0.1",
                "--server.port=" + args[3],
                "--payment.settlement.projection.auto-start=false",
                "--projection.measure.mode=" + args[4]));
        options.add("--payment.settlement.projection.enabled="
                + !List.of("OFF", "PAUSED").contains(args[4]));
        for (String name : List.of(
                "payment.recovery",
                "payment.reconcile",
                "payment.recovery.refund",
                "payment.observation",
                "payment.settlement.worker",
                "payment.settlement.backfill",
                "payment.settlement.statement",
                "payment.settlement.payout",
                "payment.settlement.payout.recovery",
                "payment.settlement.reconcile",
                "payment.pg")) {
            if (args.length > 6 && "payment.settlement.worker".equals(name)) continue;
            options.add("--" + name + ".enabled=false");
        }
        if (args.length > 6) {
            options.add("--pg.toss.base-url=" + args[6]);
            options.add("--pg.kakao.base-url=" + args[6]);
            options.add("--payment.settlement.worker.enabled=true");
        }
        try (var sampler = new ProjectionJvmSampler(java.nio.file.Path.of(args[5]));
                var context = new SpringApplication(PaymentServiceApplication.class, Configuration.class)
                        .run(options.toArray(String[]::new))) {
            Runtime.getRuntime().addShutdownHook(new Thread(context::close));
            while (context.isActive()) Thread.sleep(1000);
        }
    }

    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        com.sparta.ticket.payment.application.port.settlement.SettlementFactJournal measuredJournal(
                com.sparta.ticket.payment.infrastructure.persistence.settlement.projection.SettlementFactJdbc original,
                @Value("${projection.measure.mode}") String mode) {
            // Note: 추가 쓰기 OFF는 측정 전용 대조군이다. 제품의 실행기 중지는 원천 수집을 멈추지 않는다.
            return "OFF".equals(mode) ? key -> java.util.Objects.requireNonNull(key) : original::append;
        }

        @Bean
        @Primary
        SettlementPreviewReader measuredReader(
                JdbcTemplate jdbc, SettlementPreviewJdbc projected, @Value("${projection.measure.mode}") String mode) {
            return "A0".equals(mode) ? new ProjectionDirectPreview(jdbc) : projected;
        }
    }
}
