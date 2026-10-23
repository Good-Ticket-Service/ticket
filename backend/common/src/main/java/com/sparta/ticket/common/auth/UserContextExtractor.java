package com.sparta.ticket.common.auth;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.util.StringUtils;

/**
 * Note: 사용자 파라미터와 역할 검사에 같은 헤더 검증을 적용한다. 역할 헤더만 있는 요청은 인증된 사용자로 취급하지 않는다.
 */
final class UserContextExtractor {

    private UserContextExtractor() {
    }

    static UserContext extract(UnaryOperator<String> header) {
        String userId = header.apply(UserHeaders.USER_ID);
        String username = header.apply(UserHeaders.USERNAME);
        String role = header.apply(UserHeaders.ROLE);
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(username) || !StringUtils.hasText(role)) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        try {
            return new UserContext(UUID.fromString(userId), username, Role.valueOf(role));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}
