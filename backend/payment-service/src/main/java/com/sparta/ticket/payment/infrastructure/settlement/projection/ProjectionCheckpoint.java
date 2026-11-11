package com.sparta.ticket.payment.infrastructure.settlement.projection;

/** 커밋 경계 관측 지점. 운영 기본값은 무동작이며 종료 시험만 관측자를 명시 주입한다. */
@FunctionalInterface
public interface ProjectionCheckpoint {
    enum Stage {
        SOURCE_TX_COMMITTED,
        PUBLISH_ACKNOWLEDGED,
        B_DB_COMMITTED,
        C_BEFORE_OUTPUT,
        SINK_DB_COMMITTED
    }

    ProjectionCheckpoint NONE = (stage, reference) -> {};

    void hit(Stage stage, String reference);
}
