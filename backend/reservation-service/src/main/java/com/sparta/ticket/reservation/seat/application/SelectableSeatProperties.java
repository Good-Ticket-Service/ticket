package com.sparta.ticket.reservation.seat.application;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 좌석 배치도(선택 가능 좌석) 조회 설정. 값은 config-repo/reservation-service.yml 한 곳에만 둔다.
 * 측정하면서 바꿀 값이라 코드에 기본값을 두지 않는다. 값이 없거나 cacheMaxAge가 cacheRefresh보다 길지 않으면 기동이 멈춘다.
 *
 * @param cacheRefresh 스냅샷을 만든 뒤 이 시간이 지나면 다음 요청이 기존 스냅샷을 받고, 전용 스레드가 뒤에서 다시 만든다(#311)
 * @param cacheMaxAge 스냅샷을 둘 수 있는 최대 시간. 요청이 끊기거나 다시 만들기가 계속 실패하면 이때 만료되고, 다음 요청이 직접 만든다
 */
@Validated
@ConfigurationProperties(prefix = "reservation.seat.selectable")
public record SelectableSeatProperties(
        @NotNull Duration cacheRefresh, @NotNull Duration cacheMaxAge) {

    /** Note: 값이 없을 때는 @NotNull 검사가 기동을 멈추므로 여기서는 둘 다 있을 때만 본다. */
    public SelectableSeatProperties {
        if (cacheRefresh != null && cacheMaxAge != null && cacheMaxAge.compareTo(cacheRefresh) <= 0) {
            // 만료가 먼저 오면 refresh가 일어나지 않고 요청이 다시 로딩을 기다린다
            throw new IllegalArgumentException("reservation.seat.selectable.cache-max-age(" + cacheMaxAge
                    + ")는 cache-refresh(" + cacheRefresh + ")보다 길어야 합니다");
        }
    }
}
