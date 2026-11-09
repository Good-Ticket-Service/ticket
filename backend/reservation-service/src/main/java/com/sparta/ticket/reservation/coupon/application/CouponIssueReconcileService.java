package com.sparta.ticket.reservation.coupon.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.reservation.coupon.domain.Coupon;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate;
import com.sparta.ticket.reservation.coupon.domain.CouponIssueGate.IssueSnapshot;
import com.sparta.ticket.reservation.coupon.domain.CouponRepository;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 맞추는 작업(#323). 쿠폰 발급 판단 Redis 사본을 주기마다 DB 원본에 맞춘다. 스케줄러가 1분마다 부른다.
 * 발급(#300 C1)과 관리자 수정·삭제(B1)는 실패하면 그 자리에서 Redis를 되돌린다. 이 작업은 그 되돌리기를 못 한 경우의 안전망이다.
 * - 되돌리기를 시도했는데 실패함(Redis 연결 끊김, DB 확인 실패)
 * - 되돌리기를 시도할 기회가 없었음(Redis를 바꾼 직후 서버가 죽음)
 * - 커밋 결과를 몰라 안전한 쪽으로 남겨 둠
 *
 * 쿠폰마다 하는 일
 * 1. 회원 키 ↔ DB 회원 쿠폰. DB에만 있으면 바로 추가하고, Redis에만 있으면 두 번 연속 확인된 뒤 지운다
 * 2. 정보 키(total, deleted) ← DB 쿠폰. 관리자 수정·삭제 중에 옛 값으로 덮지 않도록 쿠폰 행을 잠그고 맞춘다
 * 3. DB issued_quantity ← 회원 쿠폰 수. 총 수량을 넘으면 바꾸지 않고 초과 발급 경보(오류 로그)를 남긴다
 * 4. 두 키의 유효 시간 ← 발급 종료 + 1일. 관리자가 종료 시각을 바꿔도 따라간다
 *
 * Note: 기준은 늘 DB다. Redis 정보 키가 없는 쿠폰은 맞출 사본이 없어 1·2·4를 건너뛴다(다음 발급 때 DB에서 채운다).
 * Note: 서버가 여러 대면 서버마다 돈다. 추가·덮어쓰기는 같은 DB 값을 쓰므로 겹쳐도 결과가 같고,
 * Redis에만 있는 회원은 각 서버가 스스로 두 번 확인한 뒤에만 지운다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueReconcileService {

    /**
     * Redis에만 있는 회원을 처음 본 뒤 지우기까지 기다리는 시간.
     * 발급은 Redis 기록 → DB 저장 순서라, 그 짧은 사이에는 정상 요청도 Redis에만 있다. 이것을 지우면 자리가 비어 초과 발급이 된다.
     * 정상 요청은 몇 ms~몇 초 안에 저장을 마치므로, 1분 뒤에도 DB에 없으면 실패한 발급으로 본다.
     */
    static final Duration CONFIRM_AFTER = Duration.ofMinutes(1);

    private final CouponRepository couponRepository;
    private final UserCouponRepository userCouponRepository;
    private final CouponIssueGate issueGate;
    private final CouponIssueState issueState;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** 쿠폰 ID → (Redis에만 있던 회원 ID → 처음 본 시각). 다음 실행에서 다시 확인한다. 이 서버의 스케줄러 스레드 하나만 쓴다. */
    private final Map<Long, Map<Long, Instant>> suspects = new HashMap<>();

    /** 실행이 겹치지 않게 하는 잠금. 스케줄러는 실행을 겹치지 않지만, 테스트 등이 직접 불러도 기억(suspects)이 섞이지 않게 한다. */
    private final Object runLock = new Object();

    /** 대상 쿠폰을 모두 맞추고 한 일을 센다. 쿠폰 하나가 실패해도 나머지는 계속한다. Redis를 쓸 수 없으면 이번 실행을 멈춘다. */
    public Result reconcile() {
        synchronized (runLock) {
            return reconcileTargets();
        }
    }

    private Result reconcileTargets() {
        LocalDateTime now = LocalDateTime.now(clock);
        Counter counter = new Counter();

        // 1) 키가 아직 남아 있을 쿠폰만 고른다. 끝난 지 하루가 지난 쿠폰은 키가 유효 시간으로 지워졌다
        List<Coupon> targets = couponRepository.findIssueStateTargets(now.minus(CouponIssueState.RETENTION));
        Set<Long> targetIds = new HashSet<>();
        targets.forEach(coupon -> targetIds.add(coupon.getId()));
        // 대상에서 빠진 쿠폰의 기억은 버린다(끝났거나 삭제됨)
        suspects.keySet().retainAll(targetIds);

        // 2) 쿠폰마다 맞춘다
        for (Coupon coupon : targets) {
            try {
                reconcile(coupon, counter);
            } catch (BusinessException e) {
                if (e.getErrorCode() == CommonErrorCode.SERVICE_UNAVAILABLE) {
                    // Redis 장애면 남은 쿠폰도 모두 실패한다. 이번 실행은 멈추고 다음 주기에 다시 한다
                    log.warn("Redis를 쓸 수 없어 쿠폰 발급 판단 맞추기를 멈춥니다. couponId={}", coupon.getId());
                    counter.failed++;
                    break;
                }
                counter.fail(coupon, e);
            } catch (RuntimeException e) {
                counter.fail(coupon, e);
            }
        }
        return counter.result(targets.size());
    }

    private void reconcile(Coupon coupon, Counter counter) {
        Long couponId = coupon.getId();

        // 1) Redis를 먼저 읽고 DB를 읽는다. 반대면 그사이 발급된 회원이 "Redis에만 있음"으로 잘못 보인다
        Optional<IssueSnapshot> snapshot = issueGate.snapshot(couponId);
        int dbCount = userCouponRepository.countByCouponIds(List.of(couponId)).getOrDefault(couponId, 0);

        // 2) DB issued_quantity를 회원 쿠폰 수로 맞춘다. 총 수량을 넘으면 경보만 남긴다
        syncIssuedQuantity(coupon, dbCount, counter);

        // 3) Redis 정보가 없으면 맞출 사본이 없다. 다음 발급 때 DB에서 채운다
        if (snapshot.isEmpty()) {
            suspects.remove(couponId);
            return;
        }

        // 4) 회원 키, 정보 키, 유효 시간 순으로 맞춘다
        reconcileUsers(couponId, snapshot.get().issuedCount(), dbCount, counter);
        if (snapshot.get().totalQuantity() != coupon.getTotalQuantity()
                || snapshot.get().deleted()) {
            syncMeta(coupon, counter);
        }
        issueGate.expire(couponId, issueState.ttl(coupon.getIssueEndAt()));
    }

    /** issued_quantity를 회원 쿠폰 수로 맞춘다. 회원 쿠폰 수가 총 수량을 넘으면 초과 발급이라 오류 로그를 남기고 바꾸지 않는다. */
    private void syncIssuedQuantity(Coupon coupon, int dbCount, Counter counter) {
        if (dbCount > coupon.getTotalQuantity()) {
            // 초과 발급 경보. Redis 유실 순간의 틈(#300 C3) 같은 드문 경우에만 생긴다. 알림 연결은 모니터링에서 한다
            log.error(
                    "쿠폰 초과 발급을 발견했습니다. couponId={}, 회원 쿠폰 수={}, 총 수량={}",
                    coupon.getId(),
                    dbCount,
                    coupon.getTotalQuantity());
            counter.overIssued++;
            return;
        }
        Integer changed =
                transactionTemplate.execute(status -> couponRepository.syncIssuedQuantity(coupon.getId(), dbCount));
        if (changed != null && changed > 0) {
            counter.issuedQuantityUpdated++;
        }
    }

    /**
     * 회원 키를 DB 회원 쿠폰에 맞춘다.
     * 회원 수가 같고 확인 중인 회원도 없으면 회원 전체를 읽지 않는다. 받은 회원이 수만 명인 쿠폰을 매분 읽지 않기 위해서다.
     * Note: 회원 수가 같아도 "Redis에만 1명, DB에만 1명"이면 놓친다. DB에만 있는 회원은 Redis 유실 순간의 틈에서만 생겨 드물고,
     * 그 쿠폰은 Redis에만 있는 회원이 지워질 때 회원 수가 달라져 다음 실행에서 함께 맞춰진다.
     */
    private void reconcileUsers(Long couponId, int redisCount, int dbCount, Counter counter) {
        Map<Long, Instant> previous = suspects.getOrDefault(couponId, Map.of());
        if (redisCount == dbCount && previous.isEmpty()) {
            return;
        }

        // 1) Redis를 먼저 읽고 DB를 읽는다(reconcile과 같은 이유)
        Set<Long> redisIds = issueGate.issuedUserIds(couponId);
        Set<Long> dbIds = new HashSet<>(userCouponRepository.findUserIdsByCouponId(couponId));

        // 2) DB에만 있는 회원은 바로 추가한다. 발급을 막는 쪽이라 잘못돼도 초과 발급이 생기지 않는다
        List<Long> dbOnly = dbIds.stream().filter(id -> !redisIds.contains(id)).toList();
        issueGate.addIssued(couponId, dbOnly);
        counter.added += dbOnly.size();

        // 3) Redis에만 있는 회원은 처음 본 뒤 CONFIRM_AFTER가 지났고 앞 실행에서도 봤을 때만 지운다
        Instant now = clock.instant();
        Map<Long, Instant> next = new HashMap<>();
        for (Long userId : redisIds) {
            if (dbIds.contains(userId)) {
                continue;
            }
            Instant firstSeen = previous.get(userId);
            if (firstSeen != null && !now.isBefore(firstSeen.plus(CONFIRM_AFTER))) {
                issueGate.cancel(couponId, userId);
                counter.removed++;
                log.info("DB에 없는 쿠폰 발급 기록을 Redis에서 지웠습니다. couponId={}, userId={}", couponId, userId);
            } else {
                next.put(userId, firstSeen != null ? firstSeen : now);
            }
        }
        if (next.isEmpty()) {
            suspects.remove(couponId);
        } else {
            suspects.put(couponId, next);
        }
    }

    /**
     * 정보 키(total, deleted)를 DB 값으로 덮어쓴다. 관리자 수정·삭제와 같은 쿠폰 행 잠금을 잡고 한다.
     * 관리자 요청은 Redis를 바꾼 뒤 DB 커밋까지 이 잠금을 쥐므로, 잠금을 잡았을 때의 DB 값이 확정된 값이다.
     * Note: 잠금 뒤 롤백 알림을 받은 관리자 요청의 되돌리기(restore-total)는 "아직 바꾼 값일 때만" 되돌리므로 여기서 맞춘 값을 덮지 않는다.
     */
    private void syncMeta(Coupon coupon, Counter counter) {
        transactionTemplate.executeWithoutResult(status -> {
            Optional<Coupon> locked = couponRepository.lockById(coupon.getId());
            // 그사이 삭제됐으면(잠금 조회가 빔) 삭제 표시를 남긴다. 발급이 막히는 안전한 쪽이다
            int total = locked.map(Coupon::getTotalQuantity).orElse(coupon.getTotalQuantity());
            issueGate.syncMeta(coupon.getId(), total, locked.isEmpty());
        });
        counter.metaSynced++;
    }

    /** 한 번 실행한 결과. 로그와 테스트에서 본다. */
    public record Result(
            int coupons,
            int added,
            int removed,
            int metaSynced,
            int issuedQuantityUpdated,
            int overIssued,
            int failed) {

        /** Redis·DB를 하나라도 바꿨거나 경보·실패가 있으면 true. 아무 일도 없던 실행은 로그를 남기지 않는다. */
        public boolean hasChanges() {
            return added + removed + metaSynced + issuedQuantityUpdated + overIssued + failed > 0;
        }
    }

    private static final class Counter {
        private int added;
        private int removed;
        private int metaSynced;
        private int issuedQuantityUpdated;
        private int overIssued;
        private int failed;

        private void fail(Coupon coupon, RuntimeException e) {
            log.warn("쿠폰 발급 판단을 DB에 맞추지 못했습니다. couponId={}", coupon.getId(), e);
            failed++;
        }

        private Result result(int coupons) {
            return new Result(coupons, added, removed, metaSynced, issuedQuantityUpdated, overIssued, failed);
        }
    }
}
