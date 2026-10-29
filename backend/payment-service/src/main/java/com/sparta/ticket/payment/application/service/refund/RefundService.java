package com.sparta.ticket.payment.application.service.refund;

import com.sparta.ticket.payment.application.dto.refund.RefundClaim;
import com.sparta.ticket.payment.application.dto.refund.RefundResult;
import com.sparta.ticket.payment.application.port.pg.PgClient;
import com.sparta.ticket.payment.application.service.pg.PgClientRegistry;
import com.sparta.ticket.payment.domain.model.pg.PgResult;
import com.sparta.ticket.payment.domain.model.refund.RefundReason;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RefundService {
    private final RefundTransactions transactions;
    private final PgClientRegistry clients;
    private final MeterRegistry meters;

    public RefundResult refund(String orderId, RefundReason reason) {
        long started = System.nanoTime();
        try {
            long acceptStarted = System.nanoTime();
            try {
                transactions.accept(orderId, reason);
            } finally {
                meters.timer("payment.refund.accept.duration")
                        .record(System.nanoTime() - acceptStarted, TimeUnit.NANOSECONDS);
            }
            transactions.claim(orderId).ifPresent(this::execute);
            RefundResult result = transactions.find(orderId);
            try {
                meters.counter("payment.api.results", "operation", "refund", "status", result.refundStatus())
                        .increment();
            } catch (RuntimeException ignored) {
                // Note: 환불 결과를 계측 실패로 바꾸지 않는다.
            }
            return result;
        } finally {
            meters.timer("payment.refund.request.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    public void execute(RefundClaim claim) {
        if (claim.inquireFirst()) {
            com.sparta.ticket.payment.domain.model.pg.PgInquiry inquiry;
            long inquiryStarted = System.nanoTime();
            try {
                inquiry = clients.get(claim.provider())
                        .inquire(new PgClient.PgInquireRequest(claim.orderId(), claim.transactionId()));
            } catch (RuntimeException e) {
                inquiry =
                        new com.sparta.ticket.payment.domain.model.pg.PgInquiry.Unknown("PG_CANCEL_INQUIRY_EXCEPTION");
            } finally {
                meters.timer("payment.refund.inquiry.duration")
                        .record(System.nanoTime() - inquiryStarted, TimeUnit.NANOSECONDS);
            }
            if (!transactions.applyInquiry(claim, inquiry)) return;
        }
        if (!transactions.authorizeCancel(claim)) return;
        long started = System.nanoTime();
        PgResult result;
        try {
            result = clients.get(claim.provider())
                    .cancel(new PgClient.PgCancelRequest(
                            claim.orderId(),
                            claim.transactionId(),
                            claim.amount(),
                            claim.cancelKey(),
                            claim.reason().name()));
        } catch (RuntimeException e) {
            result = new PgResult.Unknown("PG_CANCEL_EXCEPTION");
        } finally {
            meters.timer("payment.refund.cancel.duration").record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
        if (transactions.apply(claim, result)) {
            try {
                var inquiry = clients.get(claim.provider())
                        .inquire(new PgClient.PgInquireRequest(claim.orderId(), claim.transactionId()));
                transactions.confirmCanceled(claim, inquiry);
            } catch (RuntimeException e) {
                meters.counter("payment.refund.errors", "phase", "verify-stale").increment();
            }
        }
    }
}
