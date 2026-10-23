package com.sparta.ticket.common.auth;

import java.util.List;

/**
 * Gateway가 인증 후 주입하는 사용자 헤더 이름. gateway가 넣고, 서비스와 Feign 전달이 읽는다.
 */
public final class UserHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String USERNAME = "X-Username";
    public static final String ROLE = "X-User-Role";

    public static final List<String> ALL = List.of(USER_ID, USERNAME, ROLE);

    private UserHeaders() {}
}
