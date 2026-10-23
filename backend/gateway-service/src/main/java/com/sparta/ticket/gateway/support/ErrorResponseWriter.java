package com.sparta.ticket.gateway.support;

import com.sparta.ticket.common.exception.ErrorCode;
import com.sparta.ticket.common.exception.ErrorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** 공통 에러 형식으로 Gateway가 직접 응답한다. 서비스와 같은 ErrorResponse(common-core)를 써서 형식이 어긋나지 않는다. */
@Component
@RequiredArgsConstructor
public class ErrorResponseWriter {

    private final ObjectMapper objectMapper;

    public Mono<Void> write(ServerWebExchange exchange, ErrorCode errorCode) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }
        ErrorResponse body = ErrorResponse.of(errorCode, errorCode.message(), exchange.getRequest().getPath().value());

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(errorCode.status());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            DataBuffer buffer = response.bufferFactory().wrap(objectMapper.writeValueAsBytes(body));
            return response.writeWith(Mono.just(buffer));
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }
}
