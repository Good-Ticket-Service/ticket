package com.sparta.ticket.waitingroom.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Tomcat이 연결마다 잡는 소켓 읽기·쓰기 버퍼 크기. 값은 config-repo/waiting-room-service.yml 한 곳에만 둔다.
 * 값이 없으면 Tomcat 기본값(각 8KB)을 그대로 쓴다.
 *
 * @param readSize 연결 하나의 읽기 버퍼(Tomcat {@code socket.appReadBufSize})
 * @param writeSize 연결 하나의 쓰기 버퍼(Tomcat {@code socket.appWriteBufSize})
 */
@ConfigurationProperties(prefix = "waiting-room.tomcat.socket-buffer")
public record TomcatSocketBufferProperties(DataSize readSize, DataSize writeSize) {}
