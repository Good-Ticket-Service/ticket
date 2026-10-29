package com.sparta.ticket.reservation.booking.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

class BookingSchedulerPropertiesTest {

    private static final String FEIGN_CLIENTS = "spring.cloud.openfeign.client.config.";

    private static BookingSchedulerProperties loadFromConfigRepo() throws IOException {
        var sources = new YamlPropertySourceLoader()
                .load("reservation-service", new FileSystemResource("../config-repo/reservation-service.yml"));
        return new Binder(ConfigurationPropertySources.from(sources))
                .bind("reservation.booking.scheduler", BookingSchedulerProperties.class)
                .get();
    }

    @Test
    @DisplayName("config-repo의 스케줄러 값이 설정 객체로 묶이고, 값 사이의 관계를 지킨다")
    void configRepoValuesKeepTheirRelations() throws IOException {
        BookingSchedulerProperties properties = loadFromConfigRepo();
        BookingSchedulerProperties.HoldExpiry expiry = properties.holdExpiry();
        BookingSchedulerProperties.PaymentReconcile reconcile = properties.paymentReconcile();

        assertThat(expiry.timeBudget()).as("한 번 실행은 다음 주기 전에 끝난다").isLessThan(expiry.interval());
        assertThat(reconcile.timeBudget()).as("한 번 실행은 다음 주기 전에 끝난다").isLessThan(reconcile.interval());
        assertThat(reconcile.stuckAfter()).isGreaterThan(reconcile.threshold());
        BookingSchedulerProperties.CancelReconcile cancel = properties.cancelReconcile();
        assertThat(cancel.timeBudget()).as("한 번 실행은 다음 주기 전에 끝난다").isLessThan(cancel.interval());
    }

    @Test
    @DisplayName("결과 확인·취소 확인 기준 시간은 config-repo의 어떤 Feign 클라이언트의 최대 대기(connect + read)보다도 길다")
    void thresholdOutlastsEveryFeignClient() throws IOException {
        BookingSchedulerProperties properties = loadFromConfigRepo();
        Duration threshold = properties.paymentReconcile().threshold();
        Duration cancelThreshold = properties.cancelReconcile().threshold();
        Map<String, Object> feign = loadFeignTimeouts();

        Set<String> clients = new TreeSet<>();
        feign.keySet().forEach(key -> clients.add(key.substring(FEIGN_CLIENTS.length(), key.lastIndexOf('.'))));
        assertThat(clients).as("기본값이 있어야 클라이언트별 값이 없는 호출도 잴 수 있다").contains("default");
        for (String client : clients) {
            Duration maxWait = Duration.ofMillis(
                    timeout(feign, client, "connect-timeout") + timeout(feign, client, "read-timeout"));
            assertThat(threshold)
                    .as("처리 중인 승인을 또 보내지 않도록 %s의 최대 대기 %s보다 길다", client, maxWait)
                    .isGreaterThan(maxWait);
            assertThat(cancelThreshold)
                    .as("사용자 요청이 아직 기다리는 환불을 또 보내지 않도록 %s의 최대 대기 %s보다 길다", client, maxWait)
                    .isGreaterThan(maxWait);
        }
    }

    /** application.yml 위에 reservation-service.yml을 덮어 서비스가 실제로 받는 Feign 타임아웃만 모은다. */
    private static Map<String, Object> loadFeignTimeouts() throws IOException {
        Map<String, Object> merged = new HashMap<>();
        for (String file : List.of("application", "reservation-service")) {
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load(file, new FileSystemResource("../config-repo/" + file + ".yml"))) {
                EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith(FEIGN_CLIENTS)
                            && (name.endsWith(".connect-timeout") || name.endsWith(".read-timeout"))) {
                        merged.put(name, enumerable.getProperty(name));
                    }
                }
            }
        }
        return merged;
    }

    /** 클라이언트에 값이 없으면 Spring Cloud OpenFeign처럼 default 값을 쓴다. */
    private static long timeout(Map<String, Object> feign, String client, String key) {
        Object value =
                feign.getOrDefault(FEIGN_CLIENTS + client + "." + key, feign.get(FEIGN_CLIENTS + "default." + key));
        return Long.parseLong(String.valueOf(value));
    }
}
