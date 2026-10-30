package com.sparta.ticket.fakepg.presentation.controller.payout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparta.ticket.fakepg.application.service.fault.FaultInjector;
import com.sparta.ticket.fakepg.application.service.fault.ResponseHoldException;
import com.sparta.ticket.fakepg.application.service.payout.PayoutCommand;
import com.sparta.ticket.fakepg.application.service.payout.PgPayoutTransactions;
import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.domain.model.payout.PgPayout;
import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.presentation.controller.ResponseTamper;
import com.sparta.ticket.fakepg.presentation.dto.payout.PayoutRequest;
import com.sparta.ticket.fakepg.presentation.dto.payout.PayoutResponse;
import com.sparta.ticket.fakepg.presentation.dto.toss.TossErrorResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PG 지급대행(판매자 정산 지급) 흉내. 결제·취소와 API·저장소를 나누고, 토스 시크릿 키 인증과 TOSS 장애 설정을 함께 쓴다.
 *
 * <ol>
 *   <li>처리 한도를 넘으면 429 (접수 안 함)</li>
 *   <li>같은 멱등 키가 이미 있으면 본문이 같을 때 현재 결과, 다를 때 422</li>
 *   <li>지연 → 확률에 따라 접수하지 않은 500</li>
 *   <li>접수. 장애 주입의 명시 거절이면 처리 결과가 REJECTED가 될 지급으로 접수한다</li>
 *   <li>확률에 따라 응답 유실: 접수는 끝났는데 응답을 붙잡았다가 500</li>
 *   <li>확률에 따라 응답 변조: 저장한 지급은 그대로 두고 내보내는 본문만 망가뜨린다</li>
 * </ol>
 */
@RestController
@RequestMapping("/v1/payouts")
@RequiredArgsConstructor
public class PayoutController {

    private static final PgProvider PROVIDER = PgProvider.TOSS;

    private final PgPayoutTransactions transactions;
    private final FaultInjector faults;
    private final ResponseTamper tamper;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<Object> request(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PayoutRequest request) {
        faults.checkRateLimit(PROVIDER, PgOperation.PAYOUT);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 200)
            throw new PgException(PgErrorCode.INVALID_REQUEST);
        PayoutCommand command = new PayoutCommand(
                idempotencyKey, request.sellerRef(), request.accountRef(), request.holderMasked(), request.amount());
        String hash = hash(command);
        var existing = transactions.observe(idempotencyKey);
        if (existing.isPresent()) return replay(existing.get(), hash);

        PgPayout payout;
        try {
            faults.beforeProcessing(PROVIDER, PgOperation.PAYOUT);
            payout = transactions.accept(command, hash, faults.shouldFail(PROVIDER, PgOperation.PAYOUT));
        } catch (ResponseHoldException e) {
            return hold(PgOperation.PAYOUT);
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 동시 요청이 먼저 접수됐다. 그 지급을 기준으로 답한다
            return replay(transactions.observe(idempotencyKey).orElseThrow(), hash);
        }
        if (faults.shouldLoseResponse(PROVIDER, PgOperation.PAYOUT)) return hold(PgOperation.PAYOUT);
        return tampered(PgOperation.PAYOUT, PayoutResponse.from(payout));
    }

    @GetMapping("/{idempotencyKey}")
    public ResponseEntity<Object> inquire(@PathVariable String idempotencyKey) {
        faults.checkRateLimit(PROVIDER, PgOperation.PAYOUT_INQUIRE);
        PgPayout payout;
        try {
            faults.beforeProcessing(PROVIDER, PgOperation.PAYOUT_INQUIRE);
            payout = transactions
                    .observe(idempotencyKey)
                    .orElseThrow(() -> new PgException(PgErrorCode.NOT_FOUND_PAYOUT));
        } catch (ResponseHoldException e) {
            return hold(PgOperation.PAYOUT_INQUIRE);
        }
        if (faults.shouldLoseResponse(PROVIDER, PgOperation.PAYOUT_INQUIRE)) return hold(PgOperation.PAYOUT_INQUIRE);
        return tampered(PgOperation.PAYOUT_INQUIRE, PayoutResponse.from(payout));
    }

    /** 같은 키 재요청. 본문이 같으면 현재 결과를 다시 추첨하지 않고 돌려주고, 다르면 거절한다. */
    private ResponseEntity<Object> replay(PgPayout payout, String hash) {
        if (!payout.getRequestHash().equals(hash)) throw new PgException(PgErrorCode.INVALID_IDEMPOTENCY_KEY);
        return tampered(PgOperation.PAYOUT, PayoutResponse.from(payout));
    }

    private ResponseEntity<Object> tampered(PgOperation operation, PayoutResponse body) {
        return faults.tamper(PROVIDER, operation)
                .<ResponseEntity<Object>>map(mode -> ResponseEntity.ok(tamper.applyPayout(mode, body)))
                .orElse(ResponseEntity.ok(body));
    }

    private ResponseEntity<Object> hold(PgOperation operation) {
        faults.hold(PROVIDER, operation);
        PgErrorCode code = PgErrorCode.FAILED_INTERNAL_SYSTEM_PROCESSING;
        return ResponseEntity.status(code.getStatus()).body(new TossErrorResponse(code.name(), code.getMessage()));
    }

    private String hash(PayoutCommand command) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsString(command).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
