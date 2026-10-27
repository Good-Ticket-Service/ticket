package com.sparta.ticket.user.application;

import com.sparta.ticket.common.auth.UserContext;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.user.application.dto.UpdateMyInfoCommand;
import com.sparta.ticket.user.application.dto.UserResult;
import com.sparta.ticket.user.domain.User;
import com.sparta.ticket.user.domain.UserErrorCode;
import com.sparta.ticket.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 회원 정보를 바꾸는 유스케이스. 조회는 UserQueryService가 맡는다. */
@Service
@RequiredArgsConstructor
@Transactional
public class UserCommandService {

    private final UserRepository userRepository;

    /**
     * 내 정보 부분 수정. Gateway가 탈퇴자를 먼저 막지만, 권한 재확인 캐시(60초) 사이에 탈퇴했으면 404다.
     *
     * <p>Note: 변경 감지에만 맡기면 UPDATE가 커밋 때 실행되고, updatedAt(@LastModifiedDate)도 그때 채워진다. 그 전에 응답을 만들면
     * 수정 전 시각이 나가므로 saveAndFlush로 UPDATE를 먼저 실행한 뒤 결과를 만든다.
     *
     * <p>Note: 잠금을 걸지 않는다. Hibernate의 기본 UPDATE는 바뀐 필드만이 아니라 모든 컬럼을 쓰므로, 같은 회원을 동시에 고치면
     * 나중에 커밋한 요청이 읽어 둔 값으로 덮어써 먼저 커밋한 변경이 사라질 수 있다. 본인만 고치는 데이터라 이런 겹침이 드물어
     * 감수한다. 낙관적 락(@Version)은 스키마 변경과 409 처리 비용에 비해 얻는 것이 적어 쓰지 않는다.
     */
    public UserResult updateMyInfo(UserContext user, UpdateMyInfoCommand command) {
        User found = userRepository
                .findById(user.userId())
                .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
        found.updateProfile(command.name(), command.phone());
        return UserResult.from(userRepository.saveAndFlush(found));
    }
}
