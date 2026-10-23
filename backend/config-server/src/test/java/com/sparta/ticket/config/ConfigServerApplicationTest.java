package com.sparta.ticket.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigServerApplicationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("서비스 이름으로 요청하면 공통 설정과 서비스 전용 설정을 함께 내려준다")
    @SuppressWarnings("unchecked")
    void servesSharedAndServiceConfig() {
        Map<String, Object> body = restTemplate.getForObject("/user-service/default", Map.class);

        assertThat(body).extracting("name").isEqualTo("user-service");
        assertThat(body.get("propertySources").toString())
                .contains("config-repo/user-service.yml")
                .contains("config-repo/application.yml");
    }
}
