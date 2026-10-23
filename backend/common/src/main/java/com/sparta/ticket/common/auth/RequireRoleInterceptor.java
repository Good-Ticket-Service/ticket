package com.sparta.ticket.common.auth;

import com.sparta.ticket.common.exception.BusinessException;
import com.sparta.ticket.common.exception.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * {@link RequireRole}이 붙은 핸들러만 검사한다. 여기서 던진 예외도 DispatcherServlet 안이라
 * GlobalExceptionHandler가 공통 에러 형식으로 바꾼다.
 */
public class RequireRoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequireRole required = findRequireRole(method);
        if (required == null) {
            return true;
        }
        UserContext user = UserContextExtractor.extract(request::getHeader);
        if (Arrays.stream(required.value()).noneMatch(allowed -> allowed == user.role())) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return true;
    }

    private RequireRole findRequireRole(HandlerMethod method) {
        RequireRole onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RequireRole.class);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequireRole.class);
    }
}
