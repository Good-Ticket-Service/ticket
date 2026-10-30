package com.sparta.ticket.payment.domain.model.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 정산서 발행 때 미회수 차감을 판매 합계 안에서 오래된 순으로 배분한다(settlement-design 5절 4·9번). */
class OffsetAllocationTest {

    @Test
    void offsetsWholeDeductionWhenSalesCoverIt() {
        // 판매자 몫 10만·미회수 3만 → 상계 3만·지급 7만
        assertThat(OffsetAllocation.allocate(100_000, List.of(30_000L))).containsExactly(30_000L);
    }

    @Test
    void offsetsOnlyUpToSaleTotalAndLeavesRest() {
        // 판매자 몫 10만·미회수 15만 → 상계 10만·지급 0·미회수 5만 유지
        assertThat(OffsetAllocation.allocate(100_000, List.of(150_000L))).containsExactly(100_000L);
    }

    @Test
    void allocatesOldestDeductionFirstAndPartially() {
        assertThat(OffsetAllocation.allocate(50_000, List.of(20_000L, 40_000L, 10_000L)))
                .containsExactly(20_000L, 30_000L, 0L);
    }

    @Test
    void noDeductionMeansNoOffset() {
        assertThat(OffsetAllocation.allocate(100_000, List.of())).isEmpty();
    }

    @Test
    void rejectsNonPositiveSaleTotalOrRemaining() {
        assertThatThrownBy(() -> OffsetAllocation.allocate(0, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OffsetAllocation.allocate(100, List.of(0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
