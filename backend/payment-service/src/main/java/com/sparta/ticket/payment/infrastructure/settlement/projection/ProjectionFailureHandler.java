package com.sparta.ticket.payment.infrastructure.settlement.projection;

import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;

/** DB 실패 TX가 끝난 다음 별도 격리·DLT 확인을 수행한다. 성공 반환 뒤에만 원본 offset을 넘긴다. */
public interface ProjectionFailureHandler {
    void reject(UUID build, ConsumerRecord<String, String> record, String factKey, String reason, UUID replayCommandId);
}
