package com.sparta.ticket.fakepg;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 토스페이먼츠 형식의 API를 흉내 내는 테스트용 PG 서버. 결제 인증 시뮬레이션, 승인·조회·취소, 멱등 키,
 * 그리고 관리 API로 바꾸는 장애(지연, 실패, 응답 유실, 승인 처리 지연, 처리 한도)를 제공한다.
 */
@SpringBootApplication
public class FakePgApplication {

    public static void main(String[] args) {
        SpringApplication.run(FakePgApplication.class, args);
    }
}
