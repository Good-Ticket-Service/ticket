package com.sparta.ticket.performance.application.dto;

/**
 * 좌석 준비 호출 한 번의 결과. 전달 방식(Feign·Kafka)과 무관하게 이 네 가지로 나눠 회차 상태를 정한다.
 * 결과별 처리는 docs/api.md 공연 「업무 규칙」의 좌석 준비를 따른다.
 */
public sealed interface SeatSetupOutcome {

    /** 200. 응답의 requestId·configVersion이 보낸 값과 같아야 READY로 반영한다. */
    record Ready(String requestId, long configVersion, int seatCount, int sellableCount) implements SeatSetupOutcome {}

    /** 400 VALIDATION_ERROR. 다시 보내도 같은 결과라 FAILED. reason은 판매자에게 보일 안전한 요약이다. */
    record Rejected(String code, String reason) implements SeatSetupOutcome {}

    /** 409 SEAT_SETUP_STALE_VERSION. 좌석 쪽에 더 높은 구성이 이미 있다. 결과를 무시한다. */
    record Stale() implements SeatSetupOutcome {}

    /**
     * 연결 실패, 타임아웃, 5xx, 계약에 없는 응답, 409 SEAT_SETUP_IN_USE. PENDING을 유지하고 같은 requestId로 다시 보낸다.
     * detail은 로그용이다. failureReason은 재시도 한도에 닿아 FAILED가 될 때 판매자에게 보일 사유이고, null이면 일반 사유를 쓴다.
     * Note: IN_USE는 만료됐지만 정리 전인 HOLD, 결제 중인 좌석처럼 시간이 지나면 풀릴 수 있어 재시도한다(이슈 #175, 좌석 담당 제안).
     */
    record Unavailable(String detail, String failureReason) implements SeatSetupOutcome {

        public Unavailable(String detail) {
            this(detail, null);
        }
    }
}
