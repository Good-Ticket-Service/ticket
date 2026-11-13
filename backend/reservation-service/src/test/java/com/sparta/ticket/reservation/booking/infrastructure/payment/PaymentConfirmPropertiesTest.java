package com.sparta.ticket.reservation.booking.infrastructure.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;

class PaymentConfirmPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(PaymentConfirmProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues(
                    "reservation.booking.payment-confirm.via-message=true",
                    "reservation.booking.payment-confirm.window=5m",
                    "reservation.booking.payment-confirm.grace=1m",
                    "reservation.booking.payment-confirm.fallback.enabled=true",
                    "reservation.booking.payment-confirm.fallback.unsent-after=5s");

    @Test
    @DisplayName("설정 값을 그대로 읽는다")
    void bindsValues() {
        runner.run(context -> {
            PaymentConfirmProperties properties = context.getBean(PaymentConfirmProperties.class);
            assertThat(properties.viaMessage()).isTrue();
            assertThat(properties.window()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.grace()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.fallbackEnabled()).isTrue();
            assertThat(properties.unsentAfter()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    @DisplayName("config-repo의 승인 방식 값이 설정 객체로 묶이고, 동기로 돌리는 기능은 환경 변수가 없으면 켜져 있다")
    void bindsConfigRepoValues() throws IOException {
        var sources = new YamlPropertySourceLoader()
                .load("reservation-service", new FileSystemResource("../config-repo/reservation-service.yml"));
        PaymentConfirmProperties properties = new Binder(
                        ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("reservation.booking.payment-confirm", PaymentConfirmProperties.class)
                .get();

        assertThat(properties.fallbackEnabled()).isTrue();
        assertThat(properties.unsentAfter()).isGreaterThanOrEqualTo(Duration.ofSeconds(1));
        assertThat(properties.unsentAfter()).isLessThan(properties.window());
    }

    @Test
    @DisplayName("동기로 돌리는 기준 시간이 빠지면 기동이 실패한다")
    void requiresUnsentAfter() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Config.class)
                .withPropertyValues(
                        "reservation.booking.payment-confirm.window=5m",
                        "reservation.booking.payment-confirm.grace=1m",
                        "reservation.booking.payment-confirm.fallback.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("동기로 돌리는 기준 시간이 1초보다 짧으면 1초 주기 집계가 잴 수 없어 기동이 실패한다")
    void rejectsUnsentAfterBelowOneSecond() {
        runner.withPropertyValues("reservation.booking.payment-confirm.fallback.unsent-after=500ms")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("동기로 돌리는 기준 시간이 승인 시한보다 짧지 않으면 기동이 실패한다")
    void rejectsUnsentAfterNotShorterThanWindow() {
        runner.withPropertyValues("reservation.booking.payment-confirm.fallback.unsent-after=5m")
                .run(context -> assertThat(context).hasFailed());
    }
}
