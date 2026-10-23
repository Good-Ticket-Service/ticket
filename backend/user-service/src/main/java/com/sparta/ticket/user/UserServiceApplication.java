package com.sparta.ticket.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Note: common의 공통 빈(예외 처리, Audit, Feign 헤더 전달 등)은 common의 자동 설정(AutoConfiguration.imports)으로 등록된다. 그래서 스캔 범위를 상위 패키지로 넓히지 않는다.
@SpringBootApplication
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
