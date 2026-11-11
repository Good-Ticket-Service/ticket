package com.sparta.ticket.payment.presentation.seller;

import com.sparta.ticket.common.auth.CurrentUser;
import com.sparta.ticket.common.auth.RequireRole;
import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.response.ApiResponse;
import com.sparta.ticket.payment.application.port.settlement.SettlementPreviewReader;
import com.sparta.ticket.payment.application.service.settlement.SettlementPreviewTransactions;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequireRole(Role.SELLER)
@RequiredArgsConstructor
public class SellerSettlementPreviewController {
    private final SettlementPreviewTransactions transactions;

    @GetMapping("/api/seller/settlements/preview")
    public ResponseEntity<ApiResponse<SettlementPreviewReader.Preview>> preview(
            @CurrentUser UserContext user,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long scheduleId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(transactions.read(user.userId(), scheduleId, from, to, page, size)));
    }
}
