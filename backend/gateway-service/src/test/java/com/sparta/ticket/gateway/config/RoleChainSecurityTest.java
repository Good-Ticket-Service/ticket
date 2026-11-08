package com.sparta.ticket.gateway.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.web.reactive.function.server.RequestPredicates.path;

import com.sparta.ticket.common.auth.UserHeaders;
import com.sparta.ticket.gateway.auth.UserSnapshot;
import com.sparta.ticket.gateway.queue.ProtectedScopes.Protection;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Mono;

/** 경로별 체인과 역할: 결제·정산 관리자(MASTER, 매번 조회), 판매자 정산(SELLER), 대기열 생성(MASTER), 공연 판매자·관리자(로그인만), 보호 안 하는 좌석 경로. */
class RoleChainSecurityTest extends GatewaySecurityTestSupport {

    @Test
    @DisplayName("결제 관리자 요청은 매번 원본 사용자를 확인하고 검증된 헤더를 주입한다")
    void adminBypassesCache() throws Exception {
        given(userServiceClient.fetch(USER_ID))
                .willReturn(Mono.just(Optional.of(new UserSnapshot(USER_ID, "master@ticket.com", "MASTER"))));
        for (int i = 0; i < 2; i++) {
            client.get()
                    .uri("/api/admin/payments/refunds")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("MASTER"))
                    .header(UserHeaders.USER_ID, "999")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$['X-User-Id']")
                    .isEqualTo("1")
                    .jsonPath("$['X-User-Role']")
                    .isEqualTo("MASTER");
        }
        verify(userServiceClient, times(2)).fetch(USER_ID);
        verify(userLookup, never()).find(any());
    }

    @Test
    @DisplayName("결제 관리자 경로는 USER와 SELLER를 403으로 거절한다")
    void adminRejectsNonMaster() throws Exception {
        for (String role : new String[] {"USER", "SELLER"}) {
            given(userServiceClient.fetch(USER_ID))
                    .willReturn(Mono.just(Optional.of(new UserSnapshot(USER_ID, "user@ticket.com", role))));
            client.post()
                    .uri("/api/admin/payments/order/refund/retry")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken(role))
                    .header(UserHeaders.ROLE, "MASTER")
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("FORBIDDEN");
        }
    }

    @Test
    @DisplayName("정산 관리자 경로도 결제 관리자와 같이 매번 원본 사용자를 확인하고 MASTER만 허용한다")
    void adminSettlementsRequireFreshMaster() throws Exception {
        given(userServiceClient.fetch(USER_ID))
                .willReturn(Mono.just(Optional.of(new UserSnapshot(USER_ID, "seller@ticket.com", "SELLER"))));
        client.get()
                .uri("/api/admin/settlements/payouts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("SELLER"))
                .exchange()
                .expectStatus()
                .isForbidden();
        given(userServiceClient.fetch(USER_ID))
                .willReturn(Mono.just(Optional.of(new UserSnapshot(USER_ID, "master@ticket.com", "MASTER"))));
        client.get()
                .uri("/api/admin/settlements/payouts")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("MASTER"))
                .exchange()
                .expectStatus()
                .isOk();
        verify(userLookup, never()).find(any());
    }

    @Test
    @DisplayName("대기열 생성은 MASTER만 허용하고, 회원 대기열 경로는 로그인만 확인한다")
    void waitingRoomCreationRequiresMaster() throws Exception {
        given(userLookup.find(USER_ID)).willReturn(Mono.just(Optional.of(STORED_USER)));
        client.post()
                .uri("/api/admin/waiting-rooms")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                .header(UserHeaders.ROLE, "MASTER")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("FORBIDDEN");
        client.post()
                .uri("/api/waiting-rooms/3/entries")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                .exchange()
                .expectStatus()
                .isOk();

        given(userLookup.find(USER_ID))
                .willReturn(Mono.just(Optional.of(new UserSnapshot(USER_ID, "master@ticket.com", "MASTER"))));
        client.post()
                .uri("/api/admin/waiting-rooms")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("MASTER"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$['X-User-Role']")
                .isEqualTo("MASTER");
    }

    @Test
    @DisplayName("판매자 정산·수취 계좌 경로는 SELLER만 허용하고 검증된 사용자 헤더를 주입한다")
    void sellerSettlementsRequireSeller() throws Exception {
        for (String path : new String[] {"/api/seller/settlements", "/api/seller/payout-account"}) {
            given(userLookup.find(USER_ID)).willReturn(Mono.just(Optional.of(STORED_USER)));
            client.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                    .header(UserHeaders.ROLE, "SELLER")
                    .exchange()
                    .expectStatus()
                    .isForbidden();
            given(userLookup.find(USER_ID))
                    .willReturn(Mono.just(Optional.of(new UserSnapshot(USER_ID, "seller@ticket.com", "SELLER"))));
            client.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("SELLER"))
                    .header(UserHeaders.USER_ID, "999")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$['X-User-Id']")
                    .isEqualTo("1")
                    .jsonPath("$['X-User-Role']")
                    .isEqualTo("SELLER");
        }
    }

    @Test
    @DisplayName("일반 경로에 MASTER 캐시가 있어도 비활성 사용자는 관리자 명령을 실행하지 못한다")
    void adminDoesNotUseStaleMaster() throws Exception {
        given(userLookup.find(USER_ID))
                .willReturn(Mono.just(Optional.of(new UserSnapshot(USER_ID, "master@ticket.com", "MASTER"))));
        client.get()
                .uri("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("MASTER"))
                .exchange()
                .expectStatus()
                .isOk();
        given(userServiceClient.fetch(USER_ID)).willReturn(Mono.just(Optional.empty()));
        client.post()
                .uri("/api/admin/payments/order/refund/resume")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("MASTER"))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        verify(userLookup, times(1)).find(USER_ID);
    }

    @Test
    @DisplayName("사용자 조회 장애 시 캐시로 관리자 권한을 대체하지 않고 503으로 거절한다")
    void adminLookupUnavailable() throws Exception {
        given(userServiceClient.fetch(USER_ID)).willReturn(Mono.error(new IllegalStateException("down")));
        client.post()
                .uri("/api/admin/payments/order/refund/retry")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("MASTER"))
                .exchange()
                .expectStatus()
                .isEqualTo(503)
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("SERVICE_UNAVAILABLE");
        verify(userLookup, never()).find(any());
    }

    @Test
    void adminRequiresSignedToken() throws Exception {
        client.get()
                .uri("/api/admin/payments/refunds")
                .header(UserHeaders.ROLE, "MASTER")
                .exchange()
                .expectStatus()
                .isUnauthorized();
        client.get()
                .uri("/api/admin/payments/refunds")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer "
                                + token(
                                        "attacker-secret-key-0123456789abcdefghij",
                                        "MASTER",
                                        Instant.now().plusSeconds(3600)))
                .exchange()
                .expectStatus()
                .isUnauthorized();
        verify(userServiceClient, never()).fetch(any());
    }

    /**
     * 공연 판매자·관리자 경로는 인증 예외가 아니다. Gateway는 로그인만 확인하고 역할(SELLER·MASTER)은 공연 서비스가 확인한다(이슈 #134).
     * Note: Gateway에서도 역할을 막을지는 이슈 #236에서 팀이 정한다. 정해지면 이 테스트의 USER 통과 기대를 바꾼다.
     */
    @Test
    @DisplayName("공연 판매자·관리자 경로는 토큰이 없으면 401이고, 토큰이 있으면 역할과 관계없이 검증된 헤더를 붙여 넘긴다")
    void performanceSellerAndAdminRequireLoginOnly() throws Exception {
        String[] paths = {
            "/api/seller/performances",
            "/api/seller/performances/1/publication",
            "/api/seller/schedules/1",
            "/api/seller/venues/1",
            "/api/admin/venues",
            "/api/admin/venues/1/seats",
            "/api/admin/schedules/1/sales-suspension",
            "/api/admin/performances/1"
        };
        given(userLookup.find(USER_ID)).willReturn(Mono.just(Optional.of(STORED_USER)));
        for (String path : paths) {
            client.get()
                    .uri(path)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody()
                    .jsonPath("$.code")
                    .isEqualTo("UNAUTHORIZED");
            client.get()
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                    .header(UserHeaders.ROLE, "MASTER")
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$['X-User-Id']")
                    .isEqualTo("1")
                    .jsonPath("$['X-User-Role']")
                    .isEqualTo("USER");
        }
    }

    @Test
    @DisplayName("대기열이 보호하지 않는 회차면 좌석 조회·선점이 입장 토큰 없이 통과한다")
    void unprotectedScheduleSkipsEntryToken() throws Exception {
        given(userLookup.find(USER_ID)).willReturn(Mono.just(Optional.of(STORED_USER)));
        given(protectedScopes.protection("schedule:42")).willReturn(Protection.UNPROTECTED);

        client.get()
                .uri("/api/seats/schedules/42")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                .exchange()
                .expectStatus()
                .isOk();
        client.post()
                .uri("/api/seats/schedules/42/holds")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken("USER"))
                .exchange()
                .expectStatus()
                .isOk();
    }
}
