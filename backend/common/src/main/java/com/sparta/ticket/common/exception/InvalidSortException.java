package com.sparta.ticket.common.exception;

import lombok.Getter;

/**
 * 허용 목록에 없는 정렬 필드. GlobalExceptionHandler가 400 VALIDATION_ERROR와 {@code errors[].field = "sort"}로 응답한다.
 * Entity에 없는 필드로 정렬할 때(PropertyReferenceException)와 같은 형식이다.
 */
@Getter
public class InvalidSortException extends RuntimeException {

    private final String property;

    public InvalidSortException(String property) {
        super("허용되지 않은 정렬 필드: " + property);
        this.property = property;
    }
}
