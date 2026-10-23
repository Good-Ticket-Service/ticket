package com.sparta.ticket.user.infrastructure.init;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 초기 MASTER 계정. 값은 config-repo/user-service.yml이 환경 변수 MASTER_USERNAME, MASTER_PASSWORD에서 받는다. */
@ConfigurationProperties(prefix = "master")
public record MasterAccountProperties(String username, String password) {
}
