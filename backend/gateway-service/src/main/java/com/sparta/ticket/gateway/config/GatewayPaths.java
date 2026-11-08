package com.sparta.ticket.gateway.config;

import java.util.List;

/**
 * Security 체인이 역할로 나누는 경로. 체인 선택(securityMatcher)과 체인 안 역할 검사가 이 목록을 쓴다.
 * 인증 없이 통과하는 경로는 config-repo `auth.whitelist`, 입장 토큰을 검사하는 좌석 경로는 QueueTokenFilter에 있다.
 */
final class GatewayPaths {

    /** 체인 0: 결제·정산 관리자. MASTER만, 회원을 매번 조회한다. */
    static final List<String> PAYMENT_ADMIN = List.of(
            "/api/admin/payments", "/api/admin/payments/**", "/api/admin/settlements", "/api/admin/settlements/**");

    /** 체인 1: 판매자 정산·수취 계좌. SELLER만. */
    static final List<String> SELLER_SETTLEMENT =
            List.of("/api/seller/settlements", "/api/seller/settlements/**", "/api/seller/payout-account");

    /** 체인 2 안: 대기열 생성. MASTER만(#299). */
    static final List<String> WAITING_ROOM_ADMIN = List.of("/api/admin/waiting-rooms", "/api/admin/waiting-rooms/**");

    private GatewayPaths() {}

    static String[] array(List<String> paths) {
        return paths.toArray(String[]::new);
    }
}
