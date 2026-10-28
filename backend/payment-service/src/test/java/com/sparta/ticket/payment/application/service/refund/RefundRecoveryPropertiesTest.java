package com.sparta.ticket.payment.application.service.refund;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RefundRecoveryPropertiesTest {
    @Test
    void cappedBackoffAndLeaseAreSeparateFromAttempts() {
        var properties = new RefundRecoveryProperties(50, 30, 300, 60, 15);
        assertThat(properties.claimSeconds(0)).isEqualTo(30);
        assertThat(properties.claimSeconds(2)).isEqualTo(30);
        assertThat(properties.claimSeconds(3)).isEqualTo(40);
        assertThat(properties.claimSeconds(4)).isEqualTo(80);
        assertThat(properties.claimSeconds(5)).isEqualTo(160);
        assertThat(properties.claimSeconds(6)).isEqualTo(300);
        assertThat(properties.claimSeconds(Integer.MAX_VALUE)).isEqualTo(300);
    }

    @Test
    void slowJitterHasBoundsAndCapsWithoutOverflow() {
        var properties = new RefundRecoveryProperties(50, 30, 300, 60, 15);
        assertThat(properties.slowSeconds(0, 0)).isEqualTo(480);
        assertThat(properties.slowSeconds(0, 1)).isEqualTo(720);
        assertThat(properties.slowSeconds(1, 0.5)).isEqualTo(1200);
        assertThat(properties.slowSeconds(Integer.MAX_VALUE, 1)).isEqualTo(1800);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RefundRecoveryProperties(50, 30, 300, 60, 15, 20, 10, 0.2, 24))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RefundRecoveryProperties(50, 30, 300, 60, 15, 600, 1800, Double.NaN, 24))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RefundRecoveryProperties(50, 30, 300, 60, 15, 600, 1800, 0.2, 25))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
