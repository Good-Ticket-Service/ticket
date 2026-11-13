package com.sparta.ticket.waitingroom.infrastructure.config;

import org.apache.catalina.connector.Connector;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Tomcat 커넥터의 연결당 소켓 버퍼 크기를 설정값으로 바꾼다. Spring Boot에는 이 값을 바꾸는 속성이 없다.
 * Note: 소켓 버퍼는 연결이 열려 있는 동안 계속 잡혀 있어 연결 수만큼 힙을 쓴다. 오픈 순간 연결이 많으면 이 버퍼를
 * 처음 옮기는 young GC가 길어진다(#413, 동시 2만 연결에서 버퍼 312.5MB, GC 52~122ms).
 * 버퍼보다 큰 요청·응답도 실패하지 않고 여러 번 나눠 읽고 쓴다.
 */
@Configuration
@EnableConfigurationProperties(TomcatSocketBufferProperties.class)
public class TomcatSocketBufferConfig {

    static final String READ_BUFFER = "socket.appReadBufSize";
    static final String WRITE_BUFFER = "socket.appWriteBufSize";

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatSocketBufferCustomizer(
            TomcatSocketBufferProperties properties) {
        return factory -> factory.addConnectorCustomizers(connector -> apply(connector, properties));
    }

    static void apply(Connector connector, TomcatSocketBufferProperties properties) {
        setSize(connector, READ_BUFFER, properties.readSize());
        setSize(connector, WRITE_BUFFER, properties.writeSize());
    }

    /** 값이 없으면 Tomcat 기본값을 둔다. Tomcat이 속성 이름을 받지 않으면 조용히 무시되므로 기동을 멈춘다. */
    static void setSize(Connector connector, String name, DataSize size) {
        if (size == null) {
            return;
        }
        long bytes = size.toBytes();
        if (bytes <= 0 || bytes > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + "는 1바이트 이상 " + Integer.MAX_VALUE + "바이트 이하여야 한다: " + size);
        }
        if (!connector.setProperty(name, Long.toString(bytes))) {
            throw new IllegalStateException("Tomcat 커넥터가 " + name + " 설정을 받지 않았다");
        }
    }
}
