package com.sparta.ticket.payment.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.payment.application.dto.payment.SettlementPostingCommand;
import com.sparta.ticket.payment.application.service.payment.InvalidSettlementPostingException;
import com.sparta.ticket.payment.application.service.payment.SettlementJobTransactions;
import com.sparta.ticket.payment.domain.model.settlement.LedgerConflictException;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;

/** PG 호출 없이 기존 상태 수렴 처리를 커밋한 후 같은 listener 스레드에서 ACK한다. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.settlement.posting.kafka.enabled", havingValue = "true")
public class SettlementPostingConsumer {
    private final SettlementJobTransactions transactions;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final SettlementPostingDeadLetter deadLetter;

    @KafkaListener(
            id = "settlement-posting",
            topics = "${payment.settlement.posting.kafka.topic:settlement.posting.requested.v1}",
            groupId = "${payment.settlement.posting.kafka.group:settlement-posting-v1}",
            autoStartup = "${payment.settlement.posting.kafka.consumer-enabled:true}",
            containerFactory = "settlementPostingContainerFactory")
    public void consume(ConsumerRecord<String, String> record, Acknowledgment acknowledgment)
            throws InterruptedException {
        SettlementPostingCommand command = null;
        try {
            command = decode(record);
            execute(command);
        } catch (InvalidSettlementPostingException exception) {
            if (!java.util.Set.of(
                            "UNSUPPORTED_VERSION",
                            "INVALID_PAYLOAD",
                            "INVALID_PAYLOAD_SIZE",
                            "INVALID_MESSAGE_KEY",
                            "UNKNOWN_JOB",
                            "SOURCE_IDENTITY_MISMATCH",
                            "BLOCKED_JOB",
                            "INVALID_REPLAY_SOURCE",
                            "INVALID_REPLAY_STATE")
                    .contains(exception.getMessage())) throw exception;
            deadLetter.recover(
                    record, exception.getMessage(), "BLOCKED_JOB".equals(exception.getMessage()) ? command : null);
        } catch (LedgerConflictException exception) {
            deadLetter.recover(record, "LEDGER_CONFLICT", command);
        }
        acknowledgment.acknowledge();
        meters.counter("settlement.posting.consumer.acknowledged").increment();
    }

    private void execute(SettlementPostingCommand command) throws InterruptedException {
        for (int attempt = 0; ; attempt++) {
            try {
                transactions.processKafka(command);
                break;
            } catch (TransientDataAccessException
                    | CannotGetJdbcConnectionException
                    | CannotCreateTransactionException exception) {
                if (attempt >= 3) throw exception;
                meters.counter("settlement.posting.consumer.transient.retry").increment();
                Thread.sleep(1000L << attempt);
            }
        }
    }

    private SettlementPostingCommand decode(ConsumerRecord<String, String> record) {
        if (record.value() == null
                || record.value().length() > 8192
                || record.value().getBytes(StandardCharsets.UTF_8).length > 8192)
            throw new InvalidSettlementPostingException("INVALID_PAYLOAD_SIZE");
        SettlementPostingCommand command;
        try {
            SettlementPostingEvent event = mapper.readValue(record.value(), SettlementPostingEvent.class);
            if (event == null) throw new InvalidSettlementPostingException("INVALID_PAYLOAD");
            command = event.command();
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new InvalidSettlementPostingException("INVALID_PAYLOAD");
        }
        if (!Long.toString(command.paymentId()).equals(record.key()))
            throw new InvalidSettlementPostingException("INVALID_MESSAGE_KEY");
        return command;
    }
}
