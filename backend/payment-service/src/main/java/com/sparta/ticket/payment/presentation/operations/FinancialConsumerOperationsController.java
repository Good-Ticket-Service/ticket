package com.sparta.ticket.payment.presentation.operations;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.payment.application.port.settlement.FinancialConsumerRecovery;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** source/금액 입력 없이 영속 격리 이력에 대한 감사 가능한 명령만 받는다. */
@RestController
@RequestMapping("/api/admin/settlements/consumers")
@RequireRole(Role.MASTER)
@RequiredArgsConstructor
public class FinancialConsumerOperationsController {
    private final FinancialConsumerRecovery recovery;

    public record Command(
            @NotNull UUID commandId,
            @NotBlank @Size(max = 500) String reason) {}

    @GetMapping("/incidents")
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam(defaultValue = "0") @Min(0) long after,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) @Pattern(regexp = "OPEN|REPLAY_REQUESTED|RESOLVED") String status) {
        return ApiResponse.ok(recovery.list(after, limit, status));
    }

    @GetMapping("/commands/{commandId}")
    public ApiResponse<Map<String, Object>> command(@PathVariable UUID commandId) {
        return ApiResponse.ok(recovery.command(commandId));
    }

    @PostMapping("/incidents/{incidentId}/{action:replay|resolve}")
    public ApiResponse<FinancialConsumerRecovery.Outcome> request(
            @PathVariable UUID incidentId,
            @PathVariable String action,
            @Valid @RequestBody Command command,
            @CurrentUser UserContext user) {
        var result = recovery.request(
                command.commandId(),
                incidentId,
                action.toUpperCase(java.util.Locale.ROOT),
                "USER:" + user.userId(),
                command.reason());
        if ("DENIED".equals(result.status()))
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION, result.explanation());
        return ApiResponse.ok(result);
    }
}
