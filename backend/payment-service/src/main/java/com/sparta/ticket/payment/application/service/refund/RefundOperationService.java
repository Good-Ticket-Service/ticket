package com.sparta.ticket.payment.application.service.refund;

import com.sparta.ticket.payment.application.dto.refund.RefundOperation;
import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.application.service.pg.PgClientRegistry;
import com.sparta.ticket.payment.domain.model.pg.PgInquiry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** PG 조회는 트랜잭션 밖, 운영 변경과 최종 기록은 하나의 트랜잭션이다. */
@Service
@RequiredArgsConstructor
public class RefundOperationService {
    private final RefundOperationTransactions transactions;
    private final PgClientRegistry clients;

    public RefundOperation.Outcome execute(RefundOperation.Request request, String actor) {
        if (actor == null
                || actor.isBlank()
                || actor.length() > 200
                || actor.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("INVALID_EXECUTION_PRINCIPAL");
        String hash = hash(request.toString() + "|" + actor);
        var previous = transactions.previous(request.commandId(), hash);
        if (previous.isPresent()) return previous.get();
        var snapshot = transactions.snapshot(request.orderId());
        PgInquiry inquiry = observe(request.orderId(), snapshot);
        try {
            return transactions.apply(request, hash, actor, snapshot, inquiry);
        } catch (DataIntegrityViolationException conflict) {
            // Note: UNIQUE 충돌 트랜잭션이 종료된 다음 기존 명령을 조회한다. 다른 무결성 오류는 숨기지 않는다.
            return transactions.previous(request.commandId(), hash).orElseThrow(() -> conflict);
        }
    }

    public PgInquiry observe(String orderId, RefundOperationTransactions.Snapshot snapshot) {
        try {
            return clients.get(snapshot.provider())
                    .inquire(new PgClient.PgInquireRequest(orderId, snapshot.transactionId()));
        } catch (RuntimeException e) {
            return new PgInquiry.Unknown("OPERATION_INQUIRY_FAILED");
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
