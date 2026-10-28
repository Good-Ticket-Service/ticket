package com.sparta.ticket.performance;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PerformanceServiceApplicationTest extends PerformanceIntegrationTest {

    @Test
    @DisplayName("실제 PostgreSQL로 컨텍스트가 뜬다(schema.sql 적용, JPA 검증, 공통 자동 설정)")
    void contextLoads() {}
}
