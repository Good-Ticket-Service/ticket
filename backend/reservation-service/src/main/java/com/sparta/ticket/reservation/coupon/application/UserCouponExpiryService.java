package com.sparta.ticket.reservation.coupon.application;

import com.sparta.ticket.common.config.HeaderAuditorAware;
import com.sparta.ticket.reservation.coupon.domain.UserCouponRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 유효 기간이 지난 AVAILABLE 회원 쿠폰의 저장 상태를 EXPIRED로 바꾸는 만료 배치(#183). 스케줄러가 주기마다 부른다.
 * 저장값을 실제와 맞추는 정리 작업일 뿐이다. 쓸 수 있는지는 배치와 상관없이 만료 시각으로 판단한다(UserCoupon.displayStatus·reserve).
 * 배치는 만료 시각이 지난 뒤에 돌므로 그사이에는 저장값이 뒤처져 있기 때문이다.
 *
 * Note: 후보는 잠그지 않고 고르고, 묶음마다 트랜잭션 하나로 바꾼다. 한 번에 많은 행을 오래 잠그지 않기 위해서다.
 * 고른 뒤 예매가 잡은 쿠폰은 UPDATE의 조건이 다시 걸러 내므로, 인스턴스가 여러 개여도 분산 락을 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class UserCouponExpiryService {

    private final UserCouponRepository userCouponRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 만료 시각이 지난 AVAILABLE 쿠폰을 batchSize개씩 EXPIRED로 바꾸고 바꾼 건수를 돌려준다.
     * 꽉 찬 묶음이 나오면 남은 것이 더 있을 수 있어 이어서 처리한다. timeBudget을 넘기면 남은 건은 다음 실행에 넘긴다.
     */
    public int expireDue(int batchSize, Duration timeBudget) {
        long deadline = System.nanoTime() + timeBudget.toNanos();
        // 한 번 실행하는 동안 같은 시각을 기준으로 고르고 바꾼다
        LocalDateTime now = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        int expired = 0;
        while (true) {
            // 1) 바꿀 쿠폰을 잠그지 않고 고른다. 없으면 끝이다
            List<Long> ids = userCouponRepository.findExpirableIds(now, batchSize);
            if (ids.isEmpty()) {
                return expired;
            }

            // 2) 묶음 하나를 트랜잭션 하나로 바꾼다. 그사이 예매에 잡힌 쿠폰은 건너뛰고 바뀐 행 수만 센다
            Integer changed = transactionTemplate.execute(
                    status -> userCouponRepository.expire(ids, now, HeaderAuditorAware.SYSTEM));
            expired += changed == null ? 0 : changed;

            // 3) 묶음이 덜 찼으면 남은 대상이 없다. 시간을 다 썼으면 남은 건은 다음 실행에 넘긴다
            if (ids.size() < batchSize || System.nanoTime() > deadline) {
                return expired;
            }
        }
    }
}
