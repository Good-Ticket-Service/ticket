package com.sparta.ticket.fakepg.presentation.controller.admin;

import com.sparta.ticket.fakepg.application.service.admin.TransactionAdminService;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransactionStatus;
import com.sparta.ticket.fakepg.presentation.dto.admin.ExternalCancelRequest;
import com.sparta.ticket.fakepg.presentation.dto.admin.TransactionView;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 거래 기록 관리 API. 대사·정합성 테스트가 PG 쪽 기록을 읽고, 외부에서 바뀐 거래를 만들고, 실험 사이에 데이터를 비운다.
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

    /**
     * 우리 API를 거치지 않은 취소를 만든다. 금액을 비우면 전액, 승인액보다 작으면 부분 취소(토스 PARTIAL_CANCELED,
     * 카카오 PART_CANCEL_PAYMENT)다. 장애 설정과 무관하게 바로 반영한다.
     */
    @PostMapping("/{provider}/{orderId}/external-cancel")
    public TransactionView cancelExternally(
            @PathVariable PgProvider provider,
            @PathVariable String orderId,
            @RequestBody(required = false) ExternalCancelRequest request) {
        ExternalCancelRequest body = request == null ? new ExternalCancelRequest(null, null) : request;
        return TransactionView.from(
                adminService.cancelExternally(provider, orderId, body.cancelAmount(), body.reason()));
    }

    /** 거래와 멱등 키 기록을 모두 지운다. 장애 설정은 그대로 둔다. */
    @DeleteMapping
    public ResponseEntity<Void> reset() {
        adminService.reset();
        return ResponseEntity.noContent().build();
    }
}
