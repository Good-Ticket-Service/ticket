package com.sparta.ticket.gateway.security;

import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.gateway.queue.QueueProperties;
import com.sparta.ticket.gateway.queue.QueueTokenErrorCode;
import com.sparta.ticket.gateway.queue.QueueTokenVerifier;
import com.sparta.ticket.gateway.queue.ScheduleLookup;
import com.sparta.ticket.gateway.support.ErrorResponseWriter;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 좌석 조회·선점 요청의 입장 토큰(X-Queue-Token)을 검사한다. 대기열을 통과한 회원만, 통과한 공연의 좌석만 쓰게 한다(docs/api.md 좌석·대기열 절).
 * 순서: 토큰 있음 → 서명·만료 → 토큰 회원 = 로그인 회원 → 토큰 공연 = 요청한 회차의 공연. 하나라도 틀리면 403 QUEUE_TOKEN_INVALID,
 * 회차가 없으면(404) 토큰의 공연과 맞을 수 없으므로 403, 회차를 확인하지 못하면 통과시키지 않고 503이다. 대기열이 꺼져 있으면 검사하지 않는다.
 *
 * Note: 로그인 회원이 정해진 뒤여야 하므로 Security 체인 안, 인가 뒤에 둔다(SecurityConfig). Security 체인 부품이라 security 패키지에 두고,
 * 토큰 검증·회차 조회는 queue 패키지 것을 쓴다(security → queue 한 방향). WebFilter 빈으로 등록하면
 * Security 체인 밖에서 한 번 더 돌므로 빈으로 내놓지 않는다(UserHeaderInjectionFilter와 같다).
 */
public class QueueTokenFilter implements WebFilter {

    public static final String QUEUE_TOKEN_HEADER = "X-Queue-Token";

    private static final String SCHEDULE_ID = "scheduleId";

    /** 검사하는 경로. 선점 해제와 공개 잔여석은 검사하지 않는다. */
    private static final ServerWebExchangeMatcher PROTECTED = new OrServerWebExchangeMatcher(
            ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, "/api/seats/schedules/{scheduleId}"),
            ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/api/seats/schedules/{scheduleId}/holds"));

    private enum Decision {
        PASS,
        INVALID,
        UNAVAILABLE
    }

    private final QueueProperties properties;
    private final QueueTokenVerifier verifier;
    private final ScheduleLookup scheduleLookup;
    private final ErrorResponseWriter errorWriter;

    public QueueTokenFilter(
            QueueProperties properties,
            QueueTokenVerifier verifier,
            ScheduleLookup scheduleLookup,
            ErrorResponseWriter errorWriter) {
        this.properties = properties;
        this.verifier = verifier;
        this.scheduleLookup = scheduleLookup;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!properties.enabled()) {
            return chain.filter(exchange);
        }
        return PROTECTED.matches(exchange).flatMap(match -> {
            if (!match.isMatch()) {
                return chain.filter(exchange);
            }
            return decide(exchange, match).flatMap(decision -> switch (decision) {
                case PASS -> chain.filter(exchange);
                case INVALID -> errorWriter.write(exchange, QueueTokenErrorCode.QUEUE_TOKEN_INVALID);
                case UNAVAILABLE -> errorWriter.write(exchange, CommonErrorCode.SERVICE_UNAVAILABLE);
            });
        });
    }

    /**
     * 검사 결과만 정하고 다음 필터는 부르지 않는다. 회차 조회 실패를 503으로 바꾸는 onErrorResume이 뒤쪽(서비스 호출)의 에러까지
     * 삼키지 않게 하려는 것이다.
     */
    private Mono<Decision> decide(ServerWebExchange exchange, MatchResult match) {
        String token = exchange.getRequest().getHeaders().getFirst(QUEUE_TOKEN_HEADER);
        Long scheduleId = parseScheduleId(match);
        if (token == null || token.isBlank() || scheduleId == null) {
            return Mono.just(Decision.INVALID);
        }
        return Mono.zip(verifier.verify(token), currentUserId())
                .filter(pair -> pair.getT1().userId() == pair.getT2())
                .flatMap(pair -> samePerformance(scheduleId, pair.getT1().performanceId()))
                .defaultIfEmpty(Decision.INVALID);
    }

    /** 회차의 공연이 토큰의 공연과 같은지. 회차가 없으면 INVALID, 확인하지 못하면 UNAVAILABLE이다. */
    private Mono<Decision> samePerformance(long scheduleId, long tokenPerformanceId) {
        return scheduleLookup
                .performanceIdOf(scheduleId)
                .map(performanceId -> performanceId
                        .filter(id -> id == tokenPerformanceId)
                        .map(id -> Decision.PASS)
                        .orElse(Decision.INVALID))
                .onErrorReturn(Decision.UNAVAILABLE);
    }

    private static Mono<Long> currentUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(Authentication::getPrincipal)
                .filter(UserSnapshot.class::isInstance)
                .map(user -> ((UserSnapshot) user).userId());
    }

    /** 경로의 회차 ID. 숫자가 아니면 어떤 토큰과도 맞출 수 없으므로 null(검사 실패)이다. */
    private static Long parseScheduleId(MatchResult match) {
        Object value = match.getVariables().get(SCHEDULE_ID);
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
