package com.sparta.ticket.payment.application.service.settlement;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.response.PageResponse;
import com.sparta.ticket.payment.domain.exception.settlement.SettlementErrorCode;
import com.sparta.ticket.payment.domain.model.settlement.SellerPayoutAccount;
import com.sparta.ticket.payment.domain.model.settlement.StatementStatus;
import com.sparta.ticket.payment.domain.repository.settlement.SellerPayoutAccountRepository;
import com.sparta.ticket.payment.domain.repository.settlement.SellerStatementQueries;
import com.sparta.ticket.payment.domain.repository.settlement.SellerStatementQueries.Line;
import com.sparta.ticket.payment.domain.repository.settlement.SellerStatementQueries.Receivable;
import com.sparta.ticket.payment.domain.repository.settlement.SellerStatementQueries.Summary;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementStatementRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 수취 계좌 등록·변경과 본인 정산 조회(Task 20-7, settlement-design 7.4절).
 * 판매자 ID는 Gateway가 검증해 넣은 사용자 ID만 받는다. 요청 본문의 판매자 값으로 권한을 주지 않는다.
 */
@Component
@RequiredArgsConstructor
public class SellerSettlementTransactions {

    /** 기간 조건이 없을 때의 경계. 발행 시각은 이 범위 안에 있다. */
    private static final LocalDateTime EARLIEST = LocalDateTime.of(1970, 1, 1, 0, 0);

    private static final LocalDateTime LATEST = LocalDateTime.of(9999, 1, 1, 0, 0);

    private final SellerPayoutAccountRepository accounts;
    private final SettlementStatementRepository statements;
    private final SellerStatementQueries queries;

    public record PayoutAccountView(
            String bankCode, String accountMasked, String holderMasked, long version, LocalDateTime updatedAt) {

        static PayoutAccountView from(SellerPayoutAccount account) {
            return new PayoutAccountView(
                    account.getBankCode(),
                    account.getAccountMasked(),
                    account.getHolderMasked(),
                    account.getVersion(),
                    account.getUpdatedAt());
        }
    }

    /** 정산서 요약 필드를 펼쳐 명세행과 함께 내보낸다. */
    public record StatementDetail(@JsonUnwrapped Summary summary, List<Line> lines) {}

    /** 등록 전(행이 없거나 정산서 발행이 만든 빈 기준 행)이면 404다. */
    @Transactional(readOnly = true)
    public PayoutAccountView account(Long sellerId) {
        return accounts.findBySellerId(sellerId)
                .filter(SellerPayoutAccount::isRegistered)
                .map(PayoutAccountView::from)
                .orElseThrow(() -> new BusinessException(SettlementErrorCode.PAYOUT_ACCOUNT_NOT_FOUND));
    }

    /**
     * 계좌를 등록하거나 바꾼다. 판매자 행만 잠근다. 등록 전에는 If-Match 없이 등록하고, 등록 뒤에는 현재 version과 같아야 바꾼다.
     * 정산서 발행이 기준 행을 동시에 만들어도 같은 행을 잠그므로 판매자 행은 하나이고 계좌 정보가 사라지지 않는다.
     *
     * @param expectedVersion If-Match의 version. 없으면 null
     */
    @Transactional
    public PayoutAccountView register(
            Long sellerId, Long expectedVersion, String bankCode, String accountNumber, String holderName) {
        statements.ensureSellerAccount(sellerId);
        SellerPayoutAccount account = accounts.lockBySellerId(sellerId).orElseThrow();
        if (account.isRegistered() && expectedVersion == null)
            throw new BusinessException(SettlementErrorCode.PRECONDITION_REQUIRED);
        if (expectedVersion != null && expectedVersion != account.getVersion())
            throw new BusinessException(SettlementErrorCode.VERSION_CONFLICT);
        account.register(bankCode, accountNumber, holderName);
        return PayoutAccountView.from(accounts.saveAndFlush(account));
    }

    /** 발행일 [from, to] 범위의 본인 정산서. 발행 시각·ID 내림차순으로 고정한다. */
    @Transactional(readOnly = true)
    public PageResponse<Summary> statements(
            Long sellerId, LocalDate from, LocalDate to, StatementStatus status, int page, int size) {
        LocalDateTime start = from == null ? EARLIEST : from.atStartOfDay();
        LocalDateTime end = to == null ? LATEST : to.plusDays(1).atStartOfDay();
        String statusName = status == null ? null : status.name();
        long total = queries.countStatements(sellerId, start, end, statusName);
        List<Summary> content =
                queries.findStatements(sellerId, start, end, statusName, Math.multiplyExact(page, size), size);
        int totalPages = (int) ((total + size - 1) / size);
        return new PageResponse<>(content, page, size, total, totalPages, "issuedAt,DESC;id,DESC");
    }

    /** 다른 판매자의 정산서는 존재 여부를 드러내지 않도록 없는 것과 같은 404다. */
    @Transactional(readOnly = true)
    public StatementDetail statement(Long sellerId, Long statementId) {
        Summary summary = queries.findStatement(sellerId, statementId)
                .orElseThrow(() -> new BusinessException(SettlementErrorCode.STATEMENT_NOT_FOUND));
        return new StatementDetail(summary, queries.findLines(statementId));
    }

    @Transactional(readOnly = true)
    public Receivable receivable(Long sellerId) {
        return queries.findReceivable(sellerId);
    }
}
