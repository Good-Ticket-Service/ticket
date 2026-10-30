package com.sparta.ticket.payment.domain.model.settlement;

/** 지급 상태(settlement-design 7.2절). 어느 단계에서 끊겨도 READY 또는 REQUESTED로만 남아 같은 키 조회로 확정할 수 있다. */
public enum PayoutStatus {
    /** 지급대행 호출 전. 게이트 실패·계좌 미등록이면 여기 머문다. */
    READY,
    /** 실행권을 얻어 키·계좌 스냅샷을 고정했다. 접수·처리 중·결과 불명을 포함한다. */
    REQUESTED,
    /** 지급대행이 완료를 알렸고 키·금액·계좌·거래 ID가 스냅샷과 맞다. 지급 분개를 남겼다. */
    DONE,
    /** 지급대행이 실패를 확정했다(실패 코드). 돈은 나가지 않았다. */
    FAILED
}
