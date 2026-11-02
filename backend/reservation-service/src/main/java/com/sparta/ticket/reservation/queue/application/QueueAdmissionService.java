package com.sparta.ticket.reservation.queue.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.queue.domain.AdmissionPolicy;
import com.sparta.ticket.reservation.queue.domain.AdmissionResult;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 대기열이 열린 공연마다 입장을 처리한다. 입장 스케줄러가 주기마다 부른다.
 *
 * Note: 서버가 여러 대면 스케줄러도 여러 개가 같은 공연을 처리한다. 공연 하나의 처리가 Redis 스크립트 한 번이고 입장 속도를
 * Redis에 남긴 마지막 입장 시각으로 계산하므로, 겹쳐 돌아도 초당 입장 인원과 상한을 넘지 않는다. 그래서 분산 락을 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueAdmissionService {

    private final WaitingQueue waitingQueue;
    private final QueueProperties queueProperties;
    private final QueueAdmissionProperties admissionProperties;
    private final QueueAdmissionMetrics metrics;

    /** 열린 공연을 하나씩 처리한다. 대기열이 꺼져 있으면 아무것도 하지 않는다. 한 공연이 실패해도 다음 공연은 처리한다. */
    public void admitAll() {
        if (!queueProperties.enabled()) {
            return;
        }
        AdmissionPolicy policy = policy();
        long waiting = 0;
        long active = 0;
        for (long performanceId : waitingQueue.performances()) {
            Timer.Sample sample = metrics.start();
            try {
                AdmissionResult result = waitingQueue.admit(performanceId, policy);
                metrics.recorded(sample, result);
                waiting += result.waiting();
                active += result.active();
                log.debug(
                        "공연 {} 입장 처리: 입장 {}, 만료 {}, 이탈 {}, 닫힘 {}",
                        performanceId,
                        result.admitted(),
                        result.expired(),
                        result.left(),
                        result.closed());
            } catch (BusinessException e) {
                metrics.failed(sample);
                log.warn("공연 {} 입장 처리에 실패했다. 다음 주기에 다시 처리한다.", performanceId, e);
            }
        }
        metrics.cycleFinished(waiting, active);
    }

    private AdmissionPolicy policy() {
        return new AdmissionPolicy(
                queueProperties.admitPerSecond(),
                admissionProperties.burst(),
                admissionProperties.maxActive(),
                admissionProperties.leaveAfter(),
                admissionProperties.entryTtl(),
                admissionProperties.leaveBatchSize());
    }
}
