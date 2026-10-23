package com.sparta.ticket.common.response;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;

/**
 * 목록(검색) 응답의 {@code data} 형식.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, String sort) {

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                format(page.getSort()));
    }

    /** Entity 페이지를 응답 DTO 페이지로 바꾼다. 예: {@code PageResponse.from(eventPage, EventResult::from)} */
    public static <E, T> PageResponse<T> from(Page<E> page, Function<? super E, ? extends T> mapper) {
        return from(page.map(mapper));
    }

    private static String format(Sort sort) {
        if (sort.isUnsorted()) {
            return null;
        }
        return sort.stream()
                .map(order -> order.getProperty() + "," + order.getDirection())
                .collect(Collectors.joining(";"));
    }
}
