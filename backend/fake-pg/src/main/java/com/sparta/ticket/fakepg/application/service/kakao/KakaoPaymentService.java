package com.sparta.ticket.fakepg.application.service.kakao;

import com.sparta.ticket.fakepg.application.service.approval.ConfirmDecision;
import com.sparta.ticket.fakepg.application.service.approval.ConfirmOutcome;
import com.sparta.ticket.fakepg.application.service.approval.ConfirmResult;
import com.sparta.ticket.fakepg.application.service.fault.FaultInjector;
import com.sparta.ticket.fakepg.application.service.fault.ResponseHoldException;
import com.sparta.ticket.fakepg.domain.exception.PgErrorCode;
import com.sparta.ticket.fakepg.domain.exception.PgException;
import com.sparta.ticket.fakepg.domain.model.pg.PgOperation;
import com.sparta.ticket.fakepg.domain.model.pg.PgProvider;
import com.sparta.ticket.fakepg.domain.model.transaction.PgTransaction;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

/**
 * 카카오페이 단건 결제 API의 업무 규칙. 토스와 같이 요청 단위 장애는 presentation의 실행기가 맡고,
 * 여기서는 업무 결과를 바꾸는 장애(승인 거절, 취소 거절, 지연 승인)만 다룬다.
 *
 * Note: 토스와 다른 점은 셋이다. 결제창을 띄우기 전에 준비(tid 발급)를 부르고, 승인에는 금액 대신 pg_token을 보내며,
 * 멱등 키가 없어 이미 취소된 결제를 다시 취소하면 오류다. payment의 카카오 어댑터가 이 차이를 처리하는지 여기서 검증한다.
 */
@Service
public class KakaoPaymentService {

    private final KakaoPaymentTransactions transactions;
    private final FaultInjector faults;
    private final TaskScheduler delayedApprovalScheduler;
    private final Clock clock;
    private final String cid;
    private final int authValidMinutes;

    public KakaoPaymentService(
            KakaoPaymentTransactions transactions,
            FaultInjector faults,
            TaskScheduler delayedApprovalScheduler,
            Clock clock,
            @Value("${fake-pg.kakao.cid}") String cid,
            @Value("${fake-pg.auth-valid-minutes}") int authValidMinutes) {
        this.transactions = transactions;
        this.faults = faults;
        this.delayedApprovalScheduler = delayedApprovalScheduler;
        this.clock = clock;
        this.cid = cid;
        this.authValidMinutes = authValidMinutes;
    }

    public PgTransaction ready(String cid, String orderId, String partnerUserId, String itemName, int amount) {
        verifyCid(cid);
        return transactions.ready(orderId, itemName, amount, partnerUserId, authValidMinutes);
    }

    /** 사용자 승인 시뮬레이션. 브라우저 결제창에서 사용자가 비밀번호 인증을 마친 것처럼 pg_token을 발급한다. */
    public PgTransaction issuePgToken(String tid) {
        ConfirmResult result = transactions.issuePgToken(tid);
        if (result.outcome() == ConfirmOutcome.EXPIRED) {
            throw new PgException(PgErrorCode.NOT_FOUND_PAYMENT_SESSION);
        }
        return result.transaction();
    }

    public PgTransaction approve(String cid, String tid, String orderId, String partnerUserId, String pgToken) {
        verifyCid(cid);
        ConfirmResult result = transactions.approve(tid, orderId, partnerUserId, pgToken, decide());
        return switch (result.outcome()) {
            case APPROVED -> result.transaction();
            case REJECTED -> throw new PgException(PgErrorCode.REJECT_CARD_PAYMENT);
            case EXPIRED -> throw new PgException(PgErrorCode.NOT_FOUND_PAYMENT_SESSION);
            case DELAYED -> {
                Instant approveAt = clock.instant().plusMillis(faults.delayedApprovalMs(PgProvider.KAKAO));
                delayedApprovalScheduler.schedule(() -> transactions.approveLater(tid), approveAt);
                throw new ResponseHoldException();
            }
        };
    }

    public PgTransaction findByTid(String cid, String tid) {
        verifyCid(cid);
        return transactions.findByTid(tid);
    }

    public PgTransaction cancel(String cid, String tid, int cancelAmount) {
        verifyCid(cid);
        return transactions.cancel(tid, cancelAmount, faults.shouldFail(PgProvider.KAKAO, PgOperation.CANCEL));
    }

    private void verifyCid(String cid) {
        if (!this.cid.equals(cid)) {
            throw new PgException(PgErrorCode.INVALID_REQUEST, "등록되지 않은 가맹점 코드(cid)입니다.");
        }
    }

    private ConfirmDecision decide() {
        if (faults.shouldFail(PgProvider.KAKAO, PgOperation.CONFIRM)) {
            return ConfirmDecision.REJECT;
        }
        if (faults.shouldDelayApproval(PgProvider.KAKAO)) {
            return ConfirmDecision.DELAY;
        }
        return ConfirmDecision.APPROVE;
    }
}
