package com.sparta.ticket.payment.infrastructure.pg;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PgProperties.class)
public class PgConfig {}
