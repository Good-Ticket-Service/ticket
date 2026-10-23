package com.sparta.ticket.common.config;

import java.util.Set;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 검색 공통 규칙을 모든 서비스의 {@code Pageable} 파라미터에 적용한다.
 * page 기본 0, size는 10·30·50 중 하나이고 그 외 값은 10으로 보정, sort 기본 createdAt,DESC.
 * 정렬 허용 필드는 API마다 다르므로 각 서비스가 SortAllowList(Spring Data) 또는 QuerydslSupport(QueryDSL)로 검증한다.
 */
public class SearchPageableArgumentResolver implements HandlerMethodArgumentResolver {

    public static final int DEFAULT_SIZE = 10;
    public static final Set<Integer> ALLOWED_SIZES = Set.of(10, 30, 50);
    public static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    private final PageableHandlerMethodArgumentResolver delegate = new PageableHandlerMethodArgumentResolver();

    public SearchPageableArgumentResolver() {
        delegate.setFallbackPageable(PageRequest.of(0, DEFAULT_SIZE, DEFAULT_SORT));
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Pageable.class.equals(parameter.getParameterType());
    }

    @Override
    public Pageable resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest,
            WebDataBinderFactory binderFactory) {
        Pageable requested = delegate.resolveArgument(parameter, mavContainer, webRequest, binderFactory);
        int size = ALLOWED_SIZES.contains(requested.getPageSize()) ? requested.getPageSize() : DEFAULT_SIZE;
        Sort sort = requested.getSort().isSorted() ? requested.getSort() : DEFAULT_SORT;
        return PageRequest.of(requested.getPageNumber(), size, sort);
    }
}
