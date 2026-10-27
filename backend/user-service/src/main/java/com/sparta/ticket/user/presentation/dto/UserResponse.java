package com.sparta.ticket.user.presentation.dto;

import com.sparta.ticket.common.auth.Role;
import com.sparta.ticket.user.application.dto.UserResult;
import com.sparta.ticket.user.domain.UserStatus;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * 회원 응답. 시각은 공연·좌석 API와 같은 형식(초 단위, {@code +09:00} 오프셋 포함 ISO 8601)으로 내보낸다.
 *
 * <p>Note: DB의 TIMESTAMP와 Entity의 LocalDateTime에는 시간대가 없다. 컨테이너가 {@code TZ=Asia/Seoul}(backend/Dockerfile)로
 * 돌아 감사 시각이 KST로 기록되므로, 응답에서 그 값에 서울 오프셋을 붙인다.
 */
public record UserResponse(
        Long userId,
        String email,
        String name,
        String phone,
        Role role,
        UserStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    public static UserResponse from(UserResult result) {
        return new UserResponse(
                result.userId(),
                result.email(),
                result.name(),
                result.phone(),
                result.role(),
                result.status(),
                offset(result.createdAt()),
                offset(result.updatedAt()));
    }

    private static OffsetDateTime offset(LocalDateTime time) {
        return time == null
                ? null
                : time.truncatedTo(ChronoUnit.SECONDS).atZone(SEOUL).toOffsetDateTime();
    }
}
