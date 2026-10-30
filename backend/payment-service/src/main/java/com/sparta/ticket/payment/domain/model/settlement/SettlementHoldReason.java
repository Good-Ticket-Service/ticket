package com.sparta.ticket.payment.domain.model.settlement;

public enum SettlementHoldReason {
    /** 환불을 접수했다. 환불 DONE이면 상쇄되고, 실패해도 고객 환불 의사가 남아 정산서에 넣지 않는다. */
    REFUND_IN_PROGRESS,
    /** 결제에 회차 시작 시각이 없어 정산 가능일을 정할 수 없다(계약 적용 전 결제). 추측하지 않는다. */
    MISSING_SCHEDULE_START,
    /** 정산 가능일이 영업일 달력 범위를 넘는다. 달력을 늘리기 전에는 추측하지 않는다. */
    CALENDAR_NOT_COVERED
}
