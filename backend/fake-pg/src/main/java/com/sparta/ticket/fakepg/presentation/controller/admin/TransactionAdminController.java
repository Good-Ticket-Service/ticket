package com.sparta.ticket.fakepg.presentation.controller.admin;

import com.sparta.ticket.fakepg.application.service.admin.TransactionAdminService;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransactionStatus;
import com.sparta.ticket.fakepg.presentation.dto.admin.TransactionView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 거래 기록 관리 API. 대사·정합성 테스트가 PG 쪽 기록을 읽고, 실험 사이에 데이터를 비운다.
 */
@RestController
@RequestMapping("/fake/admin/transactions")
@RequiredArgsConstructor
public class TransactionAdminController {

    private final TransactionAdminService adminService;

    @GetMapping
    public List<TransactionView> search(
            @RequestParam(required = false) String orderId,
            @RequestParam(required = false) PgTransactionStatus status,
            @RequestParam(defaultValue = "100") int limit) {
        return adminService.search(orderId, status, limit).stream()
                .map(TransactionView::from)
                .toList();
    }

    /** 거래와 멱등 키 기록을 모두 지운다. 장애 설정은 그대로 둔다. */
    @DeleteMapping
    public ResponseEntity<Void> reset() {
        adminService.reset();
        return ResponseEntity.noContent().build();
    }
}
