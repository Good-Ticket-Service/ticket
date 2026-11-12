package com.sparta.ticket.payment.domain.model.settlement.projection;

/** 조회 집계의 영구 충돌. 원문/금액을 예외 메시지에 담지 않는다. */
public class ProjectionFactConflictException extends RuntimeException {
    private final String factKey;

    public ProjectionFactConflictException(String factKey, String reason) {
        super(reason);
        this.factKey = factKey;
    }

    public String factKey() {
        return factKey;
    }
}
