package com.sparta.ticket.common.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Note: 에러 코드를 enum 하나가 아니라 인터페이스로 둔 이유. common에는 공통 코드(CommonErrorCode)만 두고,
 * 각 서비스가 자기 도메인 코드(예: USER_NOT_FOUND)를 enum으로 구현해 같은 예외 처리 흐름을 탄다.
 * common이 서비스 도메인을 알 필요가 없어진다.
 */
public interface ErrorCode {

    HttpStatusCode status();

    /** 응답의 {@code code}. 대문자 스네이크 케이스. enum 구현이면 상수 이름을 그대로 쓴다. */
    String code();

    String message();
}
