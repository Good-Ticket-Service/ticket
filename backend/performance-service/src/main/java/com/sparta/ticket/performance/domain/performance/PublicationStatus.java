package com.sparta.ticket.performance.domain.performance;

/** 공연 공개 상태. 공개 후 DRAFT로 되돌리지 않는다. 일반 사용자 조회는 PUBLISHED만 대상으로 한다. */
public enum PublicationStatus {
    DRAFT,
    PUBLISHED
}
