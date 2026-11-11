package com.sparta.ticket.waitingroom.infrastructure.scheduler;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 오픈 전 GC 설정. 값은 config-repo/waiting-room-service.yml 한 곳에만 둔다.
 *
 * @param lead 오픈 몇 초 전에 GC를 부를지. 확인 주기가 1초라 실제로는 lead와 lead−1초 사이에 부른다
 */
@Validated
@ConfigurationProperties(prefix = "waiting-room.pre-open-gc")
public record PreOpenGcProperties(@NotNull Duration lead) {}
