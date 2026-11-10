package com.sparta.ticket.reservation.coupon.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * reservation-service가 Config Server에서 받는 Redis 명령 제한 시간을 확인한다(#340).
 * 통합 테스트는 config-repo를 읽지 않으므로 파일을 직접 읽어 본다.
 */
class RedisTimeoutConfigTest {

    /** Config Server와 같이 reservation-service.yml을 application.yml 위에 덮는다(앞에 둔 쪽이 이긴다). */
    private static Binder loadFromConfigRepo() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = new ArrayList<>();
        for (String file : List.of("reservation-service", "application")) {
            sources.addAll(loader.load(file, new FileSystemResource("../config-repo/" + file + ".yml")));
        }
        return new Binder(ConfigurationPropertySources.from(sources));
    }

    @Test
    @DisplayName("Redis 명령 제한 시간이 1초로 걸려, 장애 때 Lettuce 기본 60초 동안 요청 스레드를 잡지 않는다")
    void commandTimeoutIsOneSecond() throws IOException {
        Duration timeout = loadFromConfigRepo()
                .bind("spring.data.redis.timeout", Duration.class)
                .orElse(null);

        assertThat(timeout).isEqualTo(Duration.ofSeconds(1));
    }
}
