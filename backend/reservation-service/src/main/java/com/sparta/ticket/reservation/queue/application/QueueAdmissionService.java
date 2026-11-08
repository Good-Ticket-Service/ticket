package com.sparta.ticket.reservation.queue.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.queue.domain.AdmissionPolicy;
import com.sparta.ticket.reservation.queue.domain.AdmissionResult;
import com.sparta.ticket.reservation.queue.domain.WaitingQueue;
import com.sparta.ticket.reservation.queue.domain.WaitingRoom;
import com.sparta.ticket.reservation.queue.domain.WaitingRoomRepository;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 끝나지 않은 room마다 입장을 처리한다. 입장 스케줄러가 주기마다 부른다.
 *
 * Note: 서버가 여러 대면 스케줄러도 여러 개가 같은 room을 처리한다. room 하나의 처리가 Redis 스크립트 한 번이고 입장 속도를
 * Redis에 남긴 마지막 입장 시각으로 계산하므로, 겹쳐 돌아도 초당 입장 인원과 상한을 넘지 않는다. 그래서 분산 락을 두지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueueAdmissionService {

    private final WaitingQueue waitingQueue;
    private final WaitingRoomRepository waitingRoomRepository;
    private final QueueProperties queueProperties;
    private final QueueAdmissionProperties admissionProperties;
    private final QueueAdmissionMetrics metrics;

    /**
     * 끝나지 않은 room을 하나씩 처리한다. 대기열이 꺼져 있으면 아무것도 하지 않는다. 한 room이 실패해도 다음 room은 처리한다.
     * 오픈 전 room도 처리한다. 줄이 비어 있어 하는 일이 없고, 시각으로 거르지 않아 코드가 단순하다.
     * room 목록은 Redis 목록 읽기 1번에 room 설정은 캐시로 읽는다(RedisWaitingRoomRepository).
     */
    public void admitAll() {
        if (!queueProperties.enabled()) {
            return;
        }
        AdmissionPolicy policy = policy();
        long waiting = 0;
        long active = 0;
        for (WaitingRoom room : waitingRoomRepository.findActive()) {
            long roomId = room.roomId();
            Timer.Sample sample = metrics.start();
            try {
                AdmissionResult result = waitingQueue.admit(roomId, policy);
                metrics.recorded(sample, result);
                waiting += result.waiting();
                active += result.active();
                log.debug(
                        "room {} 입장 처리: 입장 {}, 만료 {}, 이탈 {}",
                        roomId,
                        result.admitted(),
                        result.expired(),
                        result.left());
            } catch (BusinessException e) {
                metrics.failed(sample);
                log.warn("room {} 입장 처리에 실패했다. 다음 주기에 다시 처리한다.", roomId, e);
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
