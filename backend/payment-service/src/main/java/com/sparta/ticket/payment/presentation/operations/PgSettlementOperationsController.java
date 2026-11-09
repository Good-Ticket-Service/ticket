package com.sparta.ticket.payment.presentation.operations;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.payment.application.port.pgsettlement.PgSettlementExecution;
import com.sparta.ticket.payment.application.service.pgsettlement.PgSettlementTransactions;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/settlements/pg")
@RequireRole(Role.MASTER)
@RequiredArgsConstructor
public class PgSettlementOperationsController {
    private final PgSettlementTransactions transactions;
    private final PgSettlementExecution executions;

    public record Run(
            @NotBlank @Pattern(regexp = "TOSS|KAKAO") String provider,
            @NotNull LocalDate settlementDate) {}

    public record Recover(@NotBlank @Size(max = 500) String reason, boolean processTerminated) {}

    public record Resolve(@NotBlank @Size(max = 500) String reason) {}

    @PostMapping("/runs")
    public ApiResponse<PgSettlementExecution.Execution> run(@Valid @RequestBody Run request) {
        return ApiResponse.ok(executions.run(request.provider(), request.settlementDate()));
    }

    @PostMapping("/executions/{executionId}/recover")
    public ApiResponse<PgSettlementExecution.Execution> recover(
            @PathVariable long executionId, @Valid @RequestBody Recover request, @CurrentUser UserContext user) {
        return ApiResponse.ok(executions.recover(
                executionId, request.reason(), "USER:" + user.userId(), request.processTerminated()));
    }

    @GetMapping("/balances")
    public ApiResponse<com.sparta.ticket.payment.application.port.pgsettlement.PgSettlementStore.Balances> balances() {
        return ApiResponse.ok(transactions.balances());
    }

    @GetMapping("/executions/{executionId}")
    public ApiResponse<PgSettlementExecution.Progress> progress(@PathVariable long executionId) {
        return ApiResponse.ok(executions.progress(executionId));
    }

    @PostMapping("/findings/{id}/resolve")
    public ApiResponse<Void> resolve(
            @PathVariable long id, @Valid @RequestBody Resolve request, @CurrentUser UserContext user) {
        transactions.resolve(id, request.reason(), "USER:" + user.userId());
        return ApiResponse.ok(null);
    }

    @GetMapping("/days")
    public ApiResponse<List<Map<String, Object>>> days(
            @RequestParam @Pattern(regexp = "TOSS|KAKAO") String provider,
            @RequestParam(defaultValue = "2025-12-31") LocalDate after,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(transactions.days(provider, after, limit));
    }

    @GetMapping("/findings")
    public ApiResponse<List<Map<String, Object>>> findings(
            @RequestParam @Pattern(regexp = "TOSS|KAKAO") String provider,
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(defaultValue = "OPEN") @Pattern(regexp = "OPEN|RESOLVED") String status) {
        return ApiResponse.ok(transactions.findings(provider, after, limit, status));
    }
}
