package com.sparta.ticket.waitingroom.infrastructure.scheduler;

import com.sparta.ticket.waitingroom.domain.WaitingRoomRepository;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 오픈 전 GC를 켠다. waiting-room.pre-open-gc.enabled=true일 때만 켜진다.
 * Note: 입장 스케줄러와 같이 설정이 없으면 꺼진다. 통합 테스트는 이 설정을 넣지 않으므로 꺼진 채로 돈다. 운영 값은 config-repo에서 켠다.
 */
@Configuration
@EnableConfigurationProperties(PreOpenGcProperties.class)
@ConditionalOnProperty(prefix = "waiting-room.pre-open-gc", name = "enabled", havingValue = "true")
public class PreOpenGcConfig {

    @Bean
    public PreOpenGc preOpenGc(
            PreOpenGcProperties properties, WaitingRoomRepository waitingRoomRepository, Clock clock) {
        return new PreOpenGc(waitingRoomRepository, clock, properties.lead(), System::gc);
    }
}
