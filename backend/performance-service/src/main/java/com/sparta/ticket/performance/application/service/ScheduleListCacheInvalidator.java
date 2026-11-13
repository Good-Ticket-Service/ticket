package com.sparta.ticket.performance.application.service;

import com.sparta.ticket.performance.application.port.PublicScheduleListCache;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 실제 저장한 트랜잭션의 커밋 뒤 목록을 지운다. REQUIRES_NEW의 안쪽 저장에도 같은 원칙을 적용한다. */
@Component
@RequiredArgsConstructor
public class ScheduleListCacheInvalidator {
    private final PublicScheduleListCache cache;

    public void evictAfterCommit(Long performanceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("회차 목록 캐시 무효화는 저장 트랜잭션 안에서 등록해야 한다.");
        }
        // Note: 현재 트랜잭션의 동기화 목록에 삭제 대상을 모은다. Spring이 REQUIRES_NEW에서 이 목록을
        // 중단·복원하므로, 바깥 트랜잭션과 같은 공연을 저장해도 안쪽 커밋의 삭제를 합치지 않는다.
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof PendingEvictions pending && pending.cache == cache) {
                pending.performanceIds.add(performanceId);
                return;
            }
        }
        PendingEvictions pending = new PendingEvictions(cache);
        pending.performanceIds.add(performanceId);
        TransactionSynchronizationManager.registerSynchronization(pending);
    }

    private static final class PendingEvictions implements TransactionSynchronization {
        private final PublicScheduleListCache cache;
        private final Set<Long> performanceIds = new LinkedHashSet<>();

        private PendingEvictions(PublicScheduleListCache cache) {
            this.cache = cache;
        }

        @Override
        public void afterCommit() {
            performanceIds.forEach(cache::evict);
        }
    }
}
