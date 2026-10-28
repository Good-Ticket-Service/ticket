package com.sparta.ticket.user.application;

import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.user.application.dto.InternalUserResult;
import com.sparta.ticket.user.application.dto.UserResult;
import com.sparta.ticket.user.domain.User;
import com.sparta.ticket.user.domain.UserErrorCode;
import com.sparta.ticket.user.domain.UserRepository;
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

    /** Gateway 권한 재확인용. 논리 삭제 여부와 별개로 ACTIVE 사용자만 인증에 사용한다. */
    public InternalUserResult getInternalUser(Long userId) {
        User user = findUser(userId);
        if (!user.isActive()) throw new BusinessException(UserErrorCode.USER_NOT_FOUND);
        return InternalUserResult.from(user);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId).orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }
}
