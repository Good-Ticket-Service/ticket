package com.sparta.ticket.reservation.queue.infrastructure.config;

import com.sparta.ticket.reservation.queue.application.QueueAdmissionProperties;
import com.sparta.ticket.reservation.queue.application.QueueProperties;
import com.sparta.ticket.reservation.queue.infrastructure.token.QueueTokenProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 대기열 설정(reservation.queue.*), 입장 처리 설정(reservation.queue.admission.*), 입장 토큰 설정(reservation.queue.token.*)을 등록한다. */
@Configuration
@EnableConfigurationProperties({QueueProperties.class, QueueAdmissionProperties.class, QueueTokenProperties.class})
public class QueueConfig {}
