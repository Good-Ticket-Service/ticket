package com.sparta.ticket.reservation.booking.application;

import com.sparta.ticket.reservation.booking.domain.ReservationRepository;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 시간이 지난 HOLD를 만료하고 좌석을 돌려준다. 후보는 잠그지 않고 고르고, 건마다 트랜잭션 하나로 처리한다.
 * 인스턴스가 여러 개여도 조건부 UPDATE가 한 번만 성공하므로 분산 락을 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HoldExpiryService {

    private final ReservationRepository reservationRepository;
    private final ReservationTransactions transactions;
    private final ChangeStamps changeStamps;

    /** 한 묶음을 처리하고 만료한 건수를 돌려준다. timeBudget을 넘기면 남은 건은 다음 실행에 넘긴다. */
    public int expireDue(int batchSize, Duration timeBudget) {
        long deadline = System.nanoTime() + timeBudget.toNanos();
        int expired = 0;
        for (Long reservationId : reservationRepository.findExpiredHoldIds(changeStamps.now(), batchSize)) {
            if (System.nanoTime() > deadline) {
                break;
            }
            try {
                if (transactions.expireHold(reservationId)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.warn("선점 만료에 실패했다. 다음 실행에서 다시 고른다. reservationId={}", reservationId, e);
            }
        }
        return expired;
    }
}
