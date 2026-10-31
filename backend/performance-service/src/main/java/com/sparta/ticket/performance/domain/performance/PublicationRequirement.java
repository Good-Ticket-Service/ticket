package com.sparta.ticket.performance.domain.performance;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 공연 공개 필수 조건. 부족하면 409 PUBLICATION_REQUIREMENTS_NOT_MET의 메시지에 item을 확인 순서대로 나열한다. */
@Getter
@RequiredArgsConstructor
public enum PublicationRequirement {
    DESCRIPTION("description"),
    POSTER_URL("posterUrl"),
    /** 삭제·취소되지 않은 회차가 하나 이상 있어야 한다. 좌석 준비 상태(PENDING·FAILED)는 따지지 않는다. */
    SCHEDULES("schedules"),
    /** 판매 제외되지 않은 좌석이 하나 이상 있어야 한다. */
    SELLABLE_SEATS("sellableSeats"),
    /** 판매 대상 좌석이 쓰는 등급에 모두 가격이 있어야 한다. */
    GRADE_PRICES("gradePrices");

    /** 응답 메시지에 쓰는 항목 이름. 요청·응답 필드 이름과 같다. */
    private final String item;
}
