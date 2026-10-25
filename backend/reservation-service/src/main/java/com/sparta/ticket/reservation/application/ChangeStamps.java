package com.sparta.ticket.reservation.application;

import com.sparta.ticket.common.config.HeaderAuditorAware;
import com.sparta.ticket.reservation.domain.ChangeStamp;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

/**
 * 조건부 UPDATE에 쓸 현재 시각과 사용자. 시각은 주입한 Clock으로, 사용자는 JPA 감사와 같은 AuditorAware로 얻는다.
 * 그래서 요청 경로에서는 헤더의 사용자, 스케줄러처럼 요청 밖에서는 SYSTEM이 들어간다.
 */
@Component
@RequiredArgsConstructor
public class ChangeStamps {

    private final Clock clock;
    private final AuditorAware<String> auditorAware;

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public ChangeStamp stamp() {
        return new ChangeStamp(now(), auditorAware.getCurrentAuditor().orElse(HeaderAuditorAware.SYSTEM));
    }
}
