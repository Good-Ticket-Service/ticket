package com.sparta.ticket.gateway.queue;

import com.sparta.ticket.common.exception.CommonErrorCode;
import com.sparta.ticket.gateway.auth.CurrentUser;
import com.sparta.ticket.gateway.auth.UserSnapshot;
import com.sparta.ticket.gateway.error.ErrorResponseWriter;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher.MatchResult;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 좌석 조회(목록·구역 목록·layout·status)·선점 요청의 입장 토큰(X-Queue-Token)을 검사한다. 대기열이 보호하는 회차면, 대기열을 통과한 회원만 토큰의 scope에 든 회차의 좌석을 쓰게 한다
 * (docs/api.md 좌석·대기열 절).
 * 순서: URL의 회차로 만든 schedule:{scheduleId}가 보호 중인가(ProtectedScopes) → 토큰 있음 → 서명·만료 → 토큰 회원 = 로그인 회원
 * → 그 scope가 토큰 scopes에 있음. 보호 중이 아니면(room이 없거나 closeAt이 지남) 토큰 없이 통과하고, 토큰 검사가 하나라도 틀리면
 * 403 QUEUE_TOKEN_INVALID다. 보호 목록을 한 번도 받지 못했으면 대기열 없이 통과시키지 않고 503이다.
 * 보호 목록은 메모리에서 읽고 토큰은 서명으로 확인하므로 요청마다 다른 서비스를 부르지 않는다.
 *
 * Note: 로그인 회원이 정해진 뒤여야 하므로 Security 체인 안, 인가 뒤에 둔다(SecurityConfig). WebFilter 빈으로 등록하면
 * Security 체인 밖에서 한 번 더 돌므로 빈으로 내놓지 않는다(UserHeaderInjectionFilter와 같다).
 */
public class QueueTokenFilter implements WebFilter {

    public static final String QUEUE_TOKEN_HEADER = "X-Queue-Token";

    private static final String SCHEDULE_ID = "scheduleId";

    /** URL의 회차 ID로 scope를 만드는 접두사. 대기열이 room을 만들 때 받는 scope 형식(대상:ID)과 같다. */
    private static final String SCHEDULE_SCOPE_PREFIX = "schedule:";

    /**
     * 검사하는 경로(입장 토큰 검사 대상은 여기서만 정한다). 선점 해제와 공개 잔여석은 검사하지 않는다.
     * 좌석 조회는 HEAD도 검사한다. Spring MVC가 GET 핸들러에 HEAD를 자동으로 열어 두어, GET만 보면 HEAD로 토큰 없이 핸들러를 실행할 수 있다.
     */
    private static final ServerWebExchangeMatcher SEAT_PATHS = new OrServerWebExchangeMatcher(
            seatQuery(HttpMethod.GET),
            seatQuery(HttpMethod.HEAD),
            ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/api/seats/schedules/{scheduleId}/holds"));

    /** 좌석 조회 경로(목록·구역 목록·layout·status)를 주어진 메서드로 매칭한다. */
    private static ServerWebExchangeMatcher seatQuery(HttpMethod method) {
        return ServerWebExchangeMatchers.pathMatchers(
                method,
                "/api/seats/schedules/{scheduleId}",
                "/api/seats/schedules/{scheduleId}/sections",
                "/api/seats/schedules/{scheduleId}/layout",
                "/api/seats/schedules/{scheduleId}/status");
    }

    private enum Decision {
        PASS,
        INVALID,
        UNAVAILABLE
    }

    private final ProtectedScopes protectedScopes;
    private final QueueTokenVerifier verifier;
    private final ErrorResponseWriter errorWriter;

    public QueueTokenFilter(
            ProtectedScopes protectedScopes, QueueTokenVerifier verifier, ErrorResponseWriter errorWriter) {
        this.protectedScopes = protectedScopes;
        this.verifier = verifier;
        this.errorWriter = errorWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return SEAT_PATHS.matches(exchange).flatMap(match -> {
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

    /** 검사 결과만 정하고 다음 필터는 부르지 않는다. 결과에 따른 응답은 filter가 한곳에서 정한다. */
    private Mono<Decision> decide(ServerWebExchange exchange, MatchResult match) {
        Long scheduleId = parseScheduleId(match);
        if (scheduleId == null) {
            return Mono.just(Decision.INVALID);
        }
        String scope = SCHEDULE_SCOPE_PREFIX + scheduleId;
        return switch (protectedScopes.protection(scope)) {
            case UNPROTECTED -> Mono.just(Decision.PASS);
            case UNKNOWN -> Mono.just(Decision.UNAVAILABLE);
            case PROTECTED -> checkToken(exchange, scope);
        };
    }

    private Mono<Decision> checkToken(ServerWebExchange exchange, String scope) {
        String token = exchange.getRequest().getHeaders().getFirst(QUEUE_TOKEN_HEADER);
        if (token == null || token.isBlank()) {
            return Mono.just(Decision.INVALID);
        }
        return Mono.zip(verifier.verify(token), CurrentUser.get().map(UserSnapshot::userId))
                .filter(pair -> pair.getT1().userId() == pair.getT2())
                .filter(pair -> pair.getT1().allows(scope))
                .map(pair -> Decision.PASS)
                .defaultIfEmpty(Decision.INVALID);
    }

    /**
     * 경로의 회차 ID. 숫자가 아니면 null(검사 실패)이다. 좌석 서비스도 같은 숫자로 읽으므로 "012"는 12로 맞춘다
     * (scope 문자열을 그대로 비교하면 schedule:012로 토큰 검사를 피할 수 있다).
     */
    private static Long parseScheduleId(MatchResult match) {
        Object value = match.getVariables().get(SCHEDULE_ID);
        try {
            return value == null ? null : Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
