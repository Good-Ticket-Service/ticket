package com.sparta.ticket.common.exception;

import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 서비스의 예외를 공통 에러 형식 하나로 바꾸는 지점. 컨트롤러와 서비스는 예외를 던지기만 하고 응답 모양을 몰라도 된다.
 *
 * Note: Spring MVC 표준 예외(415, 405, 406, 404, 파라미터 누락·타입 불일치 등 약 20종)는 직접 나열하지 않고
 * {@link ResponseEntityExceptionHandler}에 맡긴다. 이 클래스가 예외마다 올바른 HTTP 상태와 헤더(405의 Allow, 415의 Accept)를 정하고,
 * 마지막에 {@link #handleExceptionInternal}을 부른다. 여기서는 그 메서드 하나만 바꿔 응답 본문을 공통 에러 형식으로 만든다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Object> handleBusiness(BusinessException e, HttpServletRequest request) {
        return respond(e.getErrorCode(), e.getMessage(), request.getRequestURI(), null, null);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraint(ConstraintViolationException e, HttpServletRequest request) {
        List<ErrorResponse.FieldError> errors = e.getConstraintViolations().stream()
                .map(violation -> new ErrorResponse.FieldError(violation.getPropertyPath().toString(), violation.getMessage()))
                .toList();
        return validationError(request.getRequestURI(), errors);
    }

    /** 정렬 파라미터에 Entity에 없는 필드를 넣은 경우. 예: {@code sort=unknown,DESC}. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<Object> handleUnknownSort(PropertyReferenceException e, HttpServletRequest request) {
        return sortError(request.getRequestURI(), e.getPropertyName());
    }

    /** API별 정렬 허용 목록(SortAllowList, QuerydslSupport)에 없는 필드. 예: {@code sort=password,DESC}. */
    @ExceptionHandler(InvalidSortException.class)
    public ResponseEntity<Object> handleDisallowedSort(InvalidSortException e, HttpServletRequest request) {
        return sortError(request.getRequestURI(), e.getProperty());
    }

    /** Feign 연결 실패·타임아웃(RetryableException 등). 응답을 받은 실패는 FeignErrorDecoder가 BusinessException으로 바꾼다. */
    @ExceptionHandler(FeignException.class)
    public ResponseEntity<Object> handleFeign(FeignException e, HttpServletRequest request) {
        log.warn("하위 서비스 호출 실패: {} {} cause={}", request.getMethod(), request.getRequestURI(), e.toString());
        return respond(CommonErrorCode.SERVICE_UNAVAILABLE, CommonErrorCode.SERVICE_UNAVAILABLE.message(), request.getRequestURI(), null, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception: {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(CommonErrorCode.INTERNAL_SERVER_ERROR, CommonErrorCode.INTERNAL_SERVER_ERROR.message(), request.getRequestURI(), null, null);
    }

    /** 요청 본문 검증(@Valid) 실패: 필드별 사유를 담는다. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return validationError(path(request), errors);
    }

    /** 컨트롤러 파라미터 검증 실패. 예: {@code @RequestParam @Min(1) int quantity}. 파라미터별 사유를 담는다. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErrorResponse.FieldError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ErrorResponse.FieldError(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return validationError(path(request), errors);
    }

    /** 나머지 Spring MVC 표준 예외. 부모가 정한 상태와 헤더는 그대로 두고, 본문만 공통 에러 형식으로 바꾼다. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (isCommitted(request)) {
            return null;
        }
        ErrorCode errorCode = toCommonCode(statusCode);
        if (errorCode == CommonErrorCode.INTERNAL_SERVER_ERROR) {
            log.error("Spring MVC 처리 실패: {}", path(request), ex);
        }
        List<ErrorResponse.FieldError> errors = errorCode == CommonErrorCode.VALIDATION_ERROR ? List.of() : null;
        return respond(errorCode, errorCode.message(), path(request), errors, headers);
    }

    private ErrorCode toCommonCode(HttpStatusCode status) {
        return switch (HttpStatus.valueOf(status.value())) {
            case BAD_REQUEST -> CommonErrorCode.VALIDATION_ERROR;
            case NOT_FOUND -> CommonErrorCode.NOT_FOUND;
            case METHOD_NOT_ALLOWED -> CommonErrorCode.METHOD_NOT_ALLOWED;
            case NOT_ACCEPTABLE -> CommonErrorCode.NOT_ACCEPTABLE;
            case UNSUPPORTED_MEDIA_TYPE -> CommonErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case SERVICE_UNAVAILABLE -> CommonErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError()
                    ? new SimpleErrorCode(status, CommonErrorCode.INVALID_REQUEST.code(), CommonErrorCode.INVALID_REQUEST.message())
                    : CommonErrorCode.INTERNAL_SERVER_ERROR;
        };
    }

    private ResponseEntity<Object> sortError(String path, String property) {
        return validationError(path, List.of(new ErrorResponse.FieldError("sort", "정렬할 수 없는 필드입니다: " + property)));
    }

    private ResponseEntity<Object> validationError(String path, List<ErrorResponse.FieldError> errors) {
        return respond(CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.VALIDATION_ERROR.message(), path, errors, null);
    }

    private ResponseEntity<Object> respond(ErrorCode errorCode, String message, String path,
                                           @Nullable List<ErrorResponse.FieldError> errors, @Nullable HttpHeaders headers) {
        return ResponseEntity.status(errorCode.status())
                .headers(headers)
                .body(ErrorResponse.of(errorCode, message, path, errors));
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : null;
    }

    private static boolean isCommitted(WebRequest request) {
        if (request instanceof ServletWebRequest servlet) {
            HttpServletResponse response = servlet.getResponse();
            return response != null && response.isCommitted();
        }
        return false;
    }
}
