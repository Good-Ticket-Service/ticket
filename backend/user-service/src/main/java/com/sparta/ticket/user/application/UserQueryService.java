package com.sparta.ticket.user.application;

import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.user.application.dto.InternalUserResult;
import com.sparta.ticket.user.application.dto.UserResult;
import com.sparta.ticket.user.domain.User;
import com.sparta.ticket.user.domain.UserErrorCode;
import com.sparta.ticket.user.domain.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserQueryService {

    private final UserRepository userRepository;

    /** 내 정보 조회. Gateway가 탈퇴자를 먼저 막지만, 권한 재확인 캐시(60초) 사이에 탈퇴했으면 404다. */
    public UserResult getMyInfo(UserContext user) {
        return UserResult.from(findUser(user.userId()));
    }

    /** Gateway 권한 재확인과 다른 서비스의 사용자 확인용. 탈퇴 사용자는 @SQLRestriction으로 조회되지 않아 404가 된다. */
    public InternalUserResult getInternalUser(UUID userId) {
        return InternalUserResult.from(findUser(userId));
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }
}
