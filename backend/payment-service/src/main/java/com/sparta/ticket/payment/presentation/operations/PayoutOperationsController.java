package com.sparta.ticket.payment.presentation.operations;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.payment.application.dto.settlement.PayoutOperation;
import com.sparta.ticket.payment.application.service.settlement.PayoutOperationService;
import com.sparta.ticket.payment.application.service.settlement.PayoutOperationTransactions;
import com.sparta.ticket.payment.domain.model.settlement.PayoutStatus;
import com.sparta.ticket.payment.domain.repository.settlement.PayoutOperationQueries;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Gateway가 검증한 MASTER만 지급 운영 조회와 retry를 실행한다(Task 20-10). */
@RestController
@RequestMapping("/api/admin/settlements")
@RequireRole(Role.MASTER)
@RequiredArgsConstructor
public class PayoutOperationsController {
    private final PayoutOperationTransactions transactions;
    private final PayoutOperationService service;

    @GetMapping("/payouts")
    public ApiResponse<PayoutOperation.Page<PayoutOperationQueries.View>> list(
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) PayoutStatus status,
            @RequestParam(required = false) PayoutOperation.Attention attention) {
        return ApiResponse.ok(transactions.list(
                after, limit, status == null ? null : status.name(), attention == null ? null : attention.name()));
    }

    @GetMapping("/payouts/{payoutId}")
    public ApiResponse<PayoutOperation.Detail> detail(@PathVariable Long payoutId) {
        return ApiResponse.ok(transactions.detail(payoutId));
    }

    @PostMapping("/payouts/{payoutId}/retry")
    public ApiResponse<PayoutOperation.Outcome> retry(
            @PathVariable Long payoutId,
            @Valid @RequestBody PayoutRetryRequest request,
            @CurrentUser UserContext user) {
        var outcome = service.retry(request.toCommand(payoutId), "USER:" + user.userId());
        // Note: 거절 결과도 서비스에서 먼저 커밋한다. HTTP 오류 변환 때문에 명령 기록을 롤백하지 않는다.
        if (outcome.result() != PayoutOperation.Result.APPLIED)
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION, outcome.explanation());
        return ApiResponse.ok(outcome);
    }

    @GetMapping("/receivables")
    public ApiResponse<PayoutOperation.Page<PayoutOperationQueries.Receivable>> receivables(
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(transactions.receivables(after, limit));
    }
}
