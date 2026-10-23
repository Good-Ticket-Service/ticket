package com.sparta.ticket.user.application;

import com.sparta.ticket.user.domain.User;

/**
 * 토큰 발급 기능. application은 JWT 라이브러리를 모르고, 구현(JwtProvider)은 infrastructure에 둔다.
 */
public interface TokenIssuer {

    String issue(User user);

    long expirationSeconds();
}
