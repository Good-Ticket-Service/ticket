package com.sparta.ticket.payment.application.port.settlement;

import com.sparta.ticket.payment.domain.model.settlement.projection.SettlementFact;
import java.util.List;
import java.util.UUID;

/** 전달 기술과 분리한 집계 적용 경계. 구현은 중복·순서·금액과 적용 기록을 함께 보호한다. */
public interface ProjectionBatchApplier {
    void apply(UUID build, List<SettlementFact> facts);
}
