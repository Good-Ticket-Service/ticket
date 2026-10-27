package com.sparta.ticket.payment.domain.model.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentFailureTypeTest {

    @Test
    @DisplayName("실패 코드를 계약의 failureType으로 분류하고, 내부 목록 밖의 PG 거절 코드는 REJECTED다")
    void classify() {
        assertThat(PaymentFailureType.of("SUPERSEDED")).isEqualTo(PaymentFailureType.CLOSED);
        assertThat(PaymentFailureType.of("READY_FAILED")).isEqualTo(PaymentFailureType.CLOSED);
        assertThat(PaymentFailureType.of("PG_UNRESOLVED")).isEqualTo(PaymentFailureType.UNAVAILABLE);
        assertThat(PaymentFailureType.of("EXPIRED")).isEqualTo(PaymentFailureType.EXPIRED);
        assertThat(PaymentFailureType.of("NOT_APPROVED")).isEqualTo(PaymentFailureType.NOT_APPROVED);
        assertThat(PaymentFailureType.of("AMOUNT_MISMATCH")).isEqualTo(PaymentFailureType.REJECTED);
        assertThat(PaymentFailureType.of("REJECT_CARD_PAYMENT")).isEqualTo(PaymentFailureType.REJECTED);
        assertThat(PaymentFailureType.of(null)).isNull();
    }
}
