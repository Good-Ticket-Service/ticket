package com.sparta.ticket.performance.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparta.ticket.performance.support.PerformanceIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 이슈 #471: 공연 서비스의 DB 연결에 플래너 비용 설정(application.yml의 hikari data-source-properties)이 실제로 붙는지 확인한다.
 * Note: URL을 환경 변수·Testcontainers로 바꿔도 남는 속성이다. 설정을 다른 곳에서 다시 정의해 값이 빠지면 이 테스트가 실패한다.
 */
class DataSourcePlannerSettingIntegrationTest extends PerformanceIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("공연 서비스 연결은 random_page_cost 1.1로 열린다")
    void connectionUsesLowerRandomPageCost() {
        assertThat(jdbc.queryForObject("SHOW random_page_cost", String.class)).isEqualTo("1.1");
    }
}
