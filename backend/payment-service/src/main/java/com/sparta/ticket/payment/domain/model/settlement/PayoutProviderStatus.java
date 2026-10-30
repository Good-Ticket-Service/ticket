package com.sparta.ticket.payment.domain.model.settlement;

/** REQUESTED 지급에서 지급대행이 마지막으로 알려 준 상태. 처리 중임을 확인한 것과 결과를 모르는 것을 나눈다. */
public enum PayoutProviderStatus {
    /** 지급대행이 접수했다. 완료가 아니다. */
    ACCEPTED,
    /** 지급대행이 처리하고 있다. */
    PROCESSING,
    /** 응답 유실·조회 장애 등으로 지급대행 쪽 상태를 모른다. */
    UNKNOWN
}
