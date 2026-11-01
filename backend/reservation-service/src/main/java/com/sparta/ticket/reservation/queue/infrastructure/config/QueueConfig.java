package com.sparta.ticket.reservation.queue.infrastructure.config;

import com.sparta.ticket.reservation.queue.application.QueueAdmissionProperties;
import com.sparta.ticket.reservation.queue.application.QueueProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 대기열 설정(reservation.queue.*)과 입장 처리 설정(reservation.queue.admission.*)을 등록한다. */
@Configuration
@EnableConfigurationProperties({QueueProperties.class, QueueAdmissionProperties.class})
public class QueueConfig {}
