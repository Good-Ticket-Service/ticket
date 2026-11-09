package com.sparta.ticket.waitingroom.infrastructure.config;

import com.sparta.ticket.waitingroom.application.QueueAdmissionProperties;
import com.sparta.ticket.waitingroom.application.QueueProperties;
import com.sparta.ticket.waitingroom.infrastructure.token.QueueTokenProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 대기열 설정(waiting-room.*), 입장 처리 설정(waiting-room.admission.*), 입장 토큰 설정(waiting-room.token.*)을 등록한다. */
@Configuration
@EnableConfigurationProperties({QueueProperties.class, QueueAdmissionProperties.class, QueueTokenProperties.class})
public class QueueConfig {}
