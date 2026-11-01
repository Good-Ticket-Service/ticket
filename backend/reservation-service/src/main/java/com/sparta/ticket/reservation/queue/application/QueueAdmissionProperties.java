package com.sparta.ticket.reservation.queue.application;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 입장 처리 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다.
 * 측정하면서 바꿀 값이라 코드에 기본값을 두지 않는다. 초당 입장 인원은 예상 대기 시간과 함께 쓰므로 {@link QueueProperties}에 있다.
 *
 * @param maxActive 동시에 입장해 있을 수 있는 인원 상한
 * @param burst 입장할 사람이 없는 동안 쌓아 둘 수 있는 입장 시간. 다음 처리에서 이만큼을 한꺼번에 들여보낼 수 있다
 * @param leaveAfter 마지막 조회 뒤 이 시간이 지나면 떠난 회원으로 보고 줄에서 뺀다
 * @param entryTtl 입장 유효 시간. 입장 토큰의 만료와 같다(계약 10분)
 * @param leaveBatchSize 입장 처리 한 번에 정리할 이탈자 수. 한 번의 스크립트가 Redis를 오래 붙잡지 않게 나눈다
 */
@Validated
@ConfigurationProperties(prefix = "reservation.queue.admission")
public record QueueAdmissionProperties(
        @Positive int maxActive,
        @NotNull Duration burst,
        @NotNull Duration leaveAfter,
        @NotNull Duration entryTtl,
        @Positive int leaveBatchSize) {}
