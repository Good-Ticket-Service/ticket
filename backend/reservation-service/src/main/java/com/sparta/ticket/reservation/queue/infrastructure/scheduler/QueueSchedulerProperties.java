package com.sparta.ticket.reservation.queue.infrastructure.scheduler;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 입장 스케줄러 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다.
 *
 * @param interval 앞 처리가 끝난 뒤 다음 처리까지 쉬는 시간. 짧을수록 입장이 고르게 나뉘고 입장 알림이 빨라진다
 */
@Validated
@ConfigurationProperties(prefix = "reservation.queue.scheduler")
public record QueueSchedulerProperties(@NotNull Duration interval) {}
