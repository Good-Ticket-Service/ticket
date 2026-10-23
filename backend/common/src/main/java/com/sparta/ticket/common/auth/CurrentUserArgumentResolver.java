package com.sparta.ticket.common.auth;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Note: HandlerMethodArgumentResolver는 컨트롤러 메서드 파라미터를 채우는 확장 지점이다. 헤더 파싱을 컨트롤러마다
 * 반복하지 않고 {@code @CurrentUser UserContext user} 한 줄로 받게 한다. 이 헤더는 Gateway가 클라이언트 값을 지우고
 * 다시 넣은 값이라 서비스는 믿고 쓴다. 대신 서비스 포트를 외부에 열지 않는 것이 전제다.
 */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && UserContext.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public UserContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                       NativeWebRequest request, WebDataBinderFactory binderFactory) {
        return UserContextExtractor.extract(request::getHeader);
    }
}
