package com.sparta.ticket.user.domain;

public enum UserStatus {
    /** 정상 회원. 가입 시 상태다. */
    ACTIVE,
    /** 탈퇴한 회원. 로그인을 막고 정보는 1년 보유한다. */
    WITHDRAWN
}
