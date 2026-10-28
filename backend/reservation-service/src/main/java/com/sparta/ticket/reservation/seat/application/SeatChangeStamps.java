package com.sparta.ticket.reservation.seat.application;

import com.sparta.ticket.common.config.HeaderAuditorAware;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

/**
 * 좌석 조건부 UPDATE에 쓸 현재 시각과 사용자. 시각은 seat·booking이 함께 쓰는 Clock으로, 사용자는 JPA 감사와 같은 AuditorAware로 얻는다.
 * 그래서 요청 경로에서는 헤더의 사용자, 스케줄러처럼 요청 밖에서는 SYSTEM이 들어간다.
 *
 * Note: booking에도 같은 규칙의 클래스가 있지만 seat는 booking.application.facade 밖의 booking 타입을 쓰지 않으므로(#34) 따로 둔다.
 */
@Component
@RequiredArgsConstructor
public class SeatChangeStamps {

    private final Clock clock;
    private final AuditorAware<String> auditorAware;

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public String by() {
        return auditorAware.getCurrentAuditor().orElse(HeaderAuditorAware.SYSTEM);
    }
}
