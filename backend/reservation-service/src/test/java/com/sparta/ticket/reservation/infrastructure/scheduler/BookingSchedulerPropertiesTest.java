package com.sparta.ticket.reservation.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;

class BookingSchedulerPropertiesTest {

    /** 승인 호출의 최대 대기. connect 5초 + read 15초(orchestration.md P6). */
    private static final Duration MAX_CONFIRM_WAIT = Duration.ofSeconds(5 + 15);

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
        assertThat(reconcile.threshold())
                .as("처리 중인 승인을 또 보내지 않도록 승인 호출의 최대 대기보다 길다")
                .isGreaterThan(MAX_CONFIRM_WAIT);
        assertThat(reconcile.stuckAfter()).isGreaterThan(reconcile.threshold());
    }
}
