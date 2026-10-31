package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.payment.application.dto.settlement.PayoutOperation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** 지급 운영 명령 진입점. 입력·실행자 해시로 같은 명령을 재현하고, 같은 ID 동시 INSERT 경쟁은 트랜잭션 밖에서 다시 조회한다. */
@Service
@RequiredArgsConstructor
public class PayoutOperationService {

    private final PayoutOperationTransactions transactions;

    public PayoutOperation.Outcome retry(PayoutOperation.Retry request, String actor) {
        if (actor == null
                || actor.isBlank()
                || actor.length() > 200
                || actor.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("INVALID_EXECUTION_PRINCIPAL");
        String hash = hash(request + "|" + actor);
        var previous = transactions.previous(request.commandId(), hash);
        if (previous.isPresent()) return previous.get();
        try {
            return transactions.retry(request, hash, actor);
        } catch (DataIntegrityViolationException conflict) {
            // Note: UNIQUE 충돌 트랜잭션이 끝난 다음 기존 명령을 조회한다. 다른 무결성 오류는 숨기지 않는다.
            return transactions.previous(request.commandId(), hash).orElseThrow(() -> conflict);
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
