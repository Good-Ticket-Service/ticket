package com.sparta.ticket.payment.presentation.seller;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.common.response.PageResponse;
import com.sparta.ticket.payment.application.service.settlement.SellerSettlementTransactions;
import com.sparta.ticket.payment.application.service.settlement.SellerSettlementTransactions.PayoutAccountView;
import com.sparta.ticket.payment.application.service.settlement.SellerSettlementTransactions.StatementDetail;
import com.sparta.ticket.payment.domain.model.settlement.StatementStatus;
import com.sparta.ticket.payment.domain.repository.settlement.SellerStatementQueries.Receivable;
import com.sparta.ticket.payment.domain.repository.settlement.SellerStatementQueries.Summary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 판매자 수취 계좌와 본인 정산 조회(docs/api.md 판매자 정산). 판매자 ID는 Gateway가 넣은 사용자 ID다.
 * Note: If-Match는 required=false로 받는다. 등록 전 최초 등록은 생략할 수 있고, 등록 뒤 누락은 서비스가 428로 답한다.
 */
@Validated
@RestController
@RequestMapping("/api/seller")
@RequireRole(Role.SELLER)
@RequiredArgsConstructor
public class SellerSettlementController {

    private final SellerSettlementTransactions settlements;

    @GetMapping("/payout-account")
    public ResponseEntity<ApiResponse<PayoutAccountView>> account(@CurrentUser UserContext user) {
        return withEtag(settlements.account(user.userId()));
    }

    @PutMapping("/payout-account")
    public ResponseEntity<ApiResponse<PayoutAccountView>> register(
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody PayoutAccountRequest request,
            @CurrentUser UserContext user) {
        return withEtag(settlements.register(
                user.userId(), version(ifMatch), request.bankCode(), request.accountNumber(), request.holderName()));
    }

    @GetMapping("/settlements")
    public ApiResponse<PageResponse<Summary>> statements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) StatementStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @CurrentUser UserContext user) {
        if (from != null && to != null && from.isAfter(to))
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR);
        return ApiResponse.ok(settlements.statements(user.userId(), from, to, status, page, size));
    }

    @GetMapping("/settlements/receivable")
    public ApiResponse<Receivable> receivable(@CurrentUser UserContext user) {
        return ApiResponse.ok(settlements.receivable(user.userId()));
    }

    @GetMapping("/settlements/{statementId}")
    public ApiResponse<StatementDetail> statement(
            @PathVariable @Min(1) long statementId, @CurrentUser UserContext user) {
        return ApiResponse.ok(settlements.statement(user.userId(), statementId));
    }

    private static ResponseEntity<ApiResponse<PayoutAccountView>> withEtag(PayoutAccountView view) {
        return ResponseEntity.ok().eTag("\"" + view.version() + "\"").body(ApiResponse.ok(view));
    }

    /** If-Match는 version을 큰따옴표로 감싼 값이다(예: "3"). 없으면 null, 형식이 틀리면 400이다. */
    private static Long version(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) return null;
        String value = ifMatch.strip();
        if (!value.matches("\"\\d{1,18}\"")) throw new BusinessException(CommonErrorCode.VALIDATION_ERROR);
        return Long.parseLong(value.substring(1, value.length() - 1));
    }
}
