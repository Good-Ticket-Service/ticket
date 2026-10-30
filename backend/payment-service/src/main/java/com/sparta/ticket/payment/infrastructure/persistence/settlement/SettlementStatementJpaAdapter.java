package com.sparta.ticket.payment.infrastructure.persistence.settlement;

import com.sparta.ticket.payment.domain.model.settlement.Payout;
import com.sparta.ticket.payment.domain.model.settlement.SettlementStatement;
import com.sparta.ticket.payment.domain.model.settlement.SettlementStatementLine;
import com.sparta.ticket.payment.domain.repository.settlement.SettlementStatementRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 정산서·명세·지급·판매자 기준 행을 한 발행 트랜잭션에서 다룬다. 여러 Entity를 쓰므로 Spring Data 대신 EntityManager로 묶는다. */
@Repository
public class SettlementStatementJpaAdapter implements SettlementStatementRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public SettlementStatement saveAndFlush(SettlementStatement statement) {
        entityManager.persist(statement);
        entityManager.flush();
        return statement;
    }

    @Override
    public int nextSequence(Long sellerId, Long scheduleId) {
        Number next = (Number) entityManager
                .createNativeQuery("SELECT COALESCE(MAX(sequence) + 1, 0) FROM p_settlement_statements"
                        + " WHERE seller_id = :sellerId AND schedule_id = :scheduleId")
                .setParameter("sellerId", sellerId)
                .setParameter("scheduleId", scheduleId)
                .getSingleResult();
        return next.intValue();
    }

    @Override
    public void saveLines(List<SettlementStatementLine> lines) {
        lines.forEach(entityManager::persist);
        entityManager.flush();
    }

    @Override
    public Payout savePayout(Payout payout) {
        entityManager.persist(payout);
        entityManager.flush();
        return payout;
    }

    @Override
    public void limitLockWait(long timeoutMillis) {
        // SET은 바인딩 변수를 받지 않는다. 숫자만 들어가므로 문자열로 만들어도 안전하다
        entityManager
                .createNativeQuery("SET LOCAL lock_timeout = '" + timeoutMillis + "ms'")
                .executeUpdate();
    }

    /**
     * Note: 이미 있는 판매자는 INSERT를 시도하지 않는다. ON CONFLICT도 다른 트랜잭션이 만들고 있는 같은 행의 커밋을 기다리므로,
     * 판매자 행이 생긴 뒤에는 그 대기와 IDENTITY 값 소모를 피한다. 처음 만드는 판매자만 짧게 기다릴 수 있다.
     */
    @Override
    public void ensureSellerAccount(Long sellerId) {
        boolean exists = !entityManager
                .createNativeQuery("SELECT 1 FROM p_seller_payout_accounts WHERE seller_id = :sellerId")
                .setParameter("sellerId", sellerId)
                .getResultList()
                .isEmpty();
        if (exists) return;
        entityManager
                .createNativeQuery("INSERT INTO p_seller_payout_accounts (seller_id, version, created_at, created_by,"
                        + " updated_at, updated_by) VALUES (:sellerId, 0, now(), 'settlement', now(), 'settlement')"
                        + " ON CONFLICT (seller_id) DO NOTHING")
                .setParameter("sellerId", sellerId)
                .executeUpdate();
    }

    @Override
    public Optional<Long> lockSellerAccountSkipLocked(Long sellerId) {
        List<?> rows = entityManager
                .createNativeQuery("SELECT id FROM p_seller_payout_accounts WHERE seller_id = :sellerId"
                        + " FOR UPDATE SKIP LOCKED")
                .setParameter("sellerId", sellerId)
                .getResultList();
        return rows.isEmpty() ? Optional.empty() : Optional.of(((Number) rows.get(0)).longValue());
    }
}
