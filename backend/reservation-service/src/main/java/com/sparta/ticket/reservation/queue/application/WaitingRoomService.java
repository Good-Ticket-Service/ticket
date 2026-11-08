package com.sparta.ticket.reservation.queue.application;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.reservation.queue.application.dto.WaitingRoomResult;
import com.sparta.ticket.reservation.queue.domain.QueueErrorCode;
import com.sparta.ticket.reservation.queue.domain.WaitingRoom;
import com.sparta.ticket.reservation.queue.domain.WaitingRoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 대기열(room) 생성과 scope 조회. 관리자가 보호할 범위와 시각을 정해 만들고, 프런트는 회차 화면에서 scope로 roomId를 찾는다.
 * 대기열은 scope를 해석하지 않는다. 형식(대상:ID) 검사는 요청 DTO가 한다.
 */
@Service
@RequiredArgsConstructor
public class WaitingRoomService {

    /**
     * 생성 시각과 openAt 사이의 최소 여유. Gateway는 보호 대상 목록을 주기적으로(10초) 받으므로,
     * 오픈 전에 Gateway가 목록을 받아 두도록 이만큼 앞서 만들게 한다.
     */
    static final Duration MIN_LEAD_TIME = Duration.ofMinutes(1);

    private final WaitingRoomRepository repository;
    private final QueueAdmissionProperties admissionProperties;
    private final Clock clock;

    /**
     * room을 만든다. openAt이 지금 + 1분보다 이르거나 closeAt이 openAt보다 이르면(같아도) 400 INVALID_TIME_RANGE,
     * 끝나지 않은 다른 room과 scope가 겹치면 409 WAITING_ROOM_SCOPE_CONFLICT다.
     * room 설정은 closeAt 뒤에도 입장 유효 시간만큼 남겨 둔다. 그 사이 들어온 요청에 404 대신 409 CLOSED를 주기 위해서다.
     */
    public WaitingRoomResult create(List<String> scopes, Instant openAt, Instant closeAt) {
        Instant now = clock.instant();
        if (openAt.isBefore(now.plus(MIN_LEAD_TIME)) || !closeAt.isAfter(openAt)) {
            throw new BusinessException(QueueErrorCode.INVALID_TIME_RANGE);
        }
        WaitingRoom room = repository.create(
                scopes.stream().distinct().toList(), openAt, closeAt, closeAt.plus(admissionProperties.entryTtl()));
        return WaitingRoomResult.from(room);
    }

    /** scope를 보호하는 끝나지 않은 room을 찾는다. 없으면 404 WAITING_ROOM_NOT_FOUND이고, 프런트는 대기열 없이 좌석 화면으로 간다. */
    public WaitingRoomResult findByScope(String scope) {
        return repository
                .findByScope(scope)
                .map(WaitingRoomResult::from)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.WAITING_ROOM_NOT_FOUND));
    }

    /** 끝나지 않은(closeAt 전) room 전부. 오픈 전 room도 들어 있다. Gateway가 토큰을 검사할 대상을 정할 때 쓴다. */
    public List<WaitingRoomResult> findActive() {
        return repository.findActive().stream().map(WaitingRoomResult::from).toList();
    }
}
