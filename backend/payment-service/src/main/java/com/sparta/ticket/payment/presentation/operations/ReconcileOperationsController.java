package com.sparta.ticket.payment.presentation.operations;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.payment.application.port.settlement.ReconcileExecution;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/settlements/reconciliation")
@RequireRole(Role.MASTER)
@RequiredArgsConstructor
public class ReconcileOperationsController {
    private final ReconcileExecution executions;

    public record Run(
            @NotNull LocalDate businessDate,
            @NotNull UUID commandId,
            @NotBlank @Pattern(regexp = "FULL") String mode,
            @NotBlank @Size(max = 500) String reason) {}

    public record Recover(@NotBlank @Size(max = 500) String reason, boolean processTerminated) {}

    @PostMapping("/runs")
    public ApiResponse<ReconcileExecution.Execution> run(
            @Valid @RequestBody Run request, @CurrentUser UserContext user) {
        return ApiResponse.ok(
                executions.run(request.businessDate(), request.commandId(), request.reason(), "USER:" + user.userId()));
    }

    @GetMapping("/executions/{id}")
    public ApiResponse<Map<String, Object>> progress(@PathVariable long id) {
        return ApiResponse.ok(executions.progress(id));
    }

    @PostMapping("/executions/{id}/retry")
    public ApiResponse<ReconcileExecution.Execution> retry(@PathVariable long id) {
        return ApiResponse.ok(executions.retry(id));
    }

    @PostMapping("/executions/{id}/recover")
    public ApiResponse<ReconcileExecution.Execution> recover(
            @PathVariable long id, @Valid @RequestBody Recover request, @CurrentUser UserContext user) {
        return ApiResponse.ok(
                executions.recover(id, request.reason(), "USER:" + user.userId(), request.processTerminated()));
    }
}
