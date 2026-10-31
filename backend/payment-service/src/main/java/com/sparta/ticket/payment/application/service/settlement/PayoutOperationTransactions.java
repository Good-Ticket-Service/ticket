package com.sparta.ticket.payment.application.service.settlement;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.payment.application.dto.settlement.PayoutOperation;
import com.sparta.ticket.payment.application.dto.settlement.PayoutOperation.Result;
import com.sparta.ticket.payment.application.service.observation.CommittedObservation;
import com.sparta.ticket.payment.domain.exception.settlement.SettlementErrorCode;
import com.sparta.ticket.payment.domain.model.settlement.Payout;
import com.sparta.ticket.payment.domain.model.settlement.PayoutOperationCommand;
import com.sparta.ticket.payment.domain.model.settlement.PayoutStatus;
import com.sparta.ticket.payment.domain.repository.settlement.PayoutOperationQueries;
import com.sparta.ticket.payment.domain.repository.settlement.PayoutOperationRepository;
import com.sparta.ticket.payment.domain.repository.settlement.PayoutRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지급 운영 retry와 조회(Task 20-10, settlement-design 7.3절). 상태 변경과 명령 기록은 한 트랜잭션이다. 거절(DENIED·STALE)도
 * 기록을 커밋한 뒤 HTTP 오류로 바꾼다.
 * Note: retry 안전성의 근거는 실패 코드가 아니라 "지급대행이 실패를 확정해 돈이 나가지 않았다"는 사실이다. 그래서 확정 실패(FAILED)는
 * 코드와 무관하게 새 키로 다시 보낼 수 있고, 결과를 모르는 REQUESTED·inquiry_only는 어떤 사유로도 retry하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PayoutOperationTransactions {

    private final PayoutRepository payouts;
    private final PayoutOperationRepository commands;
    private final PayoutOperationQueries queries;
    private final CommittedObservation observation;
    private final Clock clock;

    @Value("${payment.settlement.payout.lock-timeout-ms:5000}")
    private long lockTimeoutMillis;

    @Transactional(readOnly = true)
    public Optional<PayoutOperation.Outcome> previous(String commandId, String hash) {
        return commands.findById(commandId).map(command -> replay(command, hash));
    }

    @Transactional
    public PayoutOperation.Outcome retry(PayoutOperation.Retry request, String hash, String actor) {
        payouts.limitLockWait(lockTimeoutMillis);
        Payout payout = payouts.lock(request.payoutId())
                .orElseThrow(() -> new BusinessException(SettlementErrorCode.PAYOUT_NOT_FOUND));
        // 같은 commandId가 지급 잠금을 기다리는 사이 먼저 커밋됐을 수 있다
        Optional<PayoutOperationCommand> existing = commands.findById(request.commandId());
        if (existing.isPresent()) return replay(existing.get(), hash);

        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
        String before = state(payout.getStatus(), payout.getVersion(), payout.getAttempt());
        Result result;
        String explanation;
        String after;
        if (payout.getVersion() != request.expectedVersion() || payout.getAttempt() != request.expectedAttempt()) {
            result = Result.STALE;
            explanation = "STATE_CHANGED_INSPECT_AGAIN";
            after = before;
        } else if (payout.getStatus() != PayoutStatus.FAILED) {
            result = Result.DENIED;
            explanation = "ONLY_CONFIRMED_FAILURE_IS_RETRYABLE";
            after = before;
        } else {
            payout.retry(now);
            result = Result.APPLIED;
            explanation = "SCHEDULED_FOR_NEXT_EXECUTION_NOT_PAID";
            // @Version은 커밋 flush 때 1 오른다
            after = state(payout.getStatus(), payout.getVersion() + 1, payout.getAttempt());
        }
        PayoutOperationCommand command = commands.saveAndFlush(new PayoutOperationCommand(
                request.commandId(),
                hash,
                payout.getId(),
                "RETRY",
                actor,
                PayoutOperation.REASON,
                request.note(),
                result.name(),
                explanation,
                state(PayoutStatus.FAILED, request.expectedVersion(), request.expectedAttempt()),
                before,
                after,
                now));
        observation.counter("settlement.payout.operation", "result", result.name());
        return outcome(command);
    }

    @Transactional(readOnly = true)
    public PayoutOperation.Page<PayoutOperationQueries.View> list(
            long after, int limit, String status, String attention) {
        List<PayoutOperationQueries.View> rows = queries.findPayouts(after, limit + 1, status, attention);
        boolean more = rows.size() > limit;
        List<PayoutOperationQueries.View> items = more ? rows.subList(0, limit) : rows;
        return new PayoutOperation.Page<>(
                List.copyOf(items), more ? items.get(items.size() - 1).payoutId() : null);
    }

    @Transactional(readOnly = true)
    public PayoutOperation.Detail detail(Long payoutId) {
        PayoutOperationQueries.View current = queries.findPayout(payoutId)
                .orElseThrow(() -> new BusinessException(SettlementErrorCode.PAYOUT_NOT_FOUND));
        List<PayoutOperation.History> history =
                commands.findFirst20ByPayoutIdOrderByProcessedAtDescCommandIdDesc(payoutId).stream()
                        .map(c -> new PayoutOperation.History(
                                c.getCommandId(),
                                c.getAction(),
                                c.getActor(),
                                c.getReason(),
                                c.getNote(),
                                c.getResult(),
                                c.getExplanation(),
                                c.getBeforeState(),
                                c.getAfterState(),
                                c.getProcessedAt()))
                        .toList();
        return new PayoutOperation.Detail(current, history);
    }

    @Transactional(readOnly = true)
    public PayoutOperation.Page<PayoutOperationQueries.Receivable> receivables(long after, int limit) {
        List<PayoutOperationQueries.Receivable> rows = queries.findReceivables(after, limit + 1);
        boolean more = rows.size() > limit;
        List<PayoutOperationQueries.Receivable> items = more ? rows.subList(0, limit) : rows;
        return new PayoutOperation.Page<>(
                List.copyOf(items), more ? items.get(items.size() - 1).sellerId() : null);
    }

    private static String state(PayoutStatus status, long version, int attempt) {
        return status + ":" + version + ":" + attempt;
    }

    private static PayoutOperation.Outcome replay(PayoutOperationCommand command, String hash) {
        if (!command.getInputHash().equals(hash))
            throw new BusinessException(SettlementErrorCode.PAYOUT_COMMAND_CONFLICT);
        return outcome(command);
    }

    private static PayoutOperation.Outcome outcome(PayoutOperationCommand command) {
        String[] after = command.getAfterState().split(":");
        return new PayoutOperation.Outcome(
                command.getCommandId(),
                Result.valueOf(command.getResult()),
                command.getExplanation(),
                after[0],
                Integer.parseInt(after[2]),
                Long.parseLong(after[1]),
                command.getProcessedAt());
    }
}
