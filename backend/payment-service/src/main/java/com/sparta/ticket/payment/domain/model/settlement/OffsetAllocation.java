package com.sparta.ticket.payment.domain.model.settlement;

import java.util.ArrayList;
import java.util.List;

/**
 * 정산서 발행 때 판매자의 미회수 차감(DEDUCTION)을 판매 합계 안에서 상계한다(settlement-design 5절 4번).
 * 상계액 = min(차감 잔액 합, 판매 합계). 오래된 차감부터 채우고 남는 잔액은 다음 정산서로 넘긴다.
 */
public final class OffsetAllocation {

    private OffsetAllocation() {}

    /**
     * @param remainings id 오름차순 차감 잔액. 모두 양수다
     * @return 같은 순서의 차감별 배분액. 판매 합계가 모자라면 뒤쪽은 0이다
     */
    public static List<Long> allocate(long saleTotal, List<Long> remainings) {
        if (saleTotal <= 0) throw new IllegalArgumentException("상계할 판매 합계가 없다");
        long left = saleTotal;
        List<Long> shares = new ArrayList<>(remainings.size());
        for (long remaining : remainings) {
            if (remaining <= 0) throw new IllegalArgumentException("미회수 잔액이 없는 차감은 배분하지 않는다");
            long share = Math.min(remaining, left);
            shares.add(share);
            left -= share;
        }
        return shares;
    }
}
