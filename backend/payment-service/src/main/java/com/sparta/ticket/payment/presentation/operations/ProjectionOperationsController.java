package com.sparta.ticket.payment.presentation.operations;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.payment.application.port.settlement.ProjectionOperations;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/admin/settlements/projections")
@RequireRole(Role.MASTER)
@RequiredArgsConstructor
public class ProjectionOperationsController {
    private final ProjectionOperations operations;

    public record Create(
            @NotNull UUID commandId,
            @NotBlank @Pattern(regexp = "D|B|C") String mode,
            @NotBlank @Size(max = 500) String reason) {}

    public record Control(
            @NotNull UUID commandId,
            @NotNull @Min(0) Long expectedGeneration,
            @NotBlank @Size(max = 500) String reason) {}

    @PostMapping("/builds")
    public ApiResponse<Map<String, UUID>> create(@Valid @RequestBody Create request, @CurrentUser UserContext user) {
        UUID id = operations.create(request.commandId(), request.mode(), "USER:" + user.userId(), request.reason());
        return ApiResponse.ok(Map.of("buildId", id));
    }

    @GetMapping("/builds/{buildId}")
    public ApiResponse<Map<String, Object>> progress(@PathVariable UUID buildId) {
        return ApiResponse.ok(operations.progress(buildId));
    }

    @PostMapping("/builds/{buildId}/{action:freeze|verify|activate|resume|rollback|discard}")
    public ApiResponse<ProjectionOperations.Result> control(
            @PathVariable UUID buildId,
            @PathVariable String action,
            @Valid @RequestBody Control request,
            @CurrentUser UserContext user) {
        try {
            return ApiResponse.ok(operations.control(
                    request.commandId(),
                    buildId,
                    action,
                    request.expectedGeneration(),
                    "USER:" + user.userId(),
                    request.reason()));
        } catch (org.springframework.dao.ConcurrencyFailureException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_STATUS_TRANSITION);
        }
    }
}
