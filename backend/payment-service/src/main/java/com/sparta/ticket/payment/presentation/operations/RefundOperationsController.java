package com.sparta.ticket.payment.presentation.operations;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.payment.application.dto.refund.RefundOperation;
import com.sparta.ticket.payment.application.service.refund.RefundOperationService;
import com.sparta.ticket.payment.application.service.refund.RefundOperationTransactions;
import com.sparta.ticket.payment.domain.model.pg.PgProvider;
import com.sparta.ticket.payment.domain.model.refund.RefundRecoveryMode;
import com.sparta.ticket.payment.domain.model.refund.RefundStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Gateway가 검증한 MASTER만 운영 조회와 명령을 실행한다. */
@RestController
@RequestMapping("/api/admin/payments")
@RequireRole(Role.MASTER)
@RequiredArgsConstructor
public class RefundOperationsController {
    private final RefundOperationTransactions transactions;
    private final RefundOperationService service;
    private final Clock clock;

    @GetMapping("/refunds")
    public ApiResponse<RefundOperation.Page> list(
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) RefundStatus status,
            @RequestParam(required = false) RefundRecoveryMode mode,
            @RequestParam(required = false) PgProvider provider,
            @RequestParam(required = false) @Min(0) @Max(5256000) Long olderThanMinutes) {
        return ApiResponse.ok(transactions.list(
                after,
                limit,
                status,
                mode,
                provider,
                olderThanMinutes == null ? null : LocalDateTime.now(clock).minusMinutes(olderThanMinutes)));
    }

    @GetMapping("/{orderId}/refund")
    public ApiResponse<RefundOperation.Detail> inspect(
            @PathVariable @NotBlank @Size(max = 64) String orderId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        var current = transactions.inspect(orderId);
        String observed = refresh
                ? RefundOperationTransactions.observation(service.observe(orderId, transactions.snapshot(orderId)))
                : "NOT_QUERIED";
        return ApiResponse.ok(new RefundOperation.Detail(
                current, transactions.history(orderId), observed, refresh ? LocalDateTime.now(clock) : null));
    }

    @PostMapping("/{orderId}/refund/retry")
    public ApiResponse<RefundOperation.Outcome> retry(
            @PathVariable @NotBlank @Size(max = 64) String orderId,
            @Valid @RequestBody RefundOperationRequest request,
            @CurrentUser UserContext user) {
        return execute(orderId, request, RefundOperation.Action.RETRY, user);
    }

    @PostMapping("/{orderId}/refund/resume")
    public ApiResponse<RefundOperation.Outcome> resume(
            @PathVariable @NotBlank @Size(max = 64) String orderId,
            @Valid @RequestBody RefundOperationRequest request,
            @CurrentUser UserContext user) {
        return execute(orderId, request, RefundOperation.Action.RESUME, user);
    }

    private ApiResponse<RefundOperation.Outcome> execute(
            String orderId, RefundOperationRequest request, RefundOperation.Action action, UserContext user) {
        var outcome = service.execute(request.toCommand(orderId, action), "USER:" + user.userId());
        // Note: 거절 결과도 서비스에서 먼저 커밋한다. HTTP 오류 변환 때문에 명령 기록을 롤백하지 않는다.
        return switch (outcome.result()) {
            case APPLIED, CONFIRMED_CANCELED -> ApiResponse.ok(outcome);
            case DENIED, STALE ->
                throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION, outcome.explanation());
            case PG_UNKNOWN -> throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, outcome.explanation());
        };
    }
}
