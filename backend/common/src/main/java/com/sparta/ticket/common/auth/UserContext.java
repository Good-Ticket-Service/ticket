package com.sparta.ticket.common.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;

/**
 * Gateway가 검증한 현재 요청의 사용자. 컨트롤러에서 {@code @CurrentUser UserContext user}로 받는다.
 *
 * <p>역할 검사는 {@code @RequireRole}이, 소유자 검사는 서비스 계층이 이 헬퍼로 한다.
 * 예: {@code user.validateSelfOrMaster(order.getOrdererId())}
 *
 * <p>{@code username}에는 회원의 로그인 ID인 email이 들어 있다(X-Username 헤더 값).
 */
public record UserContext(Long userId, String username, Role role) {

    // Note: Jackson은 record의 인자 없는 isXxx()도 응답 속성으로 내보낸다. 응답 계약에 없는 값이라 숨긴다.
    // 인자가 있는 메서드(isSelf)와 is/get으로 시작하지 않는 메서드(validateXxx)는 직렬화 대상이 아니다.
    @JsonIgnore
    public boolean isMaster() {
        return role == Role.MASTER;
    }

    public boolean isSelf(Long otherUserId) {
        return userId.equals(otherUserId);
    }

    public boolean isSelfOrMaster(Long ownerUserId) {
        return isMaster() || isSelf(ownerUserId);
    }

    /** MASTER가 아니면 403 FORBIDDEN. */
    public void validateMaster() {
        if (!isMaster()) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    /** 리소스 소유자 본인도 MASTER도 아니면 403 FORBIDDEN. */
    public void validateSelfOrMaster(Long ownerUserId) {
        if (!isSelfOrMaster(ownerUserId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
