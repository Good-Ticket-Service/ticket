package com.sparta.ticket.common.auth;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import java.util.function.UnaryOperator;
import org.springframework.util.StringUtils;

/**
 * Note: 사용자 파라미터와 역할 검사에 같은 헤더 검증을 적용한다. 역할 헤더만 있는 요청은 인증된 사용자로 취급하지 않는다.
 */
final class UserContextExtractor {

    private UserContextExtractor() {}

    static UserContext extract(UnaryOperator<String> header) {
        String userId = header.apply(UserHeaders.USER_ID);
        String username = header.apply(UserHeaders.USERNAME);
        String role = header.apply(UserHeaders.ROLE);
        if (!StringUtils.hasText(userId) || !StringUtils.hasText(username) || !StringUtils.hasText(role)) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        try {
            return new UserContext(Long.valueOf(userId), username, Role.valueOf(role));
        } catch (IllegalArgumentException e) {
            // Note: 숫자가 아닌 ID의 NumberFormatException도 IllegalArgumentException의 하위 타입이라 여기서 함께 잡힌다.
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
    }
}
