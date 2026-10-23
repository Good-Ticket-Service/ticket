package com.sparta.ticket.common.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 컨트롤러 파라미터에 붙여 Gateway가 넣은 사용자 헤더를 {@link UserContext}로 받는다.
 * 헤더가 없으면 401 UNAUTHORIZED.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
