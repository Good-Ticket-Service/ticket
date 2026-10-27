package com.sparta.ticket.payment;

import com.sparta.ticket.payment.support.PaymentIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentServiceApplicationTest extends PaymentIntegrationTest {

    @Test
    @DisplayName("실제 PostgreSQL로 컨텍스트가 뜬다(schema.sql 적용, JPA 검증, 공통 자동 설정)")
    void contextLoads() {}
}
