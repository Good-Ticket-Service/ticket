package com.sparta.ticket.gateway.security;

import com.sparta.ticket.common.exception.CommonErrorCode;
import lombok.Getter;
import org.springframework.security.core.AuthenticationException;

/**
 * 인증 단계에서 응답 코드를 정해 둔 실패. 진입점(JsonAuthenticationEntryPoint)이 errorCode로 응답한다.
 * AuthenticationServiceException을 쓰지 않는 이유: 그 예외는 진입점을 거치지 않고 다시 던져져 500이 된다.
 */
@Getter
public class GatewayAuthenticationException extends AuthenticationException {

    private final CommonErrorCode errorCode;

    public GatewayAuthenticationException(CommonErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
    }

    public GatewayAuthenticationException(CommonErrorCode errorCode, Throwable cause) {
        super(errorCode.message(), cause);
        this.errorCode = errorCode;
    }
}
